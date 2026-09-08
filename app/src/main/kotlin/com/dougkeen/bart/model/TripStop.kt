package com.dougkeen.bart.model

/** A stop arrival belonging to a train leg. */
class TripStop(
    val station: Station?,
    val arrivalTime: Long,
    val departureTime: Long,
    val scheduledArrivalTime: Long = 0L,
    val scheduledDepartureTime: Long = 0L,
    val arrivalSource: PredictionSource = PredictionSource.UNKNOWN,
    val departureSource: PredictionSource = PredictionSource.UNKNOWN,
) {
    constructor(station: Station?, arrivalTime: Long) : this(
        station,
        arrivalTime,
        arrivalTime
    )

    fun arrivalDelaySeconds(): Int? = delaySeconds(arrivalTime, scheduledArrivalTime)

    fun departureDelaySeconds(): Int? = delaySeconds(departureTime, scheduledDepartureTime)

    private fun delaySeconds(actual: Long, scheduled: Long): Int? =
        if (actual > 0L && scheduled > 0L) ((actual - scheduled) / 1000L).toInt() else null
}
