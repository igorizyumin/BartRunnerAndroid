package com.dougkeen.bart.model

import java.util.ArrayList
import java.util.Collections

/** One train in a possibly multi-train itinerary. */
class TripLeg(
    val line: Line?,
    val origin: Station?,
    val destination: Station?,
    val trainDestination: Station?,
    val tripId: String?,
    val departureTime: Long,
    val arrivalTime: Long,
    stops: List<TripStop>
) {
    val stops: List<TripStop> = immutableTripLegList(stops)

    fun hasArrivalTime(): Boolean = arrivalTime > 0
}

private fun <T> immutableTripLegList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
