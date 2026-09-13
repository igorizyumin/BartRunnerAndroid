package `in`.izyum.bart.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.izyum.bart.backend.RouteDepartureProjection
import `in`.izyum.bart.backend.EtdAwareRouteDepartureProjection
import `in`.izyum.bart.backend.TransitRepository
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.model.SystemTimeSource
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.networktasks.EtdStationCache
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
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
        val showTransfers: Boolean = true,
    ) {
        companion object {
            fun loading(showTransfers: Boolean = true) =
                State(Status.LOADING, emptyList(), null, showTransfers)
            fun content(departures: List<Departure>, showTransfers: Boolean = true) =
                State(Status.CONTENT, departures, null, showTransfers)
            fun empty(showTransfers: Boolean = true) =
                State(Status.EMPTY, emptyList(), null, showTransfers)
            fun error(exception: Exception, departures: List<Departure>, showTransfers: Boolean = true) =
                State(Status.ERROR, departures, exception, showTransfers)
        }
    }

    private var showTransfers: Boolean = true
    private var departures: List<Departure> = emptyList()

    private val _uiState = MutableStateFlow(State.loading(showTransfers))
    val uiState: StateFlow<State> = _uiState.asStateFlow()

    private var collectionJob: Job? = null

    fun toggleShowTransfers() {
        setShowTransfers(!showTransfers)
    }

    @Synchronized
    fun setShowTransfers(show: Boolean) {
        if (showTransfers != show) {
            showTransfers = show
            updateState()
        }
    }

    fun isShowingTransfers(): Boolean = showTransfers

    /** Configures the query; the ViewModel owns collection until it is cleared. */
    fun setQuery(
        repository: TransitRepository,
        networkSupplier: Supplier<BartGtfsNetwork>,
        stationPair: StationPair?,
        etdStationCache: EtdStationCache? = null,
    ) {
        collectionJob?.cancel()
        collectionJob = null
        departures = emptyList()
        _uiState.value = State.loading(showTransfers)
        if (stationPair == null) {
            return
        }

        collectionJob = viewModelScope.launch {
            val baseProjection = RouteDepartureProjection(stationPair, networkSupplier)
            val etdProjection = etdStationCache?.let {
                EtdAwareRouteDepartureProjection(baseProjection, it)
            }
            val projectedState = if (etdProjection != null) {
                repository.projectedStateSuspending(
                    etdProjection::project,
                    etdProjection::areEquivalent,
                )
            } else {
                repository.projectedState(
                    baseProjection::project,
                    baseProjection::areEquivalent,
                )
            }
            projectedState
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
        updateState()
        return getDepartures()
    }

    @Synchronized
    fun clear(): List<Departure> {
        departures = emptyList()
        updateState()
        return emptyList()
    }

    fun getState(): State = uiState.value

    @Synchronized
    fun getDepartures(): List<Departure> {
        return if (showTransfers) {
            departures
        } else {
            departures.filter { !it.requiresTransfer && !it.hasTransfers() }
        }
    }

    @Synchronized
    private fun updateFromFeed(incoming: List<Departure>) {
        replace(incoming)
    }

    @Synchronized
    private fun updateError(exception: Exception) {
        val visible = getDepartures()
        _uiState.value = State.error(exception, visible, showTransfers)
    }

    @Synchronized
    private fun updateState() {
        val visible = getDepartures()
        val currentStatus = _uiState.value.status
        _uiState.value = when {
            currentStatus == Status.LOADING && departures.isEmpty() -> {
                State.loading(showTransfers)
            }
            currentStatus == Status.ERROR -> {
                State.error(_uiState.value.error ?: RuntimeException(), visible, showTransfers)
            }
            visible.isEmpty() -> {
                State.empty(showTransfers)
            }
            else -> {
                State.content(visible, showTransfers)
            }
        }
    }

    private fun immutableCopy(values: List<Departure>): List<Departure> =
        Collections.unmodifiableList(ArrayList(values))

    private fun asException(error: Throwable): Exception =
        error as? Exception ?: RuntimeException(error)
}
