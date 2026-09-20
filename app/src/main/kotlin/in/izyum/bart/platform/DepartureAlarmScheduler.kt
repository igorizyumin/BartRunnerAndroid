package `in`.izyum.bart.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import `in`.izyum.bart.activities.RoutesListActivity
import `in`.izyum.bart.model.Itinerary
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.SystemTimeSource
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.receivers.AlarmBroadcastReceiver

/** Owns Android alarm scheduling for one followed departure. */
class DepartureAlarmScheduler @JvmOverloads constructor(
    context: Context,
    private val itinerary: Itinerary,
    private val timeSource: TimeSource = SystemTimeSource
) :
    AutoCloseable {

    private companion object {
        const val TAG = "DepartureAlarmScheduler"
        const val ALARM_PREFS = "departure_alarm_state"
        const val LEAD_TIME_SUFFIX = ".leadTimeMinutes"
        const val PENDING_SUFFIX = ".pending"
        const val TRACKING_SUFFIX = ".tracking"
        const val LAST_USED_SUFFIX = ".lastUsedMillis"
        const val MAX_PERSISTED_ALARM_STATES = 16
    }

    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext
        .getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val preferences = applicationContext.getSharedPreferences(
        ALARM_PREFS, Context.MODE_PRIVATE)
    private val stateKey = buildStateKey(itinerary)
    private var storedLeadTimeMinutes = preferences.getInt(
        stateKey + LEAD_TIME_SUFFIX, 0)
    private var pendingAlarm = preferences.getBoolean(
        stateKey + PENDING_SUFFIX, false)
    init {
        prunePersistedStates()
        val nowMillis = timeSource.nowMillis()
        if (DepartureAlarmPolicy.shouldRestore(isPending,
                itinerary.hasInitialDeparturePassed(nowMillis, pessimistic = true),
                itinerary.hasExpired(nowMillis))) {
            if (!schedule()) {
                updateState(leadTimeMinutes, false)
            }
        } else if (isPending) {
            cancel()
        }
    }

    val leadTimeMinutes: Int
        get() = storedLeadTimeMinutes

    val isPending: Boolean
        get() = pendingAlarm

    val isTracking: Boolean
        get() = preferences.getBoolean(stateKey + TRACKING_SUFFIX, false)

    fun setUp(leadTimeMinutes: Int) {
        require(leadTimeMinutes >= 0) {
            "leadTimeMinutes must be non-negative"
        }
        // Keep the requested lead time, but do not expose the alarm as pending
        // until Android accepts the alarm.
        updateState(leadTimeMinutes, false)
        startTracking()
        if (schedule()) {
            updateState(leadTimeMinutes, true)
        }
    }

    fun startTracking() {
        preferences.edit {
            putBoolean(stateKey + TRACKING_SUFFIX, true)
            putLong(stateKey + LAST_USED_SUFFIX, timeSource.nowMillis())
        }
    }

    fun cancel() {
        alarmManager?.cancel(alarmIntent())
        updateState(leadTimeMinutes, false)
        Log.d(TAG, "Alarm cancelled")
    }

    fun stopTracking() {
        preferences.edit {
            putBoolean(stateKey + TRACKING_SUFFIX, false)
            putLong(stateKey + LAST_USED_SUFFIX, timeSource.nowMillis())
        }
    }

    fun notifyAlarmHasBeenHandled() {
        alarmManager?.cancel(alarmIntent())
        updateState(leadTimeMinutes, false)
        stopTracking()
    }

    fun rescheduleIfPending() {
        if (isPending && !schedule()) {
            updateState(leadTimeMinutes, false)
        }
    }

    /**
     * Closes this scheduler. When a live feed replaces the Departure instance for
     * the same train, the pending preference must survive long enough for the new
     * scheduler to restore and reschedule it with the updated ETA.
     */
    fun close(preservePending: Boolean = false) {
        if (isPending) {
            alarmManager?.cancel(alarmIntent())
            if (!preservePending) updateState(leadTimeMinutes, false)
        }
        if (!preservePending) {
            stopTracking()
        }
    }

    override fun close() {
        close(preservePending = false)
    }

    private fun alarmIntent(): PendingIntent {
        val intent = Intent(applicationContext, AlarmBroadcastReceiver::class.java)
            .setAction(DEPARTURE_ALARM_ACTION)
        return PendingIntent.getBroadcast(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmClockTime(): Long = DepartureAlarmPolicy.alarmTime(
        itinerary, leadTimeMinutes)

    private fun schedule(): Boolean {
        val manager = alarmManager
        if (manager == null) {
            Log.w(TAG,
                "No alarm manager available, so alarm will not be scheduled")
            return false
        }

        val alarmTime = alarmClockTime()
        val intent = alarmIntent()
        if (!ExactAlarmPermission.isGranted(applicationContext)) {
            Log.w(TAG, "Exact alarm permission is unavailable")
            manager.cancel(intent)
            return false
        }
        try {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(alarmTime, showIntent()),
                intent,
            )
        } catch (exception: SecurityException) {
            Log.w(TAG, "Could not schedule departure alarm", exception)
            manager.cancel(intent)
            return false
        }

        val alarmText = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(alarmTime))
        Log.v(TAG, "Scheduling alarm for $alarmText")
        return true
    }

    private fun updateState(leadTimeMinutes: Int, pending: Boolean) {
        storedLeadTimeMinutes = leadTimeMinutes
        pendingAlarm = pending
        preferences.edit {
            putInt(stateKey + LEAD_TIME_SUFFIX, leadTimeMinutes)
            putBoolean(stateKey + PENDING_SUFFIX, pending)
            putLong(stateKey + LAST_USED_SUFFIX, timeSource.nowMillis())
        }
    }

    private fun prunePersistedStates() {
        val bases = preferences.all.keys
            .filter { it.startsWith("alarm.") && it.endsWith(PENDING_SUFFIX) }
            .map { it.removeSuffix(PENDING_SUFFIX) }
            .filter { it != stateKey }
            .distinct()
        val excess = bases.size - (MAX_PERSISTED_ALARM_STATES - 1)
        if (excess <= 0) return
        bases.sortedBy { base ->
            preferences.getLong(base + LAST_USED_SUFFIX, Long.MIN_VALUE)
        }.take(excess).forEach { base ->
            preferences.edit {
                remove(base + LEAD_TIME_SUFFIX)
                remove(base + PENDING_SUFFIX)
                remove(base + TRACKING_SUFFIX)
                remove(base + LAST_USED_SUFFIX)
            }
        }
    }

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        1242,
        Intent(applicationContext, RoutesListActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildStateKey(itinerary: Itinerary): String = buildString {
        append("alarm.")
        appendStation(this, itinerary.origin)
        appendStation(this, itinerary.trainDestination)
        append('|').append(itinerary.line)
        append('|').append(itinerary.direction)
        append('|').append(itinerary.platform)
    }

    private fun appendStation(builder: StringBuilder, station: Station?) {
        builder.append('|').append(station?.abbreviation ?: "")
    }
}

internal const val DEPARTURE_ALARM_ACTION = "in.izyum.bart.action.DEPARTURE_ALARM"
