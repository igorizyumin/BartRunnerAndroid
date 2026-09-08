package com.dougkeen.bart.activities

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.presentation.DepartureTextFormatter
import com.dougkeen.bart.services.BoardedDepartureService
import com.dougkeen.bart.ui.BartRunnerTheme
import com.dougkeen.bart.ui.TripScreen
import com.dougkeen.util.WakeLocker
import kotlinx.coroutines.launch

class TripInProgressActivity : ComponentActivity() {
    companion object {
        private const val POST_NOTIFICATIONS_REQUEST_CODE = 1002
    }

    private val alarmPendingState = mutableStateOf(false)
    private val alarmLeadTimeState = mutableStateOf(0)
    private lateinit var tripProgressViewModel: TripProgressViewModel
    private lateinit var tripActionsViewModel: TripActionsViewModel
    private var isFollowing = false
    private var routeDestination: Station? = null
    private var tripRoute: StationPair? = null
    private val alarmHandler = Handler(Looper.getMainLooper())
    private var pendingAlarmLeadTimeMinutes: Int? = null

    override fun onResume() {
        super.onResume()
        val pendingLeadTime = pendingAlarmLeadTimeMinutes
        if (pendingLeadTime != null && hasExactAlarmPermission()) {
            pendingAlarmLeadTimeMinutes = null
            enableAlarm(pendingLeadTime)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra(RouteArguments.DEPARTURE_IDENTITY) != null) {
            setIntent(intent)
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BartRunnerApplication
        tripActionsViewModel = ViewModelProvider(this)[TripActionsViewModel::class.java]
        val followed = tripActionsViewModel.getFollowedDeparture()
        var route = RouteArguments.readRoute(intent)
        var identity = RouteArguments.readDepartureIdentity(intent)
        val screenMode = RouteArguments.readScreenMode(intent)
        if (route == null && followed != null && (screenMode == null || screenMode == RouteArguments.MODE_FOLLOWED)) {
            identity = followed.identity
            route = followed.getStationPair()
        }
        if (route == null || identity == null) {
            finish()
            return
        }
        routeDestination = route.destination
        tripRoute = route
        NotificationManagerCompat.from(this)
            .cancel(com.dougkeen.bart.receivers.AlarmBroadcastReceiver.ALARM_NOTIFICATION_ID)
        if (app.alarmController.isRingtoneRequested() || app.alarmController.isSounding()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            soundTheAlarm()
        }
        isFollowing = followed != null && identity == followed.identity
        alarmPendingState.value = tripActionsViewModel.isAlarmPending()
        alarmLeadTimeState.value = tripActionsViewModel.getAlarmLeadTimeMinutes()
        tripProgressViewModel = ViewModelProvider(this)[TripProgressViewModel::class.java]
        tripProgressViewModel.setQuery(
            route,
            identity,
            app.timeSource,
            initialDeparture = if (isFollowing) followed else null,
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tripProgressViewModel.departureState.collect { updated ->
                    if (updated != null && isFollowing) tripActionsViewModel.updateFollowedTrip(updated)
                }
            }
        }

        setContent {
            val departure by tripProgressViewModel.departureState.collectAsState()
            val alarmPending by alarmPendingState
            val alarmLeadTime by alarmLeadTimeState
            val alarmState by app.alarmController.state.collectAsState()
            BartRunnerTheme {
                TripScreen(
                    departure = departure,
                    route = tripRoute,
                    isFollowingInitially = isFollowing,
                    alarmVisible = alarmState.sounding || alarmState.ringtoneRequested,
                    timeSource = app.timeSource,
                    alarmPending = alarmPending,
                    alarmLeadTimeMinutes = alarmLeadTime,
                    onBack = { finish() },
                    onFollow = { followTrip(it) },
                    onSetAlarm = ::enableAlarm,
                    onCancelAlarm = {
                        sendServiceAction(tripActionsViewModel.cancelAlarm())
                        alarmPendingState.value = false
                    },
                    onClear = {
                        sendServiceAction(tripActionsViewModel.clearTrip())
                        finish()
                    },
                    onShare = ::shareArrival,
                    onSilenceAlarm = ::silenceAlarm,
                )
            }
        }
    }

    private fun soundTheAlarm() {
        val app = application as BartRunnerApplication
        if (app.alarmController.getMediaPlayer() == null) {
            val alarmUris = listOf(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            )
            alarmUris.firstOrNull { it != null && tryToPlayRingtone(it) }
        }
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 1),
        )
        alarmHandler.removeCallbacksAndMessages(null)
        alarmHandler.postDelayed(::silenceAlarm, 20_000L)
        app.alarmController.consumeRingtoneRequest()
        app.alarmController.setSounding(true)
    }

    private fun tryToPlayRingtone(uri: Uri): Boolean {
        val player = MediaPlayer.create(this, uri) ?: return false
        player.isLooping = true
        player.start()
        (application as BartRunnerApplication).alarmController.setMediaPlayer(player)
        return true
    }

    private fun silenceAlarm() {
        (application as BartRunnerApplication).alarmController.silence()
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WakeLocker.release()
    }

    private fun enableAlarm(leadTimeMinutes: Int) {
        if (!hasExactAlarmPermission()) {
            pendingAlarmLeadTimeMinutes = leadTimeMinutes
            openExactAlarmSettings()
            return
        }
        if (!hasFullScreenIntentPermission()) {
            pendingAlarmLeadTimeMinutes = leadTimeMinutes
            openFullScreenIntentSettings()
            return
        }

        tripActionsViewModel.setAlarm(leadTimeMinutes)
        alarmLeadTimeState.value = leadTimeMinutes
        alarmPendingState.value = true
        sendServiceAction(BoardedDepartureService.ACTION_REFRESH_DEPARTURE)
    }

    private fun hasExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    private fun hasFullScreenIntentPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        return getSystemService(android.app.NotificationManager::class.java)
            ?.canUseFullScreenIntent() == true
    }

    private fun openExactAlarmSettings() {
        val settingsIntent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(settingsIntent)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun openFullScreenIntentSettings() {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(settingsIntent)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    override fun onStop() {
        super.onStop()
        WakeLocker.release()
    }

    override fun onDestroy() {
        alarmHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun followTrip(departure: Departure) {
        if (isFollowing) return
        val action = tripActionsViewModel.followTrip(departure, tripRoute?.destination)
        requestNotificationPermissionIfNeeded()
        sendServiceAction(action)
        isFollowing = true
    }

    private fun shareArrival(departure: Departure) {
        val destination = departure.getStationPair()?.destination
            ?: routeDestination
            ?: departure.trainDestination
            ?: return
        val message = getString(
            R.string.arrival_message,
            destination.getName(),
            DepartureTextFormatter.estimatedArrivalTime(this, departure, false),
        )
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_trip_subject))
            putExtra(Intent.EXTRA_TEXT, message)
        }, getString(R.string.share_arrival_time)))
    }

    private fun sendServiceAction(action: String) {
        startForegroundService(Intent(this, BoardedDepartureService::class.java).setAction(action))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), POST_NOTIFICATIONS_REQUEST_CODE)
        }
    }
}
