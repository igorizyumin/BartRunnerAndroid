package com.dougkeen.bart.model

import java.util.ArrayList
import java.util.Collections

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
) {
    val stops: List<TripStop> = immutableTripLegList(stops)

    fun hasArrivalTime(): Boolean = arrivalTime > 0

    fun departureDelaySeconds(): Int? = delaySeconds(departureTime, scheduledDepartureTime)

    fun arrivalDelaySeconds(): Int? = delaySeconds(arrivalTime, scheduledArrivalTime)

    private fun delaySeconds(actual: Long, scheduled: Long): Int? =
        if (actual > 0L && scheduled > 0L) ((actual - scheduled) / 1000L).toInt() else null
}

private fun <T> immutableTripLegList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
