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

data class TransitFeedState(
    val snapshot: TransitFeedSnapshot? = null,
    val error: Exception? = null,
)

/** Owns feed polling and exposes one replaying flow for all transit consumers. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransitRepository(
    private val feedClient: TransitFeedClient,
    private val refreshIntervalMillis: Long,
    private val backgroundPollingNeeded: StateFlow<Boolean> = MutableStateFlow(false),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val appInForeground = MutableStateFlow(false)
    private val _state = MutableStateFlow(TransitFeedState())
    private val feedUpdates = MutableSharedFlow<TransitFeedState>(
        replay = 1,
        extraBufferCapacity = 1,
    )

    private var latestSnapshot: TransitFeedSnapshot? = null
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
            ) { hasSubscribers, foreground, backgroundAllowed ->
                hasSubscribers && (foreground || backgroundAllowed)
            }
                .distinctUntilChanged()
                .collectLatest { shouldPoll ->
                    if (shouldPoll) {
                        while (isActive) {
                            refreshIfStaleCancellable()
                            delay(refreshIntervalMillis)
                        }
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
        feed()
            .mapNotNull { feedState ->
                if (feedState.snapshot == null && feedState.error == null) {
                    null
                } else {
                    feedState
                }
            }
            .mapLatest { feedState ->
                feedState.error?.let {
                    return@mapLatest Result.failure<T>(it)
                }
                runCatching { project(feedState.snapshot!!) }
            }
            .distinctUntilChanged { previous, current ->
                if (previous.isFailure || current.isFailure) {
                    previous.exceptionOrNull()?.message == current.exceptionOrNull()?.message
                } else {
                    areEquivalent(previous.getOrNull(), current.getOrNull())
                }
            }
            .flowOn(Dispatchers.Default)

    /** Synchronously fetches once. Intended for tests and explicit refresh actions. */
    fun refreshNow() {
        if (!beginRefresh(force = true)) {
            return
        }

        fetchAndPublish()
    }

    /** Fetches immediately only when the feed has not been fetched recently. */
    fun refreshIfStale() {
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
            val nowMillis = System.currentTimeMillis()
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

        val refreshErrors = mutableListOf<Exception>()
        var stateToPublish: TransitFeedState? = null
        synchronized(lock) {
            refreshInProgress = false
            if (!closed) {
                fetchResult.tripUpdatesError?.let { addRefreshError(refreshErrors, it) }
                fetchResult.alertsError?.let { addRefreshError(refreshErrors, it) }

                val mergedSnapshot = mergeSnapshot(fetchResult)
                if (mergedSnapshot != null &&
                    (latestSnapshot == null ||
                        !mergedSnapshot.hasSameFeedData(latestSnapshot!!))
                ) {
                    latestSnapshot = mergedSnapshot
                    stateToPublish = TransitFeedState(snapshot = mergedSnapshot)
                }
                if (refreshErrors.isNotEmpty()) {
                    stateToPublish = TransitFeedState(
                        snapshot = latestSnapshot,
                        error = refreshErrors.first(),
                    )
                }
            }
        }

        stateToPublish?.let {
            _state.value = it
            feedUpdates.tryEmit(it)
        }
    }

    fun getLatestSnapshot(): TransitFeedSnapshot? = synchronized(lock) { latestSnapshot }

    private fun mergeSnapshot(result: TransitFeedFetchResult): TransitFeedSnapshot? {
        result.getCompleteSnapshot()?.let { return it }

        var tripUpdates: GtfsRealtime.FeedMessage? = result.tripUpdates
        var alerts: GtfsRealtime.FeedMessage? = result.alerts
        latestSnapshot?.let { snapshot ->
            if (tripUpdates == null) {
                tripUpdates = snapshot.tripUpdates
            }
            if (alerts == null) {
                alerts = snapshot.alerts
            }
        }
        if (tripUpdates == null || alerts == null) {
            return null
        }
        return TransitFeedSnapshot(tripUpdates!!, alerts!!, System.currentTimeMillis())
    }

    private fun addRefreshError(errors: MutableList<Exception>, error: Exception) {
        if (!errors.contains(error)) {
            errors.add(error)
        }
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
