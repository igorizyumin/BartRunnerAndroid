package `in`.izyum.bart.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.backend.AlertProjection
import `in`.izyum.bart.backend.RouteDepartureProjection
import `in`.izyum.bart.data.FareDiscountPreferences
import `in`.izyum.bart.data.FavoritesRepository
import `in`.izyum.bart.model.Alert
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.networktasks.ElevatorStatusClient
import `in`.izyum.bart.performance.PerformanceTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections

/** Owns all state and coordination for the favorite-routes screen. */
class RoutesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BartRunnerApplication
    private val favoritesRepository: FavoritesRepository = app.favoritesRepository
    private val transitRepository = app.transitRepository
    private val timeSource: TimeSource = app.timeSource
    private val routeJobs = mutableMapOf<StationPair, Job>()
    private var fareJob: Job? = null
    private var elevatorJob: Job? = null
    private val elevatorStatusClient = ElevatorStatusClient()
    private var riderCategoryId: String? = FareDiscountPreferences.getRiderCategoryId(application)
    private val _uiState = MutableStateFlow(RoutesUiState())

    val uiState: StateFlow<RoutesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            favoritesRepository.uiState.collectLatest { favoritesState ->
                val favorites = immutableList(favoritesState.favorites)
                fareJob?.cancel()
                _uiState.update {
                    it.copy(
                        favorites = favorites,
                        fares = it.fares.filterKeys(favorites.toSet()::contains),
                        isLoading = favoritesState.isLoading,
                    )
                }
                syncRouteJobs(favorites)
                if (!favoritesState.isLoading) {
                    loadFares(favorites)
                }
            }
        }
        viewModelScope.launch {
            app.offlineStatusController.isOffline.collectLatest { isOffline ->
                _uiState.update { current ->
                    current.copy(
                        isOffline = isOffline,
                        alertKind = if (isOffline) {
                            RoutesUiState.AlertKind.WARNING
                        } else {
                            alertKindFor(current.alerts)
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            val projection = AlertProjection()
            transitRepository.projectedState(
                projection::project,
                projection::areEquivalent,
            ).collectLatest { state ->
                state.exceptionOrNull()?.let { exception ->
                    publishError(asException(exception))
                } ?: state.getOrNull()?.let { alerts ->
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

    fun setRiderCategoryId(riderCategoryId: String?) {
        this.riderCategoryId = riderCategoryId
        FareDiscountPreferences.setRiderCategoryId(getApplication(), riderCategoryId)
        if (!_uiState.value.isLoading) {
            loadFares(_uiState.value.favorites)
        }
    }

    fun loadElevatorStatus() {
        elevatorJob?.cancel()
        _uiState.update {
            it.copy(
                elevatorDescription = null,
                elevatorIsLoading = true,
                elevatorError = null,
            )
        }
        elevatorJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val description = elevatorStatusClient.fetchDescription()
                _uiState.update {
                    it.copy(
                        elevatorDescription = description,
                        elevatorIsLoading = false,
                        elevatorError = null,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        elevatorDescription = null,
                        elevatorIsLoading = false,
                        elevatorError = exception,
                    )
                }
            }
        }
    }

    private fun syncRouteJobs(favorites: List<StationPair>) {
        PerformanceTrace.counter("BART favorite count", favorites.size)
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
                    val projection = RouteDepartureProjection(
                        route,
                        app.bartGtfsNetworkSupplier,
                    )
                    transitRepository.projectedState(
                        projection::project,
                        projection::areEquivalent,
                    ).collectLatest { state ->
                        state.exceptionOrNull()?.let { exception ->
                            publishError(asException(exception))
                        } ?: state.getOrNull()?.let { departures ->
                            val firstDeparture = departures.getDepartures()
                                .firstOrNull { !it.isCanceled() && !it.hasDeparted(timeSource) }
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
        val alertKind = if (_uiState.value.isOffline) {
            RoutesUiState.AlertKind.WARNING
        } else {
            alertKindFor(alerts)
        }
        _uiState.update {
            it.copy(
                alerts = alerts,
                alertKind = alertKind,
                error = null,
            )
        }
    }

    private fun alertKindFor(alerts: Alert.AlertList?): RoutesUiState.AlertKind = when {
        alerts == null -> RoutesUiState.AlertKind.HIDDEN
        alerts.hasAlerts() -> RoutesUiState.AlertKind.WARNING
        alerts.areNoDelaysReported() -> RoutesUiState.AlertKind.NO_DELAYS
        else -> RoutesUiState.AlertKind.HIDDEN
    }

    private fun publishError(exception: Exception) {
        _uiState.update { it.copy(error = exception) }
    }

    private fun clearError() {
        _uiState.update { current ->
            if (current.error == null) current else current.copy(error = null)
        }
    }

    private fun loadFares(favorites: List<StationPair>) {
        fareJob?.cancel()
        fareJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val staticData = app.gtfsStaticData
                val fares = favorites.mapNotNull { route ->
                    val origin = route.origin ?: return@mapNotNull null
                    val destination = route.destination ?: return@mapNotNull null
                    staticData.getFare(origin, destination, riderCategoryId)
                        ?.let { fare -> route to fare }
                }.toMap()
                _uiState.update { current ->
                    val currentFavorites = current.favorites.toSet()
                    current.copy(fares = immutableMap(fares.filterKeys { it in currentFavorites }))
                }
            } catch (exception: Exception) {
                publishError(exception)
            }
        }
        PerformanceTrace.counter("BART projection job count", routeJobs.size)
    }

    private fun immutableList(values: List<StationPair>): List<StationPair> =
        Collections.unmodifiableList(ArrayList(values))

    private fun <T> immutableMap(values: Map<StationPair, T>): Map<StationPair, T> =
        Collections.unmodifiableMap(HashMap(values))

    private fun asException(error: Throwable): Exception =
        error as? Exception ?: RuntimeException(error)
}
