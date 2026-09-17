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
import `in`.izyum.bart.backend.CanonicalTransitSnapshot
import `in`.izyum.bart.backend.Schedule
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.normalization.NormalizedRealtimeFeed
import `in`.izyum.bart.transit.normalization.RealtimeFeedNormalizer
import com.google.transit.realtime.GtfsRealtime
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap

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

    /** Canonical projections do not need a static route catalog. */
    constructor(
        origin: Station,
        destination: Station?,
        ignoreDirection: Boolean,
        bartGtfsNetwork: BartGtfsNetwork,
        timeSource: TimeSource = SystemTimeSource,
    ) : this(
        origin,
        destination,
        emptyList(),
        ignoreDirection,
        bartGtfsNetwork,
        timeSource,
    )

    init {
        requireNotNull(bartGtfsNetwork) { "A validated GTFS network is required" }
    }

    @Deprecated(
        "Pass a CanonicalTransitSnapshot; raw-feed processing belongs before projection.",
        level = DeprecationLevel.WARNING,
    )
    fun getRealTimeDepartures(feed: GtfsRealtime.FeedMessage): RealTimeDepartures {
        val normalizedFeed = RealtimeFeedNormalizer.normalize(feed)
        val feedTime = feedTime(feed)
        return getRealTimeDepartures(
            normalizedFeed,
            feedTime,
            correctedSchedule(normalizedFeed, feedTime),
        )
    }

    @Deprecated(
        "Pass a CanonicalTransitSnapshot; feed normalization belongs before projection.",
        level = DeprecationLevel.WARNING,
    )
    fun getRealTimeDepartures(
        normalizedFeed: NormalizedRealtimeFeed,
        feedTime: Long
    ): RealTimeDepartures = getRealTimeDepartures(
        normalizedFeed,
        feedTime,
        correctedSchedule(normalizedFeed, feedTime),
    )

    /**
     * Projects the finalized passenger schedule from one canonical snapshot.
     * The canonical layer owns feed normalization, trip association, and
     * realtime correction; this path does not reconstruct or supplement that
     * data from the raw feed.
     */
    fun getRealTimeDepartures(
        canonical: CanonicalTransitSnapshot,
    ): RealTimeDepartures = getRealTimeDepartures(
        canonical.correctedSchedule,
        canonical.normalizedFeed.provenance.feedTimestampMillis,
        allowLegacyRouteFallback = false,
    )

    private fun getRealTimeDepartures(
        schedule: Schedule,
        feedTime: Long,
        allowLegacyRouteFallback: Boolean = true,
    ): RealTimeDepartures {
        val departures = DepartureCollection()
        val trips = schedule.trips.map(::snapshotFromSchedule)
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
                allowLegacyRouteFallback,
            )
        }
        return RealTimeDepartures(
            origin,
            destination,
            feedTime,
            departures.unfiltered,
            departures.filtered,
            transfersIncluded = destination != null,
        )
    }

    /**
     * Legacy projection API. It still admits realtime-only trips and should be
     * replaced by the canonical-snapshot overload.
     */
    @Deprecated(
        "Pass a CanonicalTransitSnapshot; canonical data is the only supported route projection input.",
        level = DeprecationLevel.WARNING,
    )
    fun getRealTimeDepartures(
        normalizedFeed: NormalizedRealtimeFeed,
        feedTime: Long,
        schedule: Schedule,
    ): RealTimeDepartures {
        val departures = DepartureCollection()

        val trips = parseRealtimeOnlyTrips(
            normalizedFeed.tripUpdates.filterNot { it.isOperationalTelemetry }
                .map { it.rawEntity },
            feedTime,
            schedule,
        ) + schedule.trips.map(::snapshotFromSchedule)
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
                allowLegacyRouteFallback = true,
            )
        }
        return RealTimeDepartures(
            origin,
            destination,
            feedTime,
            departures.unfiltered,
            departures.filtered,
            transfersIncluded = destination != null,
        )
    }

    /**
     * Refreshes an existing itinerary from canonical passenger trips. The
     * itinerary supplies the route metadata needed for connection repair; no
     * static route enumeration is performed here.
     */
    fun updateTripLegs(
        canonical: CanonicalTransitSnapshot,
        existingLegs: List<TripLeg>,
    ): List<TripLeg> {
        val trips = canonical.correctedSchedule.trips.map(::snapshotFromSchedule)
        val tripsByIdentity = trips
            .filter { it.tripId != null && it.serviceDate != null }
            .associateBy { "${it.serviceDate}:${it.tripId}" }
        val tripsById = trips.groupBy { it.tripId }
        val updatedLegs = existingLegs.map { existing ->
            val current = existing.canonicalIdentity?.let(tripsByIdentity::get)
                ?: tripsById[existing.tripId].orEmpty().singleOrNull()
                ?: findMatchingTrip(existing, trips)
            if (current == null) existing else updateTripLeg(existing, current)
        }.toMutableList()

        (routeForCanonicalItinerary(trips, existingLegs)
            ?: routeForExistingLegs(updatedLegs))?.let { route ->
            refreshConnectingLegs(route, updatedLegs, trips)
        }
        return updatedLegs
    }

    private fun routeForCanonicalItinerary(
        trips: List<TripSnapshot>,
        existingLegs: List<TripLeg>,
    ): Route? {
        val firstLeg = existingLegs.firstOrNull() ?: return null
        val departureTime = firstLeg.departureTime.takeIf { it > 0L }
            ?: firstLeg.scheduledDepartureTime.takeIf { it > 0L }
            ?: return null
        val raptorTrips = trips.map(::raptorTrip)
        val router = RaptorRouter(raptorTrips, bartGtfsNetwork, transferPolicy)
        val journeys = router.journeys(origin, destination ?: return null, departureTime)
        val matchingJourney = journeys.firstOrNull { journey ->
            journey.legs.firstOrNull()?.trip?.id == firstLeg.tripId
        } ?: journeys.firstOrNull()
        return matchingJourney?.let(::routeForJourney)
    }

    /**
     * Replaces the stop estimates for an already-selected itinerary using
     * the latest update for each exact train. A train may no longer include
     * the passenger's origin in the feed after it has departed, so missing
     * passed stops are deliberately retained from the previous snapshot.
     */
    @Deprecated(
        "Pass canonical trip state to the trip-progress projector; raw-feed processing is legacy.",
        level = DeprecationLevel.WARNING,
    )
    fun updateTripLegs(
        feed: GtfsRealtime.FeedMessage,
        existingLegs: List<TripLeg>,
        feedTime: Long
    ): List<TripLeg> {
        val normalizedFeed = RealtimeFeedNormalizer.normalize(feed)
        return updateTripLegs(
            normalizedFeed,
            existingLegs,
            feedTime,
            correctedSchedule(normalizedFeed, feedTime),
        )
    }

    @Deprecated(
        "Pass canonical trip state to the trip-progress projector; normalized-feed processing is legacy.",
        level = DeprecationLevel.WARNING,
    )
    fun updateTripLegs(
        normalizedFeed: NormalizedRealtimeFeed,
        existingLegs: List<TripLeg>,
        feedTime: Long
    ): List<TripLeg> = updateTripLegs(
        normalizedFeed,
        existingLegs,
        feedTime,
        correctedSchedule(normalizedFeed, feedTime),
    )

    /** Legacy trip-progress API retained while TripProgressProjection migrates. */
    @Deprecated(
        "Migrate trip-progress refresh to canonical trip state.",
        level = DeprecationLevel.WARNING,
    )
    fun updateTripLegs(
        normalizedFeed: NormalizedRealtimeFeed,
        existingLegs: List<TripLeg>,
        feedTime: Long,
        schedule: Schedule
    ): List<TripLeg> {
        val trips = schedule.trips.map(::snapshotFromSchedule) + parseRealtimeOnlyTrips(
            normalizedFeed.tripUpdates.filterNot { it.isOperationalTelemetry }
                .map { it.rawEntity },
            feedTime,
            schedule,
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

    private fun routeForExistingLegs(legs: List<TripLeg>): Route? {
        if (legs.isEmpty()) return null
        val origin = legs.first().origin ?: return null
        val finalDestination = destination ?: legs.last().destination ?: return null
        val lines = legs.map { it.line ?: return null }
        val transferStations = legs.dropLast(1).map {
            it.destination ?: return null
        }
        val sequences = LinkedHashMap<Line, List<Station>>()
        legs.forEach { leg ->
            val line = leg.line ?: return@forEach
            val sequence = (listOfNotNull(leg.origin)
                + leg.stops.mapNotNull { it.station }
                + listOfNotNull(leg.destination)).distinct()
            sequences.putIfAbsent(line, sequence)
        }
        return if (lines.size == 1) {
            Route.direct(
                origin,
                finalDestination,
                lines.single(),
                null,
                sequences[lines.single()].orEmpty(),
            )
        } else {
            Route.transfer(
                origin,
                finalDestination,
                lines,
                transferStations,
                null,
                sequences,
            )
        }
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
            trip.serviceDate,
        )
    }

    /**
     * Legacy compatibility path for callers that have not migrated to the
     * canonical snapshot. Do not use for route departures.
     */
    @Deprecated(
        "Canonical route projection must not parse realtime-only trips here.",
        level = DeprecationLevel.WARNING,
    )
    private fun parseRealtimeOnlyTrips(
        entities: List<GtfsRealtime.FeedEntity>,
        feedTime: Long,
        schedule: Schedule,
    ): List<TripSnapshot> {
        val scheduledTrips = schedule.trips.map(::snapshotFromSchedule).toMutableList()
        val scheduledIds = scheduledTrips.mapNotNull { it.tripId }.toSet()
        val realtimeTrips = entities.mapNotNull { entity ->
            if (!entity.hasTripUpdate()) null else parseTrip(entity.tripUpdate, feedTime)
        }.toMutableList()
        return realtimeTrips.filter { trip -> trip.tripId !in scheduledIds }
    }

    /**
     * The canonical schedule already contains all exact GTFS-RT corrections,
     * including DMU terminal merges. This adapter only admits realtime-only
     * passenger observations that have no static counterpart.
     */
    private fun correctedSchedule(
        normalizedFeed: NormalizedRealtimeFeed,
        feedTime: Long,
    ): Schedule = Schedule.fromStatic(
        bartGtfsNetwork,
        feedTime,
        Line.values().toSet(),
    ).applyRealtime(normalizedFeed)

    private fun snapshotFromSchedule(trip: Schedule.Trip): TripSnapshot {
        val result = TripSnapshot(
            trip.key.tripId,
            trip.line,
            trip.direction,
            trip.key.serviceDate,
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
            trip.serviceDate ?: existing.serviceDate,
        )
    }

    private fun addTripUpdate(
        departures: DepartureCollection,
        trip: TripSnapshot,
        allTrips: List<TripSnapshot>,
        feedTime: Long,
        raptorRouter: RaptorRouter,
        raptorTripsBySnapshot: Map<TripSnapshot, RaptorRouter.Trip>,
        allowLegacyRouteFallback: Boolean,
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
                if (!allowLegacyRouteFallback) return
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

        val applicableRoutes = routes.filter {
            it.trainDestinationIsApplicable(trip.trainDestination, trip.line)
        }
        if (!ignoreDirection && !origin.ignoreRoutingDirection
            && routes.isNotEmpty() && applicableRoutes.isEmpty()
        ) return
        // A train can match several route alternatives because they share the
        // same first leg. Rank complete timed itineraries after validating
        // transfers, so a preferred station does not hide a much earlier
        // arrival.
        val stationOnlyRoutes = if (routes.isEmpty()) {
            listOfNotNull(routeForStationOnlyTrip(trip))
        } else {
            applicableRoutes
        }
        val candidates = stationOnlyRoutes.asSequence()
            .map { route -> route to buildTripLegs(route, trip, allTrips) }
            .filter { (route, legs) -> legs.size == route.lines.size }
            .toList()
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

    private fun routeForStationOnlyTrip(trip: TripSnapshot): Route? {
        val trainDestination = trip.trainDestination ?: return null
        val stations = trip.points.sortedBy { it.order }.map { it.station }
        return Route.direct(
            origin,
            trainDestination,
            trip.line,
            trip.direction,
            stations,
        )
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
        val line = bartGtfsNetwork.lineForRouteId(routeId) ?: return null

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
        result.canceled = trip.hasScheduleRelationship() &&
            trip.getScheduleRelationship() ==
            GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED
        if (result.pointAt(origin) == null && result.canceled) {
            // A canceled trip can still be displayed if BART supplies its
            // start time.
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
                currentTrip.serviceDate,
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
        val direction: String?,
        val serviceDate: LocalDate? = null,
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

    private fun lineForLeg(
        line: Line,
        legOrigin: Station?,
        legDestination: Station?,
        trainDestination: Station?,
    ): Line = line

    private fun lineForDeparture(trip: TripSnapshot, legs: List<TripLeg>): Line =
        legs.firstOrNull()?.line ?: trip.line

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
            Line.YELLOW -> "#ffffff33"
            Line.GREEN -> "#ff339933"
            Line.BLUE -> "#ff0099cc"
            else -> "#ffffffff"
        }
    }
}
