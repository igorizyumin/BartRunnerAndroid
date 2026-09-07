package com.dougkeen.bart.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R
import com.dougkeen.bart.backend.AlertProjection
import com.dougkeen.bart.backend.RouteDepartureProjection
import com.dougkeen.bart.data.FavoritesRepository
import com.dougkeen.bart.model.Alert
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.networktasks.GtfsStaticData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Collections
import java.util.TimeZone

/** Owns all state and coordination for the favorite-routes screen. */
class RoutesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BartRunnerApplication
    private val favoritesRepository: FavoritesRepository = app.favoritesRepository
    private val transitRepository = app.transitRepository
    private val timeSource: TimeSource = app.timeSource
    private val routeJobs = mutableMapOf<StationPair, Job>()
    private val _uiState = MutableStateFlow(RoutesUiState())

    val uiState: StateFlow<RoutesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            favoritesRepository.uiState.collectLatest { favoritesState ->
                val favorites = immutableList(favoritesState.favorites)
                _uiState.update {
                    it.copy(
                        favorites = favorites,
                        isLoading = favoritesState.isLoading,
                    )
                }
                syncRouteJobs(favorites)
                if (!favoritesState.isLoading) {
                    refreshFares(favorites)
                }
            }
        }
        viewModelScope.launch {
            transitRepository.projectedState(AlertProjection()).collectLatest { state ->
                state.error?.let { exception ->
                    publishError(exception)
                } ?: state.value?.let { alerts ->
                    publishAlerts(alerts)
                }
            }
        }
    }

    fun addFavorite(favorite: StationPair) = favoritesRepository.addFavorite(favorite)

    fun removeFavorite(favorite: StationPair) = favoritesRepository.removeFavorite(favorite)

    fun moveFavorite(from: Int, to: Int) = favoritesRepository.moveFavorite(from, to)

    fun insertFavorite(favorite: StationPair, index: Int) =
        favoritesRepository.insertFavorite(favorite, index)

    fun updateFare(favorite: StationPair, fare: String, updatedAt: Long) =
        favoritesRepository.updateFare(favorite, fare, updatedAt)

    private fun syncRouteJobs(favorites: List<StationPair>) {
        val desired = favorites.toSet()
        routeJobs.keys.toList()
            .filter { it !in desired }
            .forEach { route ->
                routeJobs.remove(route)?.cancel()
                updateFirstDeparture(route, null)
            }

        favorites.forEach { route ->
            if (route !in routeJobs) {
                routeJobs[route] = viewModelScope.launch {
                    transitRepository.projectedState(
                        RouteDepartureProjection(route, app),
                    ).collectLatest { state ->
                        state.error?.let { exception ->
                            publishError(exception)
                        } ?: state.value?.let { departures ->
                            val firstDeparture = departures.getDepartures()
                                .firstOrNull { !it.hasDeparted(timeSource) }
                            updateFirstDeparture(route, firstDeparture)
                            clearError()
                        }
                    }
                }
            }
        }
    }

    private fun updateFirstDeparture(route: StationPair, departure: Departure?) {
        _uiState.update { current ->
            val updated = current.firstDepartures.toMutableMap()
            if (departure == null) {
                updated.remove(route)
            } else {
                updated[route] = departure
            }
            current.copy(firstDepartures = immutableMap(updated))
        }
    }

    private fun publishAlerts(alerts: Alert.AlertList) {
        val alertMessage: String?
        val alertKind: RoutesUiState.AlertKind
        if (alerts.hasAlerts()) {
            val text = StringBuilder()
            alerts.getAlerts().forEachIndexed { index, alert ->
                if (index > 0) text.append("\n\n")
                if (!alert.postedTime.isNullOrEmpty()) {
                    text.append(alert.postedTime).append("\n")
                }
                text.append(alert.description.orEmpty())
            }
            alertMessage = text.toString()
            alertKind = RoutesUiState.AlertKind.WARNING
        } else if (alerts.areNoDelaysReported()) {
            alertMessage = getApplication<Application>().getString(R.string.no_delays_reported)
            alertKind = RoutesUiState.AlertKind.NO_DELAYS
        } else {
            alertMessage = null
            alertKind = RoutesUiState.AlertKind.HIDDEN
        }
        _uiState.update {
            it.copy(
                alerts = alerts,
                alertMessage = alertMessage,
                alertKind = alertKind,
                error = null,
            )
        }
    }

    private fun publishError(exception: Exception) {
        _uiState.update { it.copy(error = exception) }
    }

    private fun clearError() {
        _uiState.update { current ->
            if (current.error == null) current else current.copy(error = null)
        }
    }

    private fun refreshFares(favorites: List<StationPair>) {
        val routesNeedingFares = favorites.filter { needsFareRefresh(it) }
        if (routesNeedingFares.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val staticData = GtfsStaticData.get(app)
                val now = timeSource.nowMillis()
                routesNeedingFares.forEach { route ->
                    val origin = route.origin ?: return@forEach
                    val destination = route.destination ?: return@forEach
                    staticData.getFare(origin, destination)?.let { fare ->
                        updateFare(route, fare, now)
                    }
                }
            } catch (exception: Exception) {
                publishError(exception)
            }
        }
    }

    private fun needsFareRefresh(route: StationPair): Boolean {
        if (route.destination == null) return false
        val timeZone = TimeZone.getTimeZone("America/Los_Angeles")
        val now = Calendar.getInstance(timeZone).apply {
            timeInMillis = timeSource.nowMillis()
        }
        val lastUpdate = Calendar.getInstance(timeZone).apply {
            timeInMillis = route.fareLastUpdated
        }
        return now.get(Calendar.DAY_OF_YEAR) != lastUpdate.get(Calendar.DAY_OF_YEAR)
            || now.get(Calendar.YEAR) != lastUpdate.get(Calendar.YEAR)
    }

    private fun immutableList(values: List<StationPair>): List<StationPair> =
        Collections.unmodifiableList(ArrayList(values))

    private fun immutableMap(values: Map<StationPair, Departure>): Map<StationPair, Departure> =
        Collections.unmodifiableMap(HashMap(values))
}
