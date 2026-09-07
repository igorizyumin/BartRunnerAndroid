package com.dougkeen.bart.routing

import com.dougkeen.bart.model.Line
import com.dougkeen.bart.model.Route
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Pure BART route-planning rules. This class has no Android, network, or UI
 * dependencies and is the single source of truth for direct and transfer
 * route selection.
 */
object TripPlanner {
    private val routePreference = compareBy<Route> { routeScore(it) }

    /** Plans direct routes first, then catalog-backed transfer routes. */
    @JvmStatic
    fun routesFor(
        origin: Station?,
        destination: Station?,
        network: BartGtfsNetwork?
    ): List<Route> {
        val validatedNetwork = requireNetwork(network)
        if (origin == null || origin == destination) {
            return emptyList()
        }
        if (destination == null) {
            return catalogStationOnlyRoutes(origin, validatedNetwork)
        }

        val routes = catalogDirectRoutes(origin, destination, validatedNetwork)
            .toMutableList()
        if (routes.isEmpty()) {
            routes += catalogPreferredTransferRoutes(
                origin,
                destination,
                validatedNetwork
            )
        }
        return immutableList(routes)
    }

    @JvmStatic
    fun preferredTransferRoutes(
        origin: Station?,
        destination: Station?,
        network: BartGtfsNetwork?
    ): List<Route> {
        val validatedNetwork = requireNetwork(network)
        if (origin == null || destination == null || origin == destination) {
            return emptyList()
        }
        return preferredTransferRoutesFor(
            origin,
            destination,
            validatedNetwork
        )
    }

    @JvmStatic
    fun transferRoutes(
        origin: Station?,
        destination: Station?,
        network: BartGtfsNetwork?
    ): List<Route> {
        val validatedNetwork = requireNetwork(network)
        if (origin == null || destination == null || origin == destination) {
            return emptyList()
        }
        return immutableList(
            catalogTransferRoutes(origin, destination, validatedNetwork, false)
                .sortedWith(routePreference)
        )
    }

    @JvmStatic
    fun doubleTransferRoutes(
        origin: Station?,
        destination: Station?,
        network: BartGtfsNetwork?
    ): List<Route> {
        val validatedNetwork = requireNetwork(network)
        if (origin == null || destination == null || origin == destination) {
            return emptyList()
        }
        return immutableList(
            catalogTransferRoutes(origin, destination, validatedNetwork, true)
                .sortedWith(routePreference)
        )
    }

    private fun preferredTransferRoutesFor(
        origin: Station,
        destination: Station,
        network: BartGtfsNetwork
    ): List<Route> {
        val transferRoutes = catalogTransferRoutes(origin, destination, network, false)
            .sortedWith(routePreference)
        val doubleTransferRoutes = catalogTransferRoutes(origin, destination, network, true)
            .sortedWith(routePreference)
        if (doubleTransferRoutes.isEmpty()) {
            return immutableList(transferRoutes)
        }
        if (transferRoutes.isEmpty()
            || routeScore(doubleTransferRoutes.first()) < routeScore(transferRoutes.first())
        ) {
            return immutableList(doubleTransferRoutes)
        }
        return immutableList(transferRoutes)
    }

    private fun catalogStationOnlyRoutes(
        origin: Station,
        network: BartGtfsNetwork
    ): List<Route> {
        val routes = mutableListOf<Route>()
        for (line in network.linesForStation(origin)) {
            var bestPattern: List<Station>? = null
            for (pattern in network.routePatternsForLine(line)) {
                val stations = pattern.stations
                if (origin in stations
                    && (bestPattern == null || stations.size > bestPattern.size)
                ) {
                    bestPattern = stations
                }
            }
            if (bestPattern != null) {
                routes += Route.stationOnly(origin, line, bestPattern)
            }
        }
        return immutableList(routes)
    }

    private fun catalogDirectRoutes(
        origin: Station,
        destination: Station,
        network: BartGtfsNetwork
    ): List<Route> {
        val routes = mutableListOf<Route>()
        for (line in network.linesForStation(origin)) {
            val bestPatterns = LinkedHashMap<String?, BartGtfsNetwork.StationPattern>()
            for (pattern in network.routePatternsForLine(line)) {
                val stations = pattern.stations
                val candidateOriginIndex = stations.indexOf(origin)
                val candidateDestinationIndex = stations.indexOf(destination)
                if (candidateOriginIndex < 0
                    || candidateDestinationIndex < 0
                    || candidateOriginIndex >= candidateDestinationIndex
                ) {
                    continue
                }
                val direction = pattern.direction
                val previous = bestPatterns[direction]
                if (previous == null || stations.size > previous.stations.size) {
                    bestPatterns[direction] = pattern
                }
            }
            for (pattern in bestPatterns.values) {
                routes += Route.direct(
                    origin,
                    destination,
                    line,
                    pattern.direction,
                    pattern.stations
                )
            }
        }
        return immutableList(routes)
    }

    private fun catalogPreferredTransferRoutes(
        origin: Station,
        destination: Station,
        network: BartGtfsNetwork
    ): List<Route> = preferredTransferRoutesFor(origin, destination, network)

    private fun catalogTransferRoutes(
        origin: Station,
        destination: Station,
        network: BartGtfsNetwork,
        onlyDoubleTransfers: Boolean
    ): List<Route> {
        val routes = mutableListOf<Route>()
        val usableLines = catalogUsableLines(network)
        for (first in usableLines) {
            if (!catalogContains(first, origin, network)) {
                continue
            }
            for (last in usableLines) {
                if (first == last || !catalogContains(last, destination, network)) {
                    continue
                }
                for (transfer in catalogCommonStations(first, last, network)) {
                    if (!network.canTransfer(transfer, first, last)) {
                        continue
                    }
                    if (!onlyDoubleTransfers
                        && catalogSegment(first, origin, transfer, network) != null
                        && catalogSegment(last, transfer, destination, network) != null
                    ) {
                        addCatalogTransferRoute(
                            routes,
                            origin,
                            destination,
                            listOf(first, last),
                            listOf(transfer),
                            network
                        )
                    }
                }
                for (middle in usableLines) {
                    if (middle == first || middle == last) {
                        continue
                    }
                    for (firstTransfer in catalogCommonStations(first, middle, network)) {
                        if (!network.canTransfer(firstTransfer, first, middle)
                            || catalogSegment(first, origin, firstTransfer, network) == null
                        ) {
                            continue
                        }
                        for (secondTransfer in catalogCommonStations(middle, last, network)) {
                            if (!network.canTransfer(secondTransfer, middle, last)
                                || firstTransfer == secondTransfer
                                || catalogSegment(middle, firstTransfer, secondTransfer, network)
                                == null
                                || catalogSegment(last, secondTransfer, destination, network)
                                == null
                            ) {
                                continue
                            }
                            addCatalogTransferRoute(
                                routes,
                                origin,
                                destination,
                                listOf(first, middle, last),
                                listOf(firstTransfer, secondTransfer),
                                network
                            )
                        }
                    }
                }
            }
        }
        return uniqueRoutes(routes)
    }

    private fun addCatalogTransferRoute(
        routes: MutableList<Route>,
        origin: Station,
        destination: Station,
        lines: List<Line>,
        transfers: List<Station>,
        network: BartGtfsNetwork
    ) {
        val route = makeCatalogTransferRoute(
            origin,
            destination,
            lines,
            transfers,
            network
        )
        if (route != null && isValidTransferPath(route)) {
            routes += route
        }
    }

    private fun makeCatalogTransferRoute(
        origin: Station,
        destination: Station,
        lines: List<Line>,
        transfers: List<Station>,
        network: BartGtfsNetwork
    ): Route? {
        val stationSequences = LinkedHashMap<Line, List<Station>>()
        var direction: String? = null
        for (index in lines.indices) {
            val segmentOrigin = if (index == 0) origin else transfers[index - 1]
            val segmentDestination = if (index == lines.lastIndex) {
                destination
            } else {
                transfers[index]
            }
            val segment = catalogSegment(
                lines[index],
                segmentOrigin,
                segmentDestination,
                network
            ) ?: return null
            stationSequences[lines[index]] = segment.stations
            if (index == 0) {
                direction = segment.direction
            }
        }
        return Route.transfer(
            origin,
            destination,
            lines,
            transfers,
            direction,
            stationSequences
        )
    }

    private fun catalogUsableLines(network: BartGtfsNetwork): List<Line> =
        Line.values().filter { network.routePatternsForLine(it).isNotEmpty() }

    private fun catalogContains(
        line: Line,
        station: Station,
        network: BartGtfsNetwork
    ): Boolean = network.routePatternsForLine(line).any { station in it.stations }

    private fun catalogCommonStations(
        first: Line,
        second: Line,
        network: BartGtfsNetwork
    ): List<Station> {
        val result = mutableListOf<Station>()
        for (pattern in network.routePatternsForLine(first)) {
            for (station in pattern.stations) {
                if (station !in result && catalogContains(second, station, network)) {
                    result += station
                }
            }
        }
        return result
    }

    private fun catalogSegment(
        line: Line,
        origin: Station,
        destination: Station,
        network: BartGtfsNetwork
    ): BartGtfsNetwork.StationPattern? {
        var best: BartGtfsNetwork.StationPattern? = null
        for (pattern in network.routePatternsForLine(line)) {
            val stations = pattern.stations
            val originIndex = stations.indexOf(origin)
            val destinationIndex = stations.indexOf(destination)
            if (originIndex >= 0
                && destinationIndex >= 0
                && originIndex < destinationIndex
                && (best == null || stations.size > best.stations.size)
            ) {
                best = pattern
            }
        }
        return best
    }

    /**
     * Rejects paths that pass the final destination on an earlier leg, or
     * return through the origin after leaving it.
     */
    private fun isValidTransferPath(route: Route): Boolean {
        val origin = route.origin
        val destination = route.destination
        val lines = route.lines
        val transfers = route.transferStations
        for (index in lines.indices) {
            val segmentOrigin = if (index == 0) origin else transfers[index - 1]
            val segmentDestination = if (index == lines.lastIndex) {
                destination
            } else {
                transfers[index]
            }
            val stationSequence = route.getStationSequence(lines[index])
            if (stationSequence.indexOf(segmentOrigin) < 0
                || stationSequence.indexOf(segmentDestination) < 0
                || stationSequence.indexOf(segmentOrigin)
                == stationSequence.indexOf(segmentDestination)
            ) {
                return false
            }
            if (index < lines.lastIndex && destination != null
                && destination in stationSequence
            ) {
                return false
            }
            if (index > 0 && origin != null && origin in stationSequence) {
                return false
            }
        }
        return true
    }

    private fun routeScore(route: Route): Int {
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
        ) {
            score -= 200
        }
        for (index in route.transferStations.indices) {
            score += transferStationPenalty(route, lines, index)
        }
        return score
    }

    private fun transferStationPenalty(
        route: Route,
        lines: List<Line>,
        index: Int
    ): Int {
        val preferred = preferredTransferStation(
            route,
            lines[index],
            lines[index + 1],
            index
        )
        return if (route.transferStations[index] == preferred) 0 else 10
    }

    private fun preferredTransferStation(
        route: Route,
        first: Line,
        second: Line,
        transferIndex: Int
    ): Station? {
        if (isEastBayToSanFranciscoTrunk(first, second)) {
            return Station.BALB
        }
        if (samePair(first, second, Line.BLUE, Line.ORANGE)) {
            return Station.BAYF
        }
        if (samePair(first, second, Line.ORANGE, Line.YELLOW)) {
            val orangeStart: Station?
            val orangeEnd: Station?
            if (first == Line.ORANGE) {
                orangeStart = if (transferIndex == 0) {
                    route.origin
                } else {
                    route.transferStations[transferIndex - 1]
                }
                orangeEnd = route.transferStations[transferIndex]
            } else {
                orangeStart = route.transferStations[transferIndex]
                orangeEnd = if (transferIndex + 1 < route.transferStations.size) {
                    route.transferStations[transferIndex + 1]
                } else {
                    route.destination
                }
            }
            if (orangeStart != null && orangeEnd != null) {
                val orangeStations = route.getStationSequence(Line.ORANGE)
                val startIndex = orangeStations.indexOf(orangeStart)
                val endIndex = orangeStations.indexOf(orangeEnd)
                return if (startIndex > endIndex) Station.MCAR else Station._19TH
            }
            return Station.MCAR
        }
        return null
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
        left: Line,
        right: Line,
        expectedLeft: Line,
        expectedRight: Line
    ): Boolean = (left == expectedLeft && right == expectedRight)
        || (left == expectedRight && right == expectedLeft)

    private fun uniqueRoutes(routes: List<Route>): List<Route> {
        val unique = mutableListOf<Route>()
        for (route in routes) {
            if (unique.none {
                    it.lines == route.lines
                        && it.transferStations == route.transferStations
                }
            ) {
                unique += route
            }
        }
        return unique
    }

    private fun requireNetwork(network: BartGtfsNetwork?): BartGtfsNetwork =
        network ?: throw IllegalArgumentException(
            "A validated GTFS network is required for route planning"
        )

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
