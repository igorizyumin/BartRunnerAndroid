package com.dougkeen.bart.data

import android.content.Context
import android.util.Log
import com.dougkeen.bart.model.StationPair
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns favorite routes and their persistence. The application exposes this
 * repository, but does not own a mutable favorites list itself.
 */
class FavoritesRepository(context: Context) : AutoCloseable {
    private companion object {
        const val TAG = "FavoritesRepository"
        const val FILE_NAME = "favorite_routes.json"
    }

    private val applicationContext = context.applicationContext
    private val objectMapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val persistenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stateLock = Any()
    private var currentFavorites: List<StationPair> = emptyList()
    private var loaded = false
    private val pendingChanges = mutableListOf<(MutableList<StationPair>) -> Unit>()

    private val _uiState = MutableStateFlow(
        FavoritesUiState(emptyList(), isLoading = true)
    )
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        persistenceExecutor.execute {
            load()
        }
    }

    fun addFavorite(favorite: StationPair) {
        updateFavorites { it.add(favorite) }
    }

    fun removeFavorite(favorite: StationPair) {
        updateFavorites { it.remove(favorite) }
    }

    fun moveFavorite(from: Int, to: Int) {
        updateFavorites { favorites ->
            if (from in favorites.indices && to in favorites.indices && from != to) {
                val favorite = favorites.removeAt(from)
                favorites.add(to, favorite)
            }
        }
    }

    fun insertFavorite(favorite: StationPair, index: Int) {
        updateFavorites { favorites ->
            favorites.add(index.coerceIn(0, favorites.size), favorite)
        }
    }

    fun updateFare(favorite: StationPair, fare: String, updatedAt: Long) {
        updateFavorites { favorites ->
            val index = favorites.indexOf(favorite)
            if (index >= 0) {
                favorites[index] = favorite.withFare(fare, updatedAt)
            }
        }
    }

    private fun persistSnapshot(snapshot: List<StationPair>) {
        persistenceExecutor.execute {
            writeSnapshot(snapshot)
        }
    }

    private fun load() {
        val restored = try {
            applicationContext.openFileInput(FILE_NAME).use { input ->
                objectMapper.readValue(
                    input,
                    object : TypeReference<ArrayList<StationPair>>() {}
                )
            }
        } catch (exception: java.io.FileNotFoundException) {
            emptyList()
        } catch (exception: Exception) {
            Log.e(TAG, "Could not read favorite routes", exception)
            emptyList()
        }

        synchronized(stateLock) {
            val merged = restored.toMutableList()
            val hadPendingChanges = pendingChanges.isNotEmpty()
            pendingChanges.forEach { it(merged) }
            pendingChanges.clear()
            currentFavorites = immutableCopy(merged)
            loaded = true
            _uiState.value = FavoritesUiState(currentFavorites, isLoading = false)
            if (hadPendingChanges) {
                // Queue this write before releasing stateLock so a concurrent
                // update cannot enqueue a newer snapshot ahead of this merge.
                persistSnapshot(currentFavorites)
            }
        }
    }

    private fun updateFavorites(change: (MutableList<StationPair>) -> Unit) {
        val snapshot: List<StationPair>
        synchronized(stateLock) {
            if (!loaded) {
                pendingChanges += change
                return
            }
            val updated = currentFavorites.toMutableList()
            change(updated)
            currentFavorites = immutableCopy(updated)
            snapshot = currentFavorites
            _uiState.value = FavoritesUiState(snapshot, isLoading = false)
        }
        persistSnapshot(snapshot)
    }

    private fun writeSnapshot(snapshot: List<StationPair>) {
        try {
            applicationContext.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { output ->
                objectMapper.writeValue(output, snapshot)
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Could not write favorite routes", exception)
        }
    }

    private fun immutableCopy(favorites: List<StationPair>): List<StationPair> =
        Collections.unmodifiableList(ArrayList(favorites))

    override fun close() {
        persistenceExecutor.shutdownNow()
    }
}
