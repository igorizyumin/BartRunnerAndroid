package com.dougkeen.bart.backend

import com.dougkeen.bart.model.Line
import com.dougkeen.bart.model.PredictionSource
import com.dougkeen.bart.model.Route
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import com.dougkeen.bart.transit.gtfs.GtfsScheduledTrip
import com.dougkeen.bart.transit.gtfs.GtfsStopTime
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
        val lines = correctedBaseTrips.map { it.line }.toSet()
        val correctedContinuations = terminalContinuations(
            correctedBaseTrips,
            nominalTravelTimes,
            lines,
        )
        return create(
            correctedBaseTrips + correctedContinuations,
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
        terminalShuttleRoute(origin, destination)
            ?.takeIf(::hasUsableService)
            ?.let { return listOf(it) }
        terminalRoutesViaPitt(origin, destination).takeIf { it.isNotEmpty() }?.let {
            return it.filter(::hasUsableService)
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
        val normalRoutes = uniqueRoutes(
            direct.map(::withTerminalShuttle) + transferAlternatives
        ).sortedWith(routePreference)
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
        return immutableList(selected.map(::withTerminalShuttle).filter(::hasUsableService))
    }

    fun doubleTransferRoutes(origin: Station?, destination: Station?): List<Route> {
        if (origin == null || destination == null || origin == destination) {
            return emptyList()
        }
        return immutableList(
            catalogTransferRoutes(origin, destination, true)
                .sortedWith(routePreference)
                .map(::withTerminalShuttle)
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

    private fun terminalShuttleRoute(origin: Station, destination: Station): Route? {
        val sequence = when {
            origin == Station.PITT && destination == Station.PCTR ->
                listOf(Station.PITT, Station.PCTR)
            origin == Station.PITT && destination == Station.ANTC ->
                listOf(Station.PITT, Station.PCTR, Station.ANTC)
            origin == Station.PCTR && destination == Station.ANTC ->
                listOf(Station.PCTR, Station.ANTC)
            origin == Station.ANTC && destination == Station.PCTR ->
                listOf(Station.ANTC, Station.PCTR)
            origin == Station.ANTC && destination == Station.PITT ->
                listOf(Station.ANTC, Station.PCTR, Station.PITT)
            origin == Station.PCTR && destination == Station.PITT ->
                listOf(Station.PCTR, Station.PITT)
            else -> return null
        }
        return Route.direct(
            origin,
            destination,
            Line.YELLOW_DMU,
            if (origin == Station.ANTC) "s" else "n",
            sequence
        )
    }

    private fun terminalRoutesViaPitt(origin: Station, destination: Station): List<Route> {
        if (destination != Station.PCTR && destination != Station.ANTC) return emptyList()
        return routesFor(origin, Station.PITT).mapNotNull { route ->
            if (route.lines.lastOrNull() != Line.YELLOW || route.destination != Station.PITT) {
                null
            } else {
                Route.transfer(
                    route.origin!!,
                    destination,
                    route.lines + Line.YELLOW_DMU,
                    route.transferStations + Station.PITT,
                    route.direction,
                    route.lines.associateWith { route.getStationSequence(it) }
                        .plus(Line.YELLOW_DMU to terminalShuttleSequence(destination))
                )
            }
        }
    }

    private fun terminalShuttleSequence(destination: Station): List<Station> =
        if (destination == Station.ANTC) {
            listOf(Station.PITT, Station.PCTR, Station.ANTC)
        } else {
            listOf(Station.PITT, Station.PCTR)
        }

    private fun withTerminalShuttle(route: Route): Route {
        val destination = route.destination
        if (!route.hasTransfer()
            || (destination != Station.PCTR && destination != Station.ANTC)
            || route.lines.lastOrNull() != Line.YELLOW
            || route.transferStations.lastOrNull() == Station.PITT
        ) return route
        return Route.transfer(
            route.origin!!,
            destination!!,
            route.lines + Line.YELLOW_DMU,
            route.transferStations + Station.PITT,
            route.direction,
            route.lines.associateWith { route.getStationSequence(it) }
                .plus(Line.YELLOW_DMU to terminalShuttleSequence(destination))
        )
    }

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
                val originIndex = pattern.stations.indexOf(origin)
                val destinationIndex = pattern.stations.indexOf(destination)
                if (originIndex >= 0 && destinationIndex > originIndex
                    && (bestPatterns[pattern.direction] == null
                        || pattern.stations.size > bestPatterns[pattern.direction]!!.stations.size)
                ) {
                    bestPatterns[pattern.direction] = pattern
                }
            }
            bestPatterns.values.forEach { pattern ->
                routes += Route.direct(
                    origin, destination, line, pattern.direction, pattern.stations
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
        // The DMU is a terminal continuation, not a normal transfer line.
        // Its topology is added explicitly after a Yellow route reaches
        // Pittsburg.
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
            if (index == 0) direction = pattern.direction
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
        route.transferStations.indices.forEach { index ->
            val preferred = preferredTransferStation(route, lines[index], lines[index + 1], index)
            if (route.transferStations[index] != preferred) score += 10
        }
        return score
    }

    private fun preferredTransferStation(
        route: Route,
        first: Line,
        second: Line,
        transferIndex: Int,
    ): Station? {
        if (isEastBayToSanFranciscoTrunk(first, second)) return Station.BALB
        if (samePair(first, second, Line.BLUE, Line.ORANGE)) return Station.BAYF
        if (samePair(first, second, Line.GREEN, Line.BLUE)) return Station.BAYF
        if (samePair(first, second, Line.ORANGE, Line.YELLOW)) {
            if (first == Line.ORANGE) {
                val orangeStart = if (transferIndex == 0) route.origin
                else route.transferStations[transferIndex - 1]
                val orangeEnd = route.transferStations[transferIndex]
                val direction = network.routePatternsForLine(Line.ORANGE)
                    .firstOrNull { pattern ->
                        val startIndex = pattern.stations.indexOf(orangeStart)
                        val endIndex = pattern.stations.indexOf(orangeEnd)
                        startIndex >= 0 && endIndex > startIndex
                    }
                    ?.direction
                return when (direction) {
                    "s" -> Station.MCAR
                    "n" -> Station._19TH
                    else -> Station.MCAR
                }
            }
            val orangeStart = route.transferStations[transferIndex]
            val orangeEnd = if (transferIndex + 1 < route.transferStations.size) {
                route.transferStations[transferIndex + 1]
            } else {
                route.destination
            }
            val stations = route.getStationSequence(Line.ORANGE)
            return if (stations.indexOf(orangeStart) > stations.indexOf(orangeEnd)) {
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
        private const val DEFAULT_PITT_TO_PCTR_MILLIS = 12L * 60L * 1000L
        private const val DEFAULT_PCTR_TO_ANTC_MILLIS = 7L * 60L * 1000L
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
                network.scheduledTripsFor(serviceDate)
                    .filter { it.trip.routeId in routeIds }
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
            val withContinuations = staticTrips + terminalContinuations(staticTrips, nominal, lines)
            return create(
                withContinuations,
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

        private fun terminalContinuations(
            trips: List<Trip>,
            nominal: Map<Pair<Station, Station>, Long>,
            lines: Set<Line>,
        ): List<Trip> {
            if (Line.YELLOW !in lines) return emptyList()
            val result = mutableListOf<Trip>()
            trips.filter { it.line == Line.YELLOW }.forEach { main ->
                val pittIndex = main.stops.indexOfFirst { it.station == Station.PITT }
                val pctrIndex = main.stops.indexOfFirst { it.station == Station.PCTR }
                val antcIndex = main.stops.indexOfFirst { it.station == Station.ANTC }

                // The supplied static feed often models the Antioch shuttle
                // as part of the same Yellow trip. Split that logical trip at
                // Pittsburg while retaining its static terminal times.
                if (pittIndex >= 0 && pctrIndex > pittIndex && antcIndex > pctrIndex) {
                    result += syntheticTrip(
                        main,
                        listOf(
                            main.stops[pittIndex],
                            main.stops[pctrIndex],
                            main.stops[antcIndex],
                        ),
                        "after",
                        "n",
                        preserveSchedule = true,
                    )
                }
                if (antcIndex >= 0 && pctrIndex > antcIndex && pittIndex > pctrIndex) {
                    result += syntheticTrip(
                        main,
                        listOf(
                            main.stops[antcIndex],
                            main.stops[pctrIndex],
                            main.stops[pittIndex],
                        ),
                        "before",
                        "s",
                        preserveSchedule = true,
                    )
                }

                // Retain a nominal extension for feeds whose static trip is
                // already short-turned at Pittsburg.
                if (pittIndex == main.stops.lastIndex) {
                    val pitt = main.stops.last()
                    val pittTime = pitt.arrivalTime.takeIf { it > 0L } ?: pitt.scheduledArrivalTime
                    if (pittTime > 0L) {
                        val toPctr = nominal[Station.PITT to Station.PCTR]
                            ?: DEFAULT_PITT_TO_PCTR_MILLIS
                        val toAntc = nominal[Station.PCTR to Station.ANTC]
                            ?: DEFAULT_PCTR_TO_ANTC_MILLIS
                        result += syntheticTrip(
                            main,
                            listOf(
                                Stop(Station.PITT, pittTime, pittTime),
                                Stop(Station.PCTR, pittTime + toPctr, pittTime + toPctr),
                                Stop(Station.ANTC, pittTime + toPctr + toAntc, pittTime + toPctr + toAntc),
                            ),
                            "after",
                            "n",
                            preserveSchedule = false,
                        )
                    }
                }
                if (pittIndex == 0) {
                    val pitt = main.stops.first()
                    val pittTime = pitt.departureTime.takeIf { it > 0L } ?: pitt.scheduledDepartureTime
                    if (pittTime > 0L) {
                        val fromPctr = nominal[Station.PCTR to Station.PITT]
                            ?: DEFAULT_PITT_TO_PCTR_MILLIS
                        val fromAntc = nominal[Station.ANTC to Station.PCTR]
                            ?: DEFAULT_PCTR_TO_ANTC_MILLIS
                        result += syntheticTrip(
                            main,
                            listOf(
                                Stop(Station.ANTC, pittTime - fromPctr - fromAntc, pittTime - fromPctr - fromAntc),
                                Stop(Station.PCTR, pittTime - fromPctr, pittTime - fromPctr),
                                Stop(Station.PITT, pittTime, pittTime),
                            ),
                            "before",
                            "s",
                            preserveSchedule = false,
                        )
                    }
                }
            }
            return result
        }

        private fun syntheticTrip(
            main: Trip,
            stops: List<Stop>,
            suffix: String,
            direction: String,
            preserveSchedule: Boolean,
        ): Trip = Trip(
            TripKey(main.key.serviceDate, "${main.key.tripId}-$suffix-dmu"),
            null,
            Line.YELLOW_DMU,
            direction,
            stops.last().station,
            immutableList(if (preserveSchedule) stops else stops.map { it.copy(
                scheduledArrivalTime = 0L,
                scheduledDepartureTime = 0L,
                arrivalSource = PredictionSource.ESTIMATE,
                departureSource = PredictionSource.ESTIMATE,
            ) }),
            canceled = main.canceled,
            synthetic = true,
        )

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
