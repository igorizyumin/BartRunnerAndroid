package `in`.izyum.bart.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.backend.RouteDepartureProjection
import `in`.izyum.bart.backend.TransitRepository
import `in`.izyum.bart.backend.TripProgressProjection
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.model.TripLeg
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
    private var queryStationPair: StationPair? = null

    fun setQuery(
        stationPair: StationPair,
        departureIdentity: String,
        timeSource: TimeSource,
        initialDeparture: Departure? = null,
    ) {
        cancelCollections()
        departureTimeSource = timeSource
        queryStationPair = stationPair
        _departureState.value = initialDeparture

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
        val normalizedIncoming = queryStationPair?.destination?.let {
            incoming.withPassengerDestination(it)
        } ?: incoming
        val current = departureState.value
        val updated = current?.let {
            Departure.merge(it, normalizedIncoming, true, timeSource)
        } ?: normalizedIncoming
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
