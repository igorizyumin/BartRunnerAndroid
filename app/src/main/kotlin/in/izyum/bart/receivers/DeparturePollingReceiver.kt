package `in`.izyum.bart.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import `in`.izyum.bart.platform.DeparturePollingAlarm
import `in`.izyum.bart.platform.DeparturePollingProcessor
import `in`.izyum.bart.model.Constants
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DeparturePollingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val application = context.applicationContext as `in`.izyum.bart.BartRunnerApplication
            DeparturePollingAlarm.refresh(context, application.followedTripRepository)
            return
        }
        if (!DeparturePollingAlarm.isPollingIntent(intent)) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                DeparturePollingProcessor.process(context)
            } catch (exception: Exception) {
                Log.w(Constants.TAG, "Background departure refresh failed", exception)
                val application = context.applicationContext as `in`.izyum.bart.BartRunnerApplication
                DeparturePollingAlarm.refresh(context, application.followedTripRepository)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
