package com.dougkeen.bart.data

import com.dougkeen.bart.model.StationPair

/** Immutable state rendered by the favorite-routes screen. */
data class FavoritesUiState(
    val favorites: List<StationPair>,
    val isLoading: Boolean,
    val error: Throwable? = null,
) {
    val isEmpty: Boolean
        get() = favorites.isEmpty()
}
