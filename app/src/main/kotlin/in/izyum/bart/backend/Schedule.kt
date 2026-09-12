package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.networktasks.GtfsRealtimeFeedIndex
import `in`.izyum.bart.routing.TransferStationPreferences
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsScheduledTrip
import `in`.izyum.bart.transit.gtfs.GtfsStopTime
import com.google.transit.realtime.GtfsRealtime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * A time-scoped, immutable view of the transit schedule.
 *
 * Static trips are the base graph. Realtime updates produce a new corrected
 * view and never replace the original scheduled values.
 */
class Schedule private constructor(
    val network: BartGtfsNetwork,
    val feedTime: Long,
    val nodes: Set<Station>,
    val trips: List<Trip>,
    private val edgesByOrigin: Map<Station, List<TripEdge>>,
    private val nominalTravelTimes: Map<Pair<Station, Station>, Long>,
    private val stationResolver: (String?) -> Station?,
) {
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
        fun arrivalDelaySeconds(): Int? = delaySeconds(arrivalTime, scheduledArrivalTime)

        fun departureDelaySeconds(): Int? = delaySeconds(departureTime, scheduledDepartureTime)

        private fun delaySeconds(actual: Long, scheduled: Long): Int? =
            if (actual > 0L && scheduled > 0L) {
                ((actual - scheduled) / 1000L).toInt()
            } else {
                null
            }
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

    data class TripEdge(
        val trip: Trip,
        val from: Station,
        val to: Station,
        val scheduledDepartureTime: Long,
        val scheduledArrivalTime: Long,
        val departureTime: Long,
        val arrivalTime: Long,
        val departureSource: PredictionSource,
        val arrivalSource: PredictionSource,
    )

    /** All trips capable of leaving the station, including estimates. */
    fun edgesFrom(station: Station?): List<TripEdge> =
        if (station == null) emptyList() else edgesByOrigin[station].orEmpty()

    fun edgesBetween(from: Station?, to: Station?): List<TripEdge> =
        edgesFrom(from).filter { it.to == to }

    /** Nominal directed travel time used only when realtime omits a value. */
    fun nominalTravelTimeMillis(from: Station?, to: Station?): Long? =
        if (from == null || to == null) null else nominalTravelTimes[from to to]

    /** Returns a corrected immutable graph using the supplied realtime feed. */
    fun applyRealtime(feedIndex: GtfsRealtimeFeedIndex): Schedule {
        val updates = feedIndex.tripUpdatesById
        val correctedBaseTrips = trips.filterNot { it.synthetic }.map { trip ->
            val entity = updates[trip.key.tripId]
            if (entity?.hasTripUpdate() == true) {
                correctTrip(trip, entity.tripUpdate)
            } else {
                trip
            }
        }
        val correctedTrips = applyTerminalRealtime(correctedBaseTrips, feedIndex)
        return create(
            correctedTrips,
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
        val transferAlternatives = preferredTransferRoutes(origin, destination)
        val lateNightSfoMillbrae = if (isLateNightSfoMillbraeService()
            && destination == Station.MLBR
        ) {
            lateNightSfoMillbraeRoutes(origin, destination)
        } else {
            emptyList()
        }
        val normalRoutes = uniqueRoutes(direct + transferAlternatives)
            .sortedWith(routePreference)
        return immutableList(
            lateNightSfoMillbrae + normalRoutes.filterNot { normal ->
                lateNightSfoMillbrae.any { special ->
                    special.lines == normal.lines
                        && special.transferStations == normal.transferStations
                }
            }
        )
    }

    fun preferredTransferRoutes(origin: Station?, destination: Station?): List<Route> {
        if (origin == null || destination == null || origin == destination) {
            return emptyList()
        }
        val directTransfers = catalogTransferRoutes(origin, destination, false)
            .sortedWith(routePreference)
        val doubleTransfers = catalogTransferRoutes(origin, destination, true)
            .sortedWith(routePreference)
        val selected = if (doubleTransfers.isNotEmpty()
            && (directTransfers.isEmpty()
                || routeScore(doubleTransfers.first()) < routeScore(directTransfers.first()))
        ) doubleTransfers else directTransfers
        return immutableList(selected.filter(::hasUsableService))
    }

    fun doubleTransferRoutes(origin: Station?, destination: Station?): List<Route> {
        if (origin == null || destination == null || origin == destination) {
            return emptyList()
        }
        return immutableList(
            catalogTransferRoutes(origin, destination, true)
                .sortedWith(routePreference)
                .filter(::hasUsableService)
        )
    }

    fun transferRoutes(origin: Station?, destination: Station?): List<Route> {
        if (origin == null || destination == null || origin == destination) {
            return emptyList()
        }
        return immutableList(
            catalogTransferRoutes(origin, destination, false)
                .sortedWith(routePreference)
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

    private val routePreference = compareBy<Route> { routeScore(it) }

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

    /**
     * BART's Antioch/DMU feed has independent trip IDs. Use it to fill
     * terminal stop times on the corresponding static Yellow passenger trip;
     * it must never create a separate technical passenger departure by itself.
     */
    private fun applyTerminalRealtime(
        trips: List<Trip>,
        feedIndex: GtfsRealtimeFeedIndex,
    ): List<Trip> {
        val result = trips.toMutableList()
        val usedTrips = mutableSetOf<Int>()
        val terminalEntities = feedIndex.tripUpdateEntities
            .filter(::isTerminalRealtime)
            // A direct Antioch departure is the strongest evidence for the
            // Antioch board. Let it claim the next normal PITT train before a
            // PCTR-only DMU update can consume that same static trip.
            .sortedByDescending { entity ->
                entity.tripUpdate.stopTimeUpdateList.any { stopUpdate ->
                    stationResolver(stopUpdate.stopId) == Station.ANTC
                        && (terminalEventTime(stopUpdate.departure) != null
                        || terminalEventTime(stopUpdate.arrival) != null)
                }
            }
        terminalEntities.forEach { entity ->
            val update = entity.tripUpdate
            val points = update.stopTimeUpdateList.mapNotNull { stopUpdate ->
                val station = stationResolver(stopUpdate.stopId)
                    ?.takeIf { it == Station.PCTR || it == Station.ANTC }
                    ?: return@mapNotNull null
                station to stopUpdate
            }.toMap()
            if (points.isEmpty()) return@forEach
            val terminalStation = points.keys.firstOrNull { it == Station.PCTR }
                ?: Station.ANTC
            val terminalUpdate = points[terminalStation] ?: return@forEach
            val terminalTime = terminalEventTime(terminalUpdate.departure)
                ?: terminalEventTime(terminalUpdate.arrival) ?: return@forEach
            val platform = platformForStopId(update.stopTimeUpdateList.firstOrNull()?.stopId)
            val direction = when (platform) {
                "1" -> "n"
                "2" -> "s"
                else -> null
            }
            val antcDepartureTime = if (direction == "s") {
                points[Station.ANTC]?.let { point ->
                    terminalEventTime(point.departure)
                        ?: terminalEventTime(point.arrival)
                }
            } else {
                null
            }
            val nextPittRealtimeMatch = if (direction == "s" && antcDepartureTime != null) {
                result.indices
                    .filter { index ->
                        val trip = result[index]
                        if (index in usedTrips || !isTerminalCandidate(trip, direction, platform)) {
                            return@filter false
                        }
                        val pitt = trip.stopAt(Station.PITT) ?: return@filter false
                        pitt.arrivalSource == PredictionSource.REALTIME
                            && pitt.arrivalTime >= antcDepartureTime
                    }
                    .minByOrNull { index ->
                        result[index].stopAt(Station.PITT)?.arrivalTime ?: Long.MAX_VALUE
                    }
            } else {
                null
            }
            val match = nextPittRealtimeMatch ?: result.indices
                .filter { index ->
                    val trip = result[index]
                    if (index in usedTrips || !isTerminalCandidate(trip, direction, platform)) {
                        return@filter false
                    }
                    kotlin.math.abs(
                        (trip.stopAt(terminalStation)?.arrivalTime ?: 0L) - terminalTime
                    ) <= TERMINAL_MATCH_MAX_MILLIS
                }
                .minByOrNull { index ->
                    kotlin.math.abs(
                        (result[index].stopAt(terminalStation)?.arrivalTime ?: 0L)
                            - terminalTime
                    )
                }
                ?: return@forEach
            usedTrips += match
            result[match] = mergeTerminalRealtime(result[match], update, points)
        }
        return immutableList(result)
    }

    private fun isTerminalCandidate(
        trip: Trip,
        direction: String?,
        platform: String?,
    ): Boolean {
        if (trip.line != Line.YELLOW || trip.canceled
            || direction != null && trip.direction != direction
            || trip.stopAt(Station.PCTR) == null
            || trip.stopAt(Station.ANTC) == null
        ) return false
        val tripPlatform = trip.stopAt(Station.PITT)?.platform
        return platform == null || tripPlatform == null || platform == tripPlatform
    }

    private fun isTerminalRealtime(entity: GtfsRealtime.FeedEntity): Boolean {
        if (!entity.hasTripUpdate()) return false
        val trip = entity.tripUpdate.trip
        val routeId = trip.routeId.takeIf { trip.hasRouteId() && it.isNotEmpty() }
            ?: network.routeIdForTrip(trip.tripId)
        if (network.lineForRouteId(routeId) != null) return false
        return entity.tripUpdate.stopTimeUpdateList.any { stopUpdate ->
            stationResolver(stopUpdate.stopId) in setOf(Station.PCTR, Station.ANTC)
        }
    }

    private fun mergeTerminalRealtime(
        trip: Trip,
        update: GtfsRealtime.TripUpdate,
        points: Map<Station, GtfsRealtime.TripUpdate.StopTimeUpdate>,
    ): Trip {
        val directStations = points.keys
        var stops = trip.stops.map { stop ->
            val terminalUpdate = points[stop.station] ?: return@map stop
            // DMU trip IDs are not tied to static GTFS. Delay-only events
            // therefore cannot be interpreted against the matched electric
            // trip's schedule; use only absolute event times here.
            val arrival = terminalEventTime(terminalUpdate.arrival)
            val departure = terminalEventTime(terminalUpdate.departure)
            when {
                arrival != null || departure != null -> stop.copy(
                    arrivalTime = arrival ?: departure!!,
                    departureTime = departure ?: arrival!!,
                    arrivalSource = if (arrival != null) PredictionSource.REALTIME
                    else PredictionSource.ESTIMATE,
                    departureSource = if (departure != null) PredictionSource.REALTIME
                    else PredictionSource.ESTIMATE,
                    skipped = isSkipped(terminalUpdate),
                )
                isSkipped(terminalUpdate) -> stop.copy(
                    arrivalTime = 0L,
                    departureTime = 0L,
                    arrivalSource = PredictionSource.UNKNOWN,
                    departureSource = PredictionSource.UNKNOWN,
                    skipped = true,
                )
                else -> stop
            }
        }
        val pctr = stops.first { it.station == Station.PCTR }
        val antc = stops.first { it.station == Station.ANTC }
        if (Station.PCTR in directStations && Station.ANTC !in directStations) {
            val estimatedAntc = if (trip.direction == "n") {
                estimateAfter(pctr, antc)
            } else {
                estimateBefore(pctr, antc)
            }
            if (estimatedAntc != null) {
                stops = stops.map { if (it.station == Station.ANTC) estimatedAntc else it }
            }
        } else if (Station.ANTC in directStations && Station.PCTR !in directStations) {
            val estimatedPctr = if (trip.direction == "n") {
                estimateBefore(antc, pctr)
            } else {
                estimateAfter(antc, pctr)
            }
            if (estimatedPctr != null) {
                stops = stops.map { if (it.station == Station.PCTR) estimatedPctr else it }
            }
        }
        return trip.copy(stops = immutableList(stops))
    }

    private fun estimateBefore(next: Stop, previous: Stop): Stop? {
        val travel = nominalTravelTimeMillis(previous.station, next.station)
            ?: return null
        val nextTime = next.arrivalTime.takeIf { it > 0L } ?: return null
        val arrival = nextTime - travel
        if (arrival <= 0L) return null
        val dwell = (previous.scheduledDepartureTime - previous.scheduledArrivalTime)
            .coerceAtLeast(0L)
        return previous.copy(
            arrivalTime = arrival,
            departureTime = arrival + dwell,
            arrivalSource = PredictionSource.ESTIMATE,
            departureSource = PredictionSource.ESTIMATE,
        )
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

    /** Absolute DMU times only; delay-only and malformed epoch values are not usable. */
    private fun terminalEventTime(
        event: GtfsRealtime.TripUpdate.StopTimeEvent?,
    ): Long? = eventTime(event, 0L)?.takeIf { it >= MIN_REALTIME_EVENT_TIME_MILLIS }

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

    private fun routeScore(route: Route): Int {
        var score = route.transferStations.size * 100
        val lines = route.lines
        if ((route.origin == Station.DUBL || route.origin == Station.CAST)
            && (route.destination == Station.PITT
                || route.destination == Station.PCTR
                || route.destination == Station.ANTC)
            && lines.size == 3
            && lines[0] == Line.BLUE
            && lines[1] == Line.ORANGE
            && lines[2] == Line.YELLOW
            && route.transferStations.size == 2
        ) score -= 200
        if (isYellowSouthToBlueViaOrange(route)) {
            // This is the preferred Antioch/East Bay pattern even though it
            // adds a transfer: it avoids the split-platform West Oakland
            // connection and uses the Orange line as the BART timetable does.
            score -= 250
        }
        route.transferStations.indices.forEach { index ->
            val station = route.transferStations[index]
            if (station in TransferStationPreferences.avoidedStations
                && !TransferStationPreferences.isPreferredMacArthurYellowOrange(route, index)
            ) {
                score += 80
            } else if (station in TransferStationPreferences.busyStations) {
                score += 3
            }
            val preferred = preferredTransferStation(route, lines[index], lines[index + 1], index)
            if (station != preferred) {
                score += if (preferred == Station.LAKE
                    && station == Station.BAYF
                ) 4 else 10
            }
        }
        return score
    }

    private fun isYellowSouthToBlueViaOrange(route: Route): Boolean {
        if (route.lines != listOf(Line.YELLOW, Line.ORANGE, Line.BLUE)
            || route.transferStations.firstOrNull() != Station.MCAR
        ) return false
        return TransferStationPreferences.yellowTravelIsSouthbound(route) == true
    }

    private fun preferredTransferStation(
        route: Route,
        first: Line,
        second: Line,
        transferIndex: Int,
    ): Station? {
        if (isEastBayToSanFranciscoTrunk(first, second)) return Station.BALB
        if (samePair(first, second, Line.BLUE, Line.ORANGE)
            || samePair(first, second, Line.GREEN, Line.BLUE)
        ) {
            val segmentOrigin = if (transferIndex == 0) route.origin
            else route.transferStations[transferIndex - 1]
            val segmentDestination = if (transferIndex + 1 < route.transferStations.size) {
                route.transferStations[transferIndex + 1]
            } else {
                route.destination
            }
            if (segmentOrigin != null && segmentDestination != null
                && segment(first, segmentOrigin, Station.LAKE) != null
                && segment(second, Station.LAKE, segmentDestination) != null
                && network.canTransfer(Station.LAKE, first, second)
            ) return Station.LAKE
            return Station.BAYF
        }
        if (samePair(first, second, Line.ORANGE, Line.YELLOW)) {
            return if (TransferStationPreferences.yellowTravelIsSouthbound(route) == true) {
                Station.MCAR
            } else {
                Station._19TH
            }
        }
        return null
    }

    private fun isEastBayToSanFranciscoTrunk(first: Line, second: Line): Boolean {
        val eastBayLine = first == Line.BLUE || first == Line.GREEN
        val sanFranciscoTrunk = second == Line.RED
            || second == Line.YELLOW
            || second == Line.YELLOW_LATE_NIGHT
        val reversedEastBayLine = second == Line.BLUE || second == Line.GREEN
        val reversedSanFranciscoTrunk = first == Line.RED
            || first == Line.YELLOW
            || first == Line.YELLOW_LATE_NIGHT
        return (eastBayLine && sanFranciscoTrunk)
            || (reversedEastBayLine && reversedSanFranciscoTrunk)
    }

    private fun samePair(
        left: Line,
        right: Line,
        expectedLeft: Line,
        expectedRight: Line,
    ): Boolean = (left == expectedLeft && right == expectedRight)
        || (left == expectedRight && right == expectedLeft)

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
        private const val TERMINAL_MATCH_MAX_MILLIS = 20L * 60L * 1000L
        private const val MIN_REALTIME_EVENT_TIME_MILLIS = 1_000_000_000_000L
        private const val LATE_NIGHT_START_HOUR = 21
        private const val LATE_NIGHT_END_HOUR = 5

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
            val immutableTrips = immutableList(trips)
            val nodes = immutableSet(immutableTrips.flatMap { it.stops.map(Stop::station) }.toSet())
            val edges = LinkedHashMap<Station, MutableList<TripEdge>>()
            immutableTrips.forEach { trip ->
                trip.stops.zipWithNext().forEach { (from, to) ->
                    edges.getOrPut(from.station) { mutableListOf() } += TripEdge(
                        trip,
                        from.station,
                        to.station,
                        from.scheduledDepartureTime,
                        to.scheduledArrivalTime,
                        from.departureTime,
                        to.arrivalTime,
                        from.departureSource,
                        to.arrivalSource,
                    )
                }
            }
            return Schedule(
                network,
                feedTime,
                nodes,
                immutableTrips,
                immutableMap(edges.mapValues { (_, value) -> immutableList(value) }),
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

        private fun <T> immutableSet(values: Collection<T>): Set<T> =
            Collections.unmodifiableSet(LinkedHashSet(values))

        private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(values))
    }
}
