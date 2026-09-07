package com.dougkeen.bart.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One train in a possibly multi-train itinerary. */
public class TripLeg implements Parcelable {
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

    protected TripLeg(Parcel in) {
        int lineOrdinal = in.readInt();
        line = lineOrdinal < 0 ? null : Line.values()[lineOrdinal];
        origin = Station.getByAbbreviation(in.readString());
        destination = Station.getByAbbreviation(in.readString());
        trainDestination = Station.getByAbbreviation(in.readString());
        tripId = in.readString();
        departureTime = in.readLong();
        arrivalTime = in.readLong();
        stops = in.createTypedArrayList(TripStop.CREATOR);
        if (stops == null) {
            stops = new ArrayList<TripStop>();
        }
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

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(line == null ? -1 : line.ordinal());
        dest.writeString(origin == null ? null : origin.abbreviation);
        dest.writeString(destination == null ? null : destination.abbreviation);
        dest.writeString(trainDestination == null ? null
                : trainDestination.abbreviation);
        dest.writeString(tripId);
        dest.writeLong(departureTime);
        dest.writeLong(arrivalTime);
        dest.writeTypedList(stops);
    }

    public static final Creator<TripLeg> CREATOR = new Creator<TripLeg>() {
        @Override
        public TripLeg createFromParcel(Parcel in) {
            return new TripLeg(in);
        }

        @Override
        public TripLeg[] newArray(int size) {
            return new TripLeg[size];
        }
    };
}
