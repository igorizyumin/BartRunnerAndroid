package com.dougkeen.bart.model;

/** A stop arrival belonging to a train leg. */
public final class TripStop {
    private final Station station;
    private final long arrivalTime;
    private final long departureTime;

    public TripStop(Station station, long arrivalTime) {
        this(station, arrivalTime, arrivalTime);
    }

    public TripStop(Station station, long arrivalTime, long departureTime) {
        this.station = station;
        this.arrivalTime = arrivalTime;
        this.departureTime = departureTime;
    }

    public Station getStation() {
        return station;
    }

    public long getArrivalTime() {
        return arrivalTime;
    }

    public long getDepartureTime() {
        return departureTime;
    }

}
