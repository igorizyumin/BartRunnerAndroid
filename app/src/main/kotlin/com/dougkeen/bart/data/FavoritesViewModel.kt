package com.dougkeen.bart.data

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/** Screen-scoped facade for favorite-route state and user actions. */
class FavoritesViewModel(
    private val repository: FavoritesRepository,
) : ViewModel() {
    val uiState: StateFlow<FavoritesUiState> = repository.uiState

    fun observe(owner: LifecycleOwner, observer: FavoritesObserver) {
        repository.observe(owner, observer)
    }

    fun addFavorite(favorite: com.dougkeen.bart.model.StationPair) {
        repository.addFavorite(favorite)
    }

    fun removeFavorite(favorite: com.dougkeen.bart.model.StationPair) {
        repository.removeFavorite(favorite)
    }

    fun moveFavorite(from: Int, to: Int) {
        repository.moveFavorite(from, to)
    }

    fun insertFavorite(favorite: com.dougkeen.bart.model.StationPair, index: Int) {
        repository.insertFavorite(favorite, index)
    }

    fun persistCurrentState() {
        repository.persistCurrentState()
    }
}
