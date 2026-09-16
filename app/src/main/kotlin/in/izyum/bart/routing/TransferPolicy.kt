package `in`.izyum.bart.routing

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork

/** Hard transfer rules and application preferences used during route selection. */
class TransferPolicy(
    private val network: BartGtfsNetwork,
) {
    /** Returns whether both lines serve the station and the times are ordered. */
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
        return network.canTransfer(
            transferStation,
            effectiveTransferLine(transferStation, fromLine),
            effectiveTransferLine(transferStation, toLine),
        )
    }

    /** Returns whether a passenger can make this transfer within its required margin. */
    fun canMakeTransfer(
        arrivalTime: Long,
        departureTime: Long,
        transferStation: Station?,
        fromLine: Line?,
        toLine: Line?,
        yellowStationSequence: List<Station>? = null,
    ): Boolean {
        val earliestDeparture = earliestTransferDepartureTime(
            arrivalTime,
            transferStation,
            fromLine,
            toLine,
            yellowStationSequence,
        ) ?: return false
        return departureTime >= earliestDeparture
    }

    /**
     * Returns the earliest valid departure time for this transfer, or null when
     * the station/line pair is not physically connected. Routers can use this
     * as a binary-search cutoff in a route's departure list.
     */
    fun earliestTransferDepartureTime(
        arrivalTime: Long,
        transferStation: Station?,
        fromLine: Line?,
        toLine: Line?,
        yellowStationSequence: List<Station>? = null,
        minimumTransferSeconds: Int? = null,
    ): Long? {
        if (arrivalTime <= 0L) return null
        val station = transferStation ?: return null
        val incoming = fromLine ?: return null
        val outgoing = toLine ?: return null
        val effectiveIncoming = effectiveTransferLine(station, incoming)
        val effectiveOutgoing = effectiveTransferLine(station, outgoing)
        if (!network.canTransfer(station, effectiveIncoming, effectiveOutgoing)) return null

        val feedMinimum = network.minimumTransferSeconds(
            station, effectiveIncoming, effectiveOutgoing
        ).coerceAtLeast(0)
        val requiredMinimum = maxOf(minimumTransferSeconds ?: 0, feedMinimum)
        val hasOfficialMargin = network.isTimedTransfer(
            station, effectiveIncoming, effectiveOutgoing
        ) || network.hasExplicitMinimumTransferTime(
            station, effectiveIncoming, effectiveOutgoing
        ) || isOfficialSfoShuttleTransfer(station, incoming, outgoing)
            || isPreferredMacArthurYellowOrange(
                station, incoming, outgoing, yellowStationSequence.orEmpty()
            )
        val requiredSeconds = requiredMinimum.toLong() +
            if (hasOfficialMargin) 0L else EXTRA_MARGIN_SECONDS.toLong()
        val marginMillis = requiredSeconds * 1000L
        if (arrivalTime > Long.MAX_VALUE - marginMillis) return null
        return arrivalTime + marginMillis
    }

    /** Resolves the late-night shuttle's platform transfer to its Red-line connection. */
    fun minimumTransferSeconds(
        station: Station?,
        fromLine: Line?,
        toLine: Line?,
    ): Int = network.minimumTransferSeconds(
        station,
        effectiveTransferLine(station, fromLine),
        effectiveTransferLine(station, toLine),
    ).coerceAtLeast(0)

    private fun effectiveTransferLine(station: Station?, line: Line?): Line? =
        if (station == Station.SFIA && line == Line.YELLOW_LATE_NIGHT) Line.RED else line

    /** Returns whether the connection meets a specified minimum time. */
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

    /** Scores a route according to BART-specific transfer preferences. */
    fun routeScore(route: Route): Int {
        var score = route.transferStations.size * 100
        val lines = route.lines
        if ((route.origin == Station.DUBL || route.origin == Station.CAST)
            && (route.destination == Station.PITT
                || route.destination == Station.PCTR
                || route.destination == Station.ANTC)
            && lines.size == 3
            && lines[0] == Line.BLUE
            && lines[1] == Line.ORANGE
            && lines[2] == Line.YELLOW
            && route.transferStations.size == 2
        ) score -= 200
        route.transferStations.indices.forEach { index ->
            val station = route.transferStations[index]
            if (isAvoidedForRouteRanking(route, index)) {
                score += 80
            } else if (isBusyStation(station)) {
                score += 3
            }
            val preferred = preferredTransferStation(
                route, lines[index], lines[index + 1], index
            )
            if (station != preferred) {
                score += if (preferred == Station.LAKE
                    && station == Station.BAYF
                ) 4 else 10
            }
        }
        return score
    }

    fun isAvoidedForRouteRanking(route: Route, transferIndex: Int): Boolean {
        val station = route.transferStations.getOrNull(transferIndex) ?: return false
        return isAvoidedStation(station)
            && !isPreferredMacArthurYellowOrange(route, transferIndex)
    }

    fun isPreferredMacArthurYellowOrange(route: Route, transferIndex: Int): Boolean {
        val station = route.transferStations.getOrNull(transferIndex) ?: return false
        val fromLine = route.lines.getOrNull(transferIndex) ?: return false
        val toLine = route.lines.getOrNull(transferIndex + 1) ?: return false
        return isPreferredMacArthurYellowOrange(
            station,
            fromLine,
            toLine,
            route.getStationSequence(Line.YELLOW),
        )
    }

    /** Validates the transfer time required by the feed or the unofficial-transfer buffer. */
    fun hasRequiredTransferMargin(
        route: Route,
        transferIndex: Int,
        arrivalTime: Long,
        departureTime: Long,
        minimumTransferSeconds: Int,
    ): Boolean {
        val station = route.transferStations.getOrNull(transferIndex) ?: return false
        val fromLine = route.lines.getOrNull(transferIndex) ?: return false
        val toLine = route.lines.getOrNull(transferIndex + 1) ?: return false
        return hasRequiredTransferMargin(
            station,
            fromLine,
            toLine,
            arrivalTime,
            departureTime,
            minimumTransferSeconds,
            route.getStationSequence(Line.YELLOW),
        )
    }

    /** Transfer-margin validation for timetable algorithms without Route objects. */
    fun hasRequiredTransferMargin(
        station: Station,
        fromLine: Line,
        toLine: Line,
        arrivalTime: Long,
        departureTime: Long,
        minimumTransferSeconds: Int,
        yellowStationSequence: List<Station>?,
    ): Boolean {
        val earliestDeparture = earliestTransferDepartureTime(
            arrivalTime,
            station,
            fromLine,
            toLine,
            yellowStationSequence,
            minimumTransferSeconds,
        ) ?: return false
        return departureTime >= earliestDeparture
    }

    private fun isOfficialSfoShuttleTransfer(
        station: Station,
        fromLine: Line,
        toLine: Line,
    ): Boolean = station == Station.SFIA
        && samePair(fromLine, toLine, Line.YELLOW, Line.YELLOW_LATE_NIGHT)

    private fun isPreferredMacArthurYellowOrange(
        station: Station,
        fromLine: Line,
        toLine: Line,
        yellowStationSequence: List<Station>,
    ): Boolean {
        if (station != Station.MCAR
            || !samePair(fromLine, toLine, Line.ORANGE, Line.YELLOW)
        ) return false
        return yellowTravelIsSouthbound(yellowStationSequence) == true
    }

    /** Uses the static Yellow station sequence, not a feed direction label. */
    fun yellowTravelIsSouthbound(route: Route): Boolean? {
        return yellowTravelIsSouthbound(route.getStationSequence(Line.YELLOW))
    }

    fun yellowTravelIsSouthbound(stations: List<Station>): Boolean? {
        val mcarIndex = stations.indexOf(Station.MCAR)
        val oak19Index = stations.indexOf(Station._19TH)
        return when {
            mcarIndex < 0 || oak19Index < 0 -> null
            mcarIndex < oak19Index -> true
            oak19Index < mcarIndex -> false
            else -> null
        }
    }

    private fun preferredTransferStation(
        route: Route,
        first: Line,
        second: Line,
        transferIndex: Int,
    ): Station? {
        if (isEastBayToSanFranciscoTrunk(first, second)) return Station.BALB
        if (samePair(first, second, Line.BLUE, Line.ORANGE)
            || samePair(first, second, Line.GREEN, Line.BLUE)
        ) {
            val segmentOrigin = if (transferIndex == 0) route.origin
            else route.transferStations[transferIndex - 1]
            val segmentDestination = if (transferIndex + 1 < route.transferStations.size) {
                route.transferStations[transferIndex + 1]
            } else {
                route.destination
            }
            if (segmentOrigin != null && segmentDestination != null
                && canTravel(first, segmentOrigin, Station.LAKE)
                && canTravel(second, Station.LAKE, segmentDestination)
                && network.canTransfer(Station.LAKE, first, second)
            ) return Station.LAKE
            return Station.BAYF
        }
        if (samePair(first, second, Line.ORANGE, Line.YELLOW)) {
            return if (yellowTravelIsSouthbound(route) == true) {
                Station.MCAR
            } else {
                Station._19TH
            }
        }
        return null
    }

    private fun canTravel(
        line: Line,
        origin: Station,
        destination: Station,
    ): Boolean = network.routePatternsForLine(line).any { pattern ->
        val originIndex = pattern.stations.indexOf(origin)
        val destinationIndex = pattern.stations.indexOf(destination)
        originIndex >= 0 && destinationIndex > originIndex
    }

    private fun isEastBayToSanFranciscoTrunk(first: Line, second: Line): Boolean {
        val eastBayLine = first == Line.BLUE || first == Line.GREEN
        val sanFranciscoTrunk = second == Line.RED
            || second == Line.YELLOW
            || second == Line.YELLOW_LATE_NIGHT
        val reversedEastBayLine = second == Line.BLUE || second == Line.GREEN
        val reversedSanFranciscoTrunk = first == Line.RED
            || first == Line.YELLOW
            || first == Line.YELLOW_LATE_NIGHT
        return (eastBayLine && sanFranciscoTrunk)
            || (reversedEastBayLine && reversedSanFranciscoTrunk)
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
