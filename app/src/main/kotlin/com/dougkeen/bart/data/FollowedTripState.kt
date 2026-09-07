package com.dougkeen.bart.data

import com.dougkeen.bart.model.Departure

/** Immutable snapshot of the departure currently being followed. */
data class FollowedTripState(
    val departure: Departure?,
)
