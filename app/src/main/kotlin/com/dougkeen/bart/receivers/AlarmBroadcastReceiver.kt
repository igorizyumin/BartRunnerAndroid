package com.dougkeen.bart.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R
import com.dougkeen.bart.activities.RouteArguments
import com.dougkeen.bart.activities.TripInProgressActivity
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.services.BoardedDepartureService
import com.dougkeen.util.WakeLocker

class AlarmBroadcastReceiver : BroadcastReceiver() {
    companion object {
        const val ALARM_NOTIFICATION_ID = 124
        private val alarmHandler = Handler(Looper.getMainLooper())
    }

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as BartRunnerApplication
        val boardedDeparture = application.followedTripRepository.getFollowedDeparture()
            ?: return

        WakeLocker.acquire(context)
        application.alarmController.requestRingtone()
        startAlarmAudio(context)

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
        }
        // Android may block direct background activity launches. The full-screen
        // alarm notification below is the reliable path when the app is not visible.
        try {
            context.startActivity(targetIntent)
        } catch (_: SecurityException) {
            // The actionable full-screen notification is still posted below.
        }

        postAlarmNotification(context, targetIntent, boardedDeparture)

        application.followedTripRepository.notifyAlarmHasBeenHandled()
        context.startForegroundService(
            Intent(context, BoardedDepartureService::class.java)
                .setAction(BoardedDepartureService.ACTION_REFRESH_DEPARTURE),
        )
    }

    private fun postAlarmNotification(
        context: Context,
        targetIntent: Intent,
        departure: Departure,
    ) {
        val channelId = context.getString(R.string.alarm_notification_channel_id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        context.getString(R.string.alarm_notification_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
        }

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

    /** Starts the alarm without depending on a background activity launch. */
    private fun startAlarmAudio(context: Context) {
        val application = context.applicationContext as BartRunnerApplication
        if (application.alarmController.getMediaPlayer() == null) {
            val alarmUris = listOf(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            )
            alarmUris.firstOrNull { uri ->
                uri != null && try {
                    val player = MediaPlayer.create(context.applicationContext, uri)
                        ?: return@firstOrNull false
                    player.isLooping = true
                    player.start()
                    application.alarmController.setMediaPlayer(player)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 1),
        )
        application.alarmController.setSounding(true)
        alarmHandler.removeCallbacksAndMessages(null)
        alarmHandler.postDelayed({
            application.alarmController.silence()
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
            WakeLocker.release()
        }, 20_000L)
    }
}
