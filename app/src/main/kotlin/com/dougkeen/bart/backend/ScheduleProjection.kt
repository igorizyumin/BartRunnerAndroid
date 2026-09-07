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
            || previous.date != current.date
            || previous.getTrips().size != current.getTrips().size
        ) {
            return false
        }
        for (index in previous.getTrips().indices) {
            val left: ScheduleItem = previous.getTrips()[index]
            val right: ScheduleItem = current.getTrips()[index]
            if (left.origin != right.origin
                || left.destination != right.destination
                || left.fare != right.fare
                || left.departureTime != right.departureTime
                || left.arrivalTime != right.arrivalTime
                || left.isBikesAllowed() != right.isBikesAllowed()
                || left.trainHeadStation != right.trainHeadStation
            ) {
                return false
            }
        }
        return true
    }
}
