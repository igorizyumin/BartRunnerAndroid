package `in`.izyum.bart.model

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

/** Immutable route topology and the GTFS patterns used to construct it. */
class Route private constructor(
    val origin: Station?,
    val destination: Station?,
    val directLine: Line?,
    transferLines: List<Line>,
    val requiresTransfer: Boolean,
    val direction: String?,
    lines: List<Line>,
    transferStations: List<Station>,
    stationSequencesByLine: Map<Line, List<Station>>
) {
    val transferLines: List<Line> = immutableRouteList(transferLines)
    val lines: List<Line> = immutableRouteList(lines)
    val transferStations: List<Station> = immutableRouteList(transferStations)
    private val stationSequences: Map<Line, List<Station>> = immutableRouteMap(
        stationSequencesByLine
    )

    val transferStation: Station?
        get() = transferStations.firstOrNull()

    fun hasTransfer(): Boolean = requiresTransfer

    /** Returns the feed pattern used for this line. */
    fun getStationSequence(line: Line?): List<Station> =
        stationSequences[line].orEmpty()

    /**
     * Returns whether a train terminating at [lineDestination] can serve this
     * route leg on [viaLine].
     */
    fun trainDestinationIsApplicable(
        lineDestination: Station?,
        viaLine: Line?
    ): Boolean {
        val viaStations = getStationSequence(viaLine)
        val originIndex = viaStations.indexOf(origin)
        if (destination == null) {
            return originIndex >= 0
                && lineDestination != null
                && viaLine == directLine
                && viaStations.indexOf(lineDestination) >= 0
                && lineDestination != origin
        }

        val routeDestinationIndex = viaStations.indexOf(destination)
        val lineDestinationIndex = viaStations.indexOf(lineDestination)
        val hasDirectRouteViaLine = originIndex >= 0 && routeDestinationIndex >= 0

        if (requiresTransfer && lines.isNotEmpty()) {
            if (transferStations.isEmpty() && directLine?.requiresTransfer() == true) {
                return viaLine == directLine.transferLine1
                    || viaLine == directLine.transferLine2
            }
            // Departures are queried at the passenger origin, so only the
            // first line of a multi-leg route can be represented by an ETD
            // returned for this route. The remaining lines are paired later.
            if (viaLine != lines.first()) {
                return false
            }
            val firstTransfer = transferStations.firstOrNull() ?: destination
            val transferIndex = viaStations.indexOf(firstTransfer)
            if (originIndex < 0 || transferIndex < 0 || lineDestinationIndex < 0) {
                return false
            }
            val direction = transferIndex.compareTo(originIndex)
            return direction != 0
                && lineDestinationIndex.compareTo(originIndex) == direction
                && lineDestinationIndex.compareTo(transferIndex) * direction >= 0
        }

        return hasDirectRouteViaLine
            && lineDestinationIndex >= 0
            && ((originIndex <= routeDestinationIndex
            && routeDestinationIndex <= lineDestinationIndex
            && lineDestinationIndex >= originIndex)
            || (originIndex >= routeDestinationIndex
            && routeDestinationIndex >= lineDestinationIndex
            && lineDestinationIndex <= originIndex))
    }

    override fun toString(): String =
        "Route [origin=$origin, destination=$destination, line=$directLine, " +
            "requiresTransfer=$requiresTransfer, transferStation=$transferStation, " +
            "direction=$direction]"

    companion object {
        @JvmStatic
        fun stationOnly(
            origin: Station,
            line: Line,
            stationSequence: List<Station>
        ): Route = Route(
            origin = origin,
            destination = null,
            directLine = line,
            transferLines = emptyList(),
            requiresTransfer = false,
            direction = null,
            lines = listOf(line),
            transferStations = emptyList(),
            stationSequencesByLine = mapOf(line to stationSequence)
        )

        @JvmStatic
        fun direct(
            origin: Station,
            destination: Station,
            line: Line,
            direction: String?,
            stationSequence: List<Station>
        ): Route = Route(
            origin = origin,
            destination = destination,
            directLine = line,
            transferLines = emptyList(),
            requiresTransfer = false,
            direction = direction,
            lines = listOf(line),
            transferStations = emptyList(),
            stationSequencesByLine = mapOf(line to stationSequence)
        )

        @JvmStatic
        fun transfer(
            origin: Station,
            destination: Station,
            lines: List<Line>,
            transferStations: List<Station>,
            direction: String?,
            stationSequencesByLine: Map<Line, List<Station>>
        ): Route {
            require(lines.isNotEmpty()) { "A transfer route needs at least one line" }
            require(transferStations.size == lines.size - 1) {
                "A transfer route needs one transfer station between each line"
            }
            return Route(
                origin = origin,
                destination = destination,
                directLine = lines.first(),
                transferLines = lines.drop(1),
                requiresTransfer = true,
                direction = direction,
                lines = lines,
                transferStations = transferStations,
                stationSequencesByLine = stationSequencesByLine
            )
        }
    }
}

private fun <T> immutableRouteList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <K, V> immutableRouteMap(values: Map<K, List<V>>): Map<K, List<V>> =
    Collections.unmodifiableMap(
        LinkedHashMap(values.mapValues { (_, value) -> immutableRouteList(value) })
    )
