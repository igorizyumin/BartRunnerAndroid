package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.networktasks.GtfsRealtimeFeedIndex
import `in`.izyum.bart.routing.TransferPolicy
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsScheduledTrip
import `in`.izyum.bart.transit.gtfs.GtfsStopTime
import com.google.transit.realtime.GtfsRealtime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.LinkedHashMap

/**
 * A time-scoped, immutable view of the transit schedule.
 *
 * Static trips are the base graph. Realtime updates produce a new corrected
 * view and never replace the original scheduled values.
 */
class Schedule private constructor(
    private val network: BartGtfsNetwork,
    private val feedTime: Long,
    val trips: List<Trip>,
    private val nominalTravelTimes: Map<Pair<Station, Station>, Long>,
    private val stationResolver: (String?) -> Station?,
) {
    private val transferPolicy = TransferPolicy(network)
    data class TripKey(val serviceDate: LocalDate?, val tripId: String)

    data class Stop(
        val station: Station,
        val scheduledArrivalTime: Long,
        val scheduledDepartureTime: Long,
        val arrivalTime: Long = scheduledArrivalTime,
        val departureTime: Long = scheduledDepartureTime,
        val arrivalSource: PredictionSource = PredictionSource.SCHEDULE,
        val departureSource: PredictionSource = PredictionSource.SCHEDULE,
        val skipped: Boolean = false,
        val platform: String? = null,
    ) {
    }

    data class Trip(
        val key: TripKey,
        val routeId: String?,
        val line: Line,
        val direction: String?,
        val trainDestination: Station,
        val stops: List<Stop>,
        val canceled: Boolean = false,
        val synthetic: Boolean = false,
    ) {
        fun stopAt(station: Station?): Stop? = stops.firstOrNull { it.station == station }

        fun canServe(origin: Station?, destination: Station?): Boolean {
            val start = stops.indexOfFirst { it.station == origin }
            if (destination == null) return start >= 0
            val end = stops.indexOfFirst { it.station == destination }
            return start >= 0 && end > start
        }
    }

    /** Nominal directed travel time used only when realtime omits a value. */
    fun nominalTravelTimeMillis(from: Station?, to: Station?): Long? =
        if (from == null || to == null) null else nominalTravelTimes[from to to]

    /** Returns a corrected immutable graph using the supplied realtime feed. */
    fun applyRealtime(feedIndex: GtfsRealtimeFeedIndex): Schedule {
        val updates = feedIndex.tripUpdatesById
        // Realtime may contain separate 600-series Antioch/DMU updates. They
        // are operational telemetry, not passenger trips, so only exact
        // static trip IDs are eligible to change this schedule graph.
        val correctedBaseTrips = trips.filterNot { it.synthetic }.map { trip ->
            val entity = updates[trip.key.tripId]
            if (entity?.hasTripUpdate() == true) {
                correctTrip(trip, entity.tripUpdate)
            } else {
                trip
            }
        }
        return create(
            correctedBaseTrips,
            nominalTravelTimes,
            stationResolver,
            network,
            feedTime,
        )
    }

    /**
     * Applies operational departure times without changing the static schedule.
     * ETD has no trip IDs, so callers must first make the station/time
     * association. Once associated, shift predicted times at and after the
     * station so transfer validation uses the observed departure and the
     * scheduled running time remains intact.
     */
    fun applyDepartureOverrides(
        overrides: Map<Pair<String, Station>, Long>,
    ): Schedule {
        if (overrides.isEmpty()) return this
        val adjustedTrips = trips.map { trip ->
            val tripId = trip.key.tripId
            val override = trip.stops.mapIndexedNotNull { index, stop ->
                overrides[tripId to stop.station]?.let { index to it }
            }.firstOrNull()
            if (override == null) {
                trip
            } else {
                val (startIndex, departureTime) = override
                val scheduledDeparture = trip.stops[startIndex].scheduledDepartureTime
                val delay = departureTime - scheduledDeparture
                trip.copy(
                    stops = immutableList(trip.stops.mapIndexed { index, stop ->
                        if (index < startIndex) {
                            stop
                        } else {
                            stop.copy(
                                arrivalTime = shifted(stop.arrivalTime, delay),
                                departureTime = shifted(stop.departureTime, delay),
                                arrivalSource = PredictionSource.ESTIMATE,
                                departureSource = PredictionSource.ESTIMATE,
                            )
                        }
                    })
                )
            }
        }
        return create(
            adjustedTrips,
            nominalTravelTimes,
            stationResolver,
            network,
            feedTime,
        )
    }

    /** Routes are selected from this corrected schedule, not from static data alone. */
    fun routesFor(origin: Station?, destination: Station?): List<Route> {
        if (origin == null || origin == destination) return emptyList()
        if (destination == null) {
            return catalogStationOnlyRoutes(origin)
                .filter(::hasUsableService)
        }
        val direct = catalogDirectRoutes(origin, destination)
            .filter(::hasUsableService)
        // Keep every policy-valid transfer topology.  The realtime projector
        // needs to be able to compare their complete, timed itineraries.
        val transferAlternatives = preferredTransferRoutes(origin, destination)
        val lateNightSfoMillbrae = if (isLateNightSfoMillbraeService()
            && destination == Station.MLBR
        ) {
            lateNightSfoMillbraeRoutes(origin, destination)
        } else {
            emptyList()
        }
        return immutableList(
            sortRoutes(uniqueRoutes(direct + transferAlternatives + lateNightSfoMillbrae))
        )
    }

    fun preferredTransferRoutes(origin: Station?, destination: Station?): List<Route> {
        if (origin == null || destination == null || origin == destination) {
            return emptyList()
        }
        return immutableList(
            uniqueRoutes(
                catalogTransferRoutes(origin, destination, false)
                    + catalogTransferRoutes(origin, destination, true)
            )
                .filter(::hasUsableService)
                .let(::sortRoutes)
        )
    }

    fun doubleTransferRoutes(origin: Station?, destination: Station?): List<Route> {
        if (origin == null || destination == null || origin == destination) {
            return emptyList()
        }
        return immutableList(
            catalogTransferRoutes(origin, destination, true)
                .let(::sortRoutes)
                .filter(::hasUsableService)
        )
    }

    fun transferRoutes(origin: Station?, destination: Station?): List<Route> {
        if (origin == null || destination == null || origin == destination) {
            return emptyList()
        }
        return immutableList(
            catalogTransferRoutes(origin, destination, false)
                .let(::sortRoutes)
                .filter(::hasUsableService)
        )
    }

    fun lateNightSfoMillbraeRoutes(origin: Station?, destination: Station?): List<Route> {
        if (origin == null || destination != Station.MLBR) return emptyList()
        if (origin == Station.SFIA) {
            return listOf(Route.direct(
                origin, destination, Line.YELLOW_LATE_NIGHT, "s",
                listOf(Station.SFIA, Station.MLBR)
            ))
        }
        val pattern = network.routePatternsForLine(Line.YELLOW)
            .filter { pattern ->
                val originIndex = pattern.stations.indexOf(origin)
                val sfoIndex = pattern.stations.indexOf(Station.SFIA)
                originIndex >= 0 && sfoIndex > originIndex
            }
            .maxByOrNull { it.stations.size } ?: return emptyList()
        val route = Route.transfer(
            origin, destination,
            listOf(Line.YELLOW, Line.YELLOW_LATE_NIGHT),
            listOf(Station.SFIA), pattern.direction,
            mapOf(
                Line.YELLOW to pattern.stations,
                Line.YELLOW_LATE_NIGHT to listOf(Station.SFIA, Station.MLBR),
            )
        )
        return if (hasUsableService(route)) listOf(route) else emptyList()
    }

    /** Whether the feed represents BART's late-night SFO/Millbrae service. */
    fun isLateNightSfoMillbraeService(): Boolean {
        if (feedTime <= 0L) return false
        val hour = Instant.ofEpochMilli(feedTime).atZone(PACIFIC_ZONE).hour
        return hour >= LATE_NIGHT_START_HOUR || hour < LATE_NIGHT_END_HOUR
    }

    private fun sortRoutes(routes: List<Route>): List<Route> = routes
        .map { route -> route to scheduledArrivalTime(route) }
        .sortedWith(
            compareBy<Pair<Route, Long?>> { it.second ?: Long.MAX_VALUE }
                .thenBy { transferPolicy.routeScore(it.first) }
        )
        .map { it.first }

    private fun hasUsableService(route: Route): Boolean {
        if (route.destination == null) {
            val candidates = trips.filter { it.line == route.directLine }
            return candidates.isEmpty() || candidates.any {
                !it.canceled && it.canServe(route.origin, null)
            }
        }
        for (index in route.lines.indices) {
            val segmentOrigin = if (index == 0) route.origin else route.transferStations[index - 1]
            val segmentDestination = if (index == route.lines.lastIndex) {
                route.destination
            } else {
                route.transferStations[index]
            }
            val candidates = trips.filter { it.line == route.lines[index] }
            if (candidates.isNotEmpty() && !candidates.any {
                    !it.canceled && it.canServe(segmentOrigin, segmentDestination)
                }) {
                return false
            }
        }
        return true
    }

    /**
     * Returns the earliest scheduled end-to-end arrival for a topology.
     * This is deliberately only a coarse fallback: realtime projection uses
     * the corrected predicted stop times for the final decision.
     */
    private fun scheduledArrivalTime(route: Route): Long? {
        if (route.origin == null || route.destination == null || trips.isEmpty()) {
            return null
        }
        val firstDestination = route.transferStations.firstOrNull() ?: route.destination
        return trips.asSequence()
            .filter { it.line == route.lines.first() && !it.canceled }
            .filter { it.canServe(route.origin, firstDestination) }
            .filter { trip ->
                val departure = trip.stopAt(route.origin)?.scheduledDepartureTime ?: 0L
                feedTime <= 0L || departure >= feedTime
            }
            .mapNotNull { firstTrip -> scheduledArrivalTime(route, firstTrip) }
            .minOrNull()
    }

    private fun scheduledArrivalTime(route: Route, firstTrip: Trip): Long? {
        var arrival = firstTrip.stopAt(
            route.transferStations.firstOrNull() ?: route.destination
        )?.scheduledArrivalTime ?: return null

        for (index in 1 until route.lines.size) {
            val transfer = route.transferStations[index - 1]
            val destination = if (index == route.lines.lastIndex) {
                route.destination
            } else {
                route.transferStations[index]
            }
            val line = route.lines[index]
            val minimumTransferSeconds = transferPolicy.minimumTransferSeconds(
                transfer,
                route.lines[index - 1],
                line,
            )
            val nextTrip = trips.asSequence()
                .filter { it.line == line && !it.canceled }
                .filter { it.canServe(transfer, destination) }
                .mapNotNull { trip ->
                    val departure = trip.stopAt(transfer)?.scheduledDepartureTime ?: 0L
                    val nextArrival = trip.stopAt(destination)?.scheduledArrivalTime ?: 0L
                    if (departure >= arrival + minimumTransferSeconds * 1000L
                        && nextArrival > departure
                    ) trip to nextArrival else null
                }
                .minByOrNull { it.second }
                ?: return null
            arrival = nextTrip.second
        }
        return arrival
    }

    private fun correctTrip(trip: Trip, update: GtfsRealtime.TripUpdate): Trip {
        val realtimeByStation = LinkedHashMap<Station, GtfsRealtime.TripUpdate.StopTimeUpdate>()
        var lastUpdatedIndex = -1
        update.stopTimeUpdateList.forEach { stopUpdate ->
            val station = stationForRealtimeStop(stopUpdate.stopId, trip.stops)
                ?: return@forEach
            realtimeByStation[station] = stopUpdate
            lastUpdatedIndex = maxOf(lastUpdatedIndex, trip.stops.indexOfFirst { it.station == station })
        }
        val hasRealtimeTimes = realtimeByStation.values.any { hasTimeOrDelay(it.arrival) || hasTimeOrDelay(it.departure) }
        val correctedStops = mutableListOf<Stop>()
        trip.stops.forEachIndexed { index, stop ->
            val updateAtStation = realtimeByStation[stop.station]
            val arrival = updateAtStation?.let {
                eventTime(it.arrival, stop.scheduledArrivalTime)
            }
            val departure = updateAtStation?.let {
                eventTime(it.departure, stop.scheduledDepartureTime)
            }
            val platform = platformForStopId(updateAtStation?.stopId) ?: stop.platform
            val previous = correctedStops.lastOrNull()
            val propagated = if (hasRealtimeTimes && index > lastUpdatedIndex && previous != null) {
                estimateAfter(previous, stop)
            } else {
                null
            }
            correctedStops += when {
                arrival != null || departure != null -> stop.copy(
                    arrivalTime = arrival ?: departure ?: stop.scheduledArrivalTime,
                    departureTime = departure ?: arrival ?: stop.scheduledDepartureTime,
                    arrivalSource = if (arrival != null) PredictionSource.REALTIME else PredictionSource.ESTIMATE,
                    departureSource = if (departure != null) PredictionSource.REALTIME else PredictionSource.ESTIMATE,
                    skipped = isSkipped(updateAtStation),
                    platform = platform,
                )
                propagated != null -> propagated.copy(
                    skipped = isSkipped(updateAtStation),
                    platform = platform,
                )
                updateAtStation != null && isSkipped(updateAtStation) -> stop.copy(
                    arrivalTime = 0L,
                    departureTime = 0L,
                    arrivalSource = PredictionSource.UNKNOWN,
                    departureSource = PredictionSource.UNKNOWN,
                    skipped = true,
                    platform = platform,
                )
                updateAtStation != null -> stop.copy(platform = platform)
                else -> stop
            }
        }
        val canceled = update.hasTrip() && update.trip.hasScheduleRelationship() &&
            update.trip.scheduleRelationship == GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED
        return trip.copy(stops = immutableList(correctedStops), canceled = canceled || trip.canceled)
    }

    private fun estimateAfter(previous: Stop, current: Stop): Stop? {
        val travel = nominalTravelTimeMillis(previous.station, current.station) ?: return null
        if (previous.departureTime <= 0L || travel <= 0L) return null
        val arrival = previous.departureTime + travel
        val dwell = (current.scheduledDepartureTime - current.scheduledArrivalTime)
            .coerceAtLeast(0L)
        return current.copy(
            arrivalTime = arrival,
            departureTime = arrival + dwell,
            arrivalSource = PredictionSource.ESTIMATE,
            departureSource = PredictionSource.ESTIMATE,
        )
    }

    private fun stationForRealtimeStop(
        stopId: String?,
        stops: List<Stop>,
    ): Station? {
        if (stopId == null) return null
        stationResolver(stopId)?.let { resolved ->
            if (stops.any { it.station == resolved }) return resolved
        }
        val platformStation = stops.firstOrNull { stop ->
            stop.station.abbreviation.equals(stopId, ignoreCase = true)
                || stopId.startsWith(stop.station.abbreviation, ignoreCase = true)
        }?.station
        return platformStation
    }

    private fun hasTimeOrDelay(event: GtfsRealtime.TripUpdate.StopTimeEvent): Boolean =
        event.hasTime() || event.hasDelay()

    private fun eventTime(
        event: GtfsRealtime.TripUpdate.StopTimeEvent?,
        scheduled: Long,
    ): Long? {
        if (event == null) return null
        if (event.hasTime() && event.time > 0L) return event.time * 1000L
        if (event.hasDelay() && scheduled > 0L) return scheduled + event.delay * 1000L
        return null
    }

    private fun isSkipped(update: GtfsRealtime.TripUpdate.StopTimeUpdate?): Boolean =
        update?.hasScheduleRelationship() == true &&
            update.scheduleRelationship ==
            GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED

    private fun catalogStationOnlyRoutes(origin: Station): List<Route> =
        network.linesForStation(origin).mapNotNull { line ->
            network.routePatternsForLine(line)
                .filter { origin in it.stations }
                .maxByOrNull { it.stations.size }
                ?.let { Route.stationOnly(origin, line, it.stations) }
        }

    private fun catalogDirectRoutes(origin: Station, destination: Station): List<Route> {
        val routes = mutableListOf<Route>()
        for (line in network.linesForStation(origin)) {
            val bestPatterns = LinkedHashMap<String?, BartGtfsNetwork.StationPattern>()
            network.routePatternsForLine(line).forEach { pattern ->
                val direction = pattern.direction
                val originIndex = pattern.stations.indexOf(origin)
                val destinationIndex = pattern.stations.indexOf(destination)
                if (originIndex >= 0 && destinationIndex > originIndex
                    && (bestPatterns[direction] == null
                        || pattern.stations.size > bestPatterns[direction]!!.stations.size)
                ) {
                    bestPatterns[direction] = pattern
                }
            }
            bestPatterns.values.forEach { pattern ->
                val direction = pattern.direction
                routes += Route.direct(
                    origin, destination, line, direction, pattern.stations
                )
            }
        }
        return routes
    }

    private fun catalogTransferRoutes(
        origin: Station,
        destination: Station,
        onlyDoubleTransfers: Boolean,
    ): List<Route> {
        val routes = mutableListOf<Route>()
        // The terminal vehicle is an operational feed detail, not a
        // passenger-facing line or transfer.
        val lines = Line.values().filter {
            it != Line.YELLOW_DMU && network.routePatternsForLine(it).isNotEmpty()
        }
        for (first in lines) {
            if (!contains(first, origin)) continue
            for (last in lines) {
                if (first == last || !contains(last, destination)) continue
                commonStations(first, last).forEach { transfer ->
                    if (!network.canTransfer(transfer, first, last)
                        || onlyDoubleTransfers
                        || segment(first, origin, transfer) == null
                        || segment(last, transfer, destination) == null
                    ) continue
                    makeTransferRoute(
                        origin, destination, listOf(first, last), listOf(transfer)
                    )?.let(routes::add)
                }
                for (middle in lines) {
                    if (middle == first || middle == last) continue
                    commonStations(first, middle).forEach { firstTransfer ->
                        if (!network.canTransfer(firstTransfer, first, middle)
                            || segment(first, origin, firstTransfer) == null
                        ) return@forEach
                        commonStations(middle, last).forEach { secondTransfer ->
                            if (!network.canTransfer(secondTransfer, middle, last)
                                || firstTransfer == secondTransfer
                                || segment(middle, firstTransfer, secondTransfer) == null
                                || segment(last, secondTransfer, destination) == null
                            ) return@forEach
                            makeTransferRoute(
                                origin, destination,
                                listOf(first, middle, last),
                                listOf(firstTransfer, secondTransfer)
                            )?.let(routes::add)
                        }
                    }
                }
            }
        }
        return uniqueRoutes(routes)
    }

    private fun makeTransferRoute(
        origin: Station,
        destination: Station,
        lines: List<Line>,
        transfers: List<Station>,
    ): Route? {
        val sequences = LinkedHashMap<Line, List<Station>>()
        var direction: String? = null
        lines.forEachIndexed { index, line ->
            val segmentOrigin = if (index == 0) origin else transfers[index - 1]
            val segmentDestination = if (index == lines.lastIndex) destination else transfers[index]
            val pattern = segment(line, segmentOrigin, segmentDestination) ?: return null
            sequences[line] = pattern.stations
            if (index == 0) {
                direction = pattern.direction
            }
        }
        val route = Route.transfer(origin, destination, lines, transfers, direction, sequences)
        return if (isValidTransferPath(route)) route else null
    }

    private fun contains(line: Line, station: Station): Boolean =
        network.routePatternsForLine(line).any { station in it.stations }

    private fun commonStations(first: Line, second: Line): List<Station> {
        val result = mutableListOf<Station>()
        network.routePatternsForLine(first).forEach { pattern ->
            pattern.stations.forEach { station ->
                if (station !in result && contains(second, station)) result += station
            }
        }
        return result
    }

    private fun segment(
        line: Line,
        origin: Station,
        destination: Station,
    ): BartGtfsNetwork.StationPattern? = network.routePatternsForLine(line)
        .filter { pattern ->
            val originIndex = pattern.stations.indexOf(origin)
            val destinationIndex = pattern.stations.indexOf(destination)
            originIndex >= 0 && destinationIndex > originIndex
        }
        .maxByOrNull { it.stations.size }

    private fun isValidTransferPath(route: Route): Boolean {
        route.lines.forEachIndexed { index, line ->
            val origin = if (index == 0) route.origin else route.transferStations[index - 1]
            val destination = if (index == route.lines.lastIndex) {
                route.destination
            } else {
                route.transferStations[index]
            }
            val sequence = route.getStationSequence(line)
            val originIndex = sequence.indexOf(origin)
            val destinationIndex = sequence.indexOf(destination)
            if (originIndex < 0 || destinationIndex <= originIndex) return false
            if (index < route.lines.lastIndex && route.destination in sequence) return false
            if (index > 0 && route.origin in sequence) return false
        }
        return true
    }

    private fun uniqueRoutes(routes: List<Route>): List<Route> {
        val unique = mutableListOf<Route>()
        routes.forEach { route ->
            if (unique.none { it.lines == route.lines
                    && it.transferStations == route.transferStations }) unique += route
        }
        return unique
    }

    companion object {
        private val PACIFIC_ZONE = ZoneId.of("America/Los_Angeles")
        private const val LOOK_AHEAD_MILLIS = 2L * 60L * 60L * 1000L
        private const val LOOK_BEHIND_MILLIS = 30L * 60L * 1000L
        private const val LATE_NIGHT_START_HOUR = 21
        private const val LATE_NIGHT_END_HOUR = 5
        private const val DEFAULT_SFO_MILLBRAE_RUNNING_TIME_MILLIS = 5L * 60L * 1000L
        private const val LATE_NIGHT_SFO_MILLBRAE_ROUTE_ID = "LATE_NIGHT_SFO_MILLBRAE"

        @JvmStatic
        fun fromStatic(
            network: BartGtfsNetwork,
            feedTime: Long,
            lines: Set<Line> = Line.values().toSet(),
        ): Schedule {
            if (feedTime <= 0L) {
                return create(emptyList(), emptyMap(), network::stationForStopId, network, feedTime)
            }
            val currentDate = Instant.ofEpochMilli(feedTime).atZone(PACIFIC_ZONE).toLocalDate()
            val routeIds = lines.flatMap { network.routeIdsForLine(it) }.toSet()
            val staticTrips = mutableListOf<Trip>()
            listOf(currentDate.minusDays(1), currentDate).forEach { serviceDate ->
                network.scheduledTripsFor(
                    serviceDate,
                    routeIds,
                    feedTime - LOOK_BEHIND_MILLIS,
                    feedTime + LOOK_AHEAD_MILLIS,
                )
                    .filter { scheduledTrip ->
                        scheduledTrip.stopTimes.any { stopTime ->
                            epochMillis(serviceDate, stopTime) in
                                (feedTime - LOOK_BEHIND_MILLIS)..(feedTime + LOOK_AHEAD_MILLIS)
                        }
                    }
                    .forEach { scheduledTrip ->
                        if (staticTrips.none { it.key.tripId == scheduledTrip.trip.tripId }) {
                            toTrip(network, serviceDate, scheduledTrip)?.let(staticTrips::add)
                        }
                    }
            }
            val nominal = nominalTravelTimes(staticTrips)
            return create(
                staticTrips,
                nominal,
                network::stationForStopId,
                network,
                feedTime,
            )
        }

        private fun toTrip(
            network: BartGtfsNetwork,
            serviceDate: LocalDate,
            scheduledTrip: GtfsScheduledTrip,
        ): Trip? {
            val line = network.lineForRouteId(scheduledTrip.trip.routeId) ?: return null
            val stops = collapseStops(network, serviceDate, scheduledTrip.stopTimes)
            if (stops.size < 2) return null
            return Trip(
                TripKey(serviceDate, scheduledTrip.trip.tripId),
                scheduledTrip.trip.routeId,
                line,
                network.directionForRouteId(scheduledTrip.trip.routeId),
                stops.last().station,
                immutableList(stops),
            )
        }

        private fun collapseStops(
            network: BartGtfsNetwork,
            serviceDate: LocalDate,
            stopTimes: List<GtfsStopTime>,
        ): List<Stop> {
            val result = mutableListOf<Stop>()
            stopTimes.sortedBy { it.sequence }.forEach { stopTime ->
                val station = network.stationForStopId(stopTime.stopId)
                    ?: return@forEach
                if (station == Station.SPCL) return@forEach
                val arrival = stopTime.arrivalSeconds?.let { epochMillis(serviceDate, it) } ?: 0L
                val departure = stopTime.departureSeconds?.let { epochMillis(serviceDate, it) } ?: arrival
                val existingIndex = result.indexOfFirst { it.station == station }
                val stop = Stop(
                    station = station,
                    scheduledArrivalTime = arrival,
                    scheduledDepartureTime = departure,
                    platform = platformForStop(stopTime),
                )
                if (existingIndex >= 0) {
                    result[existingIndex] = result[existingIndex].copy(
                        scheduledArrivalTime = if (arrival > 0L) arrival else result[existingIndex].scheduledArrivalTime,
                        scheduledDepartureTime = if (departure > 0L) departure else result[existingIndex].scheduledDepartureTime,
                        platform = stop.platform ?: result[existingIndex].platform,
                    )
                } else {
                    result += stop
                }
            }
            return result
        }

        private fun nominalTravelTimes(trips: List<Trip>): Map<Pair<Station, Station>, Long> {
            val samples = LinkedHashMap<Pair<Station, Station>, MutableList<Long>>()
            trips.forEach { trip ->
                trip.stops.zipWithNext().forEach { (from, to) ->
                    val departure = from.scheduledDepartureTime
                    val arrival = to.scheduledArrivalTime
                    if (departure > 0L && arrival > departure) {
                        samples.getOrPut(from.station to to.station) { mutableListOf() }
                            .add(arrival - departure)
                    }
                }
            }
            return samples.mapValues { (_, values) -> values.sorted()[values.size / 2] }
        }

        private fun create(
            trips: List<Trip>,
            nominalTravelTimes: Map<Pair<Station, Station>, Long>,
            stationResolver: (String?) -> Station?,
            network: BartGtfsNetwork,
            feedTime: Long,
        ): Schedule {
            val baseTrips = trips.filterNot {
                it.synthetic && it.line == Line.YELLOW_LATE_NIGHT
            }
            val augmentedTrips = if (isLateNightSfoMillbraeService(feedTime)) {
                baseTrips + lateNightSfoMillbraeTrips(
                    baseTrips, nominalTravelTimes, network, feedTime
                )
            } else {
                baseTrips
            }
            val immutableTrips = immutableList(augmentedTrips)
            return Schedule(
                network,
                feedTime,
                immutableTrips,
                immutableMap(nominalTravelTimes),
                stationResolver,
            )
        }

        /** Adds the late-night SFO–Millbrae connection as an ordinary timed trip. */
        private fun lateNightSfoMillbraeTrips(
            trips: List<Trip>,
            nominalTravelTimes: Map<Pair<Station, Station>, Long>,
            network: BartGtfsNetwork,
            feedTime: Long,
        ): List<Trip> {
            val runningTime = nominalTravelTimes[Station.SFIA to Station.MLBR]
                ?.takeIf { it > 0L }
                ?: DEFAULT_SFO_MILLBRAE_RUNNING_TIME_MILLIS
            val transferMillis = network.minimumTransferSeconds(
                Station.SFIA, Line.YELLOW, Line.RED
            ).coerceAtLeast(0) * 1000L

            return trips.asSequence()
                .filter { it.line == Line.YELLOW && !it.canceled }
                .filter { it.stopAt(Station.SFIA) != null && it.stopAt(Station.MLBR) == null }
                .mapNotNull { yellowTrip ->
                    val sfo = yellowTrip.stopAt(Station.SFIA) ?: return@mapNotNull null
                    val scheduledArrival = sfo.scheduledArrivalTime
                    val effectiveArrival = sfo.arrivalTime.takeIf { it > 0L }
                        ?: scheduledArrival
                    if (scheduledArrival <= 0L || effectiveArrival <= 0L) {
                        return@mapNotNull null
                    }

                    val scheduledDeparture = scheduledArrival + transferMillis
                    val departure = effectiveArrival + transferMillis
                    if (departure < feedTime) return@mapNotNull null
                    val scheduledArrivalAtMillbrae = scheduledDeparture + runningTime
                    val arrivalAtMillbrae = departure + runningTime
                    Trip(
                        key = TripKey(
                            yellowTrip.key.serviceDate,
                            "${yellowTrip.key.tripId}-late-night-sfo-millbrae",
                        ),
                        routeId = LATE_NIGHT_SFO_MILLBRAE_ROUTE_ID,
                        line = Line.YELLOW_LATE_NIGHT,
                        direction = "s",
                        trainDestination = Station.MLBR,
                        stops = immutableList(
                            listOf(
                                Stop(
                                    station = Station.SFIA,
                                    scheduledArrivalTime = scheduledDeparture,
                                    scheduledDepartureTime = scheduledDeparture,
                                    arrivalTime = departure,
                                    departureTime = departure,
                                    arrivalSource = PredictionSource.ESTIMATE,
                                    departureSource = PredictionSource.ESTIMATE,
                                ),
                                Stop(
                                    station = Station.MLBR,
                                    scheduledArrivalTime = scheduledArrivalAtMillbrae,
                                    scheduledDepartureTime = scheduledArrivalAtMillbrae,
                                    arrivalTime = arrivalAtMillbrae,
                                    departureTime = arrivalAtMillbrae,
                                    arrivalSource = PredictionSource.ESTIMATE,
                                    departureSource = PredictionSource.ESTIMATE,
                                ),
                            )
                        ),
                        synthetic = true,
                    )
                }
                .toList()
        }

        private fun isLateNightSfoMillbraeService(feedTime: Long): Boolean {
            if (feedTime <= 0L) return false
            val hour = Instant.ofEpochMilli(feedTime).atZone(PACIFIC_ZONE).hour
            return hour >= LATE_NIGHT_START_HOUR || hour < LATE_NIGHT_END_HOUR
        }

        private fun shifted(time: Long, delay: Long): Long =
            if (time > 0L) time + delay else time

        private fun epochMillis(serviceDate: LocalDate, stopTime: GtfsStopTime): Long =
            epochMillis(serviceDate, stopTime.departureSeconds ?: stopTime.arrivalSeconds ?: 0)

        private fun epochMillis(serviceDate: LocalDate, seconds: Int): Long =
            serviceDate.atStartOfDay(PACIFIC_ZONE).toInstant().toEpochMilli() + seconds * 1000L

        private fun platformForStop(stopTime: GtfsStopTime?): String? {
            return platformForStopId(stopTime?.stopId)
        }

        private fun platformForStopId(stopId: String?): String? {
            if (stopId == null) return null
            val separator = stopId.lastIndexOf('-')
            return if (separator >= 0 && separator + 1 < stopId.length) {
                stopId.substring(separator + 1)
            } else {
                null
            }
        }

        private fun <T> immutableList(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))

        private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(values))
    }
}
