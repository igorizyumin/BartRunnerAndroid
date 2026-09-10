package `in`.izyum.bart.presentation

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.izyum.bart.R
import `in`.izyum.bart.activities.RouteArguments
import `in`.izyum.bart.activities.TripInProgressActivity
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.platform.DepartureAlarmScheduler
import `in`.izyum.bart.receivers.DepartureTrackingReceiver

/** Builds the foreground notification used while a departure alarm is pending. */
object DepartureNotificationFactory {
    @JvmStatic
    fun create(
        context: Context,
        departure: Departure,
        alarmScheduler: DepartureAlarmScheduler?,
        timeSource: TimeSource,
    ): Notification {
        val nowMillis = timeSource.nowMillis()
        val secondsLeft = departure.getMeanSecondsLeft(
            departure.minEstimate,
            departure.maxEstimate,
            nowMillis,
        )
        val minutes = (secondsLeft + 15) / 30 / 2f
        val minutesText = when {
            minutes < 1 -> context.getString(R.string.notification_less_than_minute)
            minutes == 1f -> context.getString(R.string.notification_minutes_until_departure, minutes)
            else -> context.getString(R.string.notification_minutes_until_departures, minutes)
        }
        val directionText = context.getString(
            R.string.notification_direction,
            departure.origin?.shortName.orEmpty(),
            (departure.passengerDestination ?: departure.trainDestination)?.shortName.orEmpty(),
        )
        val cancelAlarmIntent = Intent(context, DepartureTrackingReceiver::class.java)
            .setAction(DepartureTrackingReceiver.ACTION_CANCEL_ALARM)
        val channelId = context.getString(R.string.notification_channel_id)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(minutesText)
            .setContentIntent(notificationIntent(context, departure))
            .setDeleteIntent(deleteNotificationIntent(context))
            .setContentText(directionText)
            .setOnlyAlertOnce(true)

        if (secondsLeft > 0) {
            builder
                .setWhen(nowMillis + secondsLeft * 1000L)
                .setUsesChronometer(true)
        }
        if (alarmScheduler?.isPending == true) {
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                cancelAlarmIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder
                .addAction(
                    R.drawable.ic_action_cancel_alarm,
                    context.getString(R.string.notification_cancel_alarm),
                    pendingIntent,
                )
                .setSubText(context.resources.getQuantityString(
                    R.plurals.notification_alarm,
                    alarmScheduler.leadTimeMinutes,
                    alarmScheduler.leadTimeMinutes,
                ))
        }
        return builder.build()
    }

    private fun notificationIntent(context: Context, departure: Departure): PendingIntent {
        val targetIntent = Intent(context, TripInProgressActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        RouteArguments.putTrip(
            targetIntent,
            departure.getStationPair(),
            departure.identity,
            RouteArguments.MODE_FOLLOWED,
        )
        return PendingIntent.getActivity(
            context,
            0,
            targetIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun deleteNotificationIntent(context: Context): PendingIntent {
        val targetIntent = Intent(context, DepartureTrackingReceiver::class.java)
            .setAction(DepartureTrackingReceiver.ACTION_CLEAR_DEPARTURE)
        return PendingIntent.getBroadcast(context, 0, targetIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(
        context: Context,
        departure: Departure,
        repository: `in`.izyum.bart.data.FollowedTripRepository,
        timeSource: TimeSource,
    ) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                context.getString(R.string.notification_channel_id),
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        try {
            NotificationManagerCompat.from(context).notify(
                123, create(context, departure, repository.getAlarmScheduler(), timeSource),
            )
        } catch (_: SecurityException) {
            // Notification permission can be revoked independently of alarms.
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(123)
    }
}
