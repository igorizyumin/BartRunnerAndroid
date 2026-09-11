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
import `in`.izyum.bart.routing.TransferConnectionValidator
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
    ): RealTimeDepartures {
        val departures = DepartureCollection()

        val trips = parseTrips(feedIndex.tripUpdateEntities, feedTime, schedule)
            .filter { it.tripId !in excludedTripIds }
        trips.forEach { trip -> addTripUpdate(departures, trip, trips, feedTime) }
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
        val trips = parseTrips(feedIndex.tripUpdateEntities, feedTime, schedule)
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

            if (isUnscheduledTerminalLeg(route.lines[index], legOrigin, legDestination)) {
                val replacement = unscheduledTerminalLeg(
                    route.lines[index], legOrigin, legDestination
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

    private fun appendConnectingLegs(
        route: Route,
        legs: MutableList<TripLeg>,
        trips: List<TripSnapshot>
    ) {
        val tripDestination = destination ?: return
        while (legs.size < route.lines.size) {
            val index = legs.size
            val legOrigin = legs[index - 1].destination ?: return
            val legDestination = if (index < route.transferStations.size) {
                route.transferStations[index]
            } else {
                tripDestination
            }
            val currentTrip = findConnectingTrip(
                route.lines[index],
                legOrigin,
                legDestination,
                legs[index - 1],
                trips
            )
            if (currentTrip == null) {
                if (isUnscheduledTerminalLeg(route.lines[index], legOrigin, legDestination)) {
                    legs += unscheduledTerminalLeg(
                        route.lines[index], legOrigin, legDestination
                    )
                    continue
                }
                return
            }
            if (currentTrip.pointAt(legOrigin) == null) return
            legs += tripLegFor(
                route, index, legOrigin, legDestination, currentTrip
            )
        }
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
            minimumTransferSecondsAfter(route, index, legOrigin, trip.line),
            departure?.scheduledDepartureTime ?: 0L,
            arrival?.scheduledArrivalTime ?: 0L,
            departure?.departureSource ?: PredictionSource.UNKNOWN,
            arrival?.arrivalSource ?: PredictionSource.UNKNOWN,
        )
    }

    private fun isUnscheduledTerminalLeg(
        line: Line,
        origin: Station,
        destination: Station
    ): Boolean = (line == Line.YELLOW_DMU
        && origin == Station.PITT
        && (destination == Station.PCTR || destination == Station.ANTC))
        || (line == Line.YELLOW_LATE_NIGHT
        && origin == Station.SFIA
        && destination == Station.MLBR)

    private fun unscheduledTerminalLeg(
        line: Line,
        origin: Station,
        destination: Station
    ): TripLeg = TripLeg(
        line,
        origin,
        destination,
        destination,
        null,
        0L,
        0L,
        emptyList()
    )

    private fun parseTrips(
        entities: List<GtfsRealtime.FeedEntity>,
        feedTime: Long,
        schedule: Schedule
    ): List<TripSnapshot> {
        val scheduledTrips = schedule.trips.map(::snapshotFromSchedule).toMutableList()
        val scheduledIds = scheduledTrips.mapNotNull { it.tripId }.toSet()
        val realtimeOnly = entities.mapNotNull { entity ->
            if (!entity.hasTripUpdate()) null else parseTrip(entity.tripUpdate, feedTime)
        }.filter { it.tripId !in scheduledIds }

        val matchedTerminalTripIds = mutableSetOf<String?>()
        realtimeOnly.filter { it.line == Line.YELLOW_DMU }.forEach { realtimeTrip ->
            val match = scheduledTrips
                .filter { it.line == Line.YELLOW_DMU && it.trainDestination == realtimeTrip.trainDestination }
                .mapNotNull { candidate ->
                    val candidatePoint = candidate.pointAt(Station.PCTR)
                        ?: candidate.pointAt(Station.ANTC)
                    val realtimePoint = realtimeTrip.pointAt(Station.PCTR)
                        ?: realtimeTrip.pointAt(Station.ANTC)
                    if (candidatePoint == null || realtimePoint == null) null
                    else candidate to kotlin.math.abs(
                        candidatePoint.departureTime - realtimePoint.departureTime
                    )
                }
                .filter { it.second <= TERMINAL_MATCH_MAX_MILLIS }
                .minByOrNull { it.second }
                ?.first
            if (match != null) {
                mergeRealtimeTrip(match, realtimeTrip)
                matchedTerminalTripIds += realtimeTrip.tripId
            }
        }
        scheduledTrips += realtimeOnly.filter {
            it.line != Line.YELLOW_DMU || it.tripId !in matchedTerminalTripIds
        }
        return scheduledTrips.also(::mergePittsburgTerminalTrips)
    }

    private fun correctedSchedule(
        feedIndex: GtfsRealtimeFeedIndex,
        feedTime: Long,
    ): Schedule = Schedule.fromStatic(
        bartGtfsNetwork,
        feedTime,
        Line.values().toSet(),
    ).applyRealtime(feedIndex)

    private fun snapshotFromSchedule(trip: Schedule.Trip): TripSnapshot {
        val result = TripSnapshot(trip.key.tripId, trip.line, trip.direction)
        result.trainDestination = trip.trainDestination
        result.platform = trip.stopAt(origin)?.platform
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
            )
        }
        result.lastOrder = trip.stops.lastIndex
        return result
    }

    private fun mergeRealtimeTrip(target: TripSnapshot, realtime: TripSnapshot) {
        target.platform = realtime.platform ?: target.platform
        target.canceled = realtime.canceled
        realtime.points.forEach { realtimePoint ->
            val existing = target.pointAt(realtimePoint.station)
            if (existing == null) {
                target.points += realtimePoint
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
                )
            }
        }
        target.points.sortBy { it.order }
    }

    /**
     * BART publishes the Pittsburg transfer-platform update from the schedule
     * system and the PCTR/Antioch update from the separate DMU system. Their
     * trip IDs cannot be joined directly, so join the matching directional
     * pair by platform and the scheduled travel-time window.
     */
    private fun mergePittsburgTerminalTrips(trips: List<TripSnapshot>) {
        val platformTrips = trips.filter { trip ->
            (trip.line == Line.YELLOW || trip.line == Line.YELLOW_DMU)
                && (trip.feedPlatform == "1" || trip.feedPlatform == "2")
                && trip.pointAt(Station.PITT) != null
        }
        val dmuTrips = trips.filter { trip ->
            trip.line == Line.YELLOW_DMU
                && trip.pointAt(Station.PITT) == null
                && trip.pointAt(Station.PCTR) != null
        }
        for (dmuTrip in dmuTrips) {
            val pctrPoint = dmuTrip.pointAt(Station.PCTR) ?: continue
            val reverse = dmuTrip.feedPlatform == "2"
            val match = platformTrips
                .filter { it.feedPlatform == dmuTrip.feedPlatform }
                .mapNotNull { platformTrip ->
                    val pittPoint = platformTrip.pointAt(Station.PITT)
                        ?: return@mapNotNull null
                    val travelTime = if (reverse) {
                        pittPoint.departureTime - pctrPoint.departureTime
                    } else {
                        pctrPoint.departureTime - pittPoint.departureTime
                    }
                    if (travelTime in PITT_TO_PCTR_MIN_MILLIS..PITT_TO_PCTR_MAX_MILLIS) {
                        platformTrip to kotlin.math.abs(
                            travelTime - PITT_TO_PCTR_TYPICAL_MILLIS
                        )
                    } else {
                        null
                    }
                }
                .minByOrNull { it.second }
                ?.first
                ?: continue
            val pittPoint = match.pointAt(Station.PITT) ?: continue
            dmuTrip.addPoint(
                StopTimePoint(
                    Station.PITT,
                    if (reverse) pctrPoint.order + 1 else pctrPoint.order - 1,
                    pittPoint.departureTime,
                    pittPoint.arrivalTime
                )
            )
            dmuTrip.platform = match.platform
            dmuTrip.trainDestination = if (reverse) Station.PITT else Station.ANTC
        }
    }

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
        )
    }

    private fun addTripUpdate(
        departures: DepartureCollection,
        trip: TripSnapshot,
        allTrips: List<TripSnapshot>,
        feedTime: Long,
    ) {
        if (destination != null && !ignoreDirection && !origin.ignoreRoutingDirection
            && !isDirectionApplicable(trip.direction)
        ) {
            return
        }
        val originPoint = trip.pointAt(origin)
        if (originPoint == null || originPoint.departureTime <= 0) {
            return
        }
        if (originPoint.departureTime < feedTime - DEPARTURE_STALE_TOLERANCE_MILLIS) {
            return
        }
        val minutes = maxOf(0L, (originPoint.departureTime - feedTime) / 60000L).toInt()
        // A train can match several route alternatives because they share the
        // same first leg. Choose the first complete timed itinerary instead of
        // committing to the first topology match before validating transfers.
        routes.asSequence()
            .filter { it.trainDestinationIsApplicable(trip.trainDestination, trip.line) }
            .map { route -> route to buildTripLegs(route, trip, allTrips) }
            .filter { (route, legs) ->
                destination == null || (legs.size == route.lines.size
                    && legs.lastOrNull()?.destination == destination)
            }
            .firstOrNull()
            ?.let { (route, legs) ->
                val line = lineForDeparture(trip, legs)
                val departure = Departure.builder()
                    .setOrigin(origin)
                    .setTrainDestination(trip.trainDestination)
                    .setLine(line)
                    .setDirection(trip.direction)
                    .setPlatform(trip.platform)
                    .setLimited(false)
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
            direction = directionForLine(line, routeId)
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
        allTrips: List<TripSnapshot>
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
                    if (isUnscheduledTerminalLeg(lines[i], legOrigin, legDestination)) {
                        result += unscheduledTerminalLeg(
                            lines[i], legOrigin, legDestination
                        )
                        legOrigin = legDestination
                        continue
                    }
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
            if (departure == null || !TransferConnectionValidator.canTransfer(
                    arrivingLeg.arrivalTime,
                    departure.departureTime,
                    origin,
                    arrivingLeg.line,
                    line,
                    bartGtfsNetwork
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
        return bartGtfsNetwork.minimumTransferSeconds(
            transferStation, fromLine, route.lines[legIndex + 1]
        ).coerceAtLeast(0)
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

    private fun isDirectionApplicable(direction: String?): Boolean =
        direction != null && routes.any { it.direction == direction }

    private fun isAntiochShuttleTrip(
        tripUpdate: GtfsRealtime.TripUpdate
    ): Boolean = tripUpdate.getStopTimeUpdateList().any { update ->
        bartGtfsNetwork.stationForStopId(update.getStopId()) == Station.PITT
            || bartGtfsNetwork.stationForStopId(update.getStopId()) == Station.PCTR
            || bartGtfsNetwork.stationForStopId(update.getStopId()) == Station.ANTC
    }

    private fun directionForLine(line: Line, routeId: String?): String? {
        routes.firstOrNull {
            it.directLine == line || it.transferLines.contains(line)
        }?.let { return it.direction }
        return bartGtfsNetwork.directionForRouteId(routeId)
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
        private const val DEPARTURE_STALE_TOLERANCE_MILLIS = 45 * 1000L
        private const val PITT_TO_PCTR_MIN_MILLIS = 5 * 60 * 1000L
        private const val PITT_TO_PCTR_TYPICAL_MILLIS = 12 * 60 * 1000L
        private const val PITT_TO_PCTR_MAX_MILLIS = 20 * 60 * 1000L
        private const val TERMINAL_MATCH_MAX_MILLIS = 20 * 60 * 1000L
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
