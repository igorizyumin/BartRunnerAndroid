package com.dougkeen.bart.presentation;

import android.content.Context;

import com.dougkeen.bart.R;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.TimeSource;
import com.dougkeen.bart.model.TripLeg;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/** Android-facing formatting for departure text shown by the UI. */
public final class DepartureTextFormatter {
    private DepartureTextFormatter() {
    }

    public static String transferDetails(Context context, Departure departure) {
        if (!departure.hasTransfers()) {
            return "";
        }
        DateTimeFormatter timeFormat = timeFormatter(context);
        StringBuilder details = new StringBuilder();
        for (int i = 0; i < departure.getTripLegs().size(); i++) {
            TripLeg leg = departure.getTripLegs().get(i);
            if (i > 0) {
                details.append("\n");
            }
            String lineName = leg.getLine() == null
                    ? context.getString(R.string.train)
                    : leg.getLine().getDisplayName();
            String departureTime = leg.getDepartureTime() <= 0
                    ? context.getString(R.string.trip_no_departure_scheduled)
                    : formatTime(timeFormat, leg.getDepartureTime());
            String legText = context.getString(R.string.transfer_leg,
                    lineName, departureTime);
            if (leg.getOrigin() != null && leg.getDestination() != null) {
                legText = context.getString(R.string.transfer_leg_route,
                        lineName, departureTime,
                        context.getString(R.string.route_title_arrow,
                                leg.getOrigin().shortName,
                                leg.getDestination().shortName));
            }
            details.append(legText);
            if (leg.getArrivalTime() > 0) {
                details.append(context.getString(R.string.transfer_leg_arrival,
                        formatTime(timeFormat, leg.getArrivalTime())));
            }
            if (i + 1 < departure.getTripLegs().size()
                    && leg.getArrivalTime() > 0
                    && departure.getTripLegs().get(i + 1).getDepartureTime() > 0) {
                long margin = departure.getTripLegs().get(i + 1).getDepartureTime()
                        - leg.getArrivalTime();
                long safeMargin = Math.max(0L, margin);
                long marginMinutes = safeMargin / 60000L;
                long marginSeconds = (safeMargin % 60000L) / 1000L;
                String marginText;
                if (marginMinutes > 0) {
                    marginText = marginSeconds > 0
                            ? context.getString(R.string.connection_margin_minutes_seconds,
                            marginMinutes, marginSeconds)
                            : context.getString(R.string.connection_margin_minutes,
                            marginMinutes);
                } else {
                    marginText = context.getString(R.string.connection_margin_seconds,
                            marginSeconds);
                }
                details.append(context.getString(R.string.transfer_connection_separator,
                        context.getString(R.string.transfer_connection_margin, marginText)));
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
            return context.getString(R.string.estimated_arrival_unknown);
        }
        long minutesLeft = departure.getEstimatedArrivalMinutesLeft(nowMillis);
        if (departure.isCanceled()) {
            return "";
        } else if (minutesLeft < 0) {
            return context.getString(R.string.arrived_at_destination);
        } else if (minutesLeft == 0) {
            return context.getString(R.string.arrives_around_less_than_minute,
                    estimatedArrivalTime(context, departure, false));
        } else if (minutesLeft == 1) {
            return context.getString(R.string.arrives_around_one_minute,
                    estimatedArrivalTime(context, departure, false));
        } else {
            return context.getString(R.string.arrives_around_minutes,
                    estimatedArrivalTime(context, departure, false), minutesLeft);
        }
    }

    public static String estimatedArrivalTime(Context context, Departure departure) {
        return estimatedArrivalTime(context, departure, false);
    }

    public static String estimatedArrivalTime(Context context, Departure departure,
                                              boolean compact) {
        if (departure.getEstimatedTripTime() <= 0
                && departure.getArrivalTimeOverride() <= 0) {
            return "";
        }
        return formatTime(timeFormatter(context), departure.getEstimatedArrivalTime());
    }

    public static String estimatedDepartureTime(Context context, Departure departure) {
        return estimatedDepartureTime(context, departure, false);
    }

    public static String estimatedDepartureTime(Context context, Departure departure,
                                                boolean compact) {
        if (departure.getMeanEstimate() <= 0) {
            return "";
        }
        return formatTime(timeFormatter(context), departure.getMeanEstimate());
    }

    public static String countdown(Context context, Departure departure,
                                   TimeSource timeSource) {
        return countdown(context, departure, timeSource.nowMillis());
    }

    public static String countdown(Context context, Departure departure,
                                   long nowMillis) {
        int secondsLeft = departure.getMeanSecondsLeft(
                departure.getMinEstimate(), departure.getMaxEstimate(), nowMillis);
        if (departure.isCanceled()) {
            return context.getString(R.string.departure_canceled);
        } else if (departure.hasDeparted(nowMillis)) {
            if (departure.getOrigin() != null
                    && departure.getOrigin().longStationLinger
                    && departure.beganAsDeparted()) {
                return context.getString(R.string.departure_at_station);
            }
            return context.getString(departure.isListedInETDs()
                    ? R.string.leaving : R.string.departed);
        }
        return context.getString(R.string.departure_countdown,
                secondsLeft / 60, secondsLeft % 60);
    }

    public static String uncertainty(Context context, Departure departure,
                                     TimeSource timeSource) {
        if (departure.hasDeparted(timeSource) || departure.isCanceled()) {
            return "";
        }
        return context.getString(R.string.uncertainty_seconds,
                departure.getUncertaintySeconds());
    }

    public static String trainLengthAndPlatform(Context context, Departure departure) {
        String length = departure.getTrainLength();
        String platform = departure.getPlatform();
        if (length == null || length.trim().isEmpty()) {
            return platform == null || platform.trim().isEmpty()
                    ? "" : context.getString(R.string.platform, platform);
        }
        if (platform == null || platform.trim().isEmpty()) {
            return context.getString(R.string.train_length, length);
        }
        return context.getString(R.string.train_length_platform, length, platform);
    }

    public static String formatTime(Context context, long millis) {
        return formatTime(timeFormatter(context), millis);
    }

    public static String formatBartScheduleTime(long millis) {
        return DateTimeFormatter.ofPattern("h:mma", Locale.US)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(millis));
    }

    private static String formatTime(DateTimeFormatter formatter, long millis) {
        return formatter.format(Instant.ofEpochMilli(millis));
    }

    private static DateTimeFormatter timeFormatter(Context context) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(locale)
                .withZone(ZoneId.systemDefault());
    }
}
