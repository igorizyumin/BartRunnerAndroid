package com.dougkeen.bart.backend

import com.google.transit.realtime.GtfsRealtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class TransitFeedState(
    val snapshot: TransitFeedSnapshot? = null,
    val error: Exception? = null,
)

data class TransitProjectionState<T>(
    val value: T? = null,
    val error: Exception? = null,
)

/** Owns feed polling and exposes one replaying flow for all transit consumers. */
class TransitRepository(
    private val feedClient: TransitFeedClient,
    private val scheduler: ScheduledExecutorService,
    private val refreshIntervalMillis: Long,
) : AutoCloseable {
    private val lock = Any()
    private val _state = MutableStateFlow(TransitFeedState())

    private var latestSnapshot: TransitFeedSnapshot? = null
    private var scheduledRefresh: ScheduledFuture<*>? = null
    private var refreshInProgress = false
    private var flowConsumers = 0
    private var closed = false

    init {
        require(refreshIntervalMillis > 0) {
            "refreshIntervalMillis must be positive"
        }
    }

    /** Latest complete feed and its most recent refresh error, if any. */
    val state: StateFlow<TransitFeedState> = _state.asStateFlow()

    /**
     * Replays the latest state and holds one polling lease for the collector.
     * Polling stops when the last collector is cancelled.
     */
    fun feed(): Flow<TransitFeedState> = state
        .onStart { acquireFlowConsumer() }
        .onCompletion { releaseFlowConsumer() }

    /** Derives a projection from the shared feed without callback dispatch. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> projectedState(projection: TransitProjection<T>): Flow<TransitProjectionState<T>> =
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
                    return@mapLatest TransitProjectionState<T>(error = it)
                }
                try {
                    TransitProjectionState(projection.project(feedState.snapshot!!))
                } catch (exception: Exception) {
                    TransitProjectionState(error = exception)
                }
            }
            .distinctUntilChanged { previous, current ->
                if (previous.error != null || current.error != null) {
                    previous.error?.message == current.error?.message
                } else {
                    projection.areEquivalent(previous.value, current.value)
                }
            }
            .flowOn(Dispatchers.Default)

    /** Synchronously fetches once. Intended for tests and explicit refresh actions. */
    fun refreshNow() {
        synchronized(lock) {
            if (closed || refreshInProgress) {
                return
            }
            refreshInProgress = true
        }

        val fetchResult = try {
            feedClient.fetchFeeds()
        } catch (exception: Exception) {
            TransitFeedFetchResult.failed(exception)
        }

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
                if (flowConsumers > 0) {
                    scheduleNextRefreshLocked()
                }
            }
        }

        stateToPublish?.let { _state.value = it }
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

    private fun acquireFlowConsumer() {
        synchronized(lock) {
            check(!closed) { "Repository is closed" }
            flowConsumers++
            ensureRefreshScheduledLocked()
        }
    }

    private fun releaseFlowConsumer() {
        synchronized(lock) {
            if (flowConsumers > 0) {
                flowConsumers--
            }
            if (flowConsumers == 0) {
                scheduledRefresh?.cancel(false)
                scheduledRefresh = null
            }
        }
    }

    private fun ensureRefreshScheduledLocked() {
        if (scheduledRefresh == null && !refreshInProgress) {
            scheduledRefresh = scheduler.schedule({
                synchronized(lock) {
                    scheduledRefresh = null
                    if (closed || flowConsumers == 0) {
                        return@schedule
                    }
                }
                refreshNow()
            }, 0, TimeUnit.MILLISECONDS)
        }
    }

    private fun scheduleNextRefreshLocked() {
        if (scheduledRefresh == null) {
            scheduledRefresh = scheduler.schedule({
                synchronized(lock) {
                    scheduledRefresh = null
                    if (closed || flowConsumers == 0) {
                        return@schedule
                    }
                }
                refreshNow()
            }, refreshIntervalMillis, TimeUnit.MILLISECONDS)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            flowConsumers = 0
            scheduledRefresh?.cancel(false)
            scheduledRefresh = null
        }
    }
}
