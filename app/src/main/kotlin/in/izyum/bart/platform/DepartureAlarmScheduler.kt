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
import `in`.izyum.bart.model.Constants
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.SystemTimeSource
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.receivers.AlarmBroadcastReceiver
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
        const val TRACKING_SUFFIX = ".tracking"
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
                departure.hasInitialDeparturePassed(nowMillis, pessimistic = true),
                departure.hasExpired(nowMillis))) {
            schedule()
        } else if (isPending) {
            cancel()
        }
    }

    val leadTimeMinutes: Int
        get() = _state.value.leadTimeMinutes

    val isPending: Boolean
        get() = _state.value.pending

    val isTracking: Boolean
        get() = preferences.getBoolean(stateKey + TRACKING_SUFFIX, false)

    val secondsUntilAlarm: Int
        get() = DepartureAlarmPolicy.secondsUntilAlarm(
            departure, leadTimeMinutes, timeSource.nowMillis())

    fun setUp(leadTimeMinutes: Int) {
        require(leadTimeMinutes >= 0) {
            "leadTimeMinutes must be non-negative"
        }
        updateState(leadTimeMinutes, true)
        startTracking()
        schedule()
    }

    fun startTracking() {
        preferences.edit { putBoolean(stateKey + TRACKING_SUFFIX, true) }
    }

    fun cancel() {
        alarmManager?.cancel(alarmIntent())
        updateState(leadTimeMinutes, false)
        Log.d(Constants.TAG, "Alarm cancelled")
    }

    fun stopTracking() {
        preferences.edit { putBoolean(stateKey + TRACKING_SUFFIX, false) }
    }

    fun notifyAlarmHasBeenHandled() {
        alarmManager?.cancel(alarmIntent())
        updateState(leadTimeMinutes, false)
        preferences.edit { putBoolean(stateKey + TRACKING_SUFFIX, true) }
    }

    fun rescheduleIfPending() {
        if (isPending) schedule()
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
            .setAction(Constants.ACTION_ALARM)
        return PendingIntent.getBroadcast(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmClockTime(): Long = DepartureAlarmPolicy.alarmTime(
        departure, leadTimeMinutes)

    private fun schedule() {
        val manager = alarmManager
        if (manager == null) {
            Log.w(Constants.TAG,
                "No alarm manager available, so alarm will not be scheduled")
            return
        }

        val alarmTime = alarmClockTime()
        val intent = alarmIntent()
        if (!ExactAlarmPermission.isGranted(applicationContext)) {
            Log.w(Constants.TAG, "Exact alarm permission is unavailable")
            return
        }
        try {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(alarmTime, showIntent()),
                intent,
            )
        } catch (exception: SecurityException) {
            Log.w(Constants.TAG, "Could not schedule departure alarm", exception)
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

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        1242,
        Intent(applicationContext, RoutesListActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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
