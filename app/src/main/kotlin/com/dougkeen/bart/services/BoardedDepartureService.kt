package com.dougkeen.bart.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R
import com.dougkeen.bart.backend.RouteDepartureProjection
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.model.SystemTimeSource
import com.dougkeen.bart.presentation.DepartureNotificationFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps the notification for the followed departure current while the user is travelling. */
class BoardedDepartureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timeSource = SystemTimeSource
    private val serviceMutex = Mutex()
    private var pollJob: Job? = null
    private var departureCollection: Job? = null
    private var stationPair: StationPair? = null
    private var hasShutDown = false
    private var notificationManager: NotificationManagerCompat? = null

    private val application: BartRunnerApplication
        get() = getApplication() as BartRunnerApplication

    private val followedTripRepository
        get() = application.followedTripRepository

    private val transitRepository
        get() = application.transitRepository

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        hasShutDown = false
        serviceScope.launch {
            serviceMutex.withLock {
                intent?.let(::handleIntent)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        hasShutDown = true
        pollJob?.cancel()
        cancelDepartureCollection()
        serviceScope.cancel()
        stopForegroundCompat()
        notificationManager?.cancel(DEPARTURE_NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_CANCEL_ALARM -> {
                cancelAlarm()
                if (followedTripRepository.getFollowedDeparture() == null) {
                    shutDown(false)
                } else {
                    updateNotification()
                }
                return
            }

            ACTION_CLEAR_DEPARTURE -> {
                cancelAlarm()
                followedTripRepository.clearFollowedDeparture()
                shutDown(false)
                return
            }
        }

        val boardedDeparture = followedTripRepository.getFollowedDeparture()
        if (boardedDeparture == null) {
            shutDown(false)
            return
        }

        updateStationPair(boardedDeparture.getStationPair())
        updateNotification()
        startPolling()
    }

    private fun updateStationPair(nextStationPair: StationPair?) {
        if (nextStationPair == stationPair) {
            return
        }
        cancelDepartureCollection()
        stationPair = nextStationPair
        if (nextStationPair == null) {
            return
        }

        departureCollection = serviceScope.launch {
            transitRepository.projectedState(
                RouteDepartureProjection(nextStationPair, applicationContext),
            ).collectLatest { projectionState ->
                projectionState.value?.let { departures ->
                    serviceMutex.withLock {
                        onDeparturesChanged(departures.getDepartures())
                    }
                }
            }
        }
    }

    private fun onDeparturesChanged(departures: List<Departure>) {
        if (hasShutDown) {
            return
        }
        val boardedDeparture = followedTripRepository.getFollowedDeparture()
        if (boardedDeparture == null) {
            shutDown(false)
            return
        }

        val updatedDeparture = departures.firstOrNull { departure ->
            departure.identity == boardedDeparture.identity
        } ?: return

        if (shouldUpdateNotification(boardedDeparture, updatedDeparture)) {
            followedTripRepository.setFollowedDeparture(
                Departure.merge(boardedDeparture, updatedDeparture, false, timeSource),
            )
            updateAlarm()
            updateNotification()
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) {
            return
        }
        pollJob = serviceScope.launch {
            while (isActive) {
                val shouldContinue = serviceMutex.withLock {
                    if (hasShutDown) {
                        false
                    } else {
                        val departure = followedTripRepository.getFollowedDeparture()
                        if (shouldStopPolling(
                                departure != null,
                                departure?.hasDeparted(timeSource) == true,
                            )) {
                            shutDown(false)
                            false
                        } else {
                            updateAlarm()
                            updateNotification()
                            true
                        }
                    }
                }
                if (!shouldContinue) {
                    return@launch
                }
                delay(
                    pollIntervalMillisForAlarm(
                        followedTripRepository.getAlarmScheduler()?.secondsUntilAlarm ?: 0,
                    ),
                )
            }
        }
    }

    @VisibleForTesting
    internal fun shouldUpdateNotification(
        previous: Departure,
        incoming: Departure,
    ): Boolean = previous.getMeanSecondsLeft(timeSource) != incoming.getMeanSecondsLeft(timeSource)
        || previous.getUncertaintySeconds() != incoming.getUncertaintySeconds()

    @VisibleForTesting
    internal fun shouldStopPolling(hasFollowedDeparture: Boolean, hasDeparted: Boolean): Boolean =
        !hasFollowedDeparture || hasDeparted

    @VisibleForTesting
    internal fun pollIntervalMillisForAlarm(secondsUntilAlarm: Int): Long =
        if (secondsUntilAlarm > FAST_POLL_THRESHOLD_SECONDS) {
            SLOW_POLL_MILLIS
        } else {
            FAST_POLL_MILLIS
        }

    private fun updateAlarm() {
        followedTripRepository.getAlarmScheduler()?.update()
    }

    private fun cancelAlarm() {
        followedTripRepository.getAlarmScheduler()?.cancel()
    }

    private fun shutDown(isBeingDestroyed: Boolean) {
        if (hasShutDown) {
            return
        }
        hasShutDown = true
        pollJob?.cancel()
        pollJob = null
        cancelDepartureCollection()
        stopForegroundCompat()
        notificationManager?.cancel(DEPARTURE_NOTIFICATION_ID)
        if (!isBeingDestroyed) {
            stopSelf()
        }
    }

    private fun cancelDepartureCollection() {
        departureCollection?.cancel()
        departureCollection = null
        stationPair = null
    }

    private fun updateNotification() {
        if (hasShutDown) {
            return
        }
        val departure = followedTripRepository.getFollowedDeparture() ?: return
        val notification = DepartureNotificationFactory.create(
            applicationContext,
            departure,
            followedTripRepository.getAlarmScheduler(),
            timeSource,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager?.notify(DEPARTURE_NOTIFICATION_ID, notification)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DEPARTURE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(DEPARTURE_NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            getString(R.string.notification_channel_id),
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_FOLLOW_DEPARTURE = "com.dougkeen.action.FOLLOW_BOARDED_DEPARTURE"
        const val ACTION_CANCEL_ALARM = "com.dougkeen.action.CANCEL_BOARDED_DEPARTURE_ALARM"
        const val ACTION_CLEAR_DEPARTURE = "com.dougkeen.action.CLEAR_BOARDED_DEPARTURE"
        private const val DEPARTURE_NOTIFICATION_ID = 123
        private const val FAST_POLL_MILLIS = 6_000L
        private const val SLOW_POLL_MILLIS = 15_000L
        private const val FAST_POLL_THRESHOLD_SECONDS = 3 * 60
    }
}
