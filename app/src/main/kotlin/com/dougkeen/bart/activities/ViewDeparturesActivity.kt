package com.dougkeen.bart.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.ui.BartRunnerTheme
import com.dougkeen.bart.ui.DeparturesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ViewDeparturesActivity : ComponentActivity() {
    private lateinit var stationPair: StationPair
    private val departuresViewModel: DeparturesViewModel by viewModels()
    private var routeFare by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BartRunnerApplication
        stationPair = if (savedInstanceState?.containsKey(RouteArguments.ORIGIN) == true) {
            RouteArguments.readRoute(savedInstanceState)
        } else {
            RouteArguments.readRoute(intent)
        } ?: run { finish(); return }
        if (stationPair.destination != null) {
            lifecycleScope.launch {
                routeFare = withContext(Dispatchers.IO) {
                    runCatching {
                        app.gtfsStaticData.getFare(
                            stationPair.origin!!,
                            stationPair.destination!!,
                        )
                    }.getOrNull()
                }
            }
        }
        departuresViewModel.setQuery(app.transitRepository, app.bartGtfsNetworkSupplier, stationPair)
        setContent {
            val state by departuresViewModel.uiState.collectAsStateWithLifecycle()
            BartRunnerTheme {
                DeparturesScreen(
                    route = stationPair,
                    state = state,
                    timeSource = app.timeSource,
                    fare = routeFare,
                    onBack = { finish() },
                    onOpenTrip = ::openTripSchedule,
                    onMap = { startActivity(Intent(this, ViewMapActivity::class.java)) },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::stationPair.isInitialized) RouteArguments.putRoute(outState, stationPair)
    }

    private fun openTripSchedule(departure: Departure) {
        val prepared = prepareDepartureForTrip(departure)
        startActivity(Intent(this, TripInProgressActivity::class.java).apply {
            RouteArguments.putTrip(this, stationPair, prepared.identity, RouteArguments.MODE_SCHEDULE)
        })
    }

    private fun prepareDepartureForTrip(departure: Departure): Departure =
        departure.withPassengerDestination(stationPair.destination ?: departure.trainDestination)

}
