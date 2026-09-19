package `in`.izyum.bart.data

import `in`.izyum.bart.model.Itinerary

/** Immutable state of the itinerary currently being followed. */
data class FollowedTripState(
    val itinerary: Itinerary?,
)
