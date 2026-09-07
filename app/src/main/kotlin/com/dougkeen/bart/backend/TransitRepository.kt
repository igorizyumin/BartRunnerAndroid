package com.dougkeen.bart.backend

import com.google.transit.realtime.GtfsRealtime
import java.util.ArrayList
import java.util.HashSet
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the latest complete transit snapshot and publishes it to active
 * consumers. Network refreshes are independent of the route queries made by
 * those consumers.
 */
class TransitRepository : AutoCloseable {
    interface Listener {
        fun onSnapshot(snapshot: TransitFeedSnapshot)

        fun onError(exception: Exception, lastSnapshot: TransitFeedSnapshot?)
    }

    interface Subscription : AutoCloseable {
        override fun close()
    }

    private val lock = Any()
    private val feedClient: TransitFeedClient
    private val scheduler: ScheduledExecutorService
    private val projectionExecutor: Executor
    private val callbackExecutor: Executor
    private val refreshIntervalMillis: Long
    private val listeners = HashSet<Listener>()

    private var latestSnapshot: TransitFeedSnapshot? = null
    private var scheduledRefresh: ScheduledFuture<*>? = null
    private var refreshInProgress = false
    private var closed = false

    constructor(
        feedClient: TransitFeedClient,
        scheduler: ScheduledExecutorService,
        callbackExecutor: Executor,
        refreshIntervalMillis: Long
    ) : this(feedClient, scheduler, callbackExecutor, callbackExecutor, refreshIntervalMillis)

    constructor(
        feedClient: TransitFeedClient,
        scheduler: ScheduledExecutorService,
        projectionExecutor: Executor,
        callbackExecutor: Executor,
        refreshIntervalMillis: Long
    ) {
        require(refreshIntervalMillis > 0) { "refreshIntervalMillis must be positive" }
        this.feedClient = feedClient
        this.scheduler = scheduler
        this.projectionExecutor = projectionExecutor
        this.callbackExecutor = callbackExecutor
        this.refreshIntervalMillis = refreshIntervalMillis
    }

    fun subscribe(listener: Listener): Subscription {
        requireNotNull(listener) { "listener" }

        val snapshotToReplay: TransitFeedSnapshot?
        synchronized(lock) {
            check(!closed) { "Repository is closed" }
            listeners.add(listener)
            snapshotToReplay = latestSnapshot
            ensureRefreshScheduledLocked()
        }

        if (snapshotToReplay != null) {
            dispatchSnapshot(listener, snapshotToReplay)
        }

        return object : Subscription {
            private var active = true

            override fun close() {
                synchronized(lock) {
                    if (!active) {
                        return
                    }
                    active = false
                    listeners.remove(listener)
                    if (listeners.isEmpty() && scheduledRefresh != null) {
                        scheduledRefresh?.cancel(false)
                        scheduledRefresh = null
                    }
                }
            }
        }
    }

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

        val snapshotListeners = ArrayList<Listener>()
        val errorListeners = ArrayList<Listener>()
        var snapshotToNotify: TransitFeedSnapshot? = null
        val refreshErrors = ArrayList<Exception>()
        val snapshotOnError: TransitFeedSnapshot?
        synchronized(lock) {
            refreshInProgress = false
            if (!closed) {
                fetchResult.tripUpdatesError?.let { addRefreshError(refreshErrors, it) }
                fetchResult.alertsError?.let { addRefreshError(refreshErrors, it) }

                val mergedSnapshot = mergeSnapshot(fetchResult)
                if (mergedSnapshot != null &&
                    (latestSnapshot == null || !mergedSnapshot.hasSameFeedData(latestSnapshot!!))
                ) {
                    latestSnapshot = mergedSnapshot
                    snapshotToNotify = mergedSnapshot
                    snapshotListeners.addAll(listeners)
                }
            }
            if (!closed && refreshErrors.isNotEmpty()) {
                errorListeners.addAll(listeners)
            }
            snapshotOnError = latestSnapshot
            if (!closed && listeners.isNotEmpty()) {
                scheduleNextRefreshLocked()
            }
        }

        snapshotToNotify?.let { snapshot ->
            snapshotListeners.forEach { listener -> dispatchSnapshot(listener, snapshot) }
        }
        if (refreshErrors.isNotEmpty()) {
            errorListeners.forEach { listener ->
                refreshErrors.forEach { error -> dispatchError(listener, error, snapshotOnError) }
            }
        }
    }

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

    fun getLatestSnapshot(): TransitFeedSnapshot? = synchronized(lock) { latestSnapshot }

    /**
     * Subscribes to a query-specific value derived from each changed snapshot.
     * Projection work is kept off the callback executor so UI delivery remains
     * lightweight.
     */
    fun <T> subscribe(
        projection: TransitProjection<T>,
        listener: TransitProjectionListener<T>
    ): Subscription {
        requireNotNull(projection) { "projection" }
        requireNotNull(listener) { "listener" }

        val active = AtomicBoolean(true)
        val rawSubscription = subscribe(object : Listener {
            private val projectionLock = Any()
            private var previousValue: T? = null
            private var hasPreviousValue = false

            override fun onSnapshot(snapshot: TransitFeedSnapshot) {
                if (!active.get()) {
                    return
                }
                projectionExecutor.execute {
                    try {
                        if (!active.get()) {
                            return@execute
                        }
                        val value: T
                        synchronized(projectionLock) {
                            value = projection.project(snapshot)
                            if (hasPreviousValue && projection.areEquivalent(previousValue, value)) {
                                return@execute
                            }
                            previousValue = value
                            hasPreviousValue = true
                        }
                        callbackExecutor.execute {
                            if (active.get()) {
                                listener.onData(value, snapshot)
                            }
                        }
                    } catch (exception: Exception) {
                        dispatchProjectionError(exception, snapshot)
                    }
                }
            }

            override fun onError(exception: Exception, lastSnapshot: TransitFeedSnapshot?) {
                callbackExecutor.execute {
                    if (active.get()) {
                        listener.onError(exception, lastSnapshot)
                    }
                }
            }

            private fun dispatchProjectionError(
                exception: Exception,
                snapshot: TransitFeedSnapshot
            ) {
                callbackExecutor.execute {
                    if (active.get()) {
                        listener.onError(exception, snapshot)
                    }
                }
            }
        })
        return object : Subscription {
            override fun close() {
                if (active.compareAndSet(true, false)) {
                    rawSubscription.close()
                }
            }
        }
    }

    private fun ensureRefreshScheduledLocked() {
        if (scheduledRefresh == null && !refreshInProgress) {
            scheduledRefresh = scheduler.schedule({
                synchronized(lock) {
                    scheduledRefresh = null
                    if (closed || listeners.isEmpty()) {
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
                    if (closed || listeners.isEmpty()) {
                        return@schedule
                    }
                }
                refreshNow()
            }, refreshIntervalMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun dispatchSnapshot(listener: Listener, snapshot: TransitFeedSnapshot) {
        callbackExecutor.execute {
            synchronized(lock) {
                if (closed || !listeners.contains(listener)) {
                    return@execute
                }
            }
            listener.onSnapshot(snapshot)
        }
    }

    private fun dispatchError(
        listener: Listener,
        exception: Exception,
        lastSnapshot: TransitFeedSnapshot?
    ) {
        callbackExecutor.execute {
            synchronized(lock) {
                if (closed || !listeners.contains(listener)) {
                    return@execute
                }
            }
            listener.onError(exception, lastSnapshot)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            listeners.clear()
            scheduledRefresh?.cancel(false)
            scheduledRefresh = null
        }
    }
}
