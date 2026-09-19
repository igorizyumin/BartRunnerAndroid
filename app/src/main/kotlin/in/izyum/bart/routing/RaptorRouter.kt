package `in`.izyum.bart.routing

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import java.util.LinkedHashMap

/**
 * RAPTOR router for a time-scoped timetable. Each round advances one boarding
 * layer; the retained Pareto frontier gives the at-most-k result. A round scans
 * only patterns serving stations improved in the preceding layer, and each
 * such pattern is scanned once from its first marked stop. Timetable trips are
 * split into non-overtaking route groups so one active trip is sufficient
 * during a scan.
 *
 * BART transfers happen between lines serving the same station, so no separate
 * footpath relaxation is needed. Labels retain their incoming pattern because
 * transfer margins can depend on the incoming/outgoing line and Yellow route.
 */
class RaptorRouter(
    trips: List<Trip>,
    private val network: BartGtfsNetwork,
    private val transferPolicy: TransferPolicy = TransferPolicy(network),
) {
    data class StopTime(
        val station: Station,
        val arrivalTime: Long,
        val departureTime: Long,
        val scheduledArrivalTime: Long = 0L,
        val scheduledDepartureTime: Long = 0L,
        val arrivalSource: PredictionSource = PredictionSource.UNKNOWN,
        val departureSource: PredictionSource = PredictionSource.UNKNOWN,
        val platform: String? = null,
    )

    data class Trip(
        val id: String,
        val line: Line,
        val direction: String?,
        val trainDestination: Station?,
        val stops: List<StopTime>,
        val payload: Any? = null,
        val canceled: Boolean = false,
    )

    data class Leg(
        val trip: Trip,
        val fromIndex: Int,
        val toIndex: Int,
    ) {
        val origin: Station get() = trip.stops[fromIndex].station
        val destination: Station get() = trip.stops[toIndex].station
        val departureTime: Long get() = trip.stops[fromIndex].departureTime
        val arrivalTime: Long get() = trip.stops[toIndex].arrivalTime
    }

    data class Journey(val legs: List<Leg>) {
        val arrivalTime: Long get() = legs.lastOrNull()?.arrivalTime ?: 0L
        val transferCount: Int get() = (legs.size - 1).coerceAtLeast(0)
    }

    private data class PatternKey(val line: Line, val stations: List<Station>)

    /** A route group has a shared stop pattern and no overtaking trips. */
    private data class RoutePattern(
        val key: PatternKey,
        val tripsByStop: List<List<Trip>>,
    ) {
        val stations: List<Station> get() = key.stations
    }

    private data class RoutePosition(val routeIndex: Int, val stopIndex: Int)

    private data class LabelKey(
        val station: Station,
        val incomingLine: Line?,
        val incomingPattern: PatternKey?,
    )

    /** Parent-linked label: extending a journey never copies its leg list. */
    private data class Label(
        val station: Station,
        val arrivalTime: Long,
        val boardings: Int,
        val incomingLine: Line?,
        val incomingPattern: PatternKey?,
        val parent: Label?,
        val leg: Leg?,
    )

    private data class ActiveTrip(
        val trip: Trip,
        val boardIndex: Int,
        val parent: Label,
    )

    private data class BoardingCandidate(
        val trip: Trip,
        val boardIndex: Int,
        val parent: Label,
        val departureTime: Long,
    )

    private data class TransferMarginKey(
        val station: Station,
        val fromLine: Line,
        val incomingPattern: PatternKey?,
        val outgoingPattern: PatternKey,
    )

    private val routes: List<RoutePattern> = buildRoutes(trips)
    private val routesByStation: Array<MutableList<RoutePosition>> =
        Array(Station.values().size) { mutableListOf() }

    init {
        routes.forEachIndexed { routeIndex, route ->
            route.stations.forEachIndexed { stopIndex, station ->
                routesByStation[station.ordinal] += RoutePosition(routeIndex, stopIndex)
            }
        }
    }

    /** Returns the Pareto-optimal journeys by arrival time and boardings. */
    fun journeys(
        origin: Station,
        destination: Station,
        departureTime: Long,
        maxBoardings: Int = MAX_BOARDINGS,
    ): List<Journey> {
        if (origin == destination || departureTime <= 0L || maxBoardings <= 0) {
            return emptyList()
        }
        return search(origin, destination, departureTime, maxBoardings)
    }

    private fun search(
        origin: Station,
        destination: Station,
        departureTime: Long,
        maxBoardings: Int,
    ): List<Journey> {
        val labelsByState = LinkedHashMap<LabelKey, MutableList<Label>>()
        val destinationFrontier = mutableListOf<Label>()
        var roundLabelsByStation = Array(Station.values().size) { mutableListOf<Label>() }
        val initial = Label(
            station = origin,
            arrivalTime = departureTime,
            boardings = 0,
            incomingLine = null,
            incomingPattern = null,
            parent = null,
            leg = null,
        )
        labelsByState[labelKey(initial)] = mutableListOf(initial)
        roundLabelsByStation[origin.ordinal] += initial
        val transferMarginOffsets = HashMap<TransferMarginKey, Long?>()

        for (round in 1..maxBoardings) {
            val firstMarkedStop = IntArray(routes.size) { Int.MAX_VALUE }
            roundLabelsByStation.forEachIndexed { stationIndex, stationLabels ->
                if (stationLabels.isEmpty()) return@forEachIndexed
                routesByStation[stationIndex].forEach { position ->
                    if (position.stopIndex < firstMarkedStop[position.routeIndex]) {
                        firstMarkedStop[position.routeIndex] = position.stopIndex
                    }
                }
            }
            if (firstMarkedStop.all { it == Int.MAX_VALUE }) break

            val candidates = LinkedHashMap<LabelKey, Label>()
            routes.indices.forEach { routeIndex ->
                val startIndex = firstMarkedStop[routeIndex]
                if (startIndex != Int.MAX_VALUE) {
                    scanRoute(
                        route = routes[routeIndex],
                        startIndex = startIndex,
                        round = round,
                        destination = destination,
                        roundLabelsByStation = roundLabelsByStation,
                        transferMarginOffsets = transferMarginOffsets,
                        destinationFrontier = destinationFrontier,
                        candidates = candidates,
                    )
                }
            }

            if (candidates.isEmpty()) break
            val nextRoundLabelsByStation =
                Array(Station.values().size) { mutableListOf<Label>() }
            candidates.values.forEach { candidate ->
                val key = labelKey(candidate)
                val frontier = labelsByState.getOrPut(key) { mutableListOf() }
                if (isDominated(candidate, frontier, destination)) return@forEach

                frontier.removeAll { existing -> dominates(candidate, existing) }
                frontier += candidate
                if (candidate.station == destination) {
                    addDestinationLabel(
                        destinationFrontier,
                        candidate,
                        destination,
                    )
                } else if (!isDominatedByDestination(
                        candidate.arrivalTime,
                        candidate.boardings,
                        destinationFrontier,
                    )
                ) {
                    nextRoundLabelsByStation[candidate.station.ordinal] += candidate
                }
            }

            if (nextRoundLabelsByStation.all { it.isEmpty() }) break
            roundLabelsByStation = nextRoundLabelsByStation
        }

        return destinationFrontier
            .sortedWith(compareBy<Label> { it.arrivalTime }
                .thenBy { it.boardings }
                .thenBy { preferenceScore(it, destination) })
            .map(::journey)
    }

    private fun scanRoute(
        route: RoutePattern,
        startIndex: Int,
        round: Int,
        destination: Station,
        roundLabelsByStation: Array<MutableList<Label>>,
        transferMarginOffsets: MutableMap<TransferMarginKey, Long?>,
        destinationFrontier: List<Label>,
        candidates: MutableMap<LabelKey, Label>,
    ) {
        var activeTrip: ActiveTrip? = null
        for (stopIndex in startIndex..route.stations.lastIndex) {
            val station = route.stations[stopIndex]
            val nextBoarding = earliestBoarding(
                route = route,
                station = station,
                stopIndex = stopIndex,
                labels = roundLabelsByStation[station.ordinal],
                transferMarginOffsets = transferMarginOffsets,
                destination = destination,
            )
            if (nextBoarding != null && shouldReplaceActiveTrip(
                    activeTrip,
                    nextBoarding,
                    stopIndex,
                    destination,
                )
            ) {
                activeTrip = ActiveTrip(
                    nextBoarding.trip,
                    nextBoarding.boardIndex,
                    nextBoarding.parent,
                )
            }

            val ride = activeTrip ?: continue
            if (stopIndex <= ride.boardIndex) continue
            val arrival = ride.trip.stops[stopIndex].arrivalTime
            val departure = ride.trip.stops[ride.boardIndex].departureTime
            if (arrival <= departure || arrival <= 0L || departure <= 0L) continue

            val candidate = Label(
                station = station,
                arrivalTime = arrival,
                boardings = round,
                incomingLine = route.key.line,
                incomingPattern = route.key,
                parent = ride.parent,
                leg = Leg(ride.trip, ride.boardIndex, stopIndex),
            )
            val key = labelKey(candidate)
            val old = candidates[key]
            if (old == null || isBetter(candidate, old, destination)) {
                if (station == destination || !isDominatedByDestination(
                        candidate.arrivalTime,
                        candidate.boardings,
                        destinationFrontier,
                    )
                ) {
                    candidates[key] = candidate
                }
            }
        }
    }

    /** Finds the first catchable trip by a binary search at each possible label. */
    private fun earliestBoarding(
        route: RoutePattern,
        station: Station,
        stopIndex: Int,
        labels: List<Label>,
        transferMarginOffsets: MutableMap<TransferMarginKey, Long?>,
        destination: Station,
    ): BoardingCandidate? {
        if (labels.isEmpty()) return null
        val departures = route.tripsByStop[stopIndex]
        if (departures.isEmpty()) return null

        var best: BoardingCandidate? = null
        labels.forEach { label ->
            val earliestDeparture = earliestDepartureFor(
                label,
                station,
                route.key,
                transferMarginOffsets,
            ) ?: return@forEach
            val tripIndex = lowerBoundDeparture(departures, stopIndex, earliestDeparture)
            if (tripIndex == departures.size) return@forEach
            val trip = departures[tripIndex]
            val departure = trip.stops[stopIndex].departureTime
            val candidate = BoardingCandidate(
                trip = trip,
                boardIndex = stopIndex,
                parent = label,
                departureTime = departure,
            )
            val currentBest = best
            if (currentBest == null || isBetterBoarding(
                    candidate,
                    currentBest,
                    stopIndex,
                    destination,
                )
            ) {
                best = candidate
            }
        }
        return best
    }

    private fun earliestDepartureFor(
        label: Label,
        station: Station,
        outgoingPattern: PatternKey,
        transferMarginOffsets: MutableMap<TransferMarginKey, Long?>,
    ): Long? {
        val incomingLine = label.incomingLine ?: return label.arrivalTime
        val key = TransferMarginKey(
            station,
            incomingLine,
            label.incomingPattern,
            outgoingPattern,
        )
        if (!transferMarginOffsets.containsKey(key)) {
            val yellowSequence = when {
                incomingLine == Line.YELLOW -> label.incomingPattern?.stations
                outgoingPattern.line == Line.YELLOW -> outgoingPattern.stations
                else -> null
            }
            val earliestAtOne = transferPolicy.earliestTransferDepartureTime(
                arrivalTime = 1L,
                transferStation = station,
                fromLine = incomingLine,
                toLine = outgoingPattern.line,
                yellowStationSequence = yellowSequence,
            )
            transferMarginOffsets[key] = earliestAtOne?.minus(1L)
        }
        val offset = transferMarginOffsets[key] ?: return null
        if (label.arrivalTime > Long.MAX_VALUE - offset) return null
        return label.arrivalTime + offset
    }

    private fun lowerBoundDeparture(trips: List<Trip>, stopIndex: Int, time: Long): Int {
        var low = 0
        var high = trips.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (trips[middle].stops[stopIndex].departureTime < time) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    private fun shouldReplaceActiveTrip(
        active: ActiveTrip?,
        candidate: BoardingCandidate,
        stopIndex: Int,
        destination: Station,
    ): Boolean {
        if (active == null) return true
        val activeDeparture = active.trip.stops[stopIndex].departureTime
        if (activeDeparture > 0L && candidate.departureTime != activeDeparture) {
            return candidate.departureTime < activeDeparture
        }
        val timeComparison = compareDownstreamArrivals(
            candidate.trip,
            active.trip,
            stopIndex,
        )
        if (timeComparison != 0) return timeComparison < 0
        return preferenceScoreAfterBoarding(
            candidate.parent,
            candidate.trip,
            candidate.boardIndex,
            destination,
        ) < preferenceScoreAfterBoarding(
            active.parent,
            active.trip,
            active.boardIndex,
            destination,
        )
    }

    /** Trips in one route group are non-overtaking, so this comparison is stable. */
    private fun compareDownstreamArrivals(
        first: Trip,
        second: Trip,
        afterStopIndex: Int,
    ): Int {
        for (index in afterStopIndex + 1 until minOf(first.stops.size, second.stops.size)) {
            val firstArrival = first.stops[index].arrivalTime
            val secondArrival = second.stops[index].arrivalTime
            if (firstArrival > 0L && secondArrival > 0L && firstArrival != secondArrival) {
                return firstArrival.compareTo(secondArrival)
            }
            val firstDeparture = first.stops[index].departureTime
            val secondDeparture = second.stops[index].departureTime
            if (firstDeparture > 0L && secondDeparture > 0L && firstDeparture != secondDeparture) {
                return firstDeparture.compareTo(secondDeparture)
            }
        }
        return first.id.compareTo(second.id)
    }

    private fun isBetterBoarding(
        candidate: BoardingCandidate,
        existing: BoardingCandidate,
        stopIndex: Int,
        destination: Station,
    ): Boolean {
        if (candidate.departureTime != existing.departureTime) {
            return candidate.departureTime < existing.departureTime
        }
        if (candidate.trip != existing.trip) {
            val timeComparison = compareDownstreamArrivals(
                candidate.trip,
                existing.trip,
                stopIndex,
            )
            if (timeComparison != 0) return timeComparison < 0
        }
        return preferenceScoreAfterBoarding(
            candidate.parent,
            candidate.trip,
            candidate.boardIndex,
            destination,
        ) < preferenceScoreAfterBoarding(
            existing.parent,
            existing.trip,
            existing.boardIndex,
            destination,
        )
    }

    private fun isBetter(candidate: Label, existing: Label, destination: Station): Boolean =
        candidate.arrivalTime < existing.arrivalTime
            || (candidate.arrivalTime == existing.arrivalTime
                && preferenceScore(candidate, destination)
                    < preferenceScore(existing, destination))

    private fun preferenceScoreAfterBoarding(
        parent: Label,
        trip: Trip,
        boardIndex: Int,
        destination: Station,
    ): Int = preferenceScore(
        Label(
            station = trip.stops.last().station,
            arrivalTime = trip.stops.last().arrivalTime,
            boardings = parent.boardings + 1,
            incomingLine = trip.line,
            incomingPattern = PatternKey(trip.line, trip.stops.map { it.station }),
            parent = parent,
            leg = Leg(trip, boardIndex, trip.stops.lastIndex),
        ),
        destination,
    )

    private fun preferenceScore(label: Label, destination: Station): Int {
        val legs = journey(label).legs
        if (legs.isEmpty()) return Int.MAX_VALUE
        val origin = legs.first().origin
        if (legs.size == 1) {
            val leg = legs.single()
            return transferPolicy.routeScore(
                Route.direct(
                    origin,
                    destination,
                    leg.trip.line,
                    leg.trip.direction,
                    leg.trip.stops.map { it.station },
                )
            )
        }
        val sequences = LinkedHashMap<Line, List<Station>>()
        legs.forEach { leg -> sequences[leg.trip.line] = leg.trip.stops.map { it.station } }
        return transferPolicy.routeScore(
            Route.transfer(
                origin,
                destination,
                legs.map { it.trip.line },
                legs.dropLast(1).map { it.destination },
                legs.first().trip.direction,
                sequences,
            )
        )
    }

    private fun journey(label: Label): Journey {
        val legs = ArrayList<Leg>(label.boardings)
        var current: Label? = label
        while (current != null) {
            current.leg?.let(legs::add)
            current = current.parent
        }
        legs.reverse()
        return Journey(legs)
    }

    private fun labelKey(label: Label): LabelKey = LabelKey(
        label.station,
        label.incomingLine,
        label.incomingPattern,
    )

    private fun isDominated(
        candidate: Label,
        frontier: List<Label>,
        destination: Station,
    ): Boolean =
        frontier.any { existing ->
            dominates(existing, candidate)
                && (existing.arrivalTime < candidate.arrivalTime
                    || existing.boardings < candidate.boardings
                    || preferenceScore(existing, destination)
                        <= preferenceScore(candidate, destination))
        }

    private fun dominates(first: Label, second: Label): Boolean =
        first.arrivalTime <= second.arrivalTime && first.boardings <= second.boardings

    private fun addDestinationLabel(
        frontier: MutableList<Label>,
        candidate: Label,
        destination: Station,
    ) {
        val candidateScore = preferenceScore(candidate, destination)
        val existingEquivalent = frontier.firstOrNull {
            it.arrivalTime == candidate.arrivalTime && it.boardings == candidate.boardings
        }
        if (existingEquivalent != null
            && preferenceScore(existingEquivalent, destination) <= candidateScore
        ) return
        if (frontier.any { dominates(it, candidate) &&
                (it.arrivalTime < candidate.arrivalTime
                    || it.boardings < candidate.boardings)
            }
        ) return
        frontier.removeAll { existing ->
            dominates(candidate, existing)
                && (candidate.arrivalTime < existing.arrivalTime
                    || candidate.boardings < existing.boardings
                    || candidateScore < preferenceScore(existing, destination))
        }
        frontier += candidate
    }

    private fun isDominatedByDestination(
        arrivalTime: Long,
        boardings: Int,
        destinationFrontier: List<Label>,
    ): Boolean = destinationFrontier.any {
        it.boardings <= boardings && it.arrivalTime <= arrivalTime
    }

    private fun buildRoutes(inputTrips: List<Trip>): List<RoutePattern> {
        val groupedByPattern = inputTrips.asSequence()
            .filter { !it.canceled && it.stops.size >= 2 }
            .groupBy { trip ->
                PatternKey(trip.line, trip.stops.map { it.station })
            }
        return groupedByPattern.flatMap { (key, patternTrips) ->
            if (key.stations.size < 2) return@flatMap emptyList()
            splitIntoNonOvertakingGroups(patternTrips, key.stations.size).map { group ->
                val tripsByStop = key.stations.indices.map { stopIndex ->
                    if (stopIndex == key.stations.lastIndex) {
                        emptyList()
                    } else {
                        group.asSequence()
                            .filter { it.stops[stopIndex].departureTime > 0L }
                            .sortedWith(compareBy<Trip> { it.stops[stopIndex].departureTime }
                                .thenBy { it.id })
                            .toList()
                    }
                }
                RoutePattern(key, tripsByStop)
            }
        }
    }

    private fun splitIntoNonOvertakingGroups(
        trips: List<Trip>,
        stopCount: Int,
    ): List<List<Trip>> {
        val sorted = trips.sortedWith(
            compareBy<Trip> {
                it.stops.first().departureTime.takeIf { time -> time > 0L } ?: Long.MAX_VALUE
            }.thenBy { it.id },
        )
        val result = mutableListOf<List<Trip>>()
        var current = mutableListOf<Trip>()
        var latestArrivals = LongArray(stopCount)
        var latestDepartures = LongArray(stopCount)

        fun startsNewGroup(trip: Trip): Boolean = (0 until stopCount).any { index ->
            val arrival = trip.stops[index].arrivalTime
            val departure = trip.stops[index].departureTime
            (arrival > 0L && latestArrivals[index] > arrival)
                || (departure > 0L && latestDepartures[index] > departure)
        }

        fun addToGroup(trip: Trip) {
            current += trip
            for (index in 0 until stopCount) {
                val arrival = trip.stops[index].arrivalTime
                val departure = trip.stops[index].departureTime
                if (arrival > latestArrivals[index]) latestArrivals[index] = arrival
                if (departure > latestDepartures[index]) latestDepartures[index] = departure
            }
        }

        sorted.forEach { trip ->
            if (current.isNotEmpty() && startsNewGroup(trip)) {
                result += current
                current = mutableListOf()
                latestArrivals = LongArray(stopCount)
                latestDepartures = LongArray(stopCount)
            }
            addToGroup(trip)
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    companion object {
        const val MAX_BOARDINGS = 6
    }
}
