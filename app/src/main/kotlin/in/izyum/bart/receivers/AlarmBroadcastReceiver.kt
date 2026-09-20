package `in`.izyum.bart.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.R
import `in`.izyum.bart.activities.RouteArguments
import `in`.izyum.bart.activities.TripInProgressActivity
import `in`.izyum.bart.data.AlarmPreferences
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.platform.DEPARTURE_ALARM_ACTION

class AlarmBroadcastReceiver : BroadcastReceiver() {
    companion object {
        const val ALARM_NOTIFICATION_ID = 124
        const val EXTRA_ALARM_TRIGGERED = "alarmTriggered"
        private val vibrationLock = Any()
        private var activeVibrator: Vibrator? = null

        fun cancelNotification(context: Context) {
            synchronized(vibrationLock) {
                activeVibrator?.cancel()
                activeVibrator = null
                // Keep this fallback for devices that return a shared vibrator
                // service wrapper for different Context instances.
                context.getSystemService(Vibrator::class.java)?.cancel()
            }
            try {
                NotificationManagerCompat.from(context).cancel(ALARM_NOTIFICATION_ID)
            } catch (_: SecurityException) {
                // Notification permission can be disabled independently of alarms.
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DEPARTURE_ALARM_ACTION) {
            return
        }
        val application = context.applicationContext as BartRunnerApplication
        val boardedItinerary = application.followedTripRepository.handleAlarmTriggered()
            ?: return
        val boardedDeparture = boardedItinerary.toDeparture()

        val targetIntent = Intent(context, TripInProgressActivity::class.java).apply {
            RouteArguments.putTrip(
                this,
                boardedDeparture.getStationPair(),
                boardedItinerary.selectionIdentity,
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

    }

    private fun postAlarmNotification(
        context: Context,
        targetIntent: Intent,
        departure: Departure,
    ) {
        val vibrationEnabled = AlarmPreferences.isVibrationEnabled(context)
        val channelId = context.getString(
            if (vibrationEnabled) {
                R.string.alarm_notification_channel_id
            } else {
                R.string.alarm_notification_silent_channel_id
            },
        )
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
                    // Vibration is triggered directly below with alarm usage so
                    // it remains perceptible when the phone is set to silent.
                    enableVibration(false)
                },
            )

        val fullScreenIntent = PendingIntent.getActivity(
            context,
            ALARM_NOTIFICATION_ID,
            targetIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val station = departure.origin
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setContentText(
                context.getString(
                    R.string.alarm_notification_text,
                    station?.getName().orEmpty(),
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
        if (vibrationEnabled) vibrateForAlarm(context)
    }

    private fun vibrateForAlarm(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java)
        if (vibrator == null || !vibrator.hasVibrator()) return
        val pattern = longArrayOf(0L, 500L, 500L)
        synchronized(vibrationLock) {
            activeVibrator?.cancel()
            activeVibrator = vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(pattern, 0),
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build(),
                )
            } else {
                vibrateWithAudioAttributes(
                    vibrator,
                    VibrationEffect.createWaveform(pattern, 0),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build(),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateWithAudioAttributes(
        vibrator: Vibrator,
        effect: VibrationEffect,
        audioAttributes: AudioAttributes,
    ) {
        vibrator.vibrate(effect, audioAttributes)
    }

}
