package com.dougkeen.bart.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.backend.RouteDepartureProjection
import com.dougkeen.bart.backend.TransitRepository
import com.dougkeen.bart.backend.TripProgressProjection
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.model.TripLeg
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Owns live trip state from the shared feed until the screen ViewModel clears. */
class TripProgressViewModel(application: Application) :
    AndroidViewModel(application) {

    private val _departureState = MutableStateFlow<Departure?>(null)
    val departureState: StateFlow<Departure?> = _departureState.asStateFlow()

    private var routeCollectionJob: Job? = null
    private var tripProgressCollectionJob: Job? = null
    private var departureTimeSource: TimeSource? = null

    fun setQuery(
        stationPair: StationPair,
        departureIdentity: String,
        timeSource: TimeSource,
    ) {
        cancelCollections()
        departureTimeSource = timeSource
        _departureState.value = null

        val application = getApplication<BartRunnerApplication>()
        val repository: TransitRepository = application.transitRepository

        routeCollectionJob = viewModelScope.launch {
            val projection = RouteDepartureProjection(
                stationPair,
                application.bartGtfsNetworkSupplier,
            )
            repository.projectedState(projection::project, projection::areEquivalent)
                .collectLatest { result ->
                result.getOrNull()?.let { departures ->
                    departures.getDepartures()
                        .firstOrNull { it.identity == departureIdentity }
                        ?.let(::updateFromRealtime)
                }
            }
        }
    }

    fun getDeparture(): Departure? = departureState.value

    private fun updateFromRealtime(incoming: Departure) {
        val timeSource = departureTimeSource ?: return
        val current = departureState.value
        val updated = current?.let {
            Departure.merge(it, incoming, true, timeSource)
        } ?: incoming
        publish(updated)
        startTripProgress(updated)
    }

    private fun startTripProgress(departure: Departure) {
        if (tripProgressCollectionJob?.isActive == true
            || departure.tripLegs.isEmpty()
        ) {
            return
        }
        val stationPair = departure.getStationPair() ?: return
        val origin = stationPair.origin ?: return
        val destination = stationPair.destination ?: return
        val application = getApplication<BartRunnerApplication>()
        tripProgressCollectionJob = viewModelScope.launch {
            val projection = TripProgressProjection(
                origin,
                destination,
                departure.tripLegs,
                application.bartGtfsNetworkSupplier,
            )
            application.transitRepository
                .projectedState(projection::project)
                .collectLatest { result -> result.getOrNull()?.let(::updateTripLegs) }
        }
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
