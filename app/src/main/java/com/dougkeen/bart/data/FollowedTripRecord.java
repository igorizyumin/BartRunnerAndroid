package com.dougkeen.bart.data;

import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.model.TripStop;

import java.util.ArrayList;
import java.util.List;

/** Versioned JSON schema for the durable followed-trip state. */
public final class FollowedTripRecord {
    public static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;
    public String origin;
    public String trainDestination;
    public String passengerDestination;
    public String line;
    public String trainDestinationColorHex;
    public String trainDestinationColorText;
    public String platform;
    public String direction;
    public boolean bikeAllowed;
    public String trainLength;
    public boolean requiresTransfer;
    public boolean transferScheduled;
    public boolean limited;
    public boolean canceled;
    public boolean listedInETDs = true;
    public int minutes;
    public long minEstimate;
    public long maxEstimate;
    public long arrivalTimeOverride;
    public int estimatedTripTime;
    public List<TripLegRecord> tripLegs = new ArrayList<>();

    public FollowedTripRecord() {
    }

    public static FollowedTripRecord fromDeparture(Departure departure) {
        FollowedTripRecord record = new FollowedTripRecord();
        record.origin = abbreviation(departure.getOrigin());
        record.trainDestination = abbreviation(departure.getTrainDestination());
        record.passengerDestination = abbreviation(departure.getPassengerDestination());
        record.line = departure.getLine() == null
                ? null : departure.getLine().name();
        record.trainDestinationColorHex = departure.getTrainDestinationColorHex();
        record.trainDestinationColorText = departure.getTrainDestinationColorText();
        record.platform = departure.getPlatform();
        record.direction = departure.getDirection();
        record.bikeAllowed = departure.isBikeAllowed();
        record.trainLength = departure.getTrainLength();
        record.requiresTransfer = departure.getRequiresTransfer();
        record.transferScheduled = departure.isTransferScheduled();
        record.limited = departure.isLimited();
        record.canceled = departure.isCanceled();
        record.listedInETDs = departure.isListedInETDs();
        record.minutes = departure.getMinutes();
        record.minEstimate = departure.getMinEstimate();
        record.maxEstimate = departure.getMaxEstimate();
        record.arrivalTimeOverride = departure.getArrivalTimeOverride();
        record.estimatedTripTime = departure.getEstimatedTripTime();
        for (TripLeg leg : departure.getTripLegs()) {
            record.tripLegs.add(TripLegRecord.fromTripLeg(leg));
        }
        return record;
    }

    public Departure toDeparture() {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported followed trip format version: " + version);
        }
        Departure.Builder builder = Departure.builder()
                .setOrigin(station(origin))
                .setTrainDestination(station(trainDestination))
                .setPassengerDestination(station(passengerDestination))
                .setLine(line == null ? null : Line.valueOf(line))
                .setTrainDestinationColorHex(trainDestinationColorHex)
                .setTrainDestinationColorText(trainDestinationColorText)
                .setPlatform(platform)
                .setDirection(direction)
                .setBikeAllowed(bikeAllowed)
                .setTrainLength(trainLength)
                .setRequiresTransfer(requiresTransfer)
                .setTransferScheduled(transferScheduled)
                .setLimited(limited)
                .setCanceled(canceled)
                .setListedInETDs(listedInETDs)
                .setMinutes(minutes)
                .setMinEstimate(minEstimate)
                .setMaxEstimate(maxEstimate)
                .setArrivalTimeOverride(arrivalTimeOverride)
                .setEstimatedTripTime(estimatedTripTime);
        List<TripLeg> legs = new ArrayList<>();
        if (tripLegs != null) {
            for (TripLegRecord leg : tripLegs) {
                legs.add(leg.toTripLeg());
            }
        }
        return builder.setTripLegs(legs).build();
    }

    private static String abbreviation(Station station) {
        return station == null ? null : station.abbreviation;
    }

    private static Station station(String abbreviation) {
        return abbreviation == null ? null
                : Station.getByAbbreviation(abbreviation);
    }

    public static final class TripLegRecord {
        public String line;
        public String origin;
        public String destination;
        public String trainDestination;
        public String tripId;
        public long departureTime;
        public long arrivalTime;
        public int minimumTransferSecondsAfter;
        public List<TripStopRecord> stops = new ArrayList<>();

        public TripLegRecord() {
        }

        static TripLegRecord fromTripLeg(TripLeg leg) {
            TripLegRecord record = new TripLegRecord();
            record.line = leg.getLine() == null ? null : leg.getLine().name();
            record.origin = abbreviation(leg.getOrigin());
            record.destination = abbreviation(leg.getDestination());
            record.trainDestination = abbreviation(leg.getTrainDestination());
            record.tripId = leg.getTripId();
            record.departureTime = leg.getDepartureTime();
            record.arrivalTime = leg.getArrivalTime();
            record.minimumTransferSecondsAfter = leg.getMinimumTransferSecondsAfter();
            for (TripStop stop : leg.getStops()) {
                record.stops.add(TripStopRecord.fromTripStop(stop));
            }
            return record;
        }

        TripLeg toTripLeg() {
            List<TripStop> tripStops = new ArrayList<>();
            if (stops != null) {
                for (TripStopRecord stop : stops) {
                    tripStops.add(stop.toTripStop());
                }
            }
            return new TripLeg(line == null ? null : Line.valueOf(line),
                    station(origin), station(destination),
                    station(trainDestination), tripId, departureTime,
                    arrivalTime, tripStops, minimumTransferSecondsAfter);
        }
    }

    public static final class TripStopRecord {
        public String station;
        public long arrivalTime;
        public long departureTime;

        public TripStopRecord() {
        }

        static TripStopRecord fromTripStop(TripStop stop) {
            TripStopRecord record = new TripStopRecord();
            record.station = abbreviation(stop.getStation());
            record.arrivalTime = stop.getArrivalTime();
            record.departureTime = stop.getDepartureTime();
            return record;
        }

        TripStop toTripStop() {
            return new TripStop(station(station), arrivalTime, departureTime);
        }
    }
}
