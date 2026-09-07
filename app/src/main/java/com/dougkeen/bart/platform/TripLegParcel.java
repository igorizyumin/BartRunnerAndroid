package com.dougkeen.bart.platform;

import android.os.Parcel;
import android.os.Parcelable;

import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.model.TripStop;

import java.util.ArrayList;
import java.util.List;

/** Android parcel adapter for one trip leg. */
public final class TripLegParcel implements Parcelable {
    private static final int FORMAT_MAGIC = 0x4252544c;
    private static final int FORMAT_VERSION = 1;
    private final TripLeg leg;

    public TripLegParcel(TripLeg leg) {
        this.leg = leg;
    }

    private TripLegParcel(Parcel in) {
        if (in.readInt() != FORMAT_MAGIC || in.readInt() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported trip leg parcel format");
        }
        TripLeg restored = new TripLeg();
        int lineOrdinal = in.readInt();
        restored.setLine(lineOrdinal < 0 ? null : Line.values()[lineOrdinal]);
        restored.setOrigin(readStation(in));
        restored.setDestination(readStation(in));
        restored.setTrainDestination(readStation(in));
        restored.setTripId(in.readString());
        restored.setDepartureTime(in.readLong());
        restored.setArrivalTime(in.readLong());
        ArrayList<TripStopParcel> stopParcels = in.createTypedArrayList(
                TripStopParcel.CREATOR);
        List<TripStop> stops = new ArrayList<>();
        if (stopParcels != null) {
            for (TripStopParcel stopParcel : stopParcels) {
                stops.add(stopParcel.getTripStop());
            }
        }
        restored.setStops(stops);
        leg = restored;
    }

    public TripLeg getTripLeg() {
        return leg;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(FORMAT_MAGIC);
        dest.writeInt(FORMAT_VERSION);
        dest.writeInt(leg.getLine() == null ? -1 : leg.getLine().ordinal());
        writeStation(dest, leg.getOrigin());
        writeStation(dest, leg.getDestination());
        writeStation(dest, leg.getTrainDestination());
        dest.writeString(leg.getTripId());
        dest.writeLong(leg.getDepartureTime());
        dest.writeLong(leg.getArrivalTime());
        ArrayList<TripStopParcel> stopParcels = new ArrayList<>();
        for (TripStop stop : leg.getStops()) {
            stopParcels.add(new TripStopParcel(stop));
        }
        dest.writeTypedList(stopParcels);
    }

    private static void writeStation(Parcel dest, Station station) {
        dest.writeString(station == null ? null : station.abbreviation);
    }

    private static Station readStation(Parcel in) {
        return Station.getByAbbreviation(in.readString());
    }

    public static final Creator<TripLegParcel> CREATOR =
            new Creator<TripLegParcel>() {
                @Override
                public TripLegParcel createFromParcel(Parcel in) {
                    return new TripLegParcel(in);
                }

                @Override
                public TripLegParcel[] newArray(int size) {
                    return new TripLegParcel[size];
                }
            };
}
