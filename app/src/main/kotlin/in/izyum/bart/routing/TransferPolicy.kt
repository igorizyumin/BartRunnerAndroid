package `in`.izyum.bart.routing

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork

/** Hard transfer rules and application preferences used during route selection. */
class TransferPolicy(
    private val network: BartGtfsNetwork,
) {
    /** Returns whether the feed allows this transfer, ignoring its time warning. */
    fun canTransfer(
        arrivalTime: Long,
        departureTime: Long,
        transferStation: Station?,
        fromLine: Line?,
        toLine: Line?,
    ): Boolean {
        if (arrivalTime <= 0 || departureTime < arrivalTime) {
            return false
        }
        return network.canTransfer(transferStation, fromLine, toLine)
    }

    /** Returns whether the connection meets the feed's recommended minimum. */
    fun meetsMinimumTransferTime(
        arrivalTime: Long,
        departureTime: Long,
        minimumTransferSeconds: Int,
    ): Boolean {
        if (arrivalTime <= 0 || departureTime <= 0 || minimumTransferSeconds < 0) {
            return false
        }
        return departureTime - arrivalTime >= minimumTransferSeconds * 1000L
    }

    /** Returns whether the connection has the additional application safety margin. */
    fun hasExtraMargin(
        arrivalTime: Long,
        departureTime: Long,
        minimumTransferSeconds: Int,
    ): Boolean = meetsMinimumTransferTime(
        arrivalTime,
        departureTime,
        minimumTransferSeconds + EXTRA_MARGIN_SECONDS,
    )

    fun isAvoidedStation(station: Station): Boolean = station in avoidedStations

    fun isBusyStation(station: Station): Boolean = station in busyStations

    fun isAvoidedForRouteRanking(route: Route, transferIndex: Int): Boolean {
        val station = route.transferStations.getOrNull(transferIndex) ?: return false
        return isAvoidedStation(station)
            && !isPreferredMacArthurYellowOrange(route, transferIndex)
    }

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

    companion object {
        const val EXTRA_MARGIN_SECONDS = 5 * 60
        const val MAX_PREFERRED_ARRIVAL_DELTA_MILLIS = 5 * 60 * 1000L

        private val avoidedStations: Set<Station> = setOf(
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

        private val busyStations: Set<Station> = setOf(
            Station.CIVC,
            Station.EMBR,
            Station.MONT,
            Station.POWL,
        )
    }
}
