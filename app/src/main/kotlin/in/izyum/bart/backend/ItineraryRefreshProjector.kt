package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Itinerary
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.TripStop
import `in`.izyum.bart.routing.TransferPolicy
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork

/** Refreshes a selected itinerary and replans its future when it fails. */
class ItineraryRefreshProjector(
    private val origin: Station,
    private val destination: Station,
    private val network: BartGtfsNetwork,
) {
    private val routing = CanonicalRoutingAdapter(network)
    private val transferPolicy = TransferPolicy(network)

    fun project(canonical: CanonicalTransitSnapshot, itinerary: Itinerary): Itinerary {
        val refreshed = projectLegs(canonical, itinerary.origin, itinerary.destination, itinerary.legs)
        return itinerary.replaceLegs(refreshed)
    }

    /** Refreshes one existing leg without validating or replanning its route. */
    fun refreshLeg(canonical: CanonicalTransitSnapshot, existing: TripLeg): TripLeg {
        val trips = canonical.correctedSchedule.trips
        val byIdentity = trips.associateBy { routing.canonicalTripId(it) }
        val byId = trips.groupBy { it.key.tripId }
        val current = existing.canonicalIdentity?.let(byIdentity::get)
            ?: byId[existing.tripId].orEmpty().singleOrNull()
            ?: findMatchingTrip(existing, trips)
        return if (current == null) existing else updateTripLeg(existing, current)
    }

    /** Compatibility entry point for board/projection regression tests. */
    fun project(canonical: CanonicalTransitSnapshot, existingLegs: List<TripLeg>): List<TripLeg> =
        projectLegs(canonical, origin, destination, existingLegs)

    private fun projectLegs(
        canonical: CanonicalTransitSnapshot,
        origin: Station,
        destination: Station,
        existingLegs: List<TripLeg>,
    ): List<TripLeg> {
        val trips = canonical.correctedSchedule.trips
        val byIdentity = trips.associateBy { routing.canonicalTripId(it) }
        val byId = trips.groupBy { it.key.tripId }
        val updated = existingLegs.map { existing ->
            val current = existing.canonicalIdentity?.let(byIdentity::get)
                ?: byId[existing.tripId].orEmpty().singleOrNull()
                ?: findMatchingTrip(existing, trips)
            if (current == null) existing else updateTripLeg(existing, current)
        }.toMutableList()

        val route = routeForExistingLegs(updated)
            ?: routeForCanonicalItinerary(trips, updated)
        if (route == null || itineraryIsFeasible(route, updated, trips)) {
            return updated
        }

        // Keep the current itinerary until it is no longer usable. Once a
        // leg or connection fails, discard the future suffix and let RAPTOR
        // choose one complete continuation.
        val preserved = traveledPrefix(
            updated,
            canonical.normalizedFeed.provenance.feedTimestampMillis,
        )
        val replanOrigin = preserved.lastOrNull()?.destination ?: origin
        val replanTime = maxOf(
            canonical.normalizedFeed.provenance.feedTimestampMillis,
            preserved.lastOrNull()?.arrivalTime ?: 0L,
            updated.firstOrNull()?.departureTime ?: 0L,
        )
        val inputs = routing.inputs(trips)
        val journey = routing.bestJourney(
            routing.router(inputs),
            replanOrigin,
            destination,
            replanTime,
        )
        val replanned = journey?.let { selected ->
            val replannedRoute = routing.routeForJourney(selected) ?: return@let emptyList()
            selected.legs.mapIndexedNotNull { index, leg ->
                val trip = leg.trip.payload as? Schedule.Trip ?: return@mapIndexedNotNull null
                tripLeg(replannedRoute, index, leg.origin, leg.destination, trip)
            }
        }.orEmpty()
        return preserved + replanned
    }

    private fun routeForCanonicalItinerary(
        trips: List<Schedule.Trip>,
        existingLegs: List<TripLeg>,
    ): Route? {
        val firstLeg = existingLegs.firstOrNull() ?: return null
        val departureTime = firstLeg.departureTime.takeIf { it > 0L }
            ?: firstLeg.scheduledDepartureTime.takeIf { it > 0L }
            ?: return null
        val inputs = routing.inputs(trips)
        val router = routing.router(inputs)
        val journeys = router.journeys(origin, destination, departureTime)
        val expectedId = firstLeg.canonicalIdentity ?: firstLeg.tripId
        val selected = journeys.firstOrNull { it.legs.firstOrNull()?.trip?.id == expectedId }
            ?: routing.bestJourney(router, origin, destination, departureTime)
        return selected?.let(routing::routeForJourney)
    }

    private fun routeForExistingLegs(legs: List<TripLeg>): Route? {
        if (legs.isEmpty()) return null
        val routeOrigin = legs.first().origin ?: return null
        val finalDestination = legs.last().destination ?: return null
        val lines = legs.map { it.line ?: return null }
        val transferStations = legs.dropLast(1).map { it.destination ?: return null }
        val sequences = linkedMapOf<Line, List<Station>>()
        legs.forEach { leg ->
            val line = leg.line ?: return@forEach
            sequences.putIfAbsent(
                line,
                (listOfNotNull(leg.origin)
                    + leg.stops.mapNotNull { it.station }
                    + listOfNotNull(leg.destination)).distinct(),
            )
        }
        return if (lines.size == 1) {
            Route.direct(routeOrigin, finalDestination, lines.single(), null, sequences[lines.single()].orEmpty())
        } else {
            Route.transfer(routeOrigin, finalDestination, lines, transferStations, null, sequences)
        }
    }

    private fun itineraryIsFeasible(
        route: Route,
        legs: List<TripLeg>,
        trips: List<Schedule.Trip>,
    ): Boolean {
        legs.forEachIndexed { index, leg ->
            if (isCanceled(leg, trips)) return false
        }
        for (index in 1 until route.lines.size) {
            val previous = legs.getOrNull(index - 1) ?: return false
            val next = legs.getOrNull(index) ?: return false
            val expectedDestination = if (index < route.transferStations.size) {
                route.transferStations[index]
            } else {
                destination
            }
            if (next.line != route.lines[index]
                || next.origin != previous.destination
                || next.destination != expectedDestination
                || !connectionIsFeasible(previous, next)
            ) return false
        }
        return true
    }

    private fun connectionIsFeasible(previous: TripLeg, next: TripLeg): Boolean {
        if (previous.arrivalTime <= 0L || next.departureTime <= 0L) return true
        val station = previous.destination ?: return false
        return transferPolicy.canMakeTransfer(
            previous.arrivalTime,
            next.departureTime,
            station,
            previous.line,
            next.line,
            when {
                previous.line == Line.YELLOW -> previous.stops.mapNotNull { it.station }
                next.line == Line.YELLOW -> next.stops.mapNotNull { it.station }
                else -> null
            },
        )
    }

    private fun traveledPrefix(legs: List<TripLeg>, asOf: Long): List<TripLeg> {
        if (asOf <= 0L) return emptyList()
        val prefix = mutableListOf<TripLeg>()
        for (leg in legs) {
            if (leg.arrivalTime > 0L && leg.arrivalTime <= asOf) {
                prefix += leg
                continue
            }
            val passed = leg.stops.mapIndexedNotNull { index, stop ->
                if (stop.arrivalTime > 0L && stop.arrivalTime <= asOf) index to stop else null
            }.lastOrNull() ?: break
            if (passed.second.station == leg.origin) break
            prefix += partialLeg(leg, passed.first)
            break
        }
        return prefix
    }

    private fun partialLeg(leg: TripLeg, lastPassedIndex: Int): TripLeg {
        val passedStop = leg.stops[lastPassedIndex]
        return TripLeg(
            leg.line,
            leg.origin,
            passedStop.station,
            leg.trainDestination,
            leg.tripId,
            leg.departureTime,
            passedStop.arrivalTime,
            leg.stops.take(lastPassedIndex + 1),
            0,
            leg.scheduledDepartureTime,
            passedStop.scheduledArrivalTime,
            leg.departureSource,
            passedStop.arrivalSource,
            leg.platform,
            leg.serviceDate,
            leg.canceled,
            leg.direction,
        )
    }

    private fun tripLeg(
        route: Route,
        index: Int,
        legOrigin: Station,
        legDestination: Station,
        trip: Schedule.Trip,
    ): TripLeg {
        val departure = trip.stopAt(legOrigin)
        val arrival = trip.stopAt(legDestination)
        return TripLeg(
            trip.line,
            legOrigin,
            legDestination,
            trip.trainDestination,
            trip.key.tripId,
            departure?.departureTime ?: 0L,
            arrival?.arrivalTime ?: 0L,
            trip.stops.filterBetween(legOrigin, legDestination).map { stop ->
                TripStop(
                    stop.station,
                    stop.arrivalTime,
                    stop.departureTime,
                    stop.scheduledArrivalTime,
                    stop.scheduledDepartureTime,
                    stop.arrivalSource,
                    stop.departureSource,
                )
            },
            minimumTransferSecondsAfter(route, index, legDestination, trip.line),
            departure?.scheduledDepartureTime ?: 0L,
            arrival?.scheduledArrivalTime ?: 0L,
            departure?.departureSource ?: PredictionSource.UNKNOWN,
            arrival?.arrivalSource ?: PredictionSource.UNKNOWN,
            departure?.platform,
            trip.key.serviceDate,
            trip.canceled,
            trip.direction,
        )
    }

    private fun updateTripLeg(existing: TripLeg, trip: Schedule.Trip): TripLeg {
        val originStop = trip.stopAt(existing.origin)
        val destinationStop = trip.stopAt(existing.destination)
        val stops = existing.stops.map { old ->
            val current = trip.stopAt(old.station) ?: return@map old
            TripStop(
                old.station,
                current.arrivalTime,
                current.departureTime,
                current.scheduledArrivalTime.takeIf { it > 0L } ?: old.scheduledArrivalTime,
                current.scheduledDepartureTime.takeIf { it > 0L } ?: old.scheduledDepartureTime,
                current.arrivalSource,
                current.departureSource,
            )
        }
        return TripLeg(
            trip.line,
            existing.origin,
            existing.destination,
            trip.trainDestination,
            existing.tripId,
            originStop?.departureTime ?: existing.departureTime,
            destinationStop?.arrivalTime ?: existing.arrivalTime,
            stops,
            existing.minimumTransferSecondsAfter,
            existing.scheduledDepartureTime,
            existing.scheduledArrivalTime,
            originStop?.departureSource ?: existing.departureSource,
            destinationStop?.arrivalSource ?: existing.arrivalSource,
            originStop?.platform ?: existing.platform,
            trip.key.serviceDate,
            trip.canceled,
            trip.direction,
        )
    }

    private fun findMatchingTrip(leg: TripLeg, trips: List<Schedule.Trip>): Schedule.Trip? =
        trips.firstOrNull {
            !it.canceled && it.line == leg.line && it.trainDestination == leg.trainDestination
                && it.canServe(leg.origin, leg.destination)
        }

    private fun isCanceled(leg: TripLeg, trips: List<Schedule.Trip>): Boolean =
        trips.any {
            it.canceled && it.key.tripId == leg.tripId
                && (leg.serviceDate == null || it.key.serviceDate == leg.serviceDate)
        }

    private fun minimumTransferSecondsAfter(
        route: Route,
        index: Int,
        transferStation: Station,
        fromLine: Line,
    ): Int {
        if (index + 1 >= route.lines.size) return 0
        return transferPolicy.minimumTransferSeconds(
            transferStation,
            fromLine,
            route.lines[index + 1],
        )
    }
}

private fun List<Schedule.Stop>.filterBetween(
    origin: Station,
    destination: Station,
): List<Schedule.Stop> {
    val start = indexOfFirst { it.station == origin }
    val end = indexOfFirst { it.station == destination }
    return if (start < 0 || end <= start) emptyList() else subList(start, end + 1)
}
