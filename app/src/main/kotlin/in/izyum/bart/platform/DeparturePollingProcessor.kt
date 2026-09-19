package `in`.izyum.bart.platform

import android.content.Context
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.backend.FollowedItineraryAlarmProjection
import `in`.izyum.bart.receivers.AlarmBroadcastReceiver

/** Performs one background departure refresh and arranges the next alarm. */
object DeparturePollingProcessor {
    suspend fun process(context: Context) {
        val applicationContext = context.applicationContext
        val app = applicationContext as BartRunnerApplication
        val repository = app.followedTripRepository
        if (!repository.backgroundPollingNeeded.value) {
            stop(applicationContext)
            return
        }

        val current = repository.getFollowedItinerary()
        if (current == null || !repository.backgroundPollingNeeded.value) {
            stop(applicationContext)
            return
        }

        // Keep a future wakeup armed before doing network work. A slow request,
        // process kill, or receiver deadline must not strand the follow-up.
        DeparturePollingAlarm.refresh(applicationContext, repository)

        app.transitRepository.refreshTripUpdates()
        val snapshot = app.transitRepository.getLatestSnapshot()
        if (snapshot != null) {
            val updated = FollowedItineraryAlarmProjection(
                app.bartGtfsNetworkSupplier,
            ).project(snapshot, current)
            if (updated != current) {
                repository.setFollowedItinerary(updated, refreshBackgroundWork = false)
            }
        }

        val itinerary = repository.getFollowedItinerary()
        if (itinerary == null ||
            itinerary.hasInitialDeparturePassed(
                app.timeSource.nowMillis(),
                pessimistic = true,
            ) || !repository.backgroundPollingNeeded.value
        ) {
            if (itinerary != null) repository.stopTracking()
            stop(applicationContext)
        } else {
            DeparturePollingAlarm.refresh(applicationContext, repository)
        }
    }

    private fun stop(context: Context) {
        DeparturePollingAlarm.cancel(context)
        AlarmBroadcastReceiver.cancelNotification(context)
    }
}
