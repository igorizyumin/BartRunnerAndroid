package `in`.izyum.bart.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.R
import `in`.izyum.bart.backend.RouteDepartureProjection
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.model.SystemTimeSource
import `in`.izyum.bart.presentation.DepartureNotificationFactory
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

/** Keeps a followed departure aligned with the live BART feed until departure. */
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
        when (intent?.action) {
            ACTION_CANCEL_ALARM -> {
                cancelAlarm()
                updateNotification()
                return START_REDELIVER_INTENT
            }

            ACTION_CLEAR_DEPARTURE -> {
                cancelAlarm()
                followedTripRepository.clearFollowedDeparture()
                shutDown()
                return START_NOT_STICKY
            }

            ACTION_START_ALARM_TRACKING -> Unit
            else -> {
                shutDown()
                return START_NOT_STICKY
            }
        }

        if (!isTracking()) {
            shutDown()
            return START_NOT_STICKY
        }
        updateNotification()
        serviceScope.launch {
            serviceMutex.withLock {
                handleIntent()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        hasShutDown = true
        pollJob?.cancel()
        cancelDepartureCollection()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager?.cancel(DEPARTURE_NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        shutDown()
    }

    private fun handleIntent() {
        if (!isTracking()) {
            shutDown()
            return
        }

        val boardedDeparture = followedTripRepository.getFollowedDeparture()
        if (boardedDeparture == null) {
            shutDown()
            return
        }

        updateStationPair(boardedDeparture.getStationPair())
        startPolling()
    }

    private fun isAlarmPending(): Boolean =
        followedTripRepository.getAlarmScheduler()?.isPending == true

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
            val projection = RouteDepartureProjection(
                nextStationPair,
                application.bartGtfsNetworkSupplier,
            )
            transitRepository.projectedState(projection::project, projection::areEquivalent)
                .collectLatest { result ->
                result.getOrNull()?.let { departures ->
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
            shutDown()
            return
        }

        val updatedDeparture = departures.firstOrNull { departure ->
            departure.identity == boardedDeparture.identity
        } ?: return

        if (shouldUpdateNotification(boardedDeparture, updatedDeparture)) {
            followedTripRepository.setFollowedDeparture(
                Departure.merge(boardedDeparture, updatedDeparture, false, timeSource),
            )
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
                        val hasDeparted = departure?.hasDeparted(timeSource) == true
                        if (shouldStopPolling(
                                isTracking(),
                                departure != null,
                                hasDeparted,
                        )) {
                            if (hasDeparted) {
                                followedTripRepository.stopTracking()
                            }
                            shutDown()
                            false
                        } else {
                            true
                        }
                    }
                }
                if (!shouldContinue) {
                    return@launch
                }
                followedTripRepository.refreshBackgroundPollingState()
                delay(
                    pollIntervalMillisForDeparture(
                        followedTripRepository.getFollowedDeparture(),
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
    internal fun shouldStopPolling(
        hasActiveTracking: Boolean,
        hasFollowedDeparture: Boolean,
        hasDeparted: Boolean,
    ): Boolean = !hasActiveTracking || !hasFollowedDeparture || hasDeparted

    @VisibleForTesting
    internal fun pollIntervalMillisForDeparture(departure: Departure?): Long {
        val minutesLeft = departure?.getMeanSecondsLeft(timeSource)?.div(60L)
            ?: Long.MAX_VALUE
        return when {
            minutesLeft > 15 -> SLOW_POLL_MILLIS
            minutesLeft >= 5 -> MEDIUM_POLL_MILLIS
            else -> FAST_POLL_MILLIS
        }
    }

    private fun isTracking(): Boolean =
        followedTripRepository.getAlarmScheduler()?.isTracking == true

    private fun cancelAlarm() {
        followedTripRepository.cancelAlarm()
    }

    private fun shutDown() {
        if (hasShutDown) {
            return
        }
        hasShutDown = true
        pollJob?.cancel()
        pollJob = null
        cancelDepartureCollection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager?.cancel(DEPARTURE_NOTIFICATION_ID)
        stopSelf()
    }

    private fun cancelDepartureCollection() {
        departureCollection?.cancel()
        departureCollection = null
        stationPair = null
    }

    private fun updateNotification(initialDeparture: Departure? = null) {
        if (hasShutDown) {
            return
        }
        val departure = initialDeparture ?: followedTripRepository.getFollowedDeparture() ?: return
        val notification = DepartureNotificationFactory.create(
            applicationContext,
            departure,
            followedTripRepository.getAlarmScheduler(),
            timeSource,
        )
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

    private fun createNotificationChannel() {
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
        const val ACTION_START_ALARM_TRACKING = "com.dougkeen.action.START_ALARM_TRACKING"
        const val ACTION_CANCEL_ALARM = "com.dougkeen.action.CANCEL_BOARDED_DEPARTURE_ALARM"
        const val ACTION_CLEAR_DEPARTURE = "com.dougkeen.action.CLEAR_BOARDED_DEPARTURE"
        private const val DEPARTURE_NOTIFICATION_ID = 123
        private const val FAST_POLL_MILLIS = 15_000L
        private const val MEDIUM_POLL_MILLIS = 30_000L
        private const val SLOW_POLL_MILLIS = 60_000L
    }
}
