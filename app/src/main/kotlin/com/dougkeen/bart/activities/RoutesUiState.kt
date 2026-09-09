package com.dougkeen.bart.activities

import com.dougkeen.bart.model.Alert
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.StationPair
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
    val isLoading: Boolean = true,
    val error: Exception? = null,
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
        isLoading: Boolean = this.isLoading,
        error: Exception? = this.error,
    ): RoutesUiState = RoutesUiState(
        favorites,
        firstDepartures,
        fares,
        alerts,
        alertKind,
        isLoading,
        error,
    )

    enum class AlertKind {
        HIDDEN,
        NO_DELAYS,
        WARNING,
    }
}
