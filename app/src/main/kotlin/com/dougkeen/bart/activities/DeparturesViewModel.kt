package com.dougkeen.bart.activities

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dougkeen.bart.backend.RouteDepartureProjection
import com.dougkeen.bart.backend.TransitRepository
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.model.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.ArrayList
import java.util.Collections

/** Owns departures-screen state and its shared-feed projection. */
class DeparturesViewModel(
    private val timeSource: TimeSource,
) : ViewModel() {
    enum class Status {
        LOADING,
        CONTENT,
        EMPTY,
        ERROR,
    }

    class State private constructor(
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
        context: Context,
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
            repository.projectedState(RouteDepartureProjection(stationPair, context))
                .collectLatest { projectionState ->
                    projectionState.error?.let { exception ->
                        updateError(exception)
                    } ?: projectionState.value?.let { result ->
                        updateFromFeed(result.getDepartures())
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
}
