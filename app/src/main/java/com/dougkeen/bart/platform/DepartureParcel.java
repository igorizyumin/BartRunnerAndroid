package com.dougkeen.bart.platform;

import android.os.Parcel;
import android.os.Parcelable;

import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.TripLeg;

import java.util.ArrayList;
import java.util.List;

/** Android parcel adapter for passing a departure through framework state. */
public final class DepartureParcel implements Parcelable {
    private static final int FORMAT_MAGIC = 0x42524450;
    private static final int FORMAT_VERSION = 1;
    private final Departure departure;

    public DepartureParcel(Departure departure) {
        this.departure = departure;
    }

    private DepartureParcel(Parcel in) {
        departure = readDeparture(in);
    }

    public Departure getDeparture() {
        return departure;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeDeparture(dest, departure);
    }

    private static void writeDeparture(Parcel dest, Departure departure) {
        dest.writeInt(FORMAT_MAGIC);
        dest.writeInt(FORMAT_VERSION);
        writeStation(dest, departure.getOrigin());
        writeStation(dest, departure.getTrainDestination());
        writeStation(dest, departure.getPassengerDestination());
        dest.writeString(departure.getTrainDestinationColorHex());
        dest.writeString(departure.getTrainDestinationColorText());
        dest.writeString(departure.getPlatform());
        dest.writeString(departure.getDirection());
        dest.writeByte((byte) (departure.isBikeAllowed() ? 1 : 0));
        dest.writeString(departure.getTrainLength());
        dest.writeByte((byte) (departure.getRequiresTransfer() ? 1 : 0));
        dest.writeByte((byte) (departure.isTransferScheduled() ? 1 : 0));
        dest.writeByte((byte) (departure.isLimited() ? 1 : 0));
        dest.writeByte((byte) (departure.isCanceled() ? 1 : 0));
        dest.writeByte((byte) (departure.isListedInETDs() ? 1 : 0));
        dest.writeInt(departure.getMinutes());
        dest.writeLong(departure.getMinEstimate());
        dest.writeLong(departure.getMaxEstimate());
        dest.writeLong(departure.getArrivalTimeOverride());
        dest.writeInt(departure.getEstimatedTripTime());
        dest.writeInt(departure.getLine() == null ? -1 : departure.getLine().ordinal());
        ArrayList<TripLegParcel> legParcels = new ArrayList<>();
        for (TripLeg leg : departure.getTripLegs()) {
            legParcels.add(new TripLegParcel(leg));
        }
        dest.writeTypedList(legParcels);
    }

    private static Departure readDeparture(Parcel in) {
        if (in.readInt() != FORMAT_MAGIC || in.readInt() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported departure parcel format");
        }
        Departure.Builder builder = Departure.builder()
                .setOrigin(readStation(in))
                .setTrainDestination(readStation(in))
                .setPassengerDestination(readStation(in))
                .setTrainDestinationColorHex(in.readString())
                .setTrainDestinationColorText(in.readString())
                .setPlatform(in.readString())
                .setDirection(in.readString())
                .setBikeAllowed(in.readByte() != 0)
                .setTrainLength(in.readString())
                .setRequiresTransfer(in.readByte() != 0)
                .setTransferScheduled(in.readByte() != 0)
                .setLimited(in.readByte() != 0)
                .setCanceled(in.readByte() != 0)
                .setListedInETDs(in.readByte() != 0)
                .setMinutes(in.readInt())
                .setMinEstimate(in.readLong())
                .setMaxEstimate(in.readLong())
                .setArrivalTimeOverride(in.readLong())
                .setEstimatedTripTime(in.readInt());
        int lineOrdinal = in.readInt();
        builder.setLine(lineOrdinal < 0 ? null : Line.values()[lineOrdinal]);
        ArrayList<TripLegParcel> legParcels = in.createTypedArrayList(
                TripLegParcel.CREATOR);
        List<TripLeg> legs = new ArrayList<>();
        if (legParcels != null) {
            for (TripLegParcel legParcel : legParcels) {
                legs.add(legParcel.getTripLeg());
            }
        }
        return builder.setTripLegs(legs).build();
    }

    private static void writeStation(Parcel dest, Station station) {
        dest.writeString(station == null ? null : station.abbreviation);
    }

    private static Station readStation(Parcel in) {
        return Station.getByAbbreviation(in.readString());
    }

    public static final Creator<DepartureParcel> CREATOR =
            new Creator<DepartureParcel>() {
                @Override
                public DepartureParcel createFromParcel(Parcel in) {
                    return new DepartureParcel(in);
                }

                @Override
                public DepartureParcel[] newArray(int size) {
                    return new DepartureParcel[size];
                }
            };
}
