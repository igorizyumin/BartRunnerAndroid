package com.dougkeen.bart.presentation;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.dougkeen.bart.R;
import com.dougkeen.bart.activities.TripInProgressActivity;
import com.dougkeen.bart.model.Constants;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.services.BoardedDepartureService;

import java.util.Locale;

/** Builds the foreground notification for a followed departure. */
public final class DepartureNotificationFactory {
    private DepartureNotificationFactory() {
    }

    public static Notification create(Context context, Departure departure) {
        final int halfMinutes = (departure.getMeanSecondsLeft() + 15) / 30;
        float minutes = halfMinutes / 2f;
        final String minutesText = (minutes < 1) ? "Less than one minute"
                : (String.format(Locale.US, "~%.1f minute", minutes)
                + ((minutes != 1.0) ? "s" : ""));
        final String directionText = departure.getOrigin().shortName + " to "
                + departure.getPassengerDestination().shortName;

        Intent cancelAlarmIntent = new Intent(context, BoardedDepartureService.class);
        cancelAlarmIntent.putExtra("cancelNotifications", true);

        String channelId = context.getString(R.string.notification_channel_id);
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.ic_stat_notification)
                        .setContentTitle(minutesText + " until departure")
                        .setContentIntent(notificationIntent(context))
                        .setDeleteIntent(deleteNotificationIntent(context));

        if (departure.getMeanSecondsLeft() > 0) {
            notificationBuilder.setWhen(System.currentTimeMillis()
                    + departure.getMeanSecondsLeft() * 1000L)
                    .setUsesChronometer(true);
        }

        notificationBuilder.setContentText(directionText);
        if (departure.isAlarmPending()) {
            PendingIntent pendingIntent = PendingIntent.getService(
                    context, 0, cancelAlarmIntent, PendingIntent.FLAG_IMMUTABLE);
            String subText = "Alarm " + departure.getAlarmLeadTimeMinutes()
                    + " minutes before departure";
            notificationBuilder
                    .addAction(R.drawable.ic_action_cancel_alarm,
                            "Cancel alarm", pendingIntent)
                    .setSubText(subText);
        }

        return notificationBuilder.build();
    }

    private static PendingIntent notificationIntent(Context context) {
        Intent targetIntent = new Intent(context, TripInProgressActivity.class);
        targetIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, targetIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent deleteNotificationIntent(Context context) {
        Intent targetIntent = new Intent(context, BoardedDepartureService.class);
        targetIntent.putExtra(Constants.CLEAR_DEPARTURE, true);
        return PendingIntent.getService(context, 0, targetIntent,
                PendingIntent.FLAG_IMMUTABLE);
    }
}
