package `in`.izyum.bart.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.R
import `in`.izyum.bart.activities.RouteArguments
import `in`.izyum.bart.activities.TripInProgressActivity
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Constants
import `in`.izyum.bart.platform.DeparturePollingWork

class AlarmBroadcastReceiver : BroadcastReceiver() {
    companion object {
        const val ALARM_NOTIFICATION_ID = 124
        const val EXTRA_ALARM_TRIGGERED = "alarmTriggered"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.ACTION_ALARM) {
            return
        }
        val application = context.applicationContext as BartRunnerApplication
        val boardedDeparture = application.followedTripRepository.handleAlarmTriggered()
            ?: return

        val targetIntent = Intent(context, TripInProgressActivity::class.java).apply {
            RouteArguments.putTrip(
                this,
                boardedDeparture.getStationPair(),
                boardedDeparture.identity,
                RouteArguments.MODE_FOLLOWED,
            )
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(EXTRA_ALARM_TRIGGERED, true)
        }

        postAlarmNotification(context, targetIntent, boardedDeparture)

        DeparturePollingWork.schedule(context)
    }

    private fun postAlarmNotification(
        context: Context,
        targetIntent: Intent,
        departure: Departure,
    ) {
        val channelId = context.getString(R.string.alarm_notification_channel_id)
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    channelId,
                    context.getString(R.string.alarm_notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 500)
                },
            )

        val fullScreenIntent = PendingIntent.getActivity(
            context,
            ALARM_NOTIFICATION_ID,
            targetIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val destination = departure.passengerDestination ?: departure.trainDestination
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setContentText(
                context.getString(
                    R.string.alarm_notification_text,
                    destination?.getName().orEmpty(),
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(ALARM_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked independently of alarms.
        }
    }

}
