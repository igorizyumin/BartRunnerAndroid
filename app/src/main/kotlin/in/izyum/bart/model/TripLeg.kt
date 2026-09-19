package `in`.izyum.bart.model

import java.util.ArrayList
import java.util.Collections
import java.time.LocalDate

/** One train in a possibly multi-train itinerary. */
class TripLeg @JvmOverloads constructor(
    val line: Line?,
    val origin: Station?,
    val destination: Station?,
    val trainDestination: Station?,
    val tripId: String?,
    val departureTime: Long,
    val arrivalTime: Long,
    stops: List<TripStop>,
    val minimumTransferSecondsAfter: Int = 0,
    val scheduledDepartureTime: Long = 0L,
    val scheduledArrivalTime: Long = 0L,
    val departureSource: PredictionSource = PredictionSource.UNKNOWN,
    val arrivalSource: PredictionSource = PredictionSource.UNKNOWN,
    val platform: String? = null,
    /** Static service date keeps trip IDs stable across midnight refreshes. */
    val serviceDate: LocalDate? = null,
    val canceled: Boolean = false,
    val direction: String? = null,
) {
    val stops: List<TripStop> = immutableTripLegList(stops)

    val canonicalIdentity: String?
        get() = tripIdentity?.toString() ?: tripId

    val tripIdentity: TripIdentity?
        get() = tripId?.let { id -> serviceDate?.let { TripIdentity(it, id) } }

    fun hasArrivalTime(): Boolean = arrivalTime > 0
}

/** BART's ETD range is generally +/- 30 seconds around the effective time. */
internal const val DEPARTURE_UNCERTAINTY_MILLIS = 30_000L

/** Lower-bound boarding time used by safety-sensitive countdowns. */
internal fun pessimisticDepartureTime(leg: TripLeg): Long {
    val effectiveDeparture = leg.departureTime.takeIf { it > 0L }
        ?: leg.scheduledDepartureTime
    return (effectiveDeparture - DEPARTURE_UNCERTAINTY_MILLIS).coerceAtLeast(0L)
}

private fun <T> immutableTripLegList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
