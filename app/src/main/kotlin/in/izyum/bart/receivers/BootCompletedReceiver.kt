package `in`.izyum.bart.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.platform.DeparturePollingAlarm
import `in`.izyum.bart.platform.ExactAlarmPermission

/** Re-arms persisted followed-trip work after Android clears alarms at reboot. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val application = context.applicationContext as BartRunnerApplication
        if (ExactAlarmPermission.isGranted(application)) {
            application.followedTripRepository.rescheduleAlarmIfPending()
        }
        DeparturePollingAlarm.refresh(application, application.followedTripRepository)
    }
}
