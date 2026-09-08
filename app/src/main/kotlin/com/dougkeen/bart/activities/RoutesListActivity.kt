package com.dougkeen.bart.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.ui.BartRunnerTheme
import com.dougkeen.bart.ui.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RoutesListActivity : ComponentActivity() {
    private val routesViewModel: RoutesViewModel by viewModels()

    fun addFavorite(route: com.dougkeen.bart.model.StationPair) {
        routesViewModel.addFavorite(route)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as BartRunnerApplication
        lifecycleScope.launch(Dispatchers.IO) {
            application.transitRepository.refreshIfStale()
        }
        setContent {
            val state by routesViewModel.uiState.collectAsStateWithLifecycle()
            val followedTripState by application.followedTripRepository.state.collectAsStateWithLifecycle()
            val followedTrip = if (followedTripState.departure != null) {
                application.followedTripRepository.getFollowedDeparture()
            } else {
                null
            }
            BartRunnerTheme {
                HomeScreen(
                    state = state,
                    followedTrip = followedTrip,
                    timeSource = application.timeSource,
                    onRouteSelected = { route ->
                        startActivity(Intent(this, ViewDeparturesActivity::class.java).apply {
                            RouteArguments.putRoute(this, route)
                        })
                    },
                    onAddFavorite = routesViewModel::addFavorite,
                    onRemoveFavorite = routesViewModel::removeFavorite,
                    onMoveFavorite = routesViewModel::moveFavorite,
                    onInsertFavorite = routesViewModel::insertFavorite,
                    onViewTrip = { departure ->
                        startActivity(Intent(this, TripInProgressActivity::class.java).apply {
                            RouteArguments.putTrip(
                                this,
                                departure.getStationPair(),
                                departure.identity,
                                RouteArguments.MODE_FOLLOWED,
                            )
                        })
                    },
                    onViewMap = { startActivity(Intent(this, ViewMapActivity::class.java)) },
                )
            }
        }
    }
}
