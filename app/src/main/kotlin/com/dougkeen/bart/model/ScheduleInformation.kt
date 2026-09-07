package com.dougkeen.bart.model

/** Scheduled trips and aggregate statistics for one station pair. */
class ScheduleInformation(
    private var origin: Station?,
    private var destination: Station?
) {
    private var date = 0L
    private var trips: MutableList<ScheduleItem>? = null
    private var averageTripLength = -1
    private var tripCount = 0

    fun getOrigin(): Station? = origin

    fun setOrigin(origin: Station?) {
        this.origin = origin
    }

    fun getDestination(): Station? = destination

    fun setDestination(destination: Station?) {
        this.destination = destination
    }

    fun getDate(): Long = date

    fun setDate(date: Long) {
        this.date = date
    }

    fun getTrips(): MutableList<ScheduleItem> {
        if (trips == null) {
            trips = mutableListOf()
        }
        return trips!!
    }

    fun setTrips(trips: List<ScheduleItem>?) {
        this.trips = trips?.toMutableList() ?: mutableListOf()
    }

    fun addTrip(trip: ScheduleItem) {
        getTrips() += trip
    }

    fun getLatestDepartureTime(): Long =
        if (getTrips().isEmpty()) -1 else getTrips().last().getDepartureTime()

    fun getAverageTripLength(): Int {
        if (averageTripLength < 0) {
            var sum = 0
            for (trip in getTrips()) {
                val tripLength = trip.getTripLength()
                if (tripLength > 0) {
                    sum += tripLength
                    tripCount++
                }
            }
            if (tripCount > 0) {
                averageTripLength = sum / tripCount
            }
        }
        return averageTripLength
    }

    fun getTripCountForAverage(): Int {
        getAverageTripLength()
        return tripCount
    }
}
