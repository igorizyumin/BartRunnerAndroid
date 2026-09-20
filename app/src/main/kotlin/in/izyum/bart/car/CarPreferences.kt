package `in`.izyum.bart.car

import android.content.Context
import androidx.core.content.edit
import `in`.izyum.bart.model.StationPair

/**
 * Persists Android Auto settings, including global default transfer view preferences
 * and per-route override states.
 */
object CarPreferences {
    private const val PREFS_NAME = "car_app_preferences"

    private const val KEY_DEFAULT_SHOW_TRANSFERS = "default_show_transfers"
    private const val KEY_DEFAULT_AUDIO_GUIDANCE = "default_audio_guidance"
    private const val PREFIX_ROUTE_SHOW_TRANSFERS = "route_show_transfers_"

    /**
     * Gets the global default transfer view setting.
     * When true, displays both direct trains and connecting transfer routes.
     * When false, filters out transfer routes (direct trains only).
     */
    fun getDefaultShowTransfers(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEFAULT_SHOW_TRANSFERS, true)
    }

    /** Updates the global default transfer view setting. */
    fun setDefaultShowTransfers(context: Context, showTransfers: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DEFAULT_SHOW_TRANSFERS, showTransfers) }
    }

    /** Gets the global default audio guidance setting. */
    fun getDefaultAudioGuidance(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEFAULT_AUDIO_GUIDANCE, true)
    }

    /** Updates the global default audio guidance setting. */
    fun setDefaultAudioGuidance(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DEFAULT_AUDIO_GUIDANCE, enabled) }
    }

    /**
     * Gets the show-transfers setting for a specific route (StationPair).
     * If a per-route preference is explicitly saved, returns that value.
     * Otherwise, falls back to the global default setting.
     */
    fun getShowTransfersForRoute(context: Context, stationPair: StationPair): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = getRouteKey(stationPair)
        return if (prefs.contains(key)) {
            prefs.getBoolean(key, true)
        } else {
            getDefaultShowTransfers(context)
        }
    }

    /** Saves the show-transfers preference state for a specific route. */
    fun setShowTransfersForRoute(context: Context, stationPair: StationPair, showTransfers: Boolean) {
        val key = getRouteKey(stationPair)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(key, showTransfers) }
    }

    /** Clears all per-route saved transfer overrides, reverting all routes to global default. */
    fun clearRouteOverrides(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keysToRemove = prefs.all.keys.filter { it.startsWith(PREFIX_ROUTE_SHOW_TRANSFERS) }
        prefs.edit {
            keysToRemove.forEach { remove(it) }
        }
    }

    private fun getRouteKey(stationPair: StationPair): String {
        val origin = stationPair.origin?.abbreviation ?: "ORIGIN"
        val destination = stationPair.destination?.abbreviation ?: "ALL"
        return "$PREFIX_ROUTE_SHOW_TRANSFERS${origin}_$destination"
    }
}
