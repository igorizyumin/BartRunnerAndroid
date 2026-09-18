package `in`.izyum.bart.model

import java.time.LocalDate

/** Stable identity of one published static trip on one service date. */
data class TripIdentity(
    val serviceDate: LocalDate,
    val tripId: String,
) {
    override fun toString(): String = "$serviceDate:$tripId"
}
