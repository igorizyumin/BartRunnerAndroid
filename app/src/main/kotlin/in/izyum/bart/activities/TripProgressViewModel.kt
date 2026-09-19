package `in`.izyum.bart.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.backend.RouteDepartureProjection
import `in`.izyum.bart.backend.TransitRepository
import `in`.izyum.bart.backend.TripProgressProjection
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Itinerary
import `in`.izyum.bart.model.StationPair
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

enum class TripResolutionState {
    LOADING,
    READY,
    NOT_FOUND,
}

/** Owns live trip state from the shared feed until the screen ViewModel clears. */
class TripProgressViewModel(application: Application) :
    AndroidViewModel(application) {

    private val _itineraryState = MutableStateFlow<Itinerary?>(null)
    val itineraryState: StateFlow<Itinerary?> = _itineraryState.asStateFlow()

    private val _resolutionState = MutableStateFlow(TripResolutionState.LOADING)
    val resolutionState: StateFlow<TripResolutionState> = _resolutionState.asStateFlow()

    private var routeCollectionJob: Job? = null
    private var tripProgressCollectionJob: Job? = null
    fun setQuery(
        stationPair: StationPair,
        selectionIdentity: String,
        initialItinerary: Itinerary? = null,
    ) {
        cancelCollections()
        _itineraryState.value = initialItinerary
        _resolutionState.value = if (initialItinerary == null) {
            TripResolutionState.LOADING
        } else {
            TripResolutionState.READY
        }

        val application = getApplication<BartRunnerApplication>()
        val repository: TransitRepository = application.transitRepository

        initialItinerary?.let(::startTripProgress)
        if (initialItinerary == null) {
            routeCollectionJob = viewModelScope.launch {
                val projection = RouteDepartureProjection(
                    stationPair,
                    application.bartGtfsNetworkSupplier,
                )
                val itinerary = repository
                    .projectedState(projection::project, projection::areEquivalent)
                    .mapNotNull { result -> result.getOrNull() }
                    .map { departures ->
                        departures.getDepartures()
                            .firstOrNull {
                                it.matchesSelectionIdentity(stationPair, selectionIdentity)
                            }
                            ?.let { incoming -> itineraryFromDeparture(incoming, stationPair) }
                    }
                    .first()
                if (itinerary == null) {
                    _resolutionState.value = TripResolutionState.NOT_FOUND
                } else {
                    publish(itinerary)
                    _resolutionState.value = TripResolutionState.READY
                    startTripProgress(itinerary)
                }
            }
        }
    }

    fun getItinerary(): Itinerary? = itineraryState.value

    private fun startTripProgress(itinerary: Itinerary) {
        if (tripProgressCollectionJob?.isActive == true
            || itinerary.legs.isEmpty()
        ) {
            return
        }
        val application = getApplication<BartRunnerApplication>()
        tripProgressCollectionJob = viewModelScope.launch {
            val projection = TripProgressProjection(
                itinerary.origin,
                itinerary.destination,
                itinerary.legs,
                application.bartGtfsNetworkSupplier,
            )
            application.transitRepository
                .projectedState(projection::projectItinerary)
                .collectLatest { result -> result.getOrNull()?.let(::publish) }
        }
    }

    private fun publish(replacement: Itinerary) {
        _itineraryState.value = replacement
    }

    private fun itineraryFromDeparture(
        departure: Departure,
        stationPair: StationPair,
    ): Itinerary? = Itinerary.fromDeparture(
        if (stationPair.destination != null) {
            departure.withPassengerDestination(stationPair.destination)
        } else {
            departure
        },
    )

    private fun Departure.matchesSelectionIdentity(
        stationPair: StationPair,
        selectionIdentity: String,
    ): Boolean {
        val firstLeg = tripLegs.firstOrNull()
        if (firstLeg?.tripIdentity?.toString() == selectionIdentity) return true

        val origin = origin ?: return false
        val destination = stationPair.destination ?: trainDestination ?: return false
        return selectionIdentity ==
            "itinerary|${origin.abbreviation}|${destination.abbreviation}|${firstLeg?.departureTime ?: 0L}"
    }

    private fun cancelCollections() {
        routeCollectionJob?.cancel()
        routeCollectionJob = null
        tripProgressCollectionJob?.cancel()
        tripProgressCollectionJob = null
    }
}
