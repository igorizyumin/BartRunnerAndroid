package `in`.izyum.bart.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.data.BackgroundPollingPreferences
import `in`.izyum.bart.data.FollowedTripRepository
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.backend.RouteDepartureProjection
import `in`.izyum.bart.presentation.DepartureNotificationFactory
import `in`.izyum.bart.receivers.AlarmBroadcastReceiver

/** Refreshes a followed departure once, then schedules the next refresh. */
class DeparturePollingWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BartRunnerApplication
        val repository = app.followedTripRepository
        if (!BackgroundPollingPreferences.isEnabled(applicationContext) ||
            !repository.backgroundPollingNeeded.value
        ) {
            DeparturePollingWork.cancel(applicationContext)
            DepartureNotificationFactory.cancel(applicationContext)
            AlarmBroadcastReceiver.cancelNotification(applicationContext)
            return Result.success()
        }

        repository.getFollowedDeparture()?.let { current ->
            app.transitRepository.refreshNow()
            val snapshot = app.transitRepository.getLatestSnapshot()
            val route = current.getStationPair()
            if (snapshot != null && route != null) {
                val departures = RouteDepartureProjection(
                    route, app.bartGtfsNetworkSupplier,
                ).project(snapshot).getDepartures()
                departures.firstOrNull { it.identity == current.identity }?.let { updated ->
                    if (current.getMeanSecondsLeft(app.timeSource) != updated.getMeanSecondsLeft(app.timeSource) ||
                        current.getUncertaintySeconds() != updated.getUncertaintySeconds()
                    ) {
                        repository.setFollowedDeparture(
                            Departure.merge(current, updated, false, app.timeSource),
                        )
                    }
                }
            }
        }

        val departure = repository.getFollowedDeparture()
        if (departure == null || departure.hasDeparted(app.timeSource) ||
            !repository.backgroundPollingNeeded.value
        ) {
            if (departure?.hasDeparted(app.timeSource) == true) repository.stopTracking()
            DeparturePollingWork.cancel(applicationContext)
            DepartureNotificationFactory.cancel(applicationContext)
            AlarmBroadcastReceiver.cancelNotification(applicationContext)
        } else {
            DepartureNotificationFactory.show(applicationContext, departure, repository, app.timeSource)
            DeparturePollingWork.schedule(
                applicationContext,
                repository.backgroundPollingIntervalFor(departure),
            )
        }
        return Result.success()
    }
}
