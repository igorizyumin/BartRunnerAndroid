package com.dougkeen.bart.model

import java.text.DateFormat
import java.util.Date

/** One scheduled trip between two stations. */
class ScheduleItem() {
    private var origin: Station? = null
    private var destination: Station? = null
    private var fare: String? = null
    private var departureTime: Long = 0L
    private var arrivalTime: Long = 0L
    private var bikesAllowed: Boolean = false
    private var trainHeadStation: String? = null

    constructor(origin: Station?, destination: Station?) : this() {
        this.origin = origin
        this.destination = destination
    }

    fun getOrigin(): Station? = origin

    fun setOrigin(origin: Station?) {
        this.origin = origin
    }

    fun getDestination(): Station? = destination

    fun setDestination(destination: Station?) {
        this.destination = destination
    }

    fun getFare(): String? = fare

    fun setFare(fare: String?) {
        this.fare = fare
    }

    fun getDepartureTime(): Long = departureTime

    fun setDepartureTime(departureTime: Long) {
        this.departureTime = departureTime
    }

    fun getArrivalTime(): Long = arrivalTime

    fun setArrivalTime(arrivalTime: Long) {
        this.arrivalTime = arrivalTime
    }

    fun getTripLength(): Int =
        if (departureTime <= 0 || arrivalTime <= 0) 0
        else (arrivalTime - departureTime).toInt()

    fun isBikesAllowed(): Boolean = bikesAllowed

    fun setBikesAllowed(bikesAllowed: Boolean) {
        this.bikesAllowed = bikesAllowed
    }

    fun getTrainHeadStation(): String? = trainHeadStation

    fun setTrainHeadStation(trainHeadStation: String?) {
        this.trainHeadStation = trainHeadStation
    }

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
