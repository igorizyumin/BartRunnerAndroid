package `in`.izyum.bart.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import `in`.izyum.bart.data.FollowedTripRepository
import `in`.izyum.bart.activities.RoutesListActivity
import `in`.izyum.bart.receivers.DeparturePollingReceiver

/** Schedules one-shot alarm-clock wakeups for background followed-trip refreshes. */
object DeparturePollingAlarm {
    private const val TAG = "DeparturePollingAlarm"
    private const val POLL_REQUEST_CODE = 1241
    private const val SHOW_ACTIVITY_REQUEST_CODE = 1243
    private const val ACTION_POLL = "in.izyum.bart.action.POLL_DEPARTURE"

    fun schedule(context: Context, delayMillis: Long = 0L) {
        val applicationContext = context.applicationContext
        if (!ExactAlarmPermission.isGranted(applicationContext)) {
            cancel(applicationContext)
            return
        }

        val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
            ?: return
        val triggerAtMillis = System.currentTimeMillis() + delayMillis.coerceAtLeast(0L)
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(
                    triggerAtMillis,
                    showIntent(applicationContext),
                ),
                pendingIntent(applicationContext),
            )
        } catch (exception: SecurityException) {
            cancel(applicationContext)
            Log.w(TAG, "Could not schedule exact departure refresh", exception)
        }
    }

    fun cancel(context: Context) {
        val applicationContext = context.applicationContext
        applicationContext.getSystemService(AlarmManager::class.java)
            ?.cancel(pendingIntent(applicationContext))
    }

    fun refresh(context: Context, repository: FollowedTripRepository) {
        val applicationContext = context.applicationContext
        val itinerary = repository.peekFollowedItinerary()
        if (repository.backgroundPollingNeeded.value &&
            itinerary != null &&
            ExactAlarmPermission.isGranted(applicationContext)
        ) {
            schedule(
                applicationContext,
                repository.backgroundPollingDelayMillis(itinerary),
            )
        } else {
            cancel(applicationContext)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        POLL_REQUEST_CODE,
        Intent(context, DeparturePollingReceiver::class.java).setAction(ACTION_POLL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        SHOW_ACTIVITY_REQUEST_CODE,
        Intent(context, RoutesListActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun isPollingIntent(intent: Intent): Boolean = intent.action == ACTION_POLL
}
