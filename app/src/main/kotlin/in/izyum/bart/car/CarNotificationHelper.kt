package `in`.izyum.bart.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.izyum.bart.R

/**
 * Helper utility for posting Android Auto-compliant Heads-Up Notifications (HUN)
 * with CarAppExtender to notify drivers of upcoming train departure milestones.
 */
object CarNotificationHelper {
    const val CHANNEL_ID = "bart_transit_milestones"
    private const val CHANNEL_NAME = "Commute & Transit Milestones"
    private const val CHANNEL_DESC = "Heads-Up Notifications for upcoming train departures"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
                enableVibration(true)
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun postDepartureMilestoneNotification(
        context: Context,
        routeTitle: String,
        contentText: String,
        notificationId: Int = 1001,
    ) {
        createNotificationChannel(context)

        val carExtender = CarAppExtender.Builder()
            .setImportance(NotificationManager.IMPORTANCE_HIGH)
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(routeTitle)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .extend(carExtender)

        val notificationManager = NotificationManagerCompat.from(context)
        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Permission for POST_NOTIFICATIONS may not be granted
        }
    }
}
