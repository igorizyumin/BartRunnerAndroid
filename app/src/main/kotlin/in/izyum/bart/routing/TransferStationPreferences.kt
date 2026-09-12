package `in`.izyum.bart.routing

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station

/** Shared station policy used when choosing between otherwise valid routes. */
object TransferStationPreferences {
    const val EXTRA_MARGIN_SECONDS = 5 * 60
    const val MAX_PREFERRED_ARRIVAL_DELTA_MILLIS = 5 * 60 * 1000L

    val avoidedStations: Set<Station> = setOf(
        Station.DALY,
        Station.DELN,
        Station.PLZA,
        Station.FTVL,
        Station.HAYW,
        Station.MCAR,
        Station.MLBR,
        Station.MLPT,
        Station.PHIL,
        Station.RICH,
        Station.SANL,
        Station.SHAY,
        Station.UCTY,
        Station.WOAK,
    )

    val busyStations: Set<Station> = setOf(
        Station.CIVC,
        Station.EMBR,
        Station.MONT,
        Station.POWL,
    )

    fun hasExtraMargin(
        arrivalTime: Long,
        departureTime: Long,
        minimumTransferSeconds: Int,
    ): Boolean = departureTime - arrivalTime >=
        (minimumTransferSeconds + EXTRA_MARGIN_SECONDS) * 1000L

    fun isPreferredMacArthurYellowOrange(route: Route, transferIndex: Int): Boolean {
        if (route.transferStations.getOrNull(transferIndex) != Station.MCAR
            || !samePair(
                route.lines.getOrNull(transferIndex),
                route.lines.getOrNull(transferIndex + 1),
                Line.ORANGE,
                Line.YELLOW,
            )
        ) return false
        return yellowTravelIsSouthbound(route) == true
    }

    /** Uses the static Yellow station sequence, not a feed direction label. */
    fun yellowTravelIsSouthbound(route: Route): Boolean? {
        val stations = route.getStationSequence(Line.YELLOW)
        val sfoIndex = stations.indexOf(Station.SFIA)
        val antiochIndex = stations.indexOf(Station.ANTC)
        return when {
            sfoIndex < 0 || antiochIndex < 0 -> null
            antiochIndex < sfoIndex -> true
            sfoIndex < antiochIndex -> false
            else -> null
        }
    }

    private fun samePair(
        left: Line?,
        right: Line?,
        expectedLeft: Line,
        expectedRight: Line,
    ): Boolean = (left == expectedLeft && right == expectedRight)
        || (left == expectedRight && right == expectedLeft)
}
