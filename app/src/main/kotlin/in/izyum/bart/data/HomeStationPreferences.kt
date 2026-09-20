package `in`.izyum.bart.data

import android.content.Context
import androidx.core.content.edit
import `in`.izyum.bart.model.Station

/** Stores the home station used as default origin in Trip Planner and Add Route. */
object HomeStationPreferences {
    private const val PREFERENCES_NAME = "home_station_preferences"
    private const val HOME_STATION_ABBR_KEY = "home_station_abbr"

    fun getHomeStationAbbreviation(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(HOME_STATION_ABBR_KEY, null)
            ?.takeIf { it.isNotEmpty() }

    fun getHomeStation(context: Context): Station? {
        val abbr = getHomeStationAbbreviation(context) ?: return null
        return Station.getStationList().firstOrNull { it.abbreviation.equals(abbr, ignoreCase = true) }
    }

    fun setHomeStation(context: Context, station: Station?) {
        setHomeStationAbbreviation(context, station?.abbreviation)
    }

    fun setHomeStationAbbreviation(context: Context, abbreviation: String?) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit {
                if (abbreviation.isNullOrEmpty()) remove(HOME_STATION_ABBR_KEY)
                else putString(HOME_STATION_ABBR_KEY, abbreviation)
            }
    }
}
