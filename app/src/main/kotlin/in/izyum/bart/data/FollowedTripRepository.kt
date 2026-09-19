package `in`.izyum.bart.data

import android.content.Context
import android.util.Log
import `in`.izyum.bart.model.Itinerary
import `in`.izyum.bart.model.SystemTimeSource
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.platform.DepartureAlarmScheduler
import `in`.izyum.bart.platform.DepartureAlarmPolicy
import `in`.izyum.bart.platform.DeparturePollingAlarm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Owns the followed itinerary, its process-death cache, and its observable state. */
class FollowedTripRepository @JvmOverloads constructor(
    context: Context,
    private val timeSource: TimeSource = SystemTimeSource
) : AutoCloseable {
    private companion object {
        const val TAG = "FollowedTripRepository"
        const val STORAGE_FILE_NAME = "followed_trip.json"
    }

    private val applicationContext = context.applicationContext
    private val storageFile = File(applicationContext.filesDir, STORAGE_FILE_NAME)
    private val store = FollowedTripStore(storageFile)
    private val persistenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stateLock = Any()
    private var pendingItinerary: Itinerary? = null
    private var hasPendingPersistence = false
    private var persistenceWriteQueued = false
    private var followedItinerary: Itinerary? = restore()
    private var alarmScheduler: DepartureAlarmScheduler? = followedItinerary?.let {
        DepartureAlarmScheduler(applicationContext, it)
    }

    private val _state = MutableStateFlow(toState(followedItinerary))
    private val _backgroundPollingNeeded = MutableStateFlow(
        alarmScheduler?.isTracking == true,
    )
    val state: StateFlow<FollowedTripState> = _state.asStateFlow()
    val backgroundPollingNeeded: StateFlow<Boolean> = _backgroundPollingNeeded.asStateFlow()

    fun getFollowedItinerary(): Itinerary? {
        val itinerary = synchronized(stateLock) { followedItinerary }
        if (itinerary != null && itinerary.hasInitialDeparturePassed(timeSource.nowMillis(), pessimistic = true)) {
            // A followed train is monitored only until departure. Stop the
            // background work as soon as the cached estimate crosses departure,
            // even if no realtime refresh is available.
            stopTracking()
        }
        if (itinerary != null && itinerary.hasExpired(timeSource.nowMillis())) {
            clearFollowedItinerary()
            return null
        }
        return itinerary
    }

    fun setFollowedItinerary(
        itinerary: Itinerary?,
        refreshBackgroundWork: Boolean = true,
    ) {
        val previousScheduler: DepartureAlarmScheduler?
        val preserveAlarm = synchronized(stateLock) {
            followedItinerary?.let { previous ->
                itinerary != null && isSameAlarmItinerary(previous, itinerary)
            } == true
        }
        synchronized(stateLock) {
            if (itinerary == followedItinerary) {
                return
            }
            previousScheduler = alarmScheduler
            previousScheduler?.close(preservePending = preserveAlarm)
            followedItinerary = itinerary
            alarmScheduler = itinerary?.let {
                DepartureAlarmScheduler(applicationContext, it)
            }
            _state.value = toState(itinerary)
            refreshBackgroundPollingStateLocked()
        }

        persist(itinerary)
        if (refreshBackgroundWork) {
            DeparturePollingAlarm.refresh(applicationContext, this)
        }
    }

    fun clearFollowedItinerary() {
        setFollowedItinerary(null)
    }

    fun clearFollowedDeparture() {
        clearFollowedItinerary()
    }

    fun getAlarmScheduler(): DepartureAlarmScheduler? {
        return synchronized(stateLock) { alarmScheduler }
    }

    fun setAlarm(leadTimeMinutes: Int) {
        synchronized(stateLock) { alarmScheduler }?.setUp(leadTimeMinutes)
        refreshBackgroundPollingState()
    }

    fun startTracking() {
        synchronized(stateLock) { alarmScheduler }?.startTracking()
        refreshBackgroundPollingState()
    }

    fun cancelAlarm() {
        synchronized(stateLock) { alarmScheduler }?.cancel()
        refreshBackgroundPollingState()
    }

    fun stopTracking() {
        synchronized(stateLock) { alarmScheduler }?.stopTracking()
        refreshBackgroundPollingState()
    }

    fun notifyAlarmHasBeenHandled() {
        synchronized(stateLock) { alarmScheduler }?.notifyAlarmHasBeenHandled()
        refreshBackgroundPollingState()
    }

    fun rescheduleAlarmIfPending() {
        synchronized(stateLock) { alarmScheduler }?.rescheduleIfPending()
    }

    /** Atomically claims a pending alarm and returns the departure it belongs to. */
    fun handleAlarmTriggered(): Itinerary? {
        val itinerary = synchronized(stateLock) {
            if (alarmScheduler?.isPending != true) {
                return@synchronized null
            }
            alarmScheduler?.notifyAlarmHasBeenHandled()
            followedItinerary
        }
        refreshBackgroundPollingState()
        return itinerary
    }

    /** Reads the cached trip without applying expiry or tracking side effects. */
    internal fun peekFollowedItinerary(): Itinerary? =
        synchronized(stateLock) { followedItinerary }

    private fun refreshBackgroundPollingStateInternal() {
        synchronized(stateLock) { refreshBackgroundPollingStateLocked() }
    }

    /** Refreshes the background polling cadence as the departure countdown changes. */
    fun refreshBackgroundPollingState() {
        refreshBackgroundPollingStateInternal()
        DeparturePollingAlarm.refresh(applicationContext, this)
    }

    private fun refreshBackgroundPollingStateLocked() {
        _backgroundPollingNeeded.value = alarmScheduler?.isTracking == true
    }

    internal fun backgroundPollingDelayMillis(itinerary: Itinerary?): Long {
        val nowMillis = timeSource.nowMillis()
        val scheduler = getAlarmScheduler()
        val alarmTime = if (scheduler?.isPending == true && itinerary != null) {
            DepartureAlarmPolicy.alarmTime(itinerary, scheduler.leadTimeMinutes)
        } else {
            itinerary?.getInitialArrivalTime(pessimistic = true) ?: nowMillis
        }
        return DepartureAlarmPolicy.nextPollingDelayMillis(alarmTime - nowMillis)
    }

    private fun restore(): Itinerary? = store.loadItinerary()

    private fun persist(itinerary: Itinerary?) {
        synchronized(stateLock) {
            pendingItinerary = itinerary
            hasPendingPersistence = true
            if (persistenceWriteQueued) {
                return
            }
            persistenceWriteQueued = true
        }
        persistenceExecutor.execute {
            while (true) {
                val nextItinerary = synchronized(stateLock) {
                    if (!hasPendingPersistence) {
                        persistenceWriteQueued = false
                        return@execute
                    }
                    hasPendingPersistence = false
                    pendingItinerary
                }
                try {
                    store.saveItinerary(nextItinerary)
                } catch (exception: Exception) {
                    Log.w(TAG, "Could not persist followed trip state", exception)
                }
            }
        }
    }

    private fun toState(itinerary: Itinerary?): FollowedTripState =
        FollowedTripState(itinerary)

    /**
     * Realtime updates can add trip-leg IDs after a trip has been followed. The
     * alarm belongs to the train, not to that feed detail, so retain it when the
     * route and nearby departure still identify the same train. A matching
     * first-leg trip identity is required so adjacent trains cannot inherit
     * one another's alarm state.
     */
    private fun isSameAlarmItinerary(previous: Itinerary, next: Itinerary): Boolean {
        if (previous.selectionIdentity == next.selectionIdentity) {
            return true
        }
        val previousFirstLeg = previous.legs.firstOrNull()
        val nextFirstLeg = next.legs.firstOrNull()
        if (previousFirstLeg?.tripId != nextFirstLeg?.tripId) {
            return false
        }
        if (previousFirstLeg?.serviceDate != null
            && nextFirstLeg?.serviceDate != null
            && previousFirstLeg.serviceDate != nextFirstLeg.serviceDate
        ) {
            return false
        }
        return previous.origin == next.origin
            && previous.destination == next.destination
            && previous.line == next.line
            && previous.direction == next.direction
            && previous.platform == next.platform
            && kotlin.math.abs(
                previous.getInitialDepartureTime() - next.getInitialDepartureTime(),
            ) <= 5 * 60_000L
    }

    override fun close() {
        synchronized(stateLock) { alarmScheduler }?.close()
        persistenceExecutor.shutdownNow()
    }
}
