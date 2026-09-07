package com.dougkeen.bart.model

import java.util.ArrayList
import java.util.Collections

/** Immutable scheduled trips and aggregate statistics for one station pair. */
class ScheduleInformation(
    val origin: Station?,
    val destination: Station?,
    val date: Long,
    trips: List<ScheduleItem>
) {
    private val trips: List<ScheduleItem> =
        Collections.unmodifiableList(ArrayList(trips))

    fun getTrips(): List<ScheduleItem> = trips

    fun getLatestDepartureTime(): Long =
        trips.lastOrNull()?.departureTime ?: -1L

    fun getAverageTripLength(): Int {
        var sum = 0
        var count = 0
        for (trip in trips) {
            val tripLength = trip.getTripLength()
            if (tripLength > 0) {
                sum += tripLength
                count++
            }
        }
        return if (count == 0) -1 else sum / count
    }

    fun getTripCountForAverage(): Int =
        trips.count { it.getTripLength() > 0 }
}
