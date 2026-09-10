package `in`.izyum.bart.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.izyum.bart.R
import `in`.izyum.bart.activities.RoutesListActivity
import `in`.izyum.bart.backend.TransitRepository
import `in`.izyum.bart.data.FollowedTripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps an ongoing, low-priority status notification visible while downloads fail. */
class OfflineStatusController(
    context: Context,
    private val transitRepository: TransitRepository,
    private val followedTripRepository: FollowedTripRepository,
    private val retryDownloads: suspend () -> Unit = {},
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val connectivityManager =
        applicationContext.getSystemService(ConnectivityManager::class.java)
    private val notificationManager = NotificationManagerCompat.from(applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val _isOffline = MutableStateFlow(false)
    private var networkAvailable: Boolean? = null
    private var downloadFailed = false
    private var retryJob: Job? = null
    private var closed = false

    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkState(hasValidatedNetwork(network), triggerRetry = true)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            updateNetworkState(
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                triggerRetry = true,
            )
        }

        override fun onLost(network: Network) {
            updateNetworkState(false, triggerRetry = false)
        }
    }

    init {
        createNotificationChannel()
        updateNetworkState(currentNetworkIsValidated(), triggerRetry = false)
        connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        scope.launch {
            transitRepository.state.collectLatest { state ->
                synchronized(lock) {
                    downloadFailed = state.isOffline
                }
                if (state.isOffline && followedTripRepository.backgroundPollingNeeded.value) {
                    startRetrying()
                } else {
                    stopRetrying()
                }
                updateNotification()
            }
        }
        scope.launch {
            followedTripRepository.backgroundPollingNeeded.collectLatest {
                if (!it) stopRetrying()
                updateNotification()
            }
        }
    }

    private fun updateNetworkState(available: Boolean, triggerRetry: Boolean) {
        val becameAvailable = synchronized(lock) {
            val changed = networkAvailable != true && available
            networkAvailable = available
            changed
        }
        if (triggerRetry && becameAvailable) {
            scope.launch(Dispatchers.IO) { retryDownloads() }
        }
        updateNotification()
    }

    private fun startRetrying() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val shouldRetry = synchronized(lock) { networkAvailable != false }
                if (shouldRetry) {
                    retryDownloads()
                }
                delay(RETRY_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopRetrying() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun updateNotification() {
        val shouldShow = synchronized(lock) {
            !closed && followedTripRepository.backgroundPollingNeeded.value &&
                (networkAvailable == false || downloadFailed)
        }
        _isOffline.value = shouldShow
        try {
            if (shouldShow) {
                val intent = PendingIntent.getActivity(
                    applicationContext,
                    OFFLINE_NOTIFICATION_ID,
                    Intent(applicationContext, RoutesListActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                notificationManager.notify(
                    OFFLINE_NOTIFICATION_ID,
                    NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_notification)
                        .setContentTitle(applicationContext.getString(R.string.offline_title))
                        .setContentText(applicationContext.getString(R.string.offline_message))
                        .setContentIntent(intent)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .build(),
                )
            } else {
                notificationManager.cancel(OFFLINE_NOTIFICATION_ID)
            }
        } catch (_: SecurityException) {
            // Notification permission can be disabled independently of connectivity.
        }
    }

    private fun createNotificationChannel() {
        applicationContext.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.offline_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
    }

    private fun currentNetworkIsValidated(): Boolean =
        connectivityManager?.activeNetwork?.let { network ->
            hasValidatedNetwork(network)
        } ?: false

    private fun hasValidatedNetwork(network: Network): Boolean =
        connectivityManager?.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        connectivityManager?.unregisterNetworkCallback(networkCallback)
        stopRetrying()
        scope.cancel()
        try {
            notificationManager.cancel(OFFLINE_NOTIFICATION_ID)
        } catch (_: SecurityException) {
            // Nothing to clean up when notification permission is unavailable.
        }
    }

    companion object {
        private const val CHANNEL_ID = "BART_Runner_connection_status"
        private const val OFFLINE_NOTIFICATION_ID = 125
        private const val RETRY_INTERVAL_MILLIS = 15_000L
    }
}
