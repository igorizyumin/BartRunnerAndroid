package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.RealTimeDepartures
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.TripStop
import `in`.izyum.bart.routing.TransferPolicy
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork

/** Projects selected journeys from the canonical passenger schedule. */
class DepartureProjector(
    private val origin: Station,
    private val destination: Station?,
    private val network: BartGtfsNetwork,
) {
    private val routing = CanonicalRoutingAdapter(network)
    private val transferPolicy = TransferPolicy(network)

    fun project(canonical: CanonicalTransitSnapshot): RealTimeDepartures {
        val trips = canonical.passengerTrips.map { it.trip }
        val feedTime = canonical.normalizedFeed.provenance.feedTimestampMillis
        val inputs = routing.inputs(trips)
        val byTrip = inputs.associateBy { it.trip }
        val router = routing.router(inputs)
        val departures = mutableListOf<Departure>()

        trips.forEach { trip ->
            val originStop = trip.stopAt(origin) ?: return@forEach
            if (originStop.departureTime <= 0L
                || originStop.departureTime < feedTime - DEPARTURE_STALE_TOLERANCE_MILLIS
            ) return@forEach

            val selected = if (destination != null) {
                val input = byTrip[trip] ?: return@forEach
                val journey = routing.bestJourney(router, origin, destination, originStop.departureTime)
                    ?: return@forEach
                if (journey.legs.firstOrNull()?.trip?.id != input.raptorTrip.id) {
                    return@forEach
                }
                val route = routing.routeForJourney(journey) ?: return@forEach
                val legs = journey.legs.mapIndexed { index, leg ->
                    val selectedTrip = leg.trip.payload as? Schedule.Trip ?: return@forEach
                    tripLeg(route, index, leg.origin, leg.destination, selectedTrip)
                }
                if (legs.lastOrNull()?.destination != destination) return@forEach
                route to legs
            } else {
                val route = Route.direct(
                    origin,
                    trip.trainDestination,
                    trip.line,
                    trip.direction,
                    trip.stops.map { it.station },
                )
                route to listOf(tripLeg(route, 0, origin, trip.trainDestination, trip))
            }

            val (route, legs) = selected
            val line = legs.firstOrNull()?.line ?: trip.line
            val minutes = maxOf(0L, (originStop.departureTime - feedTime) / 60_000L).toInt()
            val departure = Departure.builder()
                .setOrigin(origin)
                .setTrainDestination(trip.trainDestination)
                .setLine(line)
                .setPlatform(originStop.platform)
                .setCanceled(trip.canceled)
                .setMinutes(minutes)
                .setMinEstimate(originStop.departureTime - ESTIMATE_TOLERANCE_MILLIS)
                .setMaxEstimate(originStop.departureTime + ESTIMATE_TOLERANCE_MILLIS)
                .setTripLegs(legs)
                .let { builder ->
                    legs.lastOrNull()?.arrivalTime?.takeIf { it > 0L }?.let { arrival ->
                        builder.setEstimatedTripTime((arrival - originStop.departureTime).toInt())
                    } ?: builder
                }
                .build()
            departures += departure
        }

        return RealTimeDepartures(
            origin,
            destination,
            feedTime,
            departures,
            transfersIncluded = destination != null,
        )
    }

    private fun tripLeg(
        route: Route,
        legIndex: Int,
        legOrigin: Station,
        legDestination: Station,
        trip: Schedule.Trip,
    ): TripLeg {
        val departure = trip.stopAt(legOrigin)
        val arrival = trip.stopAt(legDestination)
        val stops = trip.stops
            .dropWhile { it.station != legOrigin }
            .takeWhile { it.station != legDestination }
            .plus(listOfNotNull(arrival))
            .map { stop ->
                TripStop(
                    stop.station,
                    stop.arrivalTime,
                    stop.departureTime,
                    stop.scheduledArrivalTime,
                    stop.scheduledDepartureTime,
                    stop.arrivalSource,
                    stop.departureSource,
                )
            }
        return TripLeg(
            trip.line,
            legOrigin,
            legDestination,
            trip.trainDestination,
            trip.key.tripId,
            departure?.departureTime ?: 0L,
            arrival?.arrivalTime ?: 0L,
            stops,
            minimumTransferSecondsAfter(route, legIndex, trip.line),
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

    private fun minimumTransferSecondsAfter(route: Route, index: Int, fromLine: Line): Int {
        if (index < 0 || index + 1 >= route.lines.size) return 0
        return transferPolicy.minimumTransferSeconds(
            route.transferStations.getOrNull(index),
            fromLine,
            route.lines[index + 1],
        )
    }

    private companion object {
        const val ESTIMATE_TOLERANCE_MILLIS = 30_000L
        const val DEPARTURE_STALE_TOLERANCE_MILLIS = 45_000L
    }
}
