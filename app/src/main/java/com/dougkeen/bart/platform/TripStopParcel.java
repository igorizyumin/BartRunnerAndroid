package com.dougkeen.bart.platform;

import android.os.Parcel;
import android.os.Parcelable;

import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.TripStop;

/** Android parcel adapter for a trip stop. */
public final class TripStopParcel implements Parcelable {
    private static final int FORMAT_MAGIC = 0x42525453;
    private static final int FORMAT_VERSION = 1;
    private final TripStop stop;

    public TripStopParcel(TripStop stop) {
        this.stop = stop;
    }

    private TripStopParcel(Parcel in) {
        if (in.readInt() != FORMAT_MAGIC || in.readInt() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported trip stop parcel format");
        }
        stop = new TripStop(readStation(in), in.readLong(), in.readLong());
    }

    public TripStop getTripStop() {
        return stop;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(FORMAT_MAGIC);
        dest.writeInt(FORMAT_VERSION);
        dest.writeString(stop.getStation() == null ? null
                : stop.getStation().abbreviation);
        dest.writeLong(stop.getArrivalTime());
        dest.writeLong(stop.getDepartureTime());
    }

    private static Station readStation(Parcel in) {
        return Station.getByAbbreviation(in.readString());
    }

    public static final Creator<TripStopParcel> CREATOR =
            new Creator<TripStopParcel>() {
                @Override
                public TripStopParcel createFromParcel(Parcel in) {
                    return new TripStopParcel(in);
                }

                @Override
                public TripStopParcel[] newArray(int size) {
                    return new TripStopParcel[size];
                }
            };
}
