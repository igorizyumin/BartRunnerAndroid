package `in`.izyum.bart.platform

import android.content.Context
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.data.BackgroundPollingPreferences
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.backend.RouteDepartureProjection
import `in`.izyum.bart.presentation.DepartureNotificationFactory
import `in`.izyum.bart.receivers.AlarmBroadcastReceiver

/** Performs one background departure refresh and arranges the next alarm. */
object DeparturePollingProcessor {
    fun process(context: Context) {
        val applicationContext = context.applicationContext
        val app = applicationContext as BartRunnerApplication
        val repository = app.followedTripRepository
        if (!BackgroundPollingPreferences.isEnabled(applicationContext) ||
            !repository.backgroundPollingNeeded.value
        ) {
            stop(applicationContext)
            return
        }

        val current = repository.getFollowedDeparture()
        if (current == null || !repository.backgroundPollingNeeded.value) {
            stop(applicationContext)
            return
        }

        // Keep a future wakeup armed before doing network work. A slow request,
        // process kill, or receiver deadline must not strand the follow-up.
        DeparturePollingAlarm.refresh(applicationContext, repository)

        app.transitRepository.refreshTripUpdatesNow()
        val snapshot = app.transitRepository.getLatestSnapshot()
        val route = current.getStationPair()
        if (snapshot != null && route != null) {
            val departures = RouteDepartureProjection(
                route, app.bartGtfsNetworkSupplier,
            ).project(snapshot).getDepartures()
            departures.firstOrNull { it.identity == current.identity }?.let { updated ->
                if (current.getMeanSecondsLeft(app.timeSource) !=
                    updated.getMeanSecondsLeft(app.timeSource) ||
                    current.getUncertaintySeconds() != updated.getUncertaintySeconds()
                ) {
                    repository.setFollowedDeparture(
                        Departure.merge(current, updated, false, app.timeSource),
                        refreshBackgroundWork = false,
                    )
                }
            }
        }

        val departure = repository.getFollowedDeparture()
        if (departure == null ||
            departure.hasInitialDeparturePassed(
                app.timeSource.nowMillis(),
                pessimistic = true,
            ) || !repository.backgroundPollingNeeded.value
        ) {
            if (departure?.hasDeparted(app.timeSource) == true) repository.stopTracking()
            stop(applicationContext)
        } else {
            DepartureNotificationFactory.show(
                applicationContext,
                departure,
                repository,
                app.timeSource,
            )
            DeparturePollingAlarm.refresh(applicationContext, repository)
        }
    }

    private fun stop(context: Context) {
        DeparturePollingAlarm.cancel(context)
        DepartureNotificationFactory.cancel(context)
        AlarmBroadcastReceiver.cancelNotification(context)
    }
}
