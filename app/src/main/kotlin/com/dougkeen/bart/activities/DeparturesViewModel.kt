package com.dougkeen.bart.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dougkeen.bart.backend.RouteDepartureProjection
import com.dougkeen.bart.backend.TransitRepository
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.model.SystemTimeSource
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.ArrayList
import java.util.Collections
import java.util.function.Supplier

/** Owns departures-screen state and its shared-feed projection. */
class DeparturesViewModel @JvmOverloads constructor(
    private val timeSource: TimeSource = SystemTimeSource,
) : ViewModel() {
    enum class Status {
        LOADING,
        CONTENT,
        EMPTY,
        ERROR,
    }

    data class State(
        val status: Status,
        val departures: List<Departure>,
        val error: Exception?,
    ) {
        companion object {
            fun loading() = State(Status.LOADING, emptyList(), null)
            fun content(departures: List<Departure>) =
                State(Status.CONTENT, departures, null)
            fun empty() = State(Status.EMPTY, emptyList(), null)
            fun error(exception: Exception, departures: List<Departure>) =
                State(Status.ERROR, departures, exception)
        }
    }

    private val _uiState = MutableStateFlow(State.loading())
    val uiState: StateFlow<State> = _uiState.asStateFlow()

    private var departures: List<Departure> = emptyList()
    private var collectionJob: Job? = null

    /** Configures the query; the ViewModel owns collection until it is cleared. */
    fun setQuery(
        repository: TransitRepository,
        networkSupplier: Supplier<BartGtfsNetwork>,
        stationPair: StationPair?,
    ) {
        collectionJob?.cancel()
        collectionJob = null
        departures = emptyList()
        _uiState.value = State.loading()
        if (stationPair == null) {
            return
        }

        collectionJob = viewModelScope.launch {
            val projection = RouteDepartureProjection(stationPair, networkSupplier)
            repository.projectedState(projection::project, projection::areEquivalent)
                .collectLatest { result ->
                    result.exceptionOrNull()?.let { exception ->
                        updateError(asException(exception))
                    } ?: result.getOrNull()?.let { departures ->
                        updateFromFeed(departures.getDepartures())
                    }
                }
        }
    }

    @Synchronized
    fun replace(incoming: List<Departure>): List<Departure> {
        departures = immutableCopy(Departure.replaceFeed(departures, incoming, timeSource))
        _uiState.value = if (departures.isEmpty()) {
            State.empty()
        } else {
            State.content(departures)
        }
        return departures
    }

    @Synchronized
    fun clear(): List<Departure> {
        departures = emptyList()
        _uiState.value = State.empty()
        return departures
    }

    fun getState(): State = uiState.value

    @Synchronized
    fun getDepartures(): List<Departure> = departures

    @Synchronized
    private fun updateFromFeed(incoming: List<Departure>) {
        replace(incoming)
    }

    @Synchronized
    private fun updateError(exception: Exception) {
        _uiState.value = State.error(exception, departures)
    }

    private fun immutableCopy(values: List<Departure>): List<Departure> =
        Collections.unmodifiableList(ArrayList(values))

    private fun asException(error: Throwable): Exception =
        error as? Exception ?: RuntimeException(error)
}
