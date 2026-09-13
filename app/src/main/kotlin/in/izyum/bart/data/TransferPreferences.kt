package `in`.izyum.bart.data

import android.content.Context
import androidx.core.content.edit
import `in`.izyum.bart.model.StationPair

/** Manages persistent preferences for route transfer filtering and defaults. */
object TransferPreferences {
    private const val PREFERENCES_NAME = "transfer_preferences"
    private const val DEFAULT_SHOW_TRANSFERS_KEY = "default_show_transfers"
    private const val ROUTE_PREF_PREFIX = "route_show_transfers_"

    fun getDefaultShowTransfers(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(DEFAULT_SHOW_TRANSFERS_KEY, true)

    fun setDefaultShowTransfers(context: Context, show: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(DEFAULT_SHOW_TRANSFERS_KEY, show) }
    }

    fun getShowTransfersForRoute(context: Context, route: StationPair?): Boolean {
        if (route == null) return getDefaultShowTransfers(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val key = routeKey(route)
        return if (prefs.contains(key)) {
            prefs.getBoolean(key, true)
        } else {
            getDefaultShowTransfers(context)
        }
    }

    fun setShowTransfersForRoute(context: Context, route: StationPair?, show: Boolean) {
        if (route == null) return
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(routeKey(route), show) }
    }

    private fun routeKey(route: StationPair): String =
        "$ROUTE_PREF_PREFIX${route.origin?.abbreviation.orEmpty()}_${route.destination?.abbreviation.orEmpty()}"
}
