package com.dougkeen.bart.backend

import com.dougkeen.bart.model.ScheduleInformation
import com.dougkeen.bart.model.ScheduleItem
import com.dougkeen.bart.model.Station

/** Projects a route's schedule from the cached static GTFS source. */
class ScheduleProjection(
    private val source: StaticScheduleSource,
    private val origin: Station,
    private val destination: Station
) : TransitProjection<ScheduleInformation> {
    init {
        requireNotNull(source) {
            "Schedule projection needs a source and two stations"
        }
        requireNotNull(origin) {
            "Schedule projection needs a source and two stations"
        }
        requireNotNull(destination) {
            "Schedule projection needs a source and two stations"
        }
    }

    override fun project(snapshot: TransitFeedSnapshot): ScheduleInformation =
        source.getSchedule(origin, destination)

    override fun areEquivalent(
        previous: ScheduleInformation?,
        current: ScheduleInformation?
    ): Boolean {
        if (previous === current) {
            return true
        }
        if (previous == null || current == null
            || previous.getDate() != current.getDate()
            || previous.getTrips().size != current.getTrips().size
        ) {
            return false
        }
        for (index in previous.getTrips().indices) {
            val left: ScheduleItem = previous.getTrips()[index]
            val right: ScheduleItem = current.getTrips()[index]
            if (left.getOrigin() != right.getOrigin()
                || left.getDestination() != right.getDestination()
                || left.getFare() != right.getFare()
                || left.getDepartureTime() != right.getDepartureTime()
                || left.getArrivalTime() != right.getArrivalTime()
                || left.isBikesAllowed() != right.isBikesAllowed()
                || left.getTrainHeadStation() != right.getTrainHeadStation()
            ) {
                return false
            }
        }
        return true
    }
}
