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
import java.util.Objects
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
    private var followedDeparture: Departure? = restore()
    private var alarmScheduler: DepartureAlarmScheduler? = followedDeparture?.let {
        DepartureAlarmScheduler(applicationContext, it)
    }

    private val _state = MutableStateFlow(toState(followedDeparture))
    val state: StateFlow<FollowedTripState> = _state.asStateFlow()

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
        synchronized(stateLock) {
            if (Objects.equals(departure, followedDeparture)
                && compareDepartures(departure, followedDeparture) == 0
            ) {
                return
            }
            previousScheduler = alarmScheduler
            previousScheduler?.close()
            followedDeparture = departure
            alarmScheduler = departure?.let {
                DepartureAlarmScheduler(applicationContext, it)
            }
            _state.value = toState(departure)
        }

        persist(departure)
    }

    fun clearFollowedDeparture() {
        setFollowedDeparture(null)
    }

    fun getAlarmScheduler(): DepartureAlarmScheduler? {
        return synchronized(stateLock) { alarmScheduler }
    }

    private fun restore(): Departure? = store.load()

    private fun persist(departure: Departure?) {
        persistenceExecutor.execute {
            try {
                store.save(departure)
            } catch (exception: Exception) {
                // Persistence is best effort; the in-memory state remains authoritative.
            }
        }
    }

    private fun compareDepartures(first: Departure?, second: Departure?): Int {
        if (first === second) return 0
        if (first == null) return -1
        if (second == null) return 1
        return first.compareTo(second)
    }

    private fun toState(departure: Departure?): FollowedTripState =
        FollowedTripState(departure)

    override fun close() {
        synchronized(stateLock) { alarmScheduler }?.close()
        persistenceExecutor.shutdownNow()
    }
}
