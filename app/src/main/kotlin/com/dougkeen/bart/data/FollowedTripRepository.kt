package com.dougkeen.bart.data

import android.content.Context
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.SystemTimeSource
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.platform.DepartureAlarmScheduler
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
        alarmScheduler?.isPending == true,
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
            _backgroundPollingNeeded.value = alarmScheduler?.isPending == true
        }

        persist(departure)
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

    fun cancelAlarm() {
        synchronized(stateLock) { alarmScheduler }?.cancel()
        refreshBackgroundPollingState()
    }

    fun notifyAlarmHasBeenHandled() {
        synchronized(stateLock) { alarmScheduler }?.notifyAlarmHasBeenHandled()
        refreshBackgroundPollingState()
    }

    private fun refreshBackgroundPollingState() {
        _backgroundPollingNeeded.value = synchronized(stateLock) {
            alarmScheduler?.isPending == true
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
