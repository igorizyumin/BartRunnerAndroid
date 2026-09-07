package com.dougkeen.bart.data

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dougkeen.bart.model.StationPair
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private var currentFavorites: List<StationPair> = emptyList()
    private var loaded = false
    private val pendingChanges = mutableListOf<(MutableList<StationPair>) -> Unit>()

    private val _uiState = MutableStateFlow(
        FavoritesUiState(emptyList(), isLoading = true)
    )
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
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

    /** Persists in-place changes made to a route's fare metadata. */
    fun persistCurrentState() {
        val snapshot = synchronized(stateLock) { currentFavorites }
        persistenceExecutor.execute {
            try {
                applicationContext.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { output ->
                    objectMapper.writeValue(output, snapshot)
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Could not write favorite routes", exception)
            }
        }
    }

    /**
     * Collects state only while the owner is STARTED. This keeps Java-based
     * activities lifecycle-aware while the source of truth remains a Flow.
     */
    fun observe(owner: LifecycleOwner, observer: FavoritesObserver) {
        val binding = object : DefaultLifecycleObserver {
            var collection: Job? = null

            override fun onStart(owner: LifecycleOwner) {
                collection?.cancel()
                collection = scope.launch {
                    uiState.collectLatest { state ->
                        withContext(Dispatchers.Main.immediate) {
                            observer.onFavoritesChanged(state)
                        }
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                collection?.cancel()
                collection = null
            }

            override fun onDestroy(owner: LifecycleOwner) {
                collection?.cancel()
                owner.lifecycle.removeObserver(this)
            }
        }
        owner.lifecycle.addObserver(binding)
    }

    private suspend fun load() {
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
            pendingChanges.forEach { it(merged) }
            pendingChanges.clear()
            currentFavorites = immutableCopy(merged)
            loaded = true
            _uiState.value = FavoritesUiState(currentFavorites, isLoading = false)
        }
    }

    private fun updateFavorites(change: (MutableList<StationPair>) -> Unit) {
        val snapshot: List<StationPair>
        synchronized(stateLock) {
            val updated = currentFavorites.toMutableList()
            change(updated)
            currentFavorites = immutableCopy(updated)
            if (!loaded) {
                pendingChanges += change
            }
            snapshot = currentFavorites
            _uiState.value = FavoritesUiState(snapshot, isLoading = !loaded)
        }
        persistenceExecutor.execute {
            try {
                applicationContext.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { output ->
                    objectMapper.writeValue(output, snapshot)
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Could not write favorite routes", exception)
            }
        }
    }

    private fun immutableCopy(favorites: List<StationPair>): List<StationPair> =
        Collections.unmodifiableList(ArrayList(favorites))

    override fun close() {
        scope.coroutineContext.cancel()
        persistenceExecutor.shutdownNow()
    }
}
