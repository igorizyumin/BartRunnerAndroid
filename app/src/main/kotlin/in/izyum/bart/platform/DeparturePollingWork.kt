package `in`.izyum.bart.platform

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import `in`.izyum.bart.data.BackgroundPollingPreferences
import `in`.izyum.bart.data.FollowedTripRepository
import `in`.izyum.bart.presentation.DepartureNotificationFactory

object DeparturePollingWork {
    private const val WORK_NAME = "followed_departure_polling"

    fun schedule(context: Context, delayMillis: Long = 0L) {
        if (!BackgroundPollingPreferences.isEnabled(context)) return
        val request = OneTimeWorkRequestBuilder<DeparturePollingWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun refresh(context: Context, repository: FollowedTripRepository) {
        if (BackgroundPollingPreferences.isEnabled(context) &&
            repository.backgroundPollingNeeded.value
        ) {
            schedule(context)
        } else {
            cancel(context)
            DepartureNotificationFactory.cancel(context)
        }
    }
}
