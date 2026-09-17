package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.routing.RaptorRouter
import `in`.izyum.bart.routing.TransferPolicy
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import java.util.LinkedHashMap

/** Converts canonical trips to RAPTOR input and owns canonical journey ranking. */
class CanonicalRoutingAdapter(
    private val network: BartGtfsNetwork,
    private val transferPolicy: TransferPolicy = TransferPolicy(network),
) {
    data class Input(
        val trip: Schedule.Trip,
        val raptorTrip: RaptorRouter.Trip,
    )

    fun inputs(schedule: Schedule): List<Input> = inputs(schedule.trips)

    fun inputs(trips: List<Schedule.Trip>): List<Input> = trips.map { trip ->
        Input(trip, raptorTrip(trip))
    }

    fun router(inputs: List<Input>): RaptorRouter = RaptorRouter(
        inputs.map { it.raptorTrip },
        network,
        transferPolicy,
    )

    fun bestJourney(
        router: RaptorRouter,
        origin: Station,
        destination: Station,
        departureTime: Long,
    ): RaptorRouter.Journey? = router.journeys(origin, destination, departureTime)
        .minWithOrNull(
            compareBy<RaptorRouter.Journey> { it.arrivalTime }
                .thenBy { it.transferCount }
                .thenBy { routeForJourney(it)?.let(transferPolicy::routeScore) ?: Int.MAX_VALUE },
        )

    fun routeForJourney(journey: RaptorRouter.Journey): Route? {
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

    fun canonicalTripId(trip: Schedule.Trip): String =
        "${trip.key.serviceDate}:${trip.key.tripId}"

    private fun raptorTrip(trip: Schedule.Trip): RaptorRouter.Trip {
        val stops = trip.stops.map { stop ->
            RaptorRouter.StopTime(
                station = stop.station,
                arrivalTime = stop.arrivalTime,
                departureTime = stop.departureTime,
                scheduledArrivalTime = stop.scheduledArrivalTime,
                scheduledDepartureTime = stop.scheduledDepartureTime,
                arrivalSource = stop.arrivalSource,
                departureSource = stop.departureSource,
                platform = stop.platform,
            )
        }
        return RaptorRouter.Trip(
            id = canonicalTripId(trip),
            routeKey = "${trip.line}:${stops.joinToString(",") { it.station.abbreviation }}",
            line = trip.line,
            direction = trip.direction,
            trainDestination = trip.trainDestination,
            stops = stops,
            payload = trip,
            canceled = trip.canceled,
        )
    }
}
