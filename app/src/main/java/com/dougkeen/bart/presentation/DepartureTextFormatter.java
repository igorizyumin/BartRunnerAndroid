package com.dougkeen.bart.presentation;

import android.content.Context;

import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.TimeSource;
import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.R;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Android-facing formatting for departure text shown by the UI. */
public final class DepartureTextFormatter {
    private static final DateFormat COMPACT_TIME_FORMAT =
            new SimpleDateFormat("h:mm", Locale.getDefault());

    private DepartureTextFormatter() {
    }

    public static String transferDetails(Context context, Departure departure) {
        if (!departure.hasTransfers()) {
            return "";
        }
        DateFormat format = android.text.format.DateFormat.getTimeFormat(context);
        StringBuilder details = new StringBuilder();
        for (int i = 0; i < departure.getTripLegs().size(); i++) {
            TripLeg leg = departure.getTripLegs().get(i);
            if (i > 0) {
                details.append("\n");
            }
            details.append(leg.getLine() == null ? "Train"
                    : leg.getLine().getDisplayName());
            details.append(" ");
            if (leg.getDepartureTime() <= 0) {
                details.append(context.getString(R.string.trip_no_departure_scheduled));
            } else {
                details.append(format.format(new Date(leg.getDepartureTime())));
            }
            if (leg.getOrigin() != null && leg.getDestination() != null) {
                details.append(" ").append(leg.getOrigin().shortName)
                        .append(" → ").append(leg.getDestination().shortName);
            }
            if (leg.getArrivalTime() > 0) {
                details.append(" (arr ")
                        .append(format.format(new Date(leg.getArrivalTime())))
                        .append(")");
            }
            if (i + 1 < departure.getTripLegs().size()
                    && leg.getArrivalTime() > 0
                    && departure.getTripLegs().get(i + 1).getDepartureTime() > 0) {
                long margin = departure.getTripLegs().get(i + 1).getDepartureTime()
                        - leg.getArrivalTime();
                long safeMargin = Math.max(0L, margin);
                long marginMinutes = safeMargin / 60000L;
                long marginSeconds = (safeMargin % 60000L) / 1000L;
                details.append(" • ");
                if (marginMinutes > 0) {
                    details.append(marginMinutes).append(" min");
                    if (marginSeconds > 0) {
                        details.append(" ").append(marginSeconds).append(" sec");
                    }
                } else {
                    details.append(marginSeconds).append(" sec");
                }
                details.append(" connection");
            }
        }
        return details.toString();
    }

    public static String estimatedArrivalMinutesLeft(Context context,
                                                      Departure departure,
                                                      TimeSource timeSource) {
        return estimatedArrivalMinutesLeft(context, departure,
                timeSource.nowMillis());
    }

    public static String estimatedArrivalMinutesLeft(Context context,
                                                      Departure departure,
                                                      long nowMillis) {
        if (!departure.hasAnyArrivalEstimate()) {
            return "Estimated arrival unknown";
        }
        long minutesLeft = departure.getEstimatedArrivalMinutesLeft(nowMillis);
        if (departure.isCanceled()) {
            return "";
        } else if (minutesLeft < 0) {
            return "Arrived at destination";
        } else if (minutesLeft == 0) {
            return "Arrives ~" + estimatedArrivalTime(context, departure, false)
                    + " (<1 min)";
        } else if (minutesLeft == 1) {
            return "Arrives ~" + estimatedArrivalTime(context, departure, false)
                    + " (1 min)";
        } else {
            return "Arrives ~" + estimatedArrivalTime(context, departure, false)
                    + " (" + minutesLeft + " mins)";
        }
    }

    public static String estimatedArrivalTime(Context context, Departure departure) {
        return estimatedArrivalTime(context, departure, false);
    }

    public static String estimatedArrivalTime(Context context, Departure departure,
                                              boolean compact) {
        if (departure.getEstimatedTripTime() > 0
                || departure.getArrivalTimeOverride() > 0) {
            Date arrivalTime = new Date(departure.getEstimatedArrivalTime());
            if (compact) {
                return COMPACT_TIME_FORMAT.format(arrivalTime);
            }
            return android.text.format.DateFormat.getTimeFormat(context)
                    .format(arrivalTime);
        }
        return "";
    }

    public static String estimatedDepartureTime(Context context, Departure departure) {
        return estimatedDepartureTime(context, departure, false);
    }

    public static String estimatedDepartureTime(Context context, Departure departure,
                                                boolean compact) {
        if (departure.getMeanEstimate() <= 0) {
            return "";
        }
        Date departureTime = new Date(departure.getMeanEstimate());
        if (compact) {
            return COMPACT_TIME_FORMAT.format(departureTime);
        }
        return android.text.format.DateFormat.getTimeFormat(context)
                .format(departureTime);
    }

    public static String countdown(Context context, Departure departure,
                                   TimeSource timeSource) {
        return countdown(context, departure, timeSource.nowMillis());
    }

    public static String countdown(Context context, Departure departure,
                                   long nowMillis) {
        StringBuilder builder = new StringBuilder();
        int secondsLeft = departure.getMeanSecondsLeft(
                departure.getMinEstimate(), departure.getMaxEstimate(), nowMillis);
        if (departure.isCanceled()) {
            return "Canceled";
        } else if (departure.hasDeparted(nowMillis)) {
            if (departure.getOrigin() != null
                    && departure.getOrigin().longStationLinger
                    && departure.beganAsDeparted()) {
                builder.append("At station");
            } else if (departure.isListedInETDs()) {
                builder.append(context.getString(R.string.leaving));
            } else {
                builder.append(context.getString(R.string.departed));
            }
        } else {
            builder.append(secondsLeft / 60);
            builder.append("m, ");
            builder.append(secondsLeft % 60);
            builder.append("s");
        }
        return builder.toString();
    }
}
