package com.dougkeen.bart.model;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.ColorInt;
import android.util.Log;

import com.dougkeen.bart.receivers.AlarmBroadcastReceiver;
import com.dougkeen.util.Observable;


import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;

public class Departure implements Parcelable, Comparable<Departure> {
    private static final int MINIMUM_MERGE_OVERLAP_MILLIS = 5000;
    private static final int EXPIRE_MINUTES_AFTER_ARRIVAL = 1;

    public Departure() {
        super();
    }

    public Departure(String destinationAbbr, String destinationColorHex,
                     String platform, String direction, boolean bikeAllowed,
                     String trainLength, int minutes) {
        super();
        this.trainDestination = Station.getByAbbreviation(destinationAbbr);
        this.destinationColorHex = destinationColorHex;
        this.platform = platform;
        this.direction = direction;
        this.bikeAllowed = bikeAllowed;
        this.trainLength = trainLength;
        this.minutes = minutes;
    }

    public Departure(Parcel in) {
        readFromParcel(in);
    }

    private Station origin;
    private Station trainDestination;
    private Station passengerDestination;
    private Line line;
    private String destinationColorHex;
    private String destinationColorText;

    @ColorInt
    private int destinationColorInt;

    private String platform;
    private String direction;
    private boolean bikeAllowed;
    private String trainLength;
    private boolean requiresTransfer;
    private boolean transferScheduled;
    private boolean limited;
    private boolean canceled;

    private int minutes;

    private long minEstimate;
    private long maxEstimate;

    private int estimatedTripTime;

    private boolean beganAsDeparted;

    private long arrivalTimeOverride;

    private Observable<Integer> alarmLeadTimeMinutes = new Observable<Integer>(
            0);
    private Observable<Boolean> alarmPending = new Observable<Boolean>(false);

    private boolean listedInETDs = true;

    private boolean selected;

    private List<TripLeg> tripLegs = new ArrayList<TripLeg>();

    public Station getOrigin() {
        return origin;
    }

    public void setOrigin(Station origin) {
        this.origin = origin;
    }

    public Station getTrainDestination() {
        return trainDestination;
    }

    public void setTrainDestination(Station destination) {
        this.trainDestination = destination;
    }

    public String getTrainDestinationName() {
        if (trainDestination != null)
            return trainDestination.name;
        return null;
    }

    public String getTrainDestinationAbbreviation() {
        if (trainDestination != null)
            return trainDestination.abbreviation;
        return null;
    }

    public Station getPassengerDestination() {
        return passengerDestination;
    }

    public void setPassengerDestination(Station passengerDestination) {
        this.passengerDestination = passengerDestination;
    }

    public List<TripLeg> getTripLegs() {
        return Collections.unmodifiableList(tripLegs);
    }

    public void setTripLegs(List<TripLeg> tripLegs) {
        this.tripLegs = new ArrayList<TripLeg>();
        if (tripLegs != null) {
            this.tripLegs.addAll(tripLegs);
        }
        if (!this.tripLegs.isEmpty()) {
            TripLeg finalLeg = this.tripLegs.get(this.tripLegs.size() - 1);
            if (finalLeg.hasArrivalTime() && getMeanEstimate() > 0) {
                setEstimatedTripTime((int) (finalLeg.getArrivalTime()
                        - getMeanEstimate()));
            }
        }
    }

    public boolean hasTransfers() {
        return tripLegs.size() > 1;
    }

    public StationPair getStationPair() {
        if (passengerDestination != null) {
            return new StationPair(origin, passengerDestination);
        } else {
            return null;
        }
    }

    public Line getLine() {
        return line;
    }

    public void setLine(Line line) {
        this.line = line;
    }

    @ColorInt
    public int getTrainDestinationColor() {
        if (destinationColorInt == 0) {
            try {
                destinationColorInt = Color.parseColor(destinationColorHex);
            } catch (IllegalArgumentException e) {
                destinationColorInt = Color.WHITE;
            }
        }
        return destinationColorInt;
    }

    public void setTrainDestinationColorHex(String destinationColor) {
        this.destinationColorHex = destinationColor;
    }

    public String getTrainDestinationColorText() {
        return destinationColorText;
    }

    public void setTrainDestinationColorText(String destinationColorText) {
        this.destinationColorText = destinationColorText;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public boolean isBikeAllowed() {
        return bikeAllowed;
    }

    public void setBikeAllowed(boolean bikeAllowed) {
        this.bikeAllowed = bikeAllowed;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }

    public String getTrainLength() {
        return trainLength;
    }

    public void setTrainLength(String trainLength) {
        this.trainLength = trainLength;
    }

    public String getTrainLengthText() {
        if (isBlank(trainLength)) {
            return "";
        }
        return trainLength + " cars";
    }

    public String getTrainLengthAndPlatform() {
        StringBuilder result = new StringBuilder();
        if (!isBlank(trainLength)) {
            result.append(trainLength).append(" cars");
        }
        if (!isBlank(platform)) {
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append("platform ").append(platform);
        }
        return result.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public boolean getRequiresTransfer() {
        return requiresTransfer;
    }

    public void setRequiresTransfer(boolean requiresTransfer) {
        this.requiresTransfer = requiresTransfer;
    }

    public boolean isTransferScheduled() {
        return transferScheduled;
    }

    public void setTransferScheduled(boolean transferScheduled) {
        this.transferScheduled = transferScheduled;
    }

    public boolean isLimited() {
        return limited;
    }

    public void setLimited(boolean limited) {
        this.limited = limited;
    }

    public int getMinutes() {
        return minutes;
    }

    public void setMinutes(int minutes) {
        this.minutes = minutes;
        if (minutes == 0) {
            beganAsDeparted = true;
        }
    }

    public long getMinEstimate() {
        return minEstimate;
    }

    public void setMinEstimate(long minEstimate) {
        this.minEstimate = minEstimate;
    }

    public long getMaxEstimate() {
        return maxEstimate;
    }

    public void setMaxEstimate(long maxEstimate) {
        this.maxEstimate = maxEstimate;
    }

    public int getEstimatedTripTime() {
        return estimatedTripTime;
    }

    public void setEstimatedTripTime(int estimatedTripTime) {
        this.estimatedTripTime = estimatedTripTime;
    }

    public boolean hasEstimatedTripTime() {
        return this.estimatedTripTime > 0;
    }

    public boolean hasAnyArrivalEstimate() {
        return this.estimatedTripTime > 0 || this.arrivalTimeOverride > 0;
    }

    public int getUncertaintySeconds() {
        return (int) (maxEstimate - minEstimate + 1000) / 2000;
    }

    public int getMinSecondsLeft() {
        return (int) ((getMinEstimate() - System.currentTimeMillis()) / 1000);
    }

    public int getMaxSecondsLeft() {
        return (int) ((getMaxEstimate() - System.currentTimeMillis()) / 1000);
    }

    public int getMeanSecondsLeft() {
        return getMeanSecondsLeft(getMinEstimate(), getMaxEstimate());
    }

    public int getMeanSecondsLeft(long min, long max) {
        return (int) ((getMeanEstimate(min, max) - System.currentTimeMillis()) / 1000);
    }

    public long getMeanEstimate() {
        return getMeanEstimate(getMinEstimate(), getMaxEstimate());
    }

    public long getMeanEstimate(long min, long max) {
        return (min + max) / 2;
    }

    public long getArrivalTimeOverride() {
        return arrivalTimeOverride;
    }

    public void setArrivalTimeOverride(long arrivalTimeOverride) {
        this.arrivalTimeOverride = arrivalTimeOverride;
    }

    public long getEstimatedArrivalTime() {
        if (!tripLegs.isEmpty()) {
            TripLeg finalLeg = tripLegs.get(tripLegs.size() - 1);
            if (finalLeg.hasArrivalTime()) {
                return finalLeg.getArrivalTime();
            }
        }
        if (arrivalTimeOverride > 0) {
            return arrivalTimeOverride;
        }
        return getMeanEstimate() + getEstimatedTripTime();
    }

    public long getEstimatedArrivalMinutesLeft() {
        long millisLeft = getEstimatedArrivalTime()
                - System.currentTimeMillis();
        if (millisLeft < 0) {
            return -1;
        } else {
            // Add ~30s to emulate rounding
            return (millisLeft + 29999) / (60 * 1000);
        }
    }

    public boolean hasDeparted() {
        return getMeanSecondsLeft() <= 0;
    }

    public boolean beganAsDeparted() {
        return beganAsDeparted;
    }

    public void calculateEstimates(long originalEstimateTime) {
        setMinEstimate(originalEstimateTime + (getMinutes() * 60 * 1000)
                - (30000));
        setMaxEstimate(getMinEstimate() + 60000);
    }

    public void mergeEstimate(Departure departure) {
        mergeEstimate(departure, true);
    }

    /**
     * Merges the origin departure estimate. A live trip screen can opt out of
     * replacing its exact per-train leg data with a less-specific origin ETD
     * snapshot; that data is refreshed separately by trip ID.
     */
    public void mergeEstimate(Departure departure, boolean updateTripLegs) {
        // Stop arrivals and connection times remain useful after the train has
        // left a long-linger station, even when the departure countdown itself
        // is intentionally kept stable.
        if (updateTripLegs && !departure.tripLegs.isEmpty()) {
            setTripLegs(departure.tripLegs);
        }
        if (departure.hasDeparted() && origin.longStationLinger
                && getMinEstimate() > 0 && !beganAsDeparted) {
            /*
             * This is probably not a true departure, but an indication that the
             * train is in the station. Don't update the estimates.
             */
            return;
        }

        boolean wasDeparted = hasDeparted();
        if (!hasAnyArrivalEstimate() && departure.hasAnyArrivalEstimate()) {
            setArrivalTimeOverride(departure.getArrivalTimeOverride());
            setEstimatedTripTime(departure.getEstimatedTripTime());
        }

        long newMin = Math.max(getMinEstimate(), departure.getMinEstimate());
        long newMax = Math.min(getMaxEstimate(), departure.getMaxEstimate());

        if ((getMaxEstimate() - departure.getMinEstimate()) < MINIMUM_MERGE_OVERLAP_MILLIS
                || departure.getMaxEstimate() - getMinEstimate() < MINIMUM_MERGE_OVERLAP_MILLIS) {
            /*
             * The estimate must have changed... just use the latest incoming
             * values
             */
            newMin = departure.getMinEstimate();
            newMax = departure.getMaxEstimate();
        }

        /*
         * If the new departure would mark this as departed, and we have < 60
         * seconds left on a fairly accurate local estimate, ignore the incoming
         * departure
         */
        if (!wasDeparted && getMeanSecondsLeft(newMin, newMax) <= 0
                && getMeanSecondsLeft() < 60 && getUncertaintySeconds() < 30) {
            Log.d(Constants.TAG,
                    "Skipping estimate merge, since it would make this departure show as 'departed' prematurely");
            return;
        }

        if (newMax > newMin) {
            // We must never have 0 or negative uncertainty
            setMinEstimate(newMin);
            setMaxEstimate(newMax);
        }
    }

    public boolean hasExpired() {
        final long now = System.currentTimeMillis();
        return getMaxEstimate() < now
                && getEstimatedArrivalTime() + EXPIRE_MINUTES_AFTER_ARRIVAL
                * 60000 < now;
    }

    public int compareTo(Departure another) {
        return (this.getMeanSecondsLeft() > another.getMeanSecondsLeft()) ? 1
                : ((this.getMeanSecondsLeft() == another.getMeanSecondsLeft()) ? 0
                : -1);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (bikeAllowed ? 1231 : 1237);
        result = prime
                * result
                + ((trainDestination == null) ? 0 : trainDestination.hashCode());
        result = prime
                * result
                + ((destinationColorHex == null) ? 0 : destinationColorHex.hashCode());
        result = prime * result
                + ((direction == null) ? 0 : direction.hashCode());
        result = prime * result + ((line == null) ? 0 : line.hashCode());
        result = prime * result + (int) (maxEstimate ^ (maxEstimate >>> 32));
        result = prime * result + (int) (minEstimate ^ (minEstimate >>> 32));
        result = prime * result + minutes;
        result = prime * result
                + ((platform == null) ? 0 : platform.hashCode());
        result = prime * result + (requiresTransfer ? 1231 : 1237);
        result = prime * result
                + ((trainLength == null) ? 0 : trainLength.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Departure other = (Departure) obj;
        if (bikeAllowed != other.bikeAllowed)
            return false;
        if (trainDestination != other.trainDestination)
            return false;
        if (destinationColorHex == null) {
            if (other.destinationColorHex != null)
                return false;
        } else if (!destinationColorHex.equals(other.destinationColorHex))
            return false;
        if (direction == null) {
            if (other.direction != null)
                return false;
        } else if (!direction.equals(other.direction))
            return false;
        if (line != other.line)
            return false;
        if (Math.abs(maxEstimate - other.maxEstimate) > getEqualsTolerance())
            return false;
        if (platform == null) {
            if (other.platform != null)
                return false;
        } else if (!platform.equals(other.platform))
            return false;
        if (requiresTransfer != other.requiresTransfer)
            return false;
        if (trainLength == null) {
            if (other.trainLength != null)
                return false;
        } else if (!trainLength.equals(other.trainLength))
            return false;
        return true;
    }

    private int getEqualsTolerance() {
        if (origin != null) {
            return origin.departureEqualityTolerance;
        } else {
            return Station.DEFAULT_DEPARTURE_EQUALITY_TOLERANCE;
        }
    }

    public String getUncertaintyText() {
        if (hasDeparted() || isCanceled()) {
            return "";
        } else {
            return "(±" + getUncertaintySeconds() + "s)";
        }
    }

    public boolean isListedInETDs() {
        return listedInETDs;
    }

    public void setListedInETDs(boolean listedInETDs) {
        this.listedInETDs = listedInETDs;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public int getAlarmLeadTimeMinutes() {
        return alarmLeadTimeMinutes.getValue();
    }

    public Observable<Integer> getAlarmLeadTimeMinutesObservable() {
        return alarmLeadTimeMinutes;
    }

    public boolean isAlarmPending() {
        return alarmPending.getValue();
    }

    public Observable<Boolean> getAlarmPendingObservable() {
        return alarmPending;
    }

    private PendingIntent getAlarmIntent(Context context) {
        Intent intent = new Intent(context, AlarmBroadcastReceiver.class);
        intent.setAction(Constants.ACTION_ALARM);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private long getAlarmClockTime() {
        return getMeanEstimate() - alarmLeadTimeMinutes.getValue() * 60 * 1000;
    }

    public int getSecondsUntilAlarm() {
        return getMeanSecondsLeft() - getAlarmLeadTimeMinutes() * 60;
    }

    public void setUpAlarm(int leadTimeMinutes, Context context, AlarmManager alarmManager) {
        this.alarmLeadTimeMinutes.setValue(leadTimeMinutes);
        this.alarmPending.setValue(true);
        scheduleAlarm(alarmManager, getAlarmIntent(context));
    }

    public void updateAlarm(Context context, AlarmManager alarmManager) {
        if (alarmManager == null) {
            Log.w(Constants.TAG, "No alarm manager available, so alarm will not be updated");
            return;
        }

        if (isAlarmPending() && getAlarmLeadTimeMinutes() > 0) {
            scheduleAlarm(alarmManager, getAlarmIntent(context));
        }
    }

    private void scheduleAlarm(AlarmManager alarmManager, PendingIntent alarmIntent) {
        if (alarmManager == null) {
            Log.w(Constants.TAG, "No alarm manager available, so alarm will not be scheduled");
            return;
        }
        long alarmTime = getAlarmClockTime();

        if (alarmTime < System.currentTimeMillis()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, alarmTime, alarmIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, alarmIntent);
                Log.w(Constants.TAG, "Exact alarm permission is unavailable; using an inexact alarm");
                return;
            }
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, alarmIntent);
            } catch (SecurityException exception) {
                // Exact alarms may be disabled by the user on Android 12+.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, alarmIntent);
                Log.w(Constants.TAG, "Exact alarm permission is unavailable; using an inexact alarm");
            }
        } else {
            try {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, alarmTime, alarmIntent);
            } catch (SecurityException exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, alarmTime, alarmIntent);
                Log.w(Constants.TAG, "Exact alarm permission is unavailable; using a regular alarm");
            }
        }

        Log.v(Constants.TAG, "Scheduling alarm for "
                + android.text.format.DateFormat.format("h:mm:ss", alarmTime));
    }

    public void cancelAlarm(Context context, AlarmManager alarmManager) {
        if (alarmManager != null) {
            alarmManager.cancel(getAlarmIntent(context));
        }
        this.alarmPending.setValue(false);
        Log.d(Constants.TAG, "Alarm cancelled");
    }

    @Override
    public String toString() {
        java.text.DateFormat format = SimpleDateFormat.getTimeInstance();
        StringBuilder builder = new StringBuilder();
        builder.append(trainDestination);
        if (requiresTransfer) {
            builder.append(" (w/ xfer)");
        }
        builder.append(", ");
        builder.append(getDebugCountdownText());
        builder.append(", ");
        builder.append(format.format(new Date(getMeanEstimate())));
        return builder.toString();
    }

    private String getDebugCountdownText() {
        int secondsLeft = getMeanSecondsLeft();
        if (isCanceled()) {
            return "Canceled";
        } else if (hasDeparted()) {
            if (origin != null && origin.longStationLinger && beganAsDeparted) {
                return "At station";
            }
            return isListedInETDs() ? "Leaving" : "Departed";
        }
        return (secondsLeft / 60) + "m, " + (secondsLeft % 60) + "s";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(origin.abbreviation);
        dest.writeString(trainDestination.abbreviation);
        dest.writeString(passengerDestination == null ? null
                : passengerDestination.abbreviation);
        dest.writeString(destinationColorHex);
        dest.writeString(platform);
        dest.writeString(direction);
        dest.writeByte((byte) (bikeAllowed ? 1 : 0));
        dest.writeString(trainLength);
        dest.writeByte((byte) (requiresTransfer ? 1 : 0));
        dest.writeInt(minutes);
        dest.writeLong(minEstimate);
        dest.writeLong(maxEstimate);
        dest.writeLong(arrivalTimeOverride);
        dest.writeInt(estimatedTripTime);
        dest.writeInt(line.ordinal());
        dest.writeByte(beganAsDeparted ? (byte) 1 : (byte) 0);
        dest.writeByte(bikeAllowed ? (byte) 1 : (byte) 0);
        dest.writeByte(requiresTransfer ? (byte) 1 : (byte) 0);
        dest.writeByte(transferScheduled ? (byte) 1 : (byte) 0);
        dest.writeByte(limited ? (byte) 1 : (byte) 0);
        dest.writeTypedList(tripLegs);
    }

    private void readFromParcel(Parcel in) {
        origin = Station.getByAbbreviation(in.readString());
        trainDestination = Station.getByAbbreviation(in.readString());
        passengerDestination = Station.getByAbbreviation(in.readString());
        destinationColorHex = in.readString();
        platform = in.readString();
        direction = in.readString();
        bikeAllowed = in.readByte() != 0;
        trainLength = in.readString();
        requiresTransfer = in.readByte() != 0;
        minutes = in.readInt();
        minEstimate = in.readLong();
        maxEstimate = in.readLong();
        arrivalTimeOverride = in.readLong();
        estimatedTripTime = in.readInt();
        line = Line.values()[in.readInt()];
        beganAsDeparted = in.readByte() == (byte) 1;
        bikeAllowed = in.readByte() == (byte) 1;
        requiresTransfer = in.readByte() == (byte) 1;
        transferScheduled = in.readByte() == (byte) 1;
        limited = in.readByte() == (byte) 1;
        tripLegs = in.createTypedArrayList(TripLeg.CREATOR);
        if (tripLegs == null) {
            tripLegs = new ArrayList<TripLeg>();
        }
    }

    public static final Parcelable.Creator<Departure> CREATOR = new Parcelable.Creator<Departure>() {
        public Departure createFromParcel(Parcel in) {
            return new Departure(in);
        }

        public Departure[] newArray(int size) {
            return new Departure[size];
        }
    };

    public void notifyAlarmHasBeenHandled() {
        this.alarmPending.setValue(false);
    }
}
