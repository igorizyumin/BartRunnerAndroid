package `in`.izyum.bart.data

import android.content.Context
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.SystemTimeSource
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.platform.DepartureAlarmScheduler
import `in`.izyum.bart.platform.DeparturePollingWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Owns the followed trip, its process-death cache, and its observable state. */
class FollowedTripRepository @JvmOverloads constructor(
    context: Context,
    private val timeSource: TimeSource = SystemTimeSource
) : AutoCloseable {
    private companion object {
        const val STORAGE_FILE_NAME = "followed_trip.json"

        fun backgroundPollingIntervalFor(departure: Departure?): Long {
            val departureSeconds = departure?.getMeanSecondsLeft(SystemTimeSource)?.toLong()
                ?: Long.MAX_VALUE
            return when {
                departureSeconds > 15 * 60 -> 60_000L
                departureSeconds >= 5 * 60 -> 30_000L
                else -> 15_000L
            }
        }
    }

    private val applicationContext = context.applicationContext
    private val storageFile = File(applicationContext.filesDir, STORAGE_FILE_NAME)
    private val store = FollowedTripStore(storageFile)
    private val persistenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stateLock = Any()
    private var pendingDeparture: Departure? = null
    private var hasPendingPersistence = false
    private var persistenceWriteQueued = false
    private var followedDeparture: Departure? = restore()
    private var alarmScheduler: DepartureAlarmScheduler? = followedDeparture?.let {
        DepartureAlarmScheduler(applicationContext, it)
    }

    private val _state = MutableStateFlow(toState(followedDeparture))
    private val _backgroundPollingNeeded = MutableStateFlow(
        alarmScheduler?.isTracking == true,
    )
    val state: StateFlow<FollowedTripState> = _state.asStateFlow()
    val backgroundPollingNeeded: StateFlow<Boolean> = _backgroundPollingNeeded.asStateFlow()

    fun getFollowedDeparture(): Departure? {
        val departure = synchronized(stateLock) { followedDeparture }
        if (departure != null && departure.hasExpired(timeSource.nowMillis())) {
            clearFollowedDeparture()
            return null
        }
        return departure
    }

    fun setFollowedDeparture(departure: Departure?) {
        val previousScheduler: DepartureAlarmScheduler?
        val preserveAlarm = synchronized(stateLock) {
            followedDeparture?.let { previous ->
                departure != null && isSameAlarmDeparture(previous, departure)
            } == true
        }
        synchronized(stateLock) {
            if (departure == followedDeparture) {
                return
            }
            previousScheduler = alarmScheduler
            previousScheduler?.close(preservePending = preserveAlarm)
            followedDeparture = departure
            alarmScheduler = departure?.let {
                DepartureAlarmScheduler(applicationContext, it)
            }
            _state.value = toState(departure)
            refreshBackgroundPollingStateLocked()
        }

        persist(departure)
        DeparturePollingWork.refresh(applicationContext, this)
    }

    fun clearFollowedDeparture() {
        setFollowedDeparture(null)
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

    /** Atomically claims a pending alarm and returns the departure it belongs to. */
    fun handleAlarmTriggered(): Departure? {
        val departure = synchronized(stateLock) {
            if (alarmScheduler?.isPending != true) {
                return@synchronized null
            }
            alarmScheduler?.notifyAlarmHasBeenHandled()
            followedDeparture
        }
        refreshBackgroundPollingState()
        return departure
    }

    private fun refreshBackgroundPollingStateInternal() {
        synchronized(stateLock) { refreshBackgroundPollingStateLocked() }
    }

    /** Refreshes the background polling cadence as the departure countdown changes. */
    fun refreshBackgroundPollingState() {
        refreshBackgroundPollingStateInternal()
        DeparturePollingWork.refresh(applicationContext, this)
    }

    private fun refreshBackgroundPollingStateLocked() {
        _backgroundPollingNeeded.value = alarmScheduler?.isTracking == true
    }

    internal fun backgroundPollingIntervalFor(departure: Departure?): Long {
        val departureSeconds: Long = departure?.getMeanSecondsLeft(timeSource)?.toLong()
            ?: Long.MAX_VALUE
        return when {
            departureSeconds > 15 * 60 -> 60_000L
            departureSeconds >= 5 * 60 -> 30_000L
            else -> 15_000L
        }
    }

    private fun restore(): Departure? = store.load()

    private fun persist(departure: Departure?) {
        synchronized(stateLock) {
            pendingDeparture = departure
            hasPendingPersistence = true
            if (persistenceWriteQueued) {
                return
            }
            persistenceWriteQueued = true
        }
        persistenceExecutor.execute {
            while (true) {
                val nextDeparture = synchronized(stateLock) {
                    if (!hasPendingPersistence) {
                        persistenceWriteQueued = false
                        return@execute
                    }
                    hasPendingPersistence = false
                    pendingDeparture
                }
                try {
                    store.save(nextDeparture)
                } catch (exception: Exception) {
                    // Persistence is best effort; the in-memory state remains authoritative.
                }
            }
        }
    }

    private fun toState(departure: Departure?): FollowedTripState =
        FollowedTripState(departure)

    /**
     * Realtime updates can add trip-leg IDs after a trip has been followed. The
     * alarm belongs to the train, not to that feed detail, so retain it when the
     * route and nearby departure still identify the same train.
     */
    private fun isSameAlarmDeparture(previous: Departure, next: Departure): Boolean {
        if (previous.identity == next.identity) {
            return true
        }
        if (previous.tripLegs.isNotEmpty() && next.tripLegs.isNotEmpty()) {
            return false
        }
        return previous.origin?.abbreviation == next.origin?.abbreviation
            && previous.trainDestination?.abbreviation == next.trainDestination?.abbreviation
            && previous.line == next.line
            && previous.direction == next.direction
            && previous.platform == next.platform
            && kotlin.math.abs(previous.getMeanEstimate() - next.getMeanEstimate()) <= 5 * 60_000L
    }

    override fun close() {
        synchronized(stateLock) { alarmScheduler }?.close()
        persistenceExecutor.shutdownNow()
    }
}
