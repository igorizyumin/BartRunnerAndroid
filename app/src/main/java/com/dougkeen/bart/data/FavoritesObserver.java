package com.dougkeen.bart.data;

/** Receives lifecycle-scoped snapshots of the user's favorite routes. */
public interface FavoritesObserver {
    void onFavoritesChanged(FavoritesUiState state);
}
