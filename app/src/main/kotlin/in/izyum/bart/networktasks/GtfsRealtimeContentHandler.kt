package `in`.izyum.bart.networktasks

import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.RealTimeDepartures
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.TripStop
import `in`.izyum.bart.model.SystemTimeSource
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.routing.TransferPolicy
import `in`.izyum.bart.routing.RaptorRouter
import `in`.izyum.bart.backend.Schedule
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import com.google.transit.realtime.GtfsRealtime
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Converts BART's GTFS-RT trip updates into the app's departure model. */
class GtfsRealtimeContentHandler @JvmOverloads constructor(
    private val origin: Station,
    private val destination: Station?,
    private val routes: List<Route>,
    private val ignoreDirection: Boolean,
    private val bartGtfsNetwork: BartGtfsNetwork,
    private val timeSource: TimeSource = SystemTimeSource,
) {
    private val transferPolicy = TransferPolicy(bartGtfsNetwork)

    init {
        requireNotNull(bartGtfsNetwork) { "A validated GTFS network is required" }
    }

    fun getRealTimeDepartures(feed: GtfsRealtime.FeedMessage): RealTimeDepartures {
        val feedIndex = GtfsRealtimeFeedIndex.from(feed)
        val feedTime = feedTime(feed)
        return getRealTimeDepartures(
            feedIndex,
            feedTime,
            correctedSchedule(feedIndex, feedTime),
        )
    }

    fun getRealTimeDepartures(
        feedIndex: GtfsRealtimeFeedIndex,
        feedTime: Long
    ): RealTimeDepartures = getRealTimeDepartures(
        feedIndex,
        feedTime,
        correctedSchedule(feedIndex, feedTime),
    )

    /** Uses a schedule that has already had this feed's realtime corrections applied. */
    fun getRealTimeDepartures(
        feedIndex: GtfsRealtimeFeedIndex,
        feedTime: Long,
        schedule: Schedule,
        excludedTripIds: Set<String> = emptySet(),
        suppressScheduleCoveredByRealtime: Boolean = true,
        forcedScheduleTripIds: Set<String> = emptySet(),
    ): RealTimeDepartures {
        val departures = DepartureCollection()

        val trips = parseTrips(
            feedIndex.tripUpdateEntities,
            feedTime,
            schedule,
            suppressScheduleCoveredByRealtime,
            forcedScheduleTripIds,
        )
            .filter { it.tripId !in excludedTripIds }
        val raptorTrips = trips.map(::raptorTrip)
        val raptorTripsBySnapshot = trips.zip(raptorTrips).toMap()
        val raptorRouter = RaptorRouter(raptorTrips, bartGtfsNetwork, transferPolicy)
        trips.forEach { trip ->
            addTripUpdate(
                departures,
                trip,
                trips,
                feedTime,
                raptorRouter,
                raptorTripsBySnapshot,
            )
        }
        return RealTimeDepartures(
            origin,
            destination,
            feedTime,
            routes,
            departures.unfiltered,
            departures.filtered,
            schedule,
        )
    }

    /**
     * Replaces the stop estimates for an already-selected itinerary using
     * the latest update for each exact train. A train may no longer include
     * the passenger's origin in the feed after it has departed, so missing
     * passed stops are deliberately retained from the previous snapshot.
     */
    fun updateTripLegs(
        feed: GtfsRealtime.FeedMessage,
        existingLegs: List<TripLeg>,
        feedTime: Long
    ): List<TripLeg> {
        val feedIndex = GtfsRealtimeFeedIndex.from(feed)
        return updateTripLegs(
            feedIndex,
            existingLegs,
            feedTime,
            correctedSchedule(feedIndex, feedTime),
        )
    }

    fun updateTripLegs(
        feedIndex: GtfsRealtimeFeedIndex,
        existingLegs: List<TripLeg>,
        feedTime: Long
    ): List<TripLeg> = updateTripLegs(
        feedIndex,
        existingLegs,
        feedTime,
        correctedSchedule(feedIndex, feedTime),
    )

    /** Uses a schedule that has already had this feed's realtime corrections applied. */
    fun updateTripLegs(
        feedIndex: GtfsRealtimeFeedIndex,
        existingLegs: List<TripLeg>,
        feedTime: Long,
        schedule: Schedule
    ): List<TripLeg> {
        val trips = parseTrips(
            feedIndex.tripUpdateEntities,
            feedTime,
            schedule,
            true,
        )
        val tripsById = trips
            .filter { !it.tripId.isNullOrEmpty() }
            .associateBy { it.tripId!! }

        val updatedLegs = existingLegs.map { existing ->
            val current = tripsById[existing.tripId] ?: findMatchingTrip(existing, trips)
            if (current == null) existing else updateTripLeg(existing, current)
        }.toMutableList()

        val route = routes.firstOrNull { matchesExistingLegs(it, updatedLegs) }
        if (route != null) {
            refreshConnectingLegs(route, updatedLegs, trips)
        }
        return updatedLegs
    }

    /**
     * Revalidates connections after each leg has been independently refreshed.
     * A delay can invalidate the previously selected next train, so replace it
     * with the first feasible later train or remove the unusable suffix.
     */
    private fun refreshConnectingLegs(
        route: Route,
        legs: MutableList<TripLeg>,
        trips: List<TripSnapshot>,
    ) {
        var index = 1
        while (index < route.lines.size) {
            val previous = legs.getOrNull(index - 1) ?: return
            val legOrigin = previous.destination ?: return
            val legDestination = if (index < route.transferStations.size) {
                route.transferStations[index]
            } else {
                destination ?: return
            }
            val existing = legs.getOrNull(index)
            if (existing != null
                && existing.line == route.lines[index]
                && existing.origin == legOrigin
                && existing.destination == legDestination
                && !isCanceled(existing, trips)
                && connectionIsFeasible(previous, existing)
            ) {
                index++
                continue
            }

            val connectingTrip = findConnectingTrip(
                route.lines[index],
                legOrigin,
                legDestination,
                previous,
                trips,
            )
            if (connectingTrip != null) {
                val replacement = tripLegFor(
                    route, index, legOrigin, legDestination, connectingTrip
                )
                if (existing == null) legs += replacement else legs[index] = replacement
                index++
                continue
            }

            while (legs.size > index) legs.removeAt(legs.lastIndex)
            return
        }
    }

    private fun connectionIsFeasible(previous: TripLeg, next: TripLeg): Boolean =
        previous.arrivalTime <= 0L || next.departureTime <= 0L
            || next.departureTime >= previous.arrivalTime

    private fun isCanceled(leg: TripLeg, trips: List<TripSnapshot>): Boolean =
        leg.tripId != null && trips.any { it.tripId == leg.tripId && it.canceled }

    private fun matchesExistingLegs(route: Route, legs: List<TripLeg>): Boolean {
        if (legs.isEmpty() || legs.size > route.lines.size) {
            return false
        }
        for (index in legs.indices) {
            val leg = legs[index]
            val expectedOrigin = if (index == 0) origin else legs[index - 1].destination
            if (leg.line != route.lines[index] || leg.origin != expectedOrigin) {
                return false
            }

            val expectedDestination = if (index < route.transferStations.size) {
                route.transferStations[index]
            } else {
                destination
            }
            if (leg.destination == expectedDestination) {
                continue
            }

            // A previously selected itinerary can end partway through a
            // route segment when the next connection was not present in the
            // feed. It is still the same line sequence, so allow the refresh
            // to continue from that actual station.
            if (index != legs.lastIndex || index >= route.transferStations.size
                || !isBeforeOnRoute(route, route.lines[index], leg.destination,
                    expectedDestination)
            ) {
                return false
            }
        }
        return true
    }

    private fun isBeforeOnRoute(
        route: Route,
        line: Line,
        station: Station?,
        destination: Station?
    ): Boolean {
        val sequence = route.getStationSequence(line)
        val stationIndex = sequence.indexOf(station)
        val destinationIndex = sequence.indexOf(destination)
        return stationIndex >= 0 && destinationIndex >= 0 && stationIndex < destinationIndex
    }

    private fun tripLegFor(
        route: Route,
        index: Int,
        legOrigin: Station,
        legDestination: Station,
        trip: TripSnapshot,
    ): TripLeg {
        val departure = trip.pointAt(legOrigin)
        val arrival = trip.pointAt(legDestination)
        val stops = trip.pointsBetween(legOrigin, legDestination).map {
            tripStop(it)
        }
        return TripLeg(
            lineForLeg(trip.line, legOrigin, legDestination, trip.trainDestination),
            legOrigin,
            legDestination,
            trip.trainDestination,
            trip.tripId,
            departure?.departureTime ?: 0L,
            arrival?.arrivalTime ?: 0L,
            stops,
            minimumTransferSecondsAfter(route, index, legDestination, trip.line),
            departure?.scheduledDepartureTime ?: 0L,
            arrival?.scheduledArrivalTime ?: 0L,
            departure?.departureSource ?: PredictionSource.UNKNOWN,
            arrival?.arrivalSource ?: PredictionSource.UNKNOWN,
            departure?.platform,
        )
    }

    private fun parseTrips(
        entities: List<GtfsRealtime.FeedEntity>,
        feedTime: Long,
        schedule: Schedule,
        suppressScheduleCoveredByRealtime: Boolean,
        forcedScheduleTripIds: Set<String> = emptySet(),
    ): List<TripSnapshot> {
        val scheduledTrips = schedule.trips.map(::snapshotFromSchedule).toMutableList()
        val scheduledIds = scheduledTrips.mapNotNull { it.tripId }.toSet()
        val realtimeTrips = entities.mapNotNull { entity ->
            if (!entity.hasTripUpdate()) null else parseTrip(entity.tripUpdate, feedTime)
        }.toMutableList()
        val scheduledById = scheduledTrips.mapNotNull { trip ->
            trip.tripId?.let { it to trip }
        }.toMap()
        realtimeTrips.forEach { realtime ->
            scheduledById[realtime.tripId]?.let { static ->
                applyStaticScheduleTimes(realtime, static)
            }
        }
        // The Antioch DMU and the electric train are reported as separate
        // updates.  DMU trip IDs are technical 600-series IDs, so they cannot
        // be used as passenger trip identities.  Join their terminal points
        // onto the nearest valid electric realtime trip before discarding the
        // DMU-only snapshot.  Scheduled electric trips are also corrected in
        // Schedule.applyRealtime; doing this here covers electric updates that
        // are not present in the static schedule snapshot.
        val dmuMergedTripIds = joinDmuRealtimeTrips(realtimeTrips, schedule)
        val realtimeOnly = realtimeTrips.filter { trip ->
            trip.tripId !in scheduledIds || trip.tripId in dmuMergedTripIds
        }
        // Once realtime has reported a departure at this station, the static
        // schedule is not allowed to invent service during the first hour.
        // Beyond that cancellation window, the cutoff follows only the
        // matching branch's latest realtime prediction.
        val latestRealtimeDepartureByBranch = realtimeTrips
            .mapNotNull { trip ->
                val point = trip.pointAt(origin) ?: return@mapNotNull null
                if (point.departureSource != PredictionSource.REALTIME
                    || point.departureTime < feedTime
                ) {
                    return@mapNotNull null
                }
                coverageKey(trip.line, trip.trainDestination) to point.departureTime
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, times) -> times.maxOrNull()!! }
        val hasForwardRealtimeAtStation = latestRealtimeDepartureByBranch.isNotEmpty()
        val coveredSchedule = if (!suppressScheduleCoveredByRealtime) {
            scheduledTrips
        } else {
            scheduledTrips.filter { trip ->
                if (trip.tripId in forcedScheduleTripIds) {
                    true
                } else if (trip.tripId in dmuMergedTripIds) {
                    // The DMU-enriched realtime snapshot is the passenger
                    // copy for this trip. Do not emit its un-enriched static
                    // counterpart as a duplicate.
                    false
                } else {
                    val originPoint = trip.pointAt(origin)
                    if (originPoint == null
                        || originPoint.departureSource != PredictionSource.SCHEDULE
                        || originPoint.departureTime < feedTime
                    ) {
                        true
                    } else {
                        val latest = latestRealtimeDepartureByBranch[
                            coverageKey(trip.line, trip.trainDestination)
                        ]
                        val inCancellationWindow = originPoint.departureTime <=
                            feedTime + REALTIME_CANCELLATION_WINDOW_MILLIS
                        if (inCancellationWindow) {
                            !hasForwardRealtimeAtStation
                        } else {
                            latest == null ||
                                latest <= feedTime + REALTIME_CANCELLATION_WINDOW_MILLIS ||
                                originPoint.departureTime > latest
                        }
                    }
                }
            }
        }
        return coveredSchedule + realtimeOnly.filter {
            it.line != Line.YELLOW_DMU
        }
    }

    private fun joinDmuRealtimeTrips(
        realtimeTrips: MutableList<TripSnapshot>,
        schedule: Schedule,
    ): Set<String> {
        val electricTrips = realtimeTrips.filter { trip ->
            trip.line == Line.YELLOW
                && !trip.canceled
                && !isDmuTripId(trip.tripId)
                && !trip.tripId.isNullOrEmpty()
        }
        val usedElectricTrips = mutableSetOf<TripSnapshot>()
        val mergedTripIds = mutableSetOf<String>()
        realtimeTrips.filter { it.line == Line.YELLOW_DMU }.forEach { dmu ->
            val direction = terminalDirection(dmu)
            val terminal = terminalPoint(dmu, direction) ?: return@forEach
            val match = electricTrips
                .filter { candidate ->
                    candidate !in usedElectricTrips
                        && (direction == null || candidate.direction == null
                        || candidate.direction == direction
                        || staticTripAllowsTerminal(candidate, direction, schedule))
                        && staticTripAllowsTerminal(candidate, direction, schedule)
                }
                .mapNotNull { candidate ->
                    terminalMatchDelta(candidate, terminal.station, terminal.time, direction, schedule)
                        ?.let { candidate to it }
                }
                .filter { (_, delta) -> delta <= DMU_MATCH_MAX_MILLIS }
                .minByOrNull { (_, delta) -> delta }
                ?.first
            if (match != null) {
                usedElectricTrips += match
                mergeRealtimeTrip(match, dmu)
                match.tripId?.let(mergedTripIds::add)
            }
        }
        return mergedTripIds
    }

    /** Preserve timetable values on a live snapshot while replacing only its
     * effective (predicted) values with realtime data. */
    private fun applyStaticScheduleTimes(
        realtime: TripSnapshot,
        static: TripSnapshot,
    ) {
        realtime.points.replaceAll { point ->
            val scheduled = static.pointAt(point.station)
            if (scheduled == null) {
                point
            } else {
                StopTimePoint(
                    point.station,
                    point.order,
                    point.departureTime,
                    point.arrivalTime,
                    scheduled.scheduledDepartureTime,
                    scheduled.scheduledArrivalTime,
                    point.departureSource,
                    point.arrivalSource,
                    point.platform ?: scheduled.platform,
                )
            }
        }
    }

    /**
     * DMU telemetry has no passenger trip identity. When its electric partner
     * does have a static trip ID, use the static itinerary to distinguish the
     * alternating PITT-only and Antioch trains. An unknown realtime ID remains
     * eligible because it may represent a genuinely unscheduled train.
     */
    private fun staticTripAllowsTerminal(
        candidate: TripSnapshot,
        direction: String?,
        schedule: Schedule,
    ): Boolean {
        val tripId = candidate.tripId ?: return true
        val staticTrip = schedule.trips.firstOrNull { it.key.tripId == tripId }
            ?: return true
        return when (direction ?: candidate.direction) {
            "n" -> staticTrip.canServe(Station.PITT, Station.ANTC)
            "s" -> staticTrip.canServe(Station.ANTC, Station.PITT)
            else -> true
        }
    }

    private fun terminalDirection(trip: TripSnapshot): String? =
        (trip.platform ?: trip.feedPlatform)?.let {
            when (it) {
                "1" -> "n"
                "2" -> "s"
                else -> null
            }
        }

    private fun terminalPoint(
        trip: TripSnapshot,
        direction: String?,
    ): TerminalPoint? {
        val station = when (direction) {
            "n" -> Station.PCTR
            "s" -> Station.ANTC
            else -> null
        }
        val point = station?.let { trip.pointAt(it) }
            ?: trip.pointAt(Station.ANTC)
            ?: trip.pointAt(Station.PCTR)
            ?: return null
        val time = point.departureTime.takeIf { it > 0L }
            ?: point.arrivalTime.takeIf { it > 0L }
            ?: return null
        return TerminalPoint(point.station, time)
    }

    private fun terminalMatchDelta(
        candidate: TripSnapshot,
        terminalStation: Station,
        terminalTime: Long,
        direction: String?,
        schedule: Schedule,
    ): Long? {
        val direct = candidate.pointAt(terminalStation)?.let { point ->
            val time = point.departureTime.takeIf { it > 0L }
                ?: point.arrivalTime.takeIf { it > 0L }
            time?.let { kotlin.math.abs(it - terminalTime) }
        }
        if (direct != null) return direct

        val pitt = candidate.pointAt(Station.PITT) ?: return null
        val pittTime = pitt.departureTime.takeIf { it > 0L }
            ?: pitt.arrivalTime.takeIf { it > 0L }
            ?: return null
        val travelDirection = direction ?: candidate.direction ?: return null
        val travel = travelTimeBetween(
            Station.PITT, terminalStation, travelDirection, schedule
        ) ?: scheduledTripTravelBetween(
            candidate.tripId, Station.PITT, terminalStation, schedule
        )
            ?: return null
        // Project along the train's direction: northbound PITT -> PCTR adds
        // running time, while southbound PITT -> ANTC subtracts it.
        val projected = when (travelDirection) {
            "n" -> pittTime + travel
            "s" -> pittTime - travel
            else -> return null
        }
        return kotlin.math.abs(projected - terminalTime)
    }

    private fun scheduledTripTravelBetween(
        tripId: String?,
        from: Station,
        to: Station,
        schedule: Schedule,
    ): Long? {
        val trip = tripId?.let { id -> schedule.trips.firstOrNull { it.key.tripId == id } }
            ?: return null
        val fromIndex = trip.stops.indexOfFirst { it.station == from }
        val toIndex = trip.stops.indexOfFirst { it.station == to }
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return null
        val step = if (toIndex > fromIndex) 1 else -1
        var total = 0L
        var index = fromIndex
        while (index != toIndex) {
            val next = index + step
            val currentStop = trip.stops[index]
            val nextStop = trip.stops[next]
            val running = if (step > 0) {
                nextStop.scheduledArrivalTime - currentStop.scheduledDepartureTime
            } else {
                currentStop.scheduledArrivalTime - nextStop.scheduledDepartureTime
            }
            if (running <= 0L) return null
            total += running
            index = next
        }
        return total
    }

    private fun travelTimeBetween(
        from: Station,
        to: Station,
        direction: String?,
        schedule: Schedule,
    ): Long? {
        val pattern = bartGtfsNetwork.routePatternsForLine(Line.YELLOW)
            .firstOrNull { direction == null || it.direction == null || it.direction == direction }
            ?: return null
        val fromIndex = pattern.stations.indexOf(from)
        val toIndex = pattern.stations.indexOf(to)
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return null
        val step = if (toIndex > fromIndex) 1 else -1
        var total = 0L
        var index = fromIndex
        while (index != toIndex) {
            val next = index + step
            total += schedule.nominalTravelTimeMillis(
                pattern.stations[index], pattern.stations[next]
            ) ?: schedule.nominalTravelTimeMillis(
                pattern.stations[next], pattern.stations[index]
            ) ?: return null
            index = next
        }
        return total
    }

    private fun isDmuTripId(tripId: String?): Boolean =
        tripId?.toIntOrNull()?.let { it in 600..799 } == true

    private data class TerminalPoint(val station: Station, val time: Long)

    private fun coverageKey(line: Line, destination: Station?): CoverageKey = CoverageKey(
        when (line) {
            Line.YELLOW_LATE_NIGHT -> Line.YELLOW
            else -> line
        },
        if (line in setOf(Line.YELLOW, Line.YELLOW_LATE_NIGHT)
            && destination in setOf(Station.MLBR, Station.SFIA)
        ) {
            Station.SFIA
        } else {
            destination
        },
    )

    private data class CoverageKey(
        val line: Line,
        val destination: Station?,
    )

    private fun correctedSchedule(
        feedIndex: GtfsRealtimeFeedIndex,
        feedTime: Long,
    ): Schedule = Schedule.fromStatic(
        bartGtfsNetwork,
        feedTime,
        Line.values().toSet(),
    ).applyRealtime(feedIndex)

    private fun snapshotFromSchedule(trip: Schedule.Trip): TripSnapshot {
        val result = TripSnapshot(
            trip.key.tripId,
            trip.line,
            trip.direction,
        )
        result.trainDestination = trip.trainDestination
        result.platform = trip.stopAt(origin)?.platform
        result.feedPlatform = trip.stopAt(Station.PITT)?.platform
        result.canceled = trip.canceled
        trip.stops.forEachIndexed { index, stop ->
            result.points += StopTimePoint(
                stop.station,
                index,
                stop.departureTime,
                stop.arrivalTime,
                stop.scheduledDepartureTime,
                stop.scheduledArrivalTime,
                stop.departureSource,
                stop.arrivalSource,
                stop.platform,
            )
        }
        result.lastOrder = trip.stops.lastIndex
        return result
    }

    private fun raptorTrip(trip: TripSnapshot): RaptorRouter.Trip {
        val points = trip.points.sortedBy { it.order }.distinctBy { it.station }
        val stations = points.map { it.station }
        return RaptorRouter.Trip(
            id = trip.tripId ?: "snapshot-${System.identityHashCode(trip)}",
            routeKey = "${trip.line}:${stations.joinToString(",")}",
            line = trip.line,
            direction = trip.direction,
            trainDestination = trip.trainDestination,
            stops = points.map { point ->
                RaptorRouter.StopTime(
                    point.station,
                    point.arrivalTime,
                    point.departureTime,
                    point.scheduledArrivalTime,
                    point.scheduledDepartureTime,
                    point.arrivalSource,
                    point.departureSource,
                    point.platform,
                )
            },
            payload = trip,
            canceled = trip.canceled,
        )
    }

    private fun routeForJourney(journey: RaptorRouter.Journey): Route? {
        val legs = journey.legs
        val origin = legs.firstOrNull()?.origin ?: return null
        val destination = legs.lastOrNull()?.destination ?: return null
        if (legs.size == 1) {
            val leg = legs.single()
            return Route.direct(
                origin,
                destination,
                leg.trip.line,
                leg.trip.direction,
                leg.trip.stops.map { it.station },
            )
        }
        val sequences = LinkedHashMap<Line, List<Station>>()
        legs.forEach { leg ->
            sequences[leg.trip.line] = leg.trip.stops.map { it.station }
        }
        return Route.transfer(
            origin,
            destination,
            legs.map { it.trip.line },
            legs.dropLast(1).map { it.destination },
            legs.first().trip.direction,
            sequences,
        )
    }

    private fun mergeRealtimeTrip(target: TripSnapshot, realtime: TripSnapshot) {
        target.platform = realtime.platform ?: target.platform
        target.canceled = target.canceled || realtime.canceled
        realtime.points.forEach { realtimePoint ->
            val existing = target.pointAt(realtimePoint.station)
            if (existing == null) {
                target.points += StopTimePoint(
                    realtimePoint.station,
                    stationOrder(target, realtimePoint.station) ?: realtimePoint.order,
                    realtimePoint.departureTime,
                    realtimePoint.arrivalTime,
                    realtimePoint.scheduledDepartureTime,
                    realtimePoint.scheduledArrivalTime,
                    realtimePoint.departureSource,
                    realtimePoint.arrivalSource,
                    realtimePoint.platform,
                )
            } else {
                target.points.remove(existing)
                target.points += StopTimePoint(
                    existing.station,
                    existing.order,
                    realtimePoint.departureTime,
                    realtimePoint.arrivalTime,
                    existing.scheduledDepartureTime,
                    existing.scheduledArrivalTime,
                    PredictionSource.REALTIME,
                    PredictionSource.REALTIME,
                    realtimePoint.platform ?: existing.platform,
                )
            }
        }
        target.points.sortBy { it.order }
        target.lastOrder = target.points.maxOfOrNull { it.order } ?: target.lastOrder
        val realtimeDestination = realtime.trainDestination
        val targetDestination = target.trainDestination
        if (realtimeDestination != null
            && (targetDestination == null
                || (stationOrder(target, realtimeDestination) ?: Int.MIN_VALUE)
                    > (stationOrder(target, targetDestination) ?: Int.MIN_VALUE))
        ) {
            target.trainDestination = realtimeDestination
        }
    }

    private fun stationOrder(trip: TripSnapshot, station: Station): Int? =
        bartGtfsNetwork.routePatternsForLine(trip.line)
            .filter { trip.direction == null || it.direction == null || it.direction == trip.direction }
            .mapNotNull { pattern -> pattern.stations.indexOf(station).takeIf { it >= 0 } }
            .minOrNull()

    private fun findMatchingTrip(leg: TripLeg, trips: List<TripSnapshot>): TripSnapshot? =
        trips.firstOrNull { trip ->
            !trip.canceled
                && trip.line == leg.line
                && trip.trainDestination == leg.trainDestination
                && trip.canServe(leg.origin, leg.destination)
        }

    private fun updateTripLeg(existing: TripLeg, trip: TripSnapshot): TripLeg {
        val originPoint = trip.pointAt(existing.origin)
        val destinationPoint = trip.pointAt(existing.destination)
        val departureTime = originPoint?.departureTime ?: existing.departureTime
        val arrivalTime = destinationPoint?.arrivalTime ?: existing.arrivalTime
        val stops = existing.stops.map { existingStop ->
            val stop = trip.pointAt(existingStop.station)
            if (stop == null) {
                existingStop
            } else {
                TripStop(
                    existingStop.station,
                    stop.arrivalTime,
                    stop.departureTime,
                    if (stop.scheduledArrivalTime > 0L) stop.scheduledArrivalTime
                    else existingStop.scheduledArrivalTime,
                    if (stop.scheduledDepartureTime > 0L) stop.scheduledDepartureTime
                    else existingStop.scheduledDepartureTime,
                    stop.arrivalSource,
                    stop.departureSource,
                )
            }
        }
        return TripLeg(
            lineForLeg(
                trip.line,
                existing.origin,
                existing.destination,
                trip.trainDestination,
            ),
            existing.origin,
            existing.destination,
            trip.trainDestination,
            existing.tripId,
            departureTime,
            arrivalTime,
            stops,
            existing.minimumTransferSecondsAfter,
            existing.scheduledDepartureTime,
            existing.scheduledArrivalTime,
            originPoint?.departureSource ?: existing.departureSource,
            destinationPoint?.arrivalSource ?: existing.arrivalSource,
            originPoint?.platform ?: existing.platform,
        )
    }

    private fun addTripUpdate(
        departures: DepartureCollection,
        trip: TripSnapshot,
        allTrips: List<TripSnapshot>,
        feedTime: Long,
        raptorRouter: RaptorRouter,
        raptorTripsBySnapshot: Map<TripSnapshot, RaptorRouter.Trip>,
    ) {
        val originPoint = trip.pointAt(origin)
        if (originPoint == null || originPoint.departureTime <= 0) {
            return
        }
        if (originPoint.departureTime < feedTime - DEPARTURE_STALE_TOLERANCE_MILLIS) {
            return
        }
        val minutes = maxOf(0L, (originPoint.departureTime - feedTime) / 60000L).toInt()
        if (destination != null) {
            val raptorTrip = raptorTripsBySnapshot[trip] ?: return
            val candidates = mutableListOf<Pair<Route, List<TripLeg>>>()
            val journeys = raptorRouter.journeys(
                origin,
                destination,
                originPoint.departureTime,
            )
            val journey = journeys.minWithOrNull(
                compareBy<RaptorRouter.Journey> { it.arrivalTime }
                    .thenBy { it.transferCount }
                    .thenBy { routeForJourney(it)?.let(transferPolicy::routeScore)
                        ?: Int.MAX_VALUE }
            )
            if (journey != null && journey.legs.firstOrNull()?.trip?.id != raptorTrip.id) {
                // Another departure available at this time reaches the
                // destination first, so this train is not a useful itinerary
                // for the requested destination.
                return
            }
            if (journey != null) {
                val route = routeForJourney(journey)
                if (route != null) {
                    val legs = journey.legs.mapIndexed { index, leg ->
                        val snapshot = leg.trip.payload as? TripSnapshot ?: return
                        tripLegFor(route, index, leg.origin, leg.destination, snapshot)
                    }
                    if (legs.lastOrNull()?.destination == destination) {
                        candidates += route to legs
                    }
                }
            }

            if (candidates.isEmpty()) {
                if (journeys.isNotEmpty()) return
                // Preserve a topology-valid departure when this feed omits the
                // terminal event entirely. It has no arrival score, so this
                // fallback is used only when RAPTOR cannot form a timed trip.
                val fallback = routes.asSequence()
                    .filter { it.trainDestinationIsApplicable(trip.trainDestination, trip.line) }
                    .map { route -> route to buildTripLegs(route, trip, allTrips) }
                    .filter { (_, legs) ->
                        legs.lastOrNull()?.destination == destination
                            && isSimpleItinerary(legs)
                    }
                    .toList()
                if (fallback.isNotEmpty()) {
                    addSelectedCandidate(
                        departures, fallback, trip, originPoint, minutes
                    )
                }
                return
            }
            val selected = candidates.minWithOrNull(
                compareBy<Pair<Route, List<TripLeg>>> {
                    it.second.lastOrNull()?.arrivalTime ?: Long.MAX_VALUE
                }.thenBy { transferPolicy.routeScore(it.first) }
            )
            if (selected != null) {
                addSelectedCandidate(
                    departures, listOf(selected), trip, originPoint, minutes
                )
            }
            return
        }

        if (!ignoreDirection && !origin.ignoreRoutingDirection
            && routes.none {
                it.trainDestinationIsApplicable(trip.trainDestination, trip.line)
            }
        ) return
        // A train can match several route alternatives because they share the
        // same first leg. Rank complete timed itineraries after validating
        // transfers, so a preferred station does not hide a much earlier
        // arrival.
        val candidates = routes.asSequence()
            .filter { it.trainDestinationIsApplicable(trip.trainDestination, trip.line) }
            .map { route -> route to buildTripLegs(route, trip, allTrips) }
            .filter { (route, legs) -> legs.size == route.lines.size }
            .toList()
        val lateNightCandidates = candidates.filter { (route, _) ->
            Line.YELLOW_LATE_NIGHT in route.lines
        }
        if (lateNightCandidates.isNotEmpty()) {
            addSelectedCandidate(
                departures, lateNightCandidates, trip, originPoint, minutes
            )
            return
        }
        val preferredTopologyExists = routes
            .filter { it.trainDestinationIsApplicable(trip.trainDestination, trip.line) }
            .any { route ->
                route.transferStations.indices.none { index ->
                    transferPolicy.isAvoidedForRouteRanking(route, index)
                }
            }
        val nonAvoidedCandidates = candidates.filter { (route, _) ->
            route.transferStations.indices.none { index ->
                transferPolicy.isAvoidedForRouteRanking(route, index)
            }
        }
        val marginSafeNonAvoided = nonAvoidedCandidates.filter { (route, legs) ->
            hasRequiredTransferMargins(route, legs)
        }
        val candidatesToChoose = if (marginSafeNonAvoided.isNotEmpty()) {
            marginSafeNonAvoided
        } else if (preferredTopologyExists) {
            emptyList()
        } else {
            candidates.filter { (route, legs) -> hasRequiredTransferMargins(route, legs) }
        }
        // Arrival is the primary live-routing signal.  Static policy is only
        // a tie-breaker after corrected realtime times have been evaluated.
        val timedCandidates = candidatesToChoose.filter { (_, legs) ->
            legs.lastOrNull()?.arrivalTime?.let { it > 0L } == true
        }
        (timedCandidates.ifEmpty { candidatesToChoose }).minWithOrNull(
            compareBy<Pair<Route, List<TripLeg>>> {
                it.second.lastOrNull()?.arrivalTime ?: Long.MAX_VALUE
            }.thenBy { transferPolicy.routeScore(it.first) }
        )
            ?.let { (route, legs) ->
                addSelectedCandidate(
                    departures, listOf(route to legs), trip, originPoint, minutes
                )
            }
    }

    private fun addSelectedCandidate(
        departures: DepartureCollection,
        candidates: List<Pair<Route, List<TripLeg>>>,
        trip: TripSnapshot,
        originPoint: StopTimePoint,
        minutes: Int,
    ) {
        candidates.firstOrNull()?.let { (route, legs) ->
                val line = lineForDeparture(trip, legs)
                val departure = Departure.builder()
                    .setOrigin(origin)
                    .setTrainDestination(trip.trainDestination)
                    .setLine(line)
                    .setDirection(trip.direction)
                    .setPlatform(trip.platform)
                    .setCanceled(trip.canceled)
                    .setTrainDestinationColorText(line.name)
                    .setTrainDestinationColorHex(colorForLine(line))
                    .setMinutes(minutes)
                    .setMinEstimate(originPoint.departureTime - ESTIMATE_TOLERANCE_MILLIS)
                    .setMaxEstimate(originPoint.departureTime + ESTIMATE_TOLERANCE_MILLIS)
                    .setTripLegs(legs)
                    .let { builder ->
                        if (legs.isNotEmpty() && legs.last().hasArrivalTime()) {
                            builder.setEstimatedTripTime(
                                (legs.last().arrivalTime - originPoint.departureTime).toInt()
                            )
                        } else {
                            builder
                        }
                    }
                    .build()
                addDeparture(departures, departure, route)
            }
    }

    private fun hasRequiredTransferMargins(
        route: Route,
        legs: List<TripLeg>,
    ): Boolean = route.transferStations.indices.all { index ->
        val arriving = legs.getOrNull(index)?.arrivalTime ?: 0L
        val departing = legs.getOrNull(index + 1)?.departureTime ?: 0L
        val minimum = legs.getOrNull(index)?.minimumTransferSecondsAfter ?: 0
        transferPolicy.hasRequiredTransferMargin(
            route,
            index,
            arriving,
            departing,
            minimum,
        )
    }

    private fun isSimpleItinerary(legs: List<TripLeg>): Boolean {
        val visited = HashSet<Station>()
        legs.forEachIndexed { legIndex, leg ->
            val stations = leg.stops.mapNotNull { it.station }.ifEmpty {
                listOfNotNull(leg.origin, leg.destination)
            }
            stations.forEachIndexed { stopIndex, station ->
                val isSharedTransfer = legIndex > 0 && stopIndex == 0
                    && station == leg.origin
                if (station in visited && !isSharedTransfer) return false
                visited += station
            }
        }
        return true
    }

    private fun addDeparture(
        collection: DepartureCollection,
        departure: Departure,
        route: Route,
    ) {
        collection.unfiltered += departure
        collection.filtered += departure.copy(
            requiresTransfer = route.hasTransfer(),
            transferScheduled = Line.YELLOW_ORANGE_SCHEDULED_TRANSFER == route.directLine,
        )
    }

    private class DepartureCollection {
        val unfiltered = mutableListOf<Departure>()
        val filtered = mutableListOf<Departure>()
    }

    private fun parseTrip(
        tripUpdate: GtfsRealtime.TripUpdate,
        feedTime: Long
    ): TripSnapshot? {
        if (!tripUpdate.hasTrip()) {
            return null
        }
        val trip = tripUpdate.getTrip()
        var routeId = if (trip.hasRouteId() && trip.getRouteId().isNotEmpty()) {
            trip.getRouteId()
        } else {
            null
        }
        if (routeId.isNullOrEmpty()) {
            routeId = bartGtfsNetwork.routeIdForTrip(trip.getTripId())
        }
        val line = bartGtfsNetwork.lineForRouteId(routeId)
            ?: if (isAntiochShuttleTrip(tripUpdate)) Line.YELLOW_DMU else return null

        val result = TripSnapshot(
            tripId = trip.getTripId(),
            line = line,
            direction = bartGtfsNetwork.directionForRouteId(routeId),
        )
        val scheduledStations = bartGtfsNetwork.stationsForTrip(trip.getTripId())
        var updateIndex = 0
        for (update in tripUpdate.getStopTimeUpdateList()) {
            if (isSkipped(update)) {
                updateIndex++
                continue
            }
            val station = bartGtfsNetwork.stationForStopId(update.getStopId())
            if (result.feedPlatform == null) {
                result.feedPlatform = platformForStopId(update.getStopId())
            }
            val departure = departureTime(update)
            val arrival = arrivalTime(update)
            if (station != null && station != Station.SPCL && (departure > 0 || arrival > 0)) {
                val scheduledOrder = scheduledStations.indexOf(station)
                val point = StopTimePoint(
                    station = station,
                    order = if (scheduledOrder >= 0) {
                        scheduledOrder
                    } else {
                        stopOrder(update, updateIndex)
                    },
                    departureTime = if (departure > 0) departure else arrival,
                    arrivalTime = if (arrival > 0) arrival else departure,
                    departureSource = PredictionSource.REALTIME,
                    arrivalSource = PredictionSource.REALTIME,
                    platform = platformForStopId(update.getStopId()),
                )
                result.points += point
                if (result.trainDestination == null || point.order > result.lastOrder) {
                    result.trainDestination = station
                    result.lastOrder = point.order
                }
                if (station == origin && result.platform == null) {
                    result.platform = platformForStopId(update.getStopId())
                }
            }
            updateIndex++
        }
        if (scheduledStations.isNotEmpty()) {
            for ((index, station) in scheduledStations.withIndex()) {
                if (result.pointAt(station) == null) {
                    result.points += StopTimePoint(
                        station = station,
                        order = index,
                        departureTime = 0L,
                        arrivalTime = 0L
                    )
                }
            }
            result.points.sortBy { it.order }
            result.trainDestination = scheduledStations.last()
            result.lastOrder = scheduledStations.lastIndex
        }
        if (result.trainDestination == null) {
            return null
        }
        if (line == Line.YELLOW_DMU && result.pointAt(Station.PITT) == null) {
            if (result.feedPlatform == "1" && result.pointAt(Station.PCTR) != null) {
                result.trainDestination = Station.ANTC
            } else if (result.feedPlatform == "2"
                && result.pointAt(Station.PCTR) != null
            ) {
                result.trainDestination = Station.PITT
            }
        }
        result.canceled = trip.hasScheduleRelationship() &&
            trip.getScheduleRelationship() ==
            GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED
        if (result.pointAt(origin) == null && result.canceled) {
            // A canceled trip can still be displayed if BART supplies its
            // start time, matching the old ETD behavior.
            val start = scheduledStartTime(trip, feedTime)
            if (start > 0) {
                result.points += StopTimePoint(
                    station = origin,
                    order = Int.MIN_VALUE,
                    departureTime = start,
                    arrivalTime = start
                )
            }
        }
        return result
    }

    private fun buildTripLegs(
        route: Route,
        firstTrip: TripSnapshot,
        allTrips: List<TripSnapshot>,
    ): List<TripLeg> {
        // A station-only lookup has no requested passenger destination, but
        // the selected train still gives us the endpoint to display. Treat it
        // as a single-leg trip, just like a lookup made directly to that
        // endpoint.
        val tripDestination = destination ?: firstTrip.trainDestination ?: return emptyList()
        val lines = route.lines
        if (lines.isEmpty()) {
            return emptyList()
        }
        val transfers = route.transferStations
        val result = mutableListOf<TripLeg>()
        var currentTrip = firstTrip
        var legOrigin = origin
        for (i in lines.indices) {
            val legDestination = if (i < transfers.size) transfers[i] else tripDestination
            if (i > 0) {
                val arrivingLeg = result[i - 1]
                val connectingTrip = findConnectingTrip(
                    lines[i],
                    legOrigin,
                    legDestination,
                    arrivingLeg,
                    allTrips
                )
                if (connectingTrip == null) {
                    break
                }
                currentTrip = connectingTrip
            }
            val departure = currentTrip.pointAt(legOrigin) ?: break
            val arrival = currentTrip.pointAt(legDestination)
            val stops = currentTrip.pointsBetween(legOrigin, legDestination).map {
                tripStop(it)
            }
            result += TripLeg(
                lineForLeg(
                    currentTrip.line,
                    legOrigin,
                    legDestination,
                    currentTrip.trainDestination,
                ),
                legOrigin,
                legDestination,
                currentTrip.trainDestination,
                currentTrip.tripId,
                departure.departureTime,
                arrival?.arrivalTime ?: 0L,
                stops,
                minimumTransferSecondsAfter(route, i, legDestination, currentTrip.line),
                departure.scheduledDepartureTime,
                arrival?.scheduledArrivalTime ?: 0L,
                departure.departureSource,
                arrival?.arrivalSource ?: PredictionSource.UNKNOWN,
                departure.platform,
            )
            legOrigin = legDestination
        }
        return result
    }

    private fun findConnectingTrip(
        line: Line,
        origin: Station,
        destination: Station,
        arrivingLeg: TripLeg,
        allTrips: List<TripSnapshot>
    ): TripSnapshot? {
        var best: TripSnapshot? = null
        for (trip in allTrips) {
            if (trip.canceled || trip.line != line || !trip.canServe(origin, destination)) {
                continue
            }
            val departure = trip.pointAt(origin)
            if (departure == null || !transferPolicy.canMakeTransfer(
                    arrivingLeg.arrivalTime,
                    departure.departureTime,
                    origin,
                    arrivingLeg.line,
                    line,
                    when {
                        arrivingLeg.line == Line.YELLOW -> arrivingLeg.stops.mapNotNull { it.station }
                        line == Line.YELLOW -> trip.points.sortedBy { it.order }.map { it.station }
                        else -> null
                    },
                )
            ) {
                continue
            }
            val bestDeparture = best?.pointAt(origin)?.departureTime
            if (best == null || departure.departureTime < bestDeparture!!) {
                best = trip
            }
        }
        return best
    }

    private fun minimumTransferSecondsAfter(
        route: Route,
        legIndex: Int,
        transferStation: Station,
        fromLine: Line
    ): Int {
        if (legIndex + 1 >= route.lines.size) {
            return 0
        }
        return transferPolicy.minimumTransferSeconds(
            transferStation, fromLine, route.lines[legIndex + 1]
        )
    }

    private class StopTimePoint(
        val station: Station,
        val order: Int,
        val departureTime: Long,
        val arrivalTime: Long,
        val scheduledDepartureTime: Long = 0L,
        val scheduledArrivalTime: Long = 0L,
        val departureSource: PredictionSource = PredictionSource.UNKNOWN,
        val arrivalSource: PredictionSource = PredictionSource.UNKNOWN,
        var platform: String? = null,
    )

    private class TripSnapshot(
        val tripId: String?,
        val line: Line,
        val direction: String?
    ) {
        var trainDestination: Station? = null
        var platform: String? = null
        var feedPlatform: String? = null
        var canceled = false
        var lastOrder = Int.MIN_VALUE
        val points = mutableListOf<StopTimePoint>()

        fun addPoint(point: StopTimePoint) {
            points.removeAll { it.station == point.station }
            points += point
            lastOrder = maxOf(lastOrder, point.order)
        }

        fun pointAt(station: Station?): StopTimePoint? =
            points.firstOrNull { it.station == station }

        fun canServe(origin: Station?, destination: Station?): Boolean {
            val start = pointAt(origin)
            val end = pointAt(destination)
            return start != null && end != null && end.order > start.order
        }

        fun pointsBetween(origin: Station?, destination: Station?): List<StopTimePoint> {
            val start = pointAt(origin)
            val end = pointAt(destination)
            if (start == null || end == null) {
                return emptyList()
            }
            return points.filter { it.order in start.order..end.order }
                .sortedBy { it.order }
        }
    }

    private fun tripStop(point: StopTimePoint): TripStop = TripStop(
        point.station,
        point.arrivalTime,
        point.departureTime,
        point.scheduledArrivalTime,
        point.scheduledDepartureTime,
        point.arrivalSource,
        point.departureSource,
    )

    private fun isAntiochShuttleTrip(
        tripUpdate: GtfsRealtime.TripUpdate
    ): Boolean = tripUpdate.getStopTimeUpdateList().any { update ->
        bartGtfsNetwork.stationForStopId(update.getStopId()) == Station.PITT
            || bartGtfsNetwork.stationForStopId(update.getStopId()) == Station.PCTR
            || bartGtfsNetwork.stationForStopId(update.getStopId()) == Station.ANTC
    }

    private fun lineForLeg(
        line: Line,
        legOrigin: Station?,
        legDestination: Station?,
        trainDestination: Station?,
    ): Line = if (line == Line.YELLOW && (
        (legOrigin == Station.SFIA && legDestination == Station.MLBR)
            || (destination == null && trainDestination == Station.MLBR)
            || origin == Station.MLBR
    )) {
        Line.YELLOW_LATE_NIGHT
    } else {
        line
    }

    private fun lineForDeparture(trip: TripSnapshot, legs: List<TripLeg>): Line =
        if (destination == null && trip.line == Line.YELLOW
            && trip.trainDestination == Station.MLBR
        ) {
            Line.YELLOW_LATE_NIGHT
        } else {
            legs.firstOrNull()?.line ?: trip.line
        }

    private fun feedTime(feed: GtfsRealtime.FeedMessage): Long =
        if (feed.hasHeader() && feed.getHeader().hasTimestamp()
            && feed.getHeader().getTimestamp() > 0
        ) {
            feed.getHeader().getTimestamp() * 1000L
        } else {
            timeSource.nowMillis()
        }

    companion object {
        private const val ESTIMATE_TOLERANCE_MILLIS = 30000L
        private const val DMU_MATCH_MAX_MILLIS = 20L * 60L * 1000L
        private const val REALTIME_CANCELLATION_WINDOW_MILLIS = 60L * 60L * 1000L
        private const val DEPARTURE_STALE_TOLERANCE_MILLIS = 45 * 1000L
        private val PACIFIC_ZONE = ZoneId.of("America/Los_Angeles")

        private fun departureTime(update: GtfsRealtime.TripUpdate.StopTimeUpdate): Long {
            if (update.hasDeparture() && update.getDeparture().hasTime()) {
                return update.getDeparture().getTime() * 1000L
            }
            if (update.hasArrival() && update.getArrival().hasTime()) {
                return update.getArrival().getTime() * 1000L
            }
            return 0L
        }

        private fun arrivalTime(update: GtfsRealtime.TripUpdate.StopTimeUpdate): Long {
            if (update.hasArrival() && update.getArrival().hasTime()) {
                return update.getArrival().getTime() * 1000L
            }
            if (update.hasDeparture() && update.getDeparture().hasTime()) {
                return update.getDeparture().getTime() * 1000L
            }
            return 0L
        }

        private fun scheduledStartTime(
            trip: GtfsRealtime.TripDescriptor,
            feedTime: Long
        ): Long {
            if (!trip.hasStartTime()) {
                return 0L
            }
            val startDate = if (trip.hasStartDate()) {
                trip.getStartDate()
            } else {
                DateTimeFormatter.BASIC_ISO_DATE.format(
                    Instant.ofEpochMilli(feedTime).atZone(PACIFIC_ZONE).toLocalDate()
                )
            }
            val timeParts = trip.getStartTime().split(":")
            if (timeParts.size != 3) {
                return 0L
            }
            return try {
                val hour = timeParts[0].toInt()
                val minute = timeParts[1].toInt()
                val second = timeParts[2].toInt()
                val date = LocalDate.parse(startDate, DateTimeFormatter.BASIC_ISO_DATE)
                date.atStartOfDay(PACIFIC_ZONE)
                    .plusHours(hour.toLong())
                    .plusMinutes(minute.toLong())
                    .plusSeconds(second.toLong())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: NumberFormatException) {
                0L
            } catch (_: DateTimeException) {
                0L
            }
        }

        private fun isSkipped(update: GtfsRealtime.TripUpdate.StopTimeUpdate): Boolean =
            update.hasScheduleRelationship() &&
                update.getScheduleRelationship() ==
                GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED

        private fun stopOrder(
            update: GtfsRealtime.TripUpdate.StopTimeUpdate,
            listIndex: Int
        ): Int {
            // BART currently omits stop_sequence from its GTFS-RT feed. The feed
            // order is still the trip order, so use it when no sequence exists.
            return if (update.hasStopSequence()) update.getStopSequence() else listIndex
        }

        private fun platformForStopId(stopId: String?): String? {
            if (stopId == null) {
                return null
            }
            val separator = stopId.lastIndexOf('-')
            return if (separator >= 0 && separator + 1 < stopId.length) {
                stopId.substring(separator + 1)
            } else {
                null
            }
        }

        private fun colorForLine(line: Line): String = when (line) {
            Line.RED -> "#ffff0000"
            Line.ORANGE -> "#ffff9933"
            Line.YELLOW, Line.YELLOW_LATE_NIGHT, Line.YELLOW_DMU -> "#ffffff33"
            Line.GREEN -> "#ff339933"
            Line.BLUE -> "#ff0099cc"
            else -> "#ffffffff"
        }
    }
}
