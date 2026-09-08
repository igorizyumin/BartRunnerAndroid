package com.dougkeen.bart.model

/** A stop arrival belonging to a train leg. */
class TripStop(
    val station: Station?,
    val arrivalTime: Long,
    val departureTime: Long
) {
    constructor(station: Station?, arrivalTime: Long) : this(
        station,
        arrivalTime,
        arrivalTime
    )
}
