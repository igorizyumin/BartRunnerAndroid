package com.dougkeen.bart.model;

import android.os.Parcel;
import android.os.Parcelable;

/** A stop arrival belonging to a train leg. */
public class TripStop implements Parcelable {
    private Station station;
    private long arrivalTime;
    private long departureTime;

    public TripStop() {
    }

    protected TripStop(Parcel in) {
        station = Station.getByAbbreviation(in.readString());
        arrivalTime = in.readLong();
        departureTime = in.readLong();
    }

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

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(station == null ? null : station.abbreviation);
        dest.writeLong(arrivalTime);
        dest.writeLong(departureTime);
    }

    public static final Creator<TripStop> CREATOR = new Creator<TripStop>() {
        @Override
        public TripStop createFromParcel(Parcel in) {
            return new TripStop(in);
        }

        @Override
        public TripStop[] newArray(int size) {
            return new TripStop[size];
        }
    };
}
