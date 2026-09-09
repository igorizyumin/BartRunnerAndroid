package com.dougkeen.bart.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R
import com.dougkeen.bart.performance.PerformanceTrace
import com.dougkeen.bart.ui.BartRunnerTheme
import com.dougkeen.bart.ui.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoutesListActivity : ComponentActivity() {
    private val routesViewModel: RoutesViewModel by viewModels()
    private var staticDataReady by mutableStateOf(false)

    fun addFavorite(route: com.dougkeen.bart.model.StationPair) {
        routesViewModel.addFavorite(route)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as BartRunnerApplication
        val needsInitialStaticLoad = !application.gtfsStaticData.hasDatabaseCache()
        staticDataReady = !needsInitialStaticLoad
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                PerformanceTrace.section("BART static data warm-up") {
                    application.gtfsStaticData.warmUp()
                }
            }
            if (needsInitialStaticLoad) {
                withContext(Dispatchers.Main.immediate) {
                    staticDataReady = true
                }
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            PerformanceTrace.section("BART startup refresh") {
                application.transitRepository.refreshIfStale()
            }
        }
        setContent {
            BartRunnerTheme {
                if (!staticDataReady) {
                    StaticDataSplash()
                } else {
                    val state by routesViewModel.uiState.collectAsStateWithLifecycle()
                    val firstContentReported = remember { mutableStateOf(false) }
                    if (!state.isLoading && !firstContentReported.value) {
                        SideEffect {
                            if (!firstContentReported.value) {
                                firstContentReported.value = true
                                PerformanceTrace.instant("BART first route state")
                                reportFullyDrawn()
                            }
                        }
                    }
                    val followedTripState by application.followedTripRepository.state.collectAsStateWithLifecycle()
                    val followedTrip = if (followedTripState.departure != null) {
                        application.followedTripRepository.getFollowedDeparture()
                    } else {
                        null
                    }
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

    @Composable
    private fun StaticDataSplash() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.static_data_loading),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
