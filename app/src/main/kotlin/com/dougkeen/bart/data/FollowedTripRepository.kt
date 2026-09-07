package com.dougkeen.bart.data

import android.app.AlarmManager
import android.content.Context
import android.os.Parcel
import android.util.Log
import com.dougkeen.bart.model.Departure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Objects
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Owns the followed trip, its process-death cache, and its observable state. */
class FollowedTripRepository(context: Context) : AutoCloseable {
    private companion object {
        const val TAG = "FollowedTripRepository"
        const val CACHE_FILE_NAME = "followed_trip.cache"
    }

    private val applicationContext = context.applicationContext
    private val cacheFile = File(applicationContext.cacheDir, CACHE_FILE_NAME)
    private val persistenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stateLock = Any()
    private var followedDeparture: Departure? = restore()

    private val _state = MutableStateFlow(FollowedTripState(followedDeparture))
    val state: StateFlow<FollowedTripState> = _state.asStateFlow()

    fun getFollowedDeparture(): Departure? {
        val departure = synchronized(stateLock) { followedDeparture }
        if (departure != null && departure.hasExpired()) {
            clearFollowedDeparture()
            return null
        }
        return departure
    }

    fun setFollowedDeparture(departure: Departure?) {
        val previous: Departure?
        synchronized(stateLock) {
            if (Objects.equals(departure, followedDeparture)
                && compareDepartures(departure, followedDeparture) == 0
            ) {
                return
            }
            previous = followedDeparture
            followedDeparture = departure
            _state.value = FollowedTripState(departure)
        }

        release(previous)
        persist(departure)
    }

    fun clearFollowedDeparture() {
        setFollowedDeparture(null)
    }

    private fun release(departure: Departure?) {
        if (departure == null) {
            return
        }
        departure.alarmLeadTimeMinutesObservable.unregisterAllObservers()
        departure.alarmPendingObservable.unregisterAllObservers()
        if (departure.isAlarmPending) {
            departure.cancelAlarm(
                applicationContext,
                applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager,
            )
        }
    }

    private fun restore(): Departure? {
        if (!cacheFile.exists()) {
            return null
        }
        return try {
            cacheFile.inputStream().use { input ->
                val bytes = input.readBytes()
                val parcel = Parcel.obtain()
                try {
                    parcel.unmarshall(bytes, 0, bytes.size)
                    parcel.setDataPosition(0)
                    Departure.CREATOR.createFromParcel(parcel)
                } finally {
                    parcel.recycle()
                }
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Could not restore followed trip", exception)
            deleteCache()
            null
        }
    }

    private fun persist(departure: Departure?) {
        persistenceExecutor.execute {
            try {
                if (departure == null) {
                    deleteCache()
                    return@execute
                }
                val parcel = Parcel.obtain()
                try {
                    departure.writeToParcel(parcel, 0)
                    cacheFile.outputStream().use { output ->
                        output.write(parcel.marshall())
                    }
                } finally {
                    parcel.recycle()
                }
            } catch (exception: Exception) {
                Log.w(TAG, "Could not persist followed trip", exception)
            }
        }
    }

    private fun deleteCache() {
        try {
            cacheFile.delete()
        } catch (exception: SecurityException) {
            Log.w(TAG, "Could not delete followed trip cache", exception)
        }
    }

    private fun compareDepartures(first: Departure?, second: Departure?): Int {
        if (first === second) return 0
        if (first == null) return -1
        if (second == null) return 1
        return first.compareTo(second)
    }

    override fun close() {
        persistenceExecutor.shutdownNow()
    }
}
