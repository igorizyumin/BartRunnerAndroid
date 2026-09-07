package com.dougkeen.bart.backend;

import com.dougkeen.bart.model.ScheduleInformation;
import com.dougkeen.bart.model.ScheduleItem;
import com.dougkeen.bart.model.Station;

import java.util.Objects;

/**
 * Projects a route's schedule from the static GTFS store. The source is
 * expected to cache static GTFS data; realtime feed changes do not require a
 * network request for every route.
 */
public final class ScheduleProjection
        implements TransitProjection<ScheduleInformation> {
    private final StaticScheduleSource source;
    private final Station origin;
    private final Station destination;

    public ScheduleProjection(StaticScheduleSource source,
                              Station origin,
                              Station destination) {
        if (source == null || origin == null || destination == null) {
            throw new IllegalArgumentException(
                    "Schedule projection needs a source and two stations");
        }
        this.source = source;
        this.origin = origin;
        this.destination = destination;
    }

    @Override
    public ScheduleInformation project(TransitFeedSnapshot snapshot)
            throws Exception {
        return source.getSchedule(origin, destination);
    }

    @Override
    public boolean areEquivalent(ScheduleInformation previous,
                                 ScheduleInformation current) {
        if (previous == current) {
            return true;
        }
        if (previous == null || current == null
                || previous.getDate() != current.getDate()
                || previous.getTrips().size() != current.getTrips().size()) {
            return false;
        }
        for (int i = 0; i < previous.getTrips().size(); i++) {
            ScheduleItem left = previous.getTrips().get(i);
            ScheduleItem right = current.getTrips().get(i);
            if (!Objects.equals(left.getOrigin(), right.getOrigin())
                    || !Objects.equals(left.getDestination(), right.getDestination())
                    || !Objects.equals(left.getFare(), right.getFare())
                    || left.getDepartureTime() != right.getDepartureTime()
                    || left.getArrivalTime() != right.getArrivalTime()
                    || left.isBikesAllowed() != right.isBikesAllowed()
                    || !Objects.equals(left.getTrainHeadStation(),
                    right.getTrainHeadStation())) {
                return false;
            }
        }
        return true;
    }
}
