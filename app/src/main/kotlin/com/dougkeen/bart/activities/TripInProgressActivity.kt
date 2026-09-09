package com.dougkeen.bart.activities

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.presentation.DepartureTextFormatter
import com.dougkeen.bart.receivers.AlarmBroadcastReceiver
import com.dougkeen.bart.services.BoardedDepartureService
import com.dougkeen.bart.ui.BartRunnerTheme
import com.dougkeen.bart.ui.TripScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TripInProgressActivity : ComponentActivity() {
    companion object {
        private const val POST_NOTIFICATIONS_REQUEST_CODE = 1002
    }

    private val tripProgressViewModel: TripProgressViewModel by viewModels()
    private val tripActionsViewModel: TripActionsViewModel by viewModels()
    private var isFollowing = false
    private var routeDestination: Station? = null
    private var tripRoute: StationPair? = null
    private var tripFare by mutableStateOf<String?>(null)
    private var fareEligible = false
    private var fareLookupKey: String? = null
    private var pendingAlarmLeadTimeMinutes: Int? = null
    private var alarmVisible by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        tripActionsViewModel.refreshAlarmState()
        val pendingLeadTime = pendingAlarmLeadTimeMinutes
        if (pendingLeadTime != null && hasExactAlarmPermission()) {
            pendingAlarmLeadTimeMinutes = null
            enableAlarm(pendingLeadTime)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(AlarmBroadcastReceiver.EXTRA_ALARM_TRIGGERED, false)) {
            alarmVisible = true
            showAlarmWindow()
            NotificationManagerCompat.from(this)
                .cancel(AlarmBroadcastReceiver.ALARM_NOTIFICATION_ID)
        }
        if (intent.getStringExtra(RouteArguments.DEPARTURE_IDENTITY) != null) {
            setIntent(intent)
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BartRunnerApplication
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
        fareEligible = route.destination != null
        tripFare = null
        resolveFare(app, route)
        alarmVisible = intent.getBooleanExtra(
            AlarmBroadcastReceiver.EXTRA_ALARM_TRIGGERED,
            false,
        )
        NotificationManagerCompat.from(this)
            .cancel(com.dougkeen.bart.receivers.AlarmBroadcastReceiver.ALARM_NOTIFICATION_ID)
        if (alarmVisible) {
            showAlarmWindow()
        }
        isFollowing = followed != null && identity == followed.identity
        tripProgressViewModel.setQuery(
            route,
            identity,
            app.timeSource,
            initialDeparture = if (isFollowing) followed else null,
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tripProgressViewModel.departureState.collect { updated ->
                    if (updated != null) {
                        if (isFollowing) tripActionsViewModel.updateFollowedTrip(updated)
                        resolveFare(app, updated.getStationPair())
                    }
                }
            }
        }

        setContent {
            val departure by tripProgressViewModel.departureState.collectAsStateWithLifecycle()
            val tripActionsState by tripActionsViewModel.uiState.collectAsStateWithLifecycle()
            BartRunnerTheme {
                TripScreen(
                    departure = departure,
                    route = tripRoute,
                    fare = tripFare,
                    isFollowingInitially = isFollowing,
                    alarmVisible = alarmVisible,
                    timeSource = app.timeSource,
                    alarmPending = tripActionsState.alarmPending,
                    alarmLeadTimeMinutes = tripActionsState.alarmLeadTimeMinutes,
                    onBack = { finish() },
                    onFollow = { followTrip(it) },
                    onSetAlarm = ::enableAlarm,
                    onCancelAlarm = {
                        tripActionsViewModel.cancelAlarm()
                        stopAlarmTrackingService()
                    },
                    onClear = {
                        tripActionsViewModel.clearTrip()
                        stopAlarmTrackingService()
                        finish()
                    },
                    onShare = ::shareArrival,
                    onSilenceAlarm = ::silenceAlarm,
                )
            }
        }
    }

    private fun silenceAlarm() {
        alarmVisible = false
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showAlarmWindow() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun enableAlarm(leadTimeMinutes: Int) {
        if (!hasExactAlarmPermission()) {
            pendingAlarmLeadTimeMinutes = leadTimeMinutes
            openExactAlarmSettings()
            return
        }
        if (!hasFullScreenIntentPermission()) {
            pendingAlarmLeadTimeMinutes = leadTimeMinutes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                openFullScreenIntentSettings()
            }
            return
        }

        tripActionsViewModel.setAlarm(leadTimeMinutes)
        startAlarmTrackingService()
    }

    private fun hasExactAlarmPermission(): Boolean {
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
            "package:$packageName".toUri(),
        )
        try {
            startActivity(settingsIntent)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:$packageName".toUri(),
                ),
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun openFullScreenIntentSettings() {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            "package:$packageName".toUri(),
        )
        try {
            startActivity(settingsIntent)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:$packageName".toUri(),
                ),
            )
        }
    }

    private fun followTrip(departure: Departure) {
        if (isFollowing) return
        tripActionsViewModel.followTrip(departure, tripRoute?.destination)
        requestNotificationPermissionIfNeeded()
        isFollowing = true
    }

    private fun resolveFare(app: BartRunnerApplication, route: StationPair?) {
        if (!fareEligible || tripFare != null) return
        val origin = route?.origin ?: return
        val destination = route.destination ?: return
        val lookupKey = "${origin.abbreviation}>${destination.abbreviation}"
        if (fareLookupKey == lookupKey) return
        fareLookupKey = lookupKey
        lifecycleScope.launch {
            val fare = withContext(Dispatchers.IO) {
                runCatching { app.gtfsStaticData.getFare(origin, destination) }.getOrNull()
            }
            if (fare != null && fareLookupKey == lookupKey) {
                tripFare = fare
            }
        }
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

    private fun startAlarmTrackingService() {
        startForegroundService(
            Intent(this, BoardedDepartureService::class.java)
                .setAction(BoardedDepartureService.ACTION_START_ALARM_TRACKING),
        )
    }

    private fun stopAlarmTrackingService() {
        stopService(Intent(this, BoardedDepartureService::class.java))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), POST_NOTIFICATIONS_REQUEST_CODE)
        }
    }
}
