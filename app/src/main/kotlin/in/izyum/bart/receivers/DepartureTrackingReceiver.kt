package `in`.izyum.bart.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.platform.DeparturePollingAlarm

class DepartureTrackingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BartRunnerApplication
        when (intent.action) {
            ACTION_CANCEL_ALARM -> app.followedTripRepository.cancelAlarm()
            ACTION_CLEAR_DEPARTURE -> app.followedTripRepository.clearFollowedDeparture()
        }
        DeparturePollingAlarm.refresh(context, app.followedTripRepository)
        if (intent.action == ACTION_CLEAR_DEPARTURE) {
            AlarmBroadcastReceiver.cancelNotification(context)
        }
    }

    companion object {
        const val ACTION_CANCEL_ALARM = "in.izyum.bart.action.CANCEL_ALARM"
        const val ACTION_CLEAR_DEPARTURE = "in.izyum.bart.action.CLEAR_DEPARTURE"
    }
}
