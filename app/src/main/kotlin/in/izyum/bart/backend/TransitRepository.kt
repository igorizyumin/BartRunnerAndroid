package `in`.izyum.bart.backend

import com.google.transit.realtime.GtfsRealtime
import `in`.izyum.bart.performance.PerformanceTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

data class RealtimeFeedState(
    val lastSuccessReceivedAtMillis: Long? = null,
    val feedTimestampMillis: Long? = null,
    val lastError: Exception? = null,
    val isUsable: Boolean = false,
)

data class TransitFeedState(
    val snapshot: TransitFeedSnapshot? = null,
    val error: Exception? = null,
    val isOffline: Boolean = false,
    val tripUpdates: RealtimeFeedState = RealtimeFeedState(),
    val alerts: RealtimeFeedState = RealtimeFeedState(),
)

/** Owns feed polling and exposes one replaying flow for all transit consumers. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransitRepository(
    private val feedClient: TransitFeedClient,
    private val refreshIntervalMillis: Long,
    private val backgroundPollingNeeded: StateFlow<Boolean> = MutableStateFlow(false),
    private val offlineSnapshotProvider: (() -> TransitFeedSnapshot)? = null,
    private val backgroundPollingIntervalMillis: StateFlow<Long> =
        MutableStateFlow(refreshIntervalMillis),
    private val tripUpdatesStaleAfterMillis: Long = 2 * 60_000L,
    private val alertsStaleAfterMillis: Long = 10 * 60_000L,
    private val nowMillisProvider: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    init {
        require(tripUpdatesStaleAfterMillis > 0) {
            "tripUpdatesStaleAfterMillis must be positive"
        }
        require(alertsStaleAfterMillis > 0) {
            "alertsStaleAfterMillis must be positive"
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val appInForeground = MutableStateFlow(false)
    private val _state = MutableStateFlow(TransitFeedState())
    private val feedUpdates = MutableSharedFlow<TransitFeedState>(
        replay = 1,
        extraBufferCapacity = 1,
    )

    private var latestSnapshot: TransitFeedSnapshot? = null
    private var latestTripUpdates: CachedRealtimeFeed? = null
    private var latestAlerts: CachedRealtimeFeed? = null
    private var lastTripUpdatesError: Exception? = null
    private var lastAlertsError: Exception? = null
    private var refreshInProgress = false
    private var lastRefreshStartedAtMillis: Long? = null
    private var closed = false

    init {
        require(refreshIntervalMillis > 0) {
            "refreshIntervalMillis must be positive"
        }
        feedUpdates.tryEmit(_state.value)
        scope.launch {
            combine(
                feedUpdates.subscriptionCount.map { count -> count > 0 },
                appInForeground,
                backgroundPollingNeeded,
                backgroundPollingIntervalMillis,
            ) { hasSubscribers, foreground, backgroundAllowed, backgroundInterval ->
                if (!hasSubscribers || (!foreground && !backgroundAllowed)) {
                    null
                } else if (foreground) {
                    refreshIntervalMillis
                } else {
                    backgroundInterval
                }
            }
                .distinctUntilChanged()
                .collectLatest { pollingIntervalMillis ->
                    while (isActive && pollingIntervalMillis != null) {
                        refreshIfStaleCancellable()
                        delay(pollingIntervalMillis)
                    }
                }
        }
    }

    /** Latest complete feed and its most recent refresh error, if any. */
    val state: StateFlow<TransitFeedState> = _state.asStateFlow()

    /** Updates whether an activity is currently visible in the app process. */
    fun setAppInForeground(inForeground: Boolean) {
        appInForeground.value = inForeground
    }

    /**
     * Replays the latest state and holds one polling lease for the collector.
     * Polling stops when the last collector is cancelled.
     */
    fun feed(): Flow<TransitFeedState> = flow {
        synchronized(lock) {
            check(!closed) { "Repository is closed" }
        }
        emitAll(feedUpdates.asSharedFlow())
    }

    /** Maps the shared feed and keeps projection failures in the stream. */
    fun <T> projectedState(
        project: (TransitFeedSnapshot) -> T,
        areEquivalent: (T?, T?) -> Boolean = { previous, current -> previous == current },
    ): Flow<Result<T>> =
        projectedStateSuspending(project, areEquivalent)

    /** Maps the shared feed through an asynchronous projection. */
    fun <T> projectedStateSuspending(
        project: suspend (TransitFeedSnapshot) -> T,
        areEquivalent: (T?, T?) -> Boolean = { previous, current -> previous == current },
    ): Flow<Result<T>> =
        feed()
            .mapNotNull { feedState ->
                if (feedState.snapshot == null && feedState.error == null) {
                    null
                } else {
                    feedState
                }
            }
            // buildStateLocked reuses the previous snapshot when a refresh
            // contains identical feed data. Deduplicate that stable snapshot
            // before invoking the potentially expensive projection. Keep
            // null-snapshot states distinct so errors and recovery still flow.
            .distinctUntilChanged { previous, current ->
                previous.snapshot != null && previous.snapshot === current.snapshot
            }
            .mapLatest { feedState ->
                val snapshot = feedState.snapshot
                if (snapshot == null) {
                    feedState.error?.let {
                        return@mapLatest Result.failure<T>(it)
                    }
                    return@mapLatest Result.failure<T>(
                        IllegalStateException("Transit feed is unavailable"),
                    )
                }
                runCatching { project(snapshot) }
            }
            .distinctUntilChanged { previous, current ->
                if (previous.isFailure || current.isFailure) {
                    previous.exceptionOrNull()?.message == current.exceptionOrNull()?.message
                } else {
                    areEquivalent(previous.getOrNull(), current.getOrNull())
                }
            }
            .flowOn(Dispatchers.Default)

    /** Refreshes once without blocking the caller's thread. */
    suspend fun refresh() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        refreshNowBlockingForTests()
    }

    /** Refreshes immediately when stale, always off the caller's thread. */
    suspend fun refreshIfStale() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        refreshIfStaleBlocking()
    }

    /** Refreshes only trip updates without blocking the caller's thread. */
    suspend fun refreshTripUpdates() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        refreshTripUpdatesNow()
    }

    /** Synchronous refresh helper reserved for JVM tests and background callers. */
    fun refreshNowBlockingForTests() {
        if (!beginRefresh(force = true)) {
            return
        }

        fetchAndPublish()
    }

    /**
     * Synchronously refreshes only trip updates, retaining the last alert feed.
     * This is used by the departure alarm loop so each wakeup has one small feed
     * request instead of downloading service alerts again.
     */
    fun refreshTripUpdatesNow() {
        if (!beginRefresh(force = true)) {
            return
        }

        val result = try {
            Result.success(feedClient.fetchTripUpdates())
        } catch (exception: Exception) {
            Result.failure<GtfsRealtime.FeedMessage>(exception)
        }

        var stateToPublish: TransitFeedState? = null
        synchronized(lock) {
            refreshInProgress = false
            if (!closed) {
                val now = nowMillisProvider()
                result.fold(
                    onSuccess = { tripUpdates ->
                        lastTripUpdatesError = null
                        latestTripUpdates = CachedRealtimeFeed(
                            tripUpdates,
                            now,
                            feedTimestampMillis(tripUpdates, now),
                            null,
                        )
                    },
                    onFailure = { exception ->
                        val error = exception as? Exception ?: RuntimeException(exception)
                        lastTripUpdatesError = error
                        latestTripUpdates = latestTripUpdates?.copy(lastError = error)
                    },
                )
                stateToPublish = buildStateLocked(now)
            }
        }
        stateToPublish?.let {
            _state.value = it
            feedUpdates.tryEmit(it)
        }
    }

    /** Fetches immediately only when the feed has not been fetched recently. */
    private fun refreshIfStaleBlocking() {
        if (!beginRefresh(force = false)) {
            return
        }

        fetchAndPublish()
    }

    private suspend fun refreshIfStaleCancellable() {
        if (!beginRefresh(force = false)) {
            return
        }

        try {
            val fetchResult = PerformanceTrace.suspendSection("BART realtime refresh") {
                runInterruptible(Dispatchers.IO) {
                    try {
                        feedClient.fetchFeeds()
                    } catch (exception: Exception) {
                        TransitFeedFetchResult.failed(exception)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            publishFetchResult(fetchResult)
        } finally {
            synchronized(lock) {
                refreshInProgress = false
            }
        }
    }

    private fun beginRefresh(force: Boolean): Boolean {
        synchronized(lock) {
            if (closed || refreshInProgress) {
                return false
            }
            val nowMillis = nowMillisProvider()
            if (!force && lastRefreshStartedAtMillis?.let {
                    nowMillis - it < refreshIntervalMillis
                } == true
            ) {
                return false
            }
            refreshInProgress = true
            lastRefreshStartedAtMillis = nowMillis
            return true
        }
    }

    private fun fetchAndPublish() {
        PerformanceTrace.section("BART realtime refresh") {
            val fetchResult = try {
                feedClient.fetchFeeds()
            } catch (exception: Exception) {
                TransitFeedFetchResult.failed(exception)
            }

            publishFetchResult(fetchResult)
        }
    }

    private fun publishFetchResult(fetchResult: TransitFeedFetchResult) {
        var stateToPublish: TransitFeedState? = null
        synchronized(lock) {
            refreshInProgress = false
            if (!closed) {
                val now = nowMillisProvider()
                if (fetchResult.tripUpdates != null) {
                    lastTripUpdatesError = null
                    latestTripUpdates = CachedRealtimeFeed(
                        fetchResult.tripUpdates,
                        now,
                        feedTimestampMillis(fetchResult.tripUpdates, now),
                        null,
                    )
                } else if (fetchResult.tripUpdatesError != null) {
                    lastTripUpdatesError = fetchResult.tripUpdatesError
                    latestTripUpdates = latestTripUpdates?.copy(
                        lastError = fetchResult.tripUpdatesError,
                    )
                }
                if (fetchResult.alerts != null) {
                    lastAlertsError = null
                    latestAlerts = CachedRealtimeFeed(
                        fetchResult.alerts,
                        now,
                        feedTimestampMillis(fetchResult.alerts, now),
                        null,
                    )
                } else if (fetchResult.alertsError != null) {
                    lastAlertsError = fetchResult.alertsError
                    latestAlerts = latestAlerts?.copy(lastError = fetchResult.alertsError)
                }
                stateToPublish = buildStateLocked(now, fetchResult.getCompleteSnapshot())
            }
        }

        stateToPublish?.let {
            _state.value = it
            feedUpdates.tryEmit(it)
        }
    }

    fun getLatestSnapshot(): TransitFeedSnapshot? = synchronized(lock) { latestSnapshot }

    private fun buildStateLocked(
        nowMillis: Long,
        preferredSnapshot: TransitFeedSnapshot? = null,
    ): TransitFeedState {
        val trip = latestTripUpdates?.status(nowMillis, tripUpdatesStaleAfterMillis)
            ?: RealtimeFeedState(lastError = lastTripUpdatesError)
        val alerts = latestAlerts?.status(nowMillis, alertsStaleAfterMillis)
            ?: RealtimeFeedState(lastError = lastAlertsError)
        val usableTrip = latestTripUpdates?.takeIf {
            it.isUsable(nowMillis, tripUpdatesStaleAfterMillis)
        }?.message ?: TransitFeedSnapshot.empty(nowMillis).tripUpdates
        val usableAlerts = latestAlerts?.takeIf {
            it.isUsable(nowMillis, alertsStaleAfterMillis)
        }?.message ?: TransitFeedSnapshot.empty(nowMillis).alerts
        val hasUsableRealtime = trip.isUsable || alerts.isUsable
        val errors = listOfNotNull(trip.lastError, alerts.lastError)
        val mergedSnapshot = preferredSnapshot ?: if (hasUsableRealtime) {
            TransitFeedSnapshot(usableTrip, usableAlerts, nowMillis)
        } else {
            // No retained realtime remains usable. The provider supplies the
            // static-only projection used by the Android application.
            offlineSnapshotProvider?.invoke() ?: TransitFeedSnapshot.empty(nowMillis)
        }
        if (latestSnapshot == null || !mergedSnapshot.hasSameFeedData(latestSnapshot)) {
            latestSnapshot = mergedSnapshot
        }
        return TransitFeedState(
            snapshot = latestSnapshot,
            error = errors.firstOrNull(),
            // Departure screens depend on trip updates. Fresh alert data must
            // not make stale or missing departure predictions appear online.
            isOffline = errors.isNotEmpty() || !trip.isUsable,
            tripUpdates = trip,
            alerts = alerts,
        )
    }

    private fun feedTimestampMillis(
        feed: GtfsRealtime.FeedMessage,
        fallbackMillis: Long,
    ): Long = if (feed.hasHeader() && feed.header.hasTimestamp() && feed.header.timestamp > 0) {
        feed.header.timestamp * 1000L
    } else {
        fallbackMillis
    }

    private data class CachedRealtimeFeed(
        val message: GtfsRealtime.FeedMessage,
        val receivedAtMillis: Long,
        val feedTimestampMillis: Long,
        val lastError: Exception?,
    ) {
        fun isUsable(nowMillis: Long, staleAfterMillis: Long): Boolean =
            nowMillis - receivedAtMillis < staleAfterMillis

        fun status(nowMillis: Long, staleAfterMillis: Long): RealtimeFeedState =
            RealtimeFeedState(
                lastSuccessReceivedAtMillis = receivedAtMillis,
                feedTimestampMillis = feedTimestampMillis,
                lastError = lastError,
                isUsable = isUsable(nowMillis, staleAfterMillis),
            )
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
        }
        scope.cancel()
    }
}
