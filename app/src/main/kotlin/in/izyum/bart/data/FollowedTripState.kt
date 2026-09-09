package `in`.izyum.bart.data

import `in`.izyum.bart.model.Departure

/** Immutable state of the departure currently being followed. */
data class FollowedTripState(
    val departure: Departure?,
)
