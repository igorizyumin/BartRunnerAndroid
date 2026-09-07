package com.dougkeen.bart.activities

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.backend.RouteDepartureProjection
import com.dougkeen.bart.backend.TransitRepository
import com.dougkeen.bart.backend.TripProgressProjection
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.model.TripLeg
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Owns live trip state from the shared feed until the screen ViewModel clears. */
class TripProgressViewModel(application: BartRunnerApplication) :
    AndroidViewModel(application) {

    private val _departureState = MutableStateFlow<Departure?>(null)
    val departureState: StateFlow<Departure?> = _departureState.asStateFlow()

    private var routeCollectionJob: Job? = null
    private var tripProgressCollectionJob: Job? = null
    private var departureTimeSource: TimeSource? = null

    fun setDeparture(initialDeparture: Departure?, timeSource: TimeSource) {
        cancelCollections()
        departureTimeSource = timeSource
        _departureState.value = initialDeparture

        val stationPair = initialDeparture?.getStationPair() ?: return
        val application = getApplication<BartRunnerApplication>()
        val repository: TransitRepository = application.transitRepository

        routeCollectionJob = viewModelScope.launch {
            repository.projectedState(
                RouteDepartureProjection(stationPair, application),
            ).collectLatest { projectionState ->
                projectionState.value?.let { result ->
                    updateFromRealtime(result.getDepartures())
                }
            }
        }

        val origin = stationPair.origin
        val destination = stationPair.destination
        if (initialDeparture.tripLegs.isNotEmpty()
            && origin != null
            && destination != null
        ) {
            tripProgressCollectionJob = viewModelScope.launch {
                repository.projectedState(
                    TripProgressProjection(
                        application,
                        origin,
                        destination,
                        initialDeparture.tripLegs,
                    ),
                ).collectLatest { projectionState ->
                    projectionState.value?.let(::updateTripLegs)
                }
            }
        }
    }

    fun getDeparture(): Departure? = departureState.value

    private fun updateFromRealtime(departures: List<Departure>) {
        val current = departureState.value ?: return
        val timeSource = departureTimeSource ?: return
        val candidate = departures.firstOrNull { it.identity == current.identity }
            ?: return
        publish(
            Departure.merge(
                current,
                candidate,
                true,
                timeSource,
            ),
        )
    }

    private fun updateTripLegs(updatedLegs: List<TripLeg>) {
        departureState.value?.let { publish(it.replaceTripLegs(updatedLegs)) }
    }

    private fun publish(replacement: Departure) {
        _departureState.value = replacement
    }

    private fun cancelCollections() {
        routeCollectionJob?.cancel()
        routeCollectionJob = null
        tripProgressCollectionJob?.cancel()
        tripProgressCollectionJob = null
    }
}
