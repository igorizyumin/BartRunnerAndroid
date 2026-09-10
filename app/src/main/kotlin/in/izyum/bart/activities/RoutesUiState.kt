package `in`.izyum.bart.activities

import `in`.izyum.bart.model.Alert
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.StationPair
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap

/** Immutable renderer state for the favorite-routes screen. */
class RoutesUiState(
    favorites: List<StationPair> = emptyList(),
    firstDepartures: Map<StationPair, Departure> = emptyMap(),
    fares: Map<StationPair, String> = emptyMap(),
    val alerts: Alert.AlertList? = null,
    val alertKind: AlertKind = AlertKind.HIDDEN,
    val isOffline: Boolean = false,
    val isLoading: Boolean = true,
    val error: Exception? = null,
    val elevatorDescription: String? = null,
    val elevatorIsLoading: Boolean = false,
    val elevatorError: Exception? = null,
) {
    val favorites: List<StationPair> =
        Collections.unmodifiableList(ArrayList(favorites))
    val firstDepartures: Map<StationPair, Departure> =
        Collections.unmodifiableMap(HashMap(firstDepartures))
    val fares: Map<StationPair, String> =
        Collections.unmodifiableMap(HashMap(fares))

    fun copy(
        favorites: List<StationPair> = this.favorites,
        firstDepartures: Map<StationPair, Departure> = this.firstDepartures,
        fares: Map<StationPair, String> = this.fares,
        alerts: Alert.AlertList? = this.alerts,
        alertKind: AlertKind = this.alertKind,
        isOffline: Boolean = this.isOffline,
        isLoading: Boolean = this.isLoading,
        error: Exception? = this.error,
        elevatorDescription: String? = this.elevatorDescription,
        elevatorIsLoading: Boolean = this.elevatorIsLoading,
        elevatorError: Exception? = this.elevatorError,
    ): RoutesUiState = RoutesUiState(
        favorites,
        firstDepartures,
        fares,
        alerts,
        alertKind,
        isOffline,
        isLoading,
        error,
        elevatorDescription,
        elevatorIsLoading,
        elevatorError,
    )

    enum class AlertKind {
        HIDDEN,
        NO_DELAYS,
        WARNING,
    }
}
