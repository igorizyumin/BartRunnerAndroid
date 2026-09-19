package `in`.izyum.bart.activities

import android.content.Intent
import android.os.Bundle
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.StationPair

/** Primitive route and trip arguments shared by activity boundaries. */
object RouteArguments {
    const val ORIGIN = "routeOrigin"
    const val DESTINATION = "routeDestination"
    const val SELECTION_IDENTITY = "selectionIdentity"
    const val SCREEN_MODE = "screenMode"

    const val MODE_SCHEDULE = "schedule"
    const val MODE_FOLLOWED = "followed"

    @JvmStatic
    fun putRoute(intent: Intent?, route: StationPair?) {
        if (intent == null || route == null) return
        intent.putExtra(ORIGIN, route.origin.abbreviation)
        intent.putExtra(DESTINATION, route.destination?.abbreviation)
    }

    @JvmStatic
    fun putRoute(bundle: Bundle?, route: StationPair?) {
        if (bundle == null || route == null) return
        bundle.putString(ORIGIN, route.origin.abbreviation)
        bundle.putString(DESTINATION, route.destination?.abbreviation)
    }

    @JvmStatic
    fun putTrip(intent: Intent?, route: StationPair?, selectionIdentity: String?, mode: String?) {
        if (intent == null) return
        putRoute(intent, route)
        intent.putExtra(SELECTION_IDENTITY, selectionIdentity)
        intent.putExtra(SCREEN_MODE, mode)
    }

    @JvmStatic
    fun readRoute(intent: Intent?): StationPair? = readRoute(intent?.extras)

    @JvmStatic
    fun readRoute(bundle: Bundle?): StationPair? {
        if (bundle == null) return null
        val origin = Station.getByAbbreviation(bundle.getString(ORIGIN)) ?: return null
        return StationPair(origin, Station.getByAbbreviation(bundle.getString(DESTINATION)))
    }

    @JvmStatic
    fun readSelectionIdentity(intent: Intent?): String? =
        intent?.getStringExtra(SELECTION_IDENTITY)

    @JvmStatic
    fun readScreenMode(intent: Intent?): String? = intent?.getStringExtra(SCREEN_MODE)
}
