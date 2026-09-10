package `in`.izyum.bart.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.SystemClock
import `in`.izyum.bart.BartRunnerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Periodically retries static schedule and realtime downloads when the app is backgrounded. */
class DownloadRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            schedule(context)
            return
        }
        if (intent?.action != ACTION_RETRY_DOWNLOADS) return
        val pendingResult = goAsync()
        val application = context.applicationContext as BartRunnerApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { application.gtfsStaticData.warmUp() }
                application.transitRepository.refreshNow()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_RETRY_DOWNLOADS = "in.izyum.bart.action.RETRY_DOWNLOADS"
        const val RETRY_INTERVAL_MILLIS = 15L * 60L * 1000L

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                RETRY_REQUEST_CODE,
                Intent(context, DownloadRetryReceiver::class.java).setAction(ACTION_RETRY_DOWNLOADS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + RETRY_INTERVAL_MILLIS,
                RETRY_INTERVAL_MILLIS,
                pendingIntent,
            )
        }

        private const val RETRY_REQUEST_CODE = 126
    }
}
