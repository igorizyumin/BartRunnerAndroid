package com.dougkeen.bart.platform

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
import com.dougkeen.bart.model.Constants
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.SystemTimeSource
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.receivers.AlarmBroadcastReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns Android alarm scheduling for one followed departure. */
class DepartureAlarmScheduler @JvmOverloads constructor(
    context: Context,
    private val departure: Departure,
    private val timeSource: TimeSource = SystemTimeSource
) :
    AutoCloseable {

    private companion object {
        const val ALARM_PREFS = "departure_alarm_state"
        const val LEAD_TIME_SUFFIX = ".leadTimeMinutes"
        const val PENDING_SUFFIX = ".pending"
    }

    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext
        .getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val preferences = applicationContext.getSharedPreferences(
        ALARM_PREFS, Context.MODE_PRIVATE)
    private val stateKey = buildStateKey(departure)
    private val _state = MutableStateFlow(
        DepartureAlarmState(
            leadTimeMinutes = preferences.getInt(
                stateKey + LEAD_TIME_SUFFIX, 0),
            pending = preferences.getBoolean(stateKey + PENDING_SUFFIX, false),
        )
    )
    val state: StateFlow<DepartureAlarmState> = _state.asStateFlow()

    init {
        val nowMillis = timeSource.nowMillis()
        if (DepartureAlarmPolicy.shouldRestore(isPending,
                departure.hasDeparted(nowMillis), departure.hasExpired(nowMillis))) {
            schedule()
        } else if (isPending) {
            cancel()
        }
    }

    val leadTimeMinutes: Int
        get() = _state.value.leadTimeMinutes

    val isPending: Boolean
        get() = _state.value.pending

    val secondsUntilAlarm: Int
        get() = DepartureAlarmPolicy.secondsUntilAlarm(
            departure.getMeanEstimate(), leadTimeMinutes, timeSource.nowMillis())

    fun setUp(leadTimeMinutes: Int) {
        require(leadTimeMinutes >= 0) {
            "leadTimeMinutes must be non-negative"
        }
        updateState(leadTimeMinutes, true)
        schedule()
    }

    fun cancel() {
        alarmManager?.cancel(alarmIntent())
        updateState(leadTimeMinutes, false)
        Log.d(Constants.TAG, "Alarm cancelled")
    }

    fun notifyAlarmHasBeenHandled() {
        updateState(leadTimeMinutes, false)
    }

    /**
     * Closes this scheduler. When a live feed replaces the Departure instance for
     * the same train, the pending preference must survive long enough for the new
     * scheduler to restore and reschedule it with the updated ETA.
     */
    fun close(preservePending: Boolean = false) {
        if (isPending) {
            alarmManager?.cancel(alarmIntent())
            if (!preservePending) {
                updateState(leadTimeMinutes, false)
            }
        }
    }

    override fun close() {
        close(preservePending = false)
    }

    private fun alarmIntent(): PendingIntent {
        val intent = Intent(applicationContext, AlarmBroadcastReceiver::class.java)
            .setAction(Constants.ACTION_ALARM)
        return PendingIntent.getBroadcast(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmClockTime(): Long = DepartureAlarmPolicy.alarmTime(
        departure.getMeanEstimate(), leadTimeMinutes)

    private fun schedule() {
        val manager = alarmManager
        if (manager == null) {
            Log.w(Constants.TAG,
                "No alarm manager available, so alarm will not be scheduled")
            return
        }

        val alarmTime = alarmClockTime()
        val intent = alarmIntent()
        if (alarmTime < timeSource.nowMillis()) {
            manager.set(AlarmManager.RTC_WAKEUP, alarmTime, intent)
        } else if (!manager.canScheduleExactAlarms()) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, intent)
            Log.w(Constants.TAG,
                "Exact alarm permission is unavailable; using an inexact alarm")
        } else {
            try {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, alarmTime, intent)
            } catch (exception: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, intent)
                Log.w(Constants.TAG,
                    "Exact alarm permission is unavailable; using an inexact alarm")
            }
        }

        val alarmText = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(alarmTime))
        Log.v(Constants.TAG, "Scheduling alarm for $alarmText")
    }

    private fun updateState(leadTimeMinutes: Int, pending: Boolean) {
        _state.value = DepartureAlarmState(leadTimeMinutes, pending)
        preferences.edit {
            putInt(stateKey + LEAD_TIME_SUFFIX, leadTimeMinutes)
            putBoolean(stateKey + PENDING_SUFFIX, pending)
        }
    }

    private fun buildStateKey(departure: Departure): String = buildString {
        append("alarm.")
        appendStation(this, departure.origin)
        appendStation(this, departure.trainDestination)
        append('|').append(departure.line)
        append('|').append(departure.direction)
        append('|').append(departure.platform)
    }

    private fun appendStation(builder: StringBuilder, station: Station?) {
        builder.append('|').append(station?.abbreviation ?: "")
    }
}
