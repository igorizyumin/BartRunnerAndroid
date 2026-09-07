package com.dougkeen.bart.model

import java.text.DateFormat
import java.util.Date

/** One immutable scheduled trip between two stations. */
data class ScheduleItem(
    val origin: Station?,
    val destination: Station?,
    val fare: String? = null,
    val departureTime: Long = 0L,
    val arrivalTime: Long = 0L,
    val bikesAllowed: Boolean = false,
    val trainHeadStation: String? = null
) {
    fun getTripLength(): Int =
        if (departureTime <= 0 || arrivalTime <= 0) 0
        else (arrivalTime - departureTime).toInt()

    fun isBikesAllowed(): Boolean = bikesAllowed

    override fun toString(): String {
        val format = DateFormat.getTimeInstance()
        return "ScheduleItem [origin=$origin, destination=$destination, fare=$fare, " +
            "departureTime=${format.format(Date(departureTime))}, " +
            "arrivalTime=${format.format(Date(arrivalTime))}, bikesAllowed=$bikesAllowed, " +
            "trainHeadStation=$trainHeadStation]"
    }

    companion object {
        const val SCHEDULE_ITEM_DEPARTURE_EQUALS_TOLERANCE = 120000
    }
}
