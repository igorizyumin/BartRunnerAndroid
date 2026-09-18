package `in`.izyum.bart.model

/**
 * The selected passenger journey. Unlike [Departure], this is the state owned
 * by the trip-progress screen: its legs may be refreshed individually and its
 * future suffix may be replaced when a connection becomes infeasible.
 */
data class Itinerary(
    val origin: Station,
    val destination: Station,
    val legs: List<TripLeg>,
) {
    val trainDestination: Station?
        get() = legs.lastOrNull()?.trainDestination ?: legs.lastOrNull()?.destination

    val line: Line?
        get() = legs.firstOrNull()?.line

    val direction: String?
        get() = legs.firstOrNull()?.direction

    val platform: String?
        get() = legs.firstOrNull()?.platform

    val canceled: Boolean
        get() = legs.any { it.canceled }

    /** Stable anchor used to reopen the selected journey across feed refreshes. */
    val selectionIdentity: String
        get() = legs.firstOrNull()?.tripIdentity?.toString()
            ?: "itinerary|${origin.abbreviation}|${destination.abbreviation}|${legs.firstOrNull()?.departureTime ?: 0L}"

    fun hasTransfers(): Boolean = legs.size > 1

    fun getStationPair(): StationPair = StationPair(origin, destination)

    fun getInitialDepartureTime(pessimistic: Boolean = false): Long =
        legs.firstOrNull()?.departureTime ?: 0L

    fun getInitialArrivalTime(pessimistic: Boolean = false): Long {
        val first = legs.firstOrNull() ?: return 0L
        val stop = first.stops.firstOrNull { it.station == origin }
        val actualDwell = if (stop != null && stop.arrivalTime > 0L && stop.departureTime > 0L) {
            (stop.departureTime - stop.arrivalTime).coerceAtLeast(0L)
        } else 0L
        val scheduledDwell = if (stop != null && stop.scheduledArrivalTime > 0L && stop.scheduledDepartureTime > 0L) {
            (stop.scheduledDepartureTime - stop.scheduledArrivalTime).coerceAtLeast(0L)
        } else 0L
        return (getInitialDepartureTime(pessimistic) - maxOf(actualDwell, scheduledDwell))
            .coerceAtLeast(0L)
    }

    fun getEstimatedArrivalTime(): Long = legs.lastOrNull()?.arrivalTime ?: 0L

    fun hasAnyArrivalEstimate(): Boolean = getEstimatedArrivalTime() > 0L

    fun getEstimatedArrivalMinutesLeft(nowMillis: Long): Long {
        val arrival = getEstimatedArrivalTime()
        if (arrival <= 0L) return 0L
        val millisLeft = arrival - nowMillis
        return if (millisLeft < 0L) -1L else (millisLeft + 29_999L) / 60_000L
    }

    fun hasInitialDeparturePassed(nowMillis: Long, pessimistic: Boolean = false): Boolean =
        getInitialDepartureTime(pessimistic) <= nowMillis

    fun hasExpired(nowMillis: Long): Boolean =
        hasAnyArrivalEstimate() && getEstimatedArrivalTime() + 60_000L < nowMillis

    fun replaceLegs(updatedLegs: List<TripLeg>): Itinerary = copy(legs = updatedLegs)

    /** Compatibility adapter for alarms, notifications, and the departure list. */
    fun toDeparture(): Departure = Departure.builder()
        .setOrigin(origin)
        .setTrainDestination(trainDestination)
        .setPassengerDestination(destination)
        .setLine(line)
        .setPlatform(platform)
        .setCanceled(canceled)
        .setMinEstimate(getInitialDepartureTime())
        .setMaxEstimate(getInitialDepartureTime())
        .setEstimatedTripTime(
            (getEstimatedArrivalTime() - getInitialDepartureTime()).coerceAtLeast(0L).toInt(),
        )
        .setTripLegs(legs)
        .build()

    companion object {
        @JvmStatic
        fun fromDeparture(departure: Departure): Itinerary? {
            val origin = departure.origin ?: return null
            val destination = departure.passengerDestination
                ?: departure.trainDestination
                ?: return null
            return Itinerary(origin, destination, departure.tripLegs)
        }
    }
}
