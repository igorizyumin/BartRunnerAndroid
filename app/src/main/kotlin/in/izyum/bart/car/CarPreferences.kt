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

    fun getDefaultShowTransfers(context: Context): Boolean {
        return runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DEFAULT_SHOW_TRANSFERS, true)
        }.getOrDefault(true)
    }

    fun setDefaultShowTransfers(context: Context, showTransfers: Boolean) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_DEFAULT_SHOW_TRANSFERS, showTransfers) }
        }
    }

    fun getDefaultAudioGuidance(context: Context): Boolean {
        return runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DEFAULT_AUDIO_GUIDANCE, true)
        }.getOrDefault(true)
    }

    fun setDefaultAudioGuidance(context: Context, enabled: Boolean) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_DEFAULT_AUDIO_GUIDANCE, enabled) }
        }
    }

    fun getShowTransfersForRoute(context: Context, stationPair: StationPair): Boolean {
        return runCatching {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = getRouteKey(stationPair)
            if (prefs.contains(key)) {
                prefs.getBoolean(key, true)
            } else {
                getDefaultShowTransfers(context)
            }
        }.getOrDefault(true)
    }

    fun setShowTransfersForRoute(context: Context, stationPair: StationPair, showTransfers: Boolean) {
        runCatching {
            val key = getRouteKey(stationPair)
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(key, showTransfers) }
        }
    }

    fun clearRouteOverrides(context: Context) {
        runCatching {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val keysToRemove = prefs.all.keys.filter { it.startsWith(PREFIX_ROUTE_SHOW_TRANSFERS) }
            prefs.edit {
                keysToRemove.forEach { remove(it) }
            }
        }
    }

    private fun getRouteKey(stationPair: StationPair): String {
        val origin = stationPair.origin?.abbreviation ?: "ORIGIN"
        val destination = stationPair.destination?.abbreviation ?: "ALL"
        return "$PREFIX_ROUTE_SHOW_TRANSFERS${origin}_$destination"
    }
}
