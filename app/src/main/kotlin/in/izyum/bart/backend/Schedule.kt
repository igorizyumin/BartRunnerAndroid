package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.routing.TransferPolicy
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsScheduledTrip
import `in`.izyum.bart.transit.gtfs.GtfsStopTime
import `in`.izyum.bart.transit.normalization.NormalizedRealtimeFeed
import `in`.izyum.bart.transit.normalization.RequiredTransferPair
import `in`.izyum.bart.transit.normalization.StaticTripIdentity
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
    data class TripKey(val serviceDate: LocalDate, val tripId: String)

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
    fun applyRealtime(feed: NormalizedRealtimeFeed): Schedule {
        val updates = feed.projectedTripUpdatesByIdentity
        val updatesById = feed.projectedTripUpdatesById
        // Only a static trip with the same ID is eligible to change this graph.
        // Operational telemetry remains in the normalized feed for its own
        // projection and cannot fabricate a static passenger identity here.
        val correctedBaseTrips = trips.map { trip ->
            val identity = StaticTripIdentity(trip.key.serviceDate, trip.key.tripId)
            val observation = updates[identity] ?: updatesById[trip.key.tripId]
                ?.takeIf { fallback ->
                    !fallback.isOperationalTelemetry
                        && fallback.startDate == null
                        && trips.count { it.key.tripId == trip.key.tripId } == 1
                }
            if (observation != null) {
                correctTrip(trip, observation.rawEntity.tripUpdate)
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
     * Applies canonical DMU observations at every resolved stop to the
     * electric passenger trip they serve at Pittsburg. The DMU identifier
     * remains provenance; the electric static identity remains the passenger
     * identity.
     */
    fun applyDmuTransferPairs(
        pairs: Collection<RequiredTransferPair>,
    ): Schedule {
        if (pairs.isEmpty()) return this
        val timingByIdentity = pairs.mapNotNull { pair ->
            val identity = pair.passengerIdentity
                ?: pair.electricAssociation?.staticIdentity
                ?: return@mapNotNull null
            val observed = pair.operationalObservation.stops.mapNotNull { stop ->
                val station = stationResolver(stop.stopId) ?: return@mapNotNull null
                if (stop.arrivalTimeMillis == null && stop.departureTimeMillis == null) {
                    return@mapNotNull null
                }
                station to stop
            }.toMap()
            identity to observed
        }.toMap()
        val adjustedTrips = trips.map { trip ->
            val observed = timingByIdentity[StaticTripIdentity(trip.key.serviceDate, trip.key.tripId)]
                ?: return@map trip
            val lastObservedIndex = trip.stops.indexOfLast { it.station in observed }
            trip.copy(stops = immutableList(trip.stops.mapIndexed { index, stop ->
                val observedStop = observed[stop.station]
                val observedArrival = observedStop?.arrivalTimeMillis
                    ?: observedStop?.departureTimeMillis
                val observedDeparture = observedStop?.departureTimeMillis
                    ?: observedStop?.arrivalTimeMillis
                when {
                    observedArrival != null || observedDeparture != null -> stop.copy(
                        arrivalTime = observedArrival ?: stop.arrivalTime,
                        departureTime = observedDeparture ?: stop.departureTime,
                        arrivalSource = PredictionSource.REALTIME,
                        departureSource = PredictionSource.REALTIME,
                    )
                    index > lastObservedIndex -> {
                        val previous = trip.stops.take(index).lastOrNull { candidate ->
                            candidate.station in observed
                        }
                        val previousStop = previous?.let { observed[it.station] }
                        val previousTime = previousStop?.departureTimeMillis
                            ?: previousStop?.arrivalTimeMillis
                        if (previous != null && previousTime != null) {
                            val travel = nominalTravelTimeMillis(previous.station, stop.station)
                            if (travel != null && travel > 0L) {
                                val arrival = previousTime + travel
                                val dwell = (stop.scheduledDepartureTime - stop.scheduledArrivalTime)
                                    .coerceAtLeast(0L)
                                // The electric update may already have supplied
                                // a realtime PITT time. DMU propagation fills
                                // gaps; it must never downgrade an existing
                                // realtime field to an estimate.
                                stop.copy(
                                    arrivalTime = if (stop.arrivalSource == PredictionSource.REALTIME) {
                                        stop.arrivalTime
                                    } else {
                                        arrival
                                    },
                                    departureTime = if (stop.departureSource == PredictionSource.REALTIME) {
                                        stop.departureTime
                                    } else {
                                        arrival + dwell
                                    },
                                    arrivalSource = if (stop.arrivalSource == PredictionSource.REALTIME) {
                                        PredictionSource.REALTIME
                                    } else {
                                        PredictionSource.ESTIMATE
                                    },
                                    departureSource = if (stop.departureSource == PredictionSource.REALTIME) {
                                        PredictionSource.REALTIME
                                    } else {
                                        PredictionSource.ESTIMATE
                                    },
                                )
                            } else {
                                stop
                            }
                        } else {
                            stop
                        }
                    }
                    else -> stop
                }
            }))
        }
        return create(
            adjustedTrips,
            nominalTravelTimes,
            stationResolver,
            network,
            feedTime,
        )
    }

    internal fun stationForStopId(stopId: String?): Station? = stationResolver(stopId)

    /**
     * Legacy static route-topology API. Timed route selection belongs to
     * RaptorRouter; callers should migrate before this API is removed.
     */
    @Deprecated(
        "Use RaptorRouter for timed route selection; this static topology API is legacy.",
        level = DeprecationLevel.WARNING,
    )
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
        return immutableList(
            sortRoutes(uniqueRoutes(direct + transferAlternatives))
        )
    }

    @Deprecated(
        "Use RaptorRouter for timed route selection; this static topology API is legacy.",
        level = DeprecationLevel.WARNING,
    )
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

    @Deprecated(
        "Use RaptorRouter for timed route selection; this static topology API is legacy.",
        level = DeprecationLevel.WARNING,
    )
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

    @Deprecated(
        "Use RaptorRouter for timed route selection; this static topology API is legacy.",
        level = DeprecationLevel.WARNING,
    )
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
            network.routePatternsForLine(it).isNotEmpty()
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
                        toTrip(network, serviceDate, scheduledTrip)?.let(staticTrips::add)
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
            return Schedule(
                network,
                feedTime,
                immutableList(trips),
                immutableMap(nominalTravelTimes),
                stationResolver,
            )
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
