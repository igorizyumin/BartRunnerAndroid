package com.dougkeen.bart.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.services.BoardedDepartureService
import com.dougkeen.bart.ui.BartRunnerTheme
import com.dougkeen.bart.ui.DeparturesScreen

class ViewDeparturesActivity : ComponentActivity() {
    companion object {
        private const val POST_NOTIFICATIONS_REQUEST_CODE = 1001
    }

    private lateinit var stationPair: StationPair
    private lateinit var departuresViewModel: DeparturesViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BartRunnerApplication
        stationPair = if (savedInstanceState?.containsKey(RouteArguments.ORIGIN) == true) {
            RouteArguments.readRoute(savedInstanceState)
        } else {
            RouteArguments.readRoute(intent)
        } ?: run { finish(); return }
        departuresViewModel = ViewModelProvider(this)[DeparturesViewModel::class.java]
        departuresViewModel.setQuery(app.transitRepository, app.bartGtfsNetworkSupplier, stationPair)
        setContent {
            val state by departuresViewModel.uiState.collectAsState()
            BartRunnerTheme {
                DeparturesScreen(
                    route = stationPair,
                    state = state,
                    timeSource = app.timeSource,
                    onBack = { finish() },
                    onOpenTrip = ::openTripSchedule,
                    onFollowTrip = { followDeparture(it, true) },
                    onMap = { startActivity(Intent(this, ViewMapActivity::class.java)) },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::stationPair.isInitialized) RouteArguments.putRoute(outState, stationPair)
    }

    private fun followDeparture(departure: Departure, openTripScreen: Boolean) {
        val prepared = prepareDepartureForTrip(departure)
        val actions = ViewModelProvider(this)[TripActionsViewModel::class.java]
        val action = actions.followTrip(prepared)
        requestNotificationPermissionIfNeeded()
        startForegroundService(Intent(this, BoardedDepartureService::class.java).setAction(action))
        if (openTripScreen) {
            startActivity(Intent(this, TripInProgressActivity::class.java).apply {
                RouteArguments.putTrip(this, prepared.getStationPair(), prepared.identity, RouteArguments.MODE_FOLLOWED)
            })
        }
    }

    private fun openTripSchedule(departure: Departure) {
        val prepared = prepareDepartureForTrip(departure)
        startActivity(Intent(this, TripInProgressActivity::class.java).apply {
            RouteArguments.putTrip(this, stationPair, prepared.identity, RouteArguments.MODE_SCHEDULE)
        })
    }

    private fun prepareDepartureForTrip(departure: Departure): Departure =
        departure.withPassengerDestination(stationPair.destination ?: departure.trainDestination)

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), POST_NOTIFICATIONS_REQUEST_CODE)
        }
    }
}
