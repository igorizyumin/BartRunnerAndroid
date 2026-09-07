package com.dougkeen.bart.platform;

import android.os.Parcel;
import android.os.Parcelable;

import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.StationPair;

/** Android parcel adapter for a station pair. */
public final class StationPairParcel implements Parcelable {
    private static final int FORMAT_MAGIC = 0x42525350;
    private static final int FORMAT_VERSION = 1;
    private final StationPair stationPair;

    public StationPairParcel(StationPair stationPair) {
        this.stationPair = stationPair;
    }

    private StationPairParcel(Parcel in) {
        if (in.readInt() != FORMAT_MAGIC || in.readInt() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported station pair parcel format");
        }
        stationPair = new StationPair(readStation(in), readStation(in),
                in.readString(), in.readLong(), in.readInt(), in.readInt());
    }

    public StationPair getStationPair() {
        return stationPair;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(FORMAT_MAGIC);
        dest.writeInt(FORMAT_VERSION);
        writeStation(dest, stationPair.getOrigin());
        writeStation(dest, stationPair.getDestination());
        dest.writeString(stationPair.getFare());
        dest.writeLong(stationPair.getFareLastUpdated());
        dest.writeInt(stationPair.getAverageTripLength());
        dest.writeInt(stationPair.getAverageTripSampleCount());
    }

    private static void writeStation(Parcel dest, Station station) {
        dest.writeString(station == null ? null : station.abbreviation);
    }

    private static Station readStation(Parcel in) {
        return Station.getByAbbreviation(in.readString());
    }

    public static final Creator<StationPairParcel> CREATOR =
            new Creator<StationPairParcel>() {
                @Override
                public StationPairParcel createFromParcel(Parcel in) {
                    return new StationPairParcel(in);
                }

                @Override
                public StationPairParcel[] newArray(int size) {
                    return new StationPairParcel[size];
                }
            };
}
