package com.dougkeen.bart.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One train in a possibly multi-train itinerary. */
public class TripLeg {
    private Line line;
    private Station origin;
    private Station destination;
    private Station trainDestination;
    private String tripId;
    private long departureTime;
    private long arrivalTime;
    private List<TripStop> stops = new ArrayList<TripStop>();

    public TripLeg() {
    }

    public Line getLine() {
        return line;
    }

    public void setLine(Line line) {
        this.line = line;
    }

    public Station getOrigin() {
        return origin;
    }

    public void setOrigin(Station origin) {
        this.origin = origin;
    }

    public Station getDestination() {
        return destination;
    }

    public void setDestination(Station destination) {
        this.destination = destination;
    }

    public Station getTrainDestination() {
        return trainDestination;
    }

    public void setTrainDestination(Station trainDestination) {
        this.trainDestination = trainDestination;
    }

    public String getTripId() {
        return tripId;
    }

    public void setTripId(String tripId) {
        this.tripId = tripId;
    }

    public long getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(long departureTime) {
        this.departureTime = departureTime;
    }

    public long getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(long arrivalTime) {
        this.arrivalTime = arrivalTime;
    }

    public List<TripStop> getStops() {
        return Collections.unmodifiableList(stops);
    }

    public void setStops(List<TripStop> stops) {
        this.stops = new ArrayList<TripStop>();
        if (stops != null) {
            this.stops.addAll(stops);
        }
    }

    public boolean hasArrivalTime() {
        return arrivalTime > 0;
    }

}
