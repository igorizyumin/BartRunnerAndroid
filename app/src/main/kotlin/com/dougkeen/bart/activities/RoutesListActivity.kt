package com.dougkeen.bart.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.ui.BartRunnerTheme
import com.dougkeen.bart.ui.HomeScreen

class RoutesListActivity : AbstractViewActivity() {
    fun addFavorite(route: com.dougkeen.bart.model.StationPair) {
        ViewModelProvider(this)[RoutesViewModel::class.java].addFavorite(route)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as BartRunnerApplication
        val routesViewModel = ViewModelProvider(this)[RoutesViewModel::class.java]
        setContent {
            val state by routesViewModel.uiState.collectAsState()
            val followedTripState by application.followedTripRepository.state.collectAsState()
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
