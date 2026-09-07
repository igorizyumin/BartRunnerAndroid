package com.dougkeen.bart.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class StationPair {
    @JsonCreator
    public StationPair(@JsonProperty("origin") Station origin,
                       @JsonProperty("destination") Station destination,
                       @JsonProperty("fare") String fare,
                       @JsonProperty("fareLastUpdated") long fareLastUpdated,
                       @JsonProperty("averageTripLength") int averageTripLength,
                       @JsonProperty("averageTripSampleCount") int averageTripSampleCount) {
        this.origin = origin;
        this.destination = destination;
        this.fare = fare;
        this.fareLastUpdated = fareLastUpdated;
        this.averageTripLength = averageTripLength;
        this.averageTripSampleCount = averageTripSampleCount;
    }

    public StationPair(Station origin, Station destination) {
        this(origin, destination, null, 0L, 0, 0);
    }

    private final Station origin;
    private final Station destination;
    private final String fare;

    private final long fareLastUpdated;
    private final int averageTripLength;
    private final int averageTripSampleCount;

    public Station getOrigin() {
        return origin;
    }

    public Station getDestination() {
        return destination;
    }

    @JsonIgnore
    public boolean isStationOnly() {
        return origin != null && destination == null;
    }

    public String getFare() {
        return fare;
    }

    public long getFareLastUpdated() {
        return fareLastUpdated;
    }

    public int getAverageTripLength() {
        return averageTripLength;
    }

    public int getAverageTripSampleCount() {
        return averageTripSampleCount;
    }

    public StationPair withFare(String fare, long fareLastUpdated) {
        return new StationPair(origin, destination, fare, fareLastUpdated,
                averageTripLength, averageTripSampleCount);
    }

    public boolean isBetweenStations(Station station1, Station station2) {
        return origin != null && destination != null
                && ((origin.equals(station1) && destination.equals(station2))
                || (origin.equals(station2) && destination.equals(station1)));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((destination == null) ? 0 : destination.hashCode());
        result = prime * result + ((origin == null) ? 0 : origin.hashCode());
        return result;
    }

    public boolean fareEquals(StationPair other) {
        if (other == null)
            return false;
        return Objects.equals(getFare(), other.getFare())
                && getFareLastUpdated() == other.getFareLastUpdated();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        StationPair other = (StationPair) obj;
        if (destination != other.destination)
            return false;
        return origin == other.origin;
    }

    @Override
    public String toString() {
        return "StationPair [origin=" + origin + ", destination=" + destination
                + "]";
    }

}
