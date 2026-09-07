package com.dougkeen.bart.transit.gtfs

import com.dougkeen.bart.model.Line
import com.dougkeen.bart.model.Station
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

/** BART-specific adapter from generic GTFS facts to stable app identities. */
class BartGtfsNetwork private constructor(
    private val catalog: GtfsNetworkCatalog,
    stationsByStopId: Map<String, Station>,
    linesByRouteId: Map<String, Line>,
    transferRules: List<TransferRule>
) {
    private val stationsByStopId = immutableMap(stationsByStopId)
    private val linesByRouteId = immutableMap(linesByRouteId)
    private val transferRules = immutableList(transferRules)

    /** Immutable GTFS station pattern mapped to a BART line and direction. */
    class StationPattern private constructor(
        val routeId: String,
        val direction: String?,
        stations: List<Station>,
        tripIds: List<String>
    ) {
        val stations: List<Station> = immutableList(stations)
        val tripIds: List<String> = immutableList(tripIds)

        override fun equals(other: Any?): Boolean =
            other is StationPattern
                && routeId == other.routeId
                && direction == other.direction
                && stations == other.stations

        override fun hashCode(): Int =
            java.util.Objects.hash(routeId, direction, stations)

        companion object {
            fun create(
                routeId: String,
                direction: String?,
                stations: List<Station>,
                tripIds: List<String>
            ): StationPattern = StationPattern(routeId, direction, stations, tripIds)
        }
    }

    /** Immutable, BART-mapped transfer rule from transfers.txt. */
    class TransferRule private constructor(
        val fromStation: Station,
        val toStation: Station,
        val fromRouteId: String?,
        val toRouteId: String?,
        val fromLine: Line?,
        val toLine: Line?,
        val transferType: Int,
        val minimumTransferSeconds: Int?
    ) {
        fun isForbidden(): Boolean = transferType == 3

        companion object {
            fun create(
                fromStation: Station,
                toStation: Station,
                fromRouteId: String?,
                toRouteId: String?,
                fromLine: Line?,
                toLine: Line?,
                transferType: Int,
                minimumTransferSeconds: Int?
            ): TransferRule = TransferRule(
                fromStation,
                toStation,
                fromRouteId,
                toRouteId,
                fromLine,
                toLine,
                transferType,
                minimumTransferSeconds
            )
        }
    }

    fun stationForStopId(stopId: String?): Station? =
        stopId?.let { stationsByStopId[it] }

    fun lineForRouteId(routeId: String?): Line? =
        routeId?.let { linesByRouteId[it] }

    /** Returns the direction encoded by the GTFS route name, if present. */
    fun directionForRouteId(routeId: String?): String? {
        val shortName = routeId?.let { catalog.routesById[it]?.shortName }
            ?.trim()
            ?.uppercase()
            ?: return null
        val direction = shortName.substringAfterLast('-', missingDelimiterValue = "")
        return when (direction) {
            "N" -> "n"
            "S" -> "s"
            else -> null
        }
    }

    /** Returns GTFS station patterns while retaining each route direction. */
    fun routePatternsForLine(line: Line?): List<StationPattern> {
        if (line == null) {
            return emptyList()
        }
        val patterns = LinkedHashSet<StationPattern>()
        for (pattern in catalog.patterns) {
            if (line != linesByRouteId[pattern.routeId]) {
                continue
            }
            val stations = stationsForPattern(pattern)
            if (stations.size >= 2) {
                patterns += StationPattern.create(
                    pattern.routeId,
                    directionForRouteId(pattern.routeId),
                    stations,
                    pattern.tripIds.toList()
                )
            }
        }
        return immutableList(patterns)
    }

    /** Returns the distinct station sequences supplied by GTFS for a line. */
    fun stationPatternsForLine(line: Line?): List<List<Station>> =
        immutableList(routePatternsForLine(line).map { it.stations }.distinct())

    fun linesForStation(station: Station?): List<Line> {
        if (station == null) {
            return emptyList()
        }
        return immutableList(
            Line.values().filter { line ->
                routePatternsForLine(line).any { station in it.stations }
            }
        )
    }

    fun isBetween(
        station: Station?,
        origin: Station?,
        destination: Station?,
        line: Line?
    ): Boolean {
        if (station == null || origin == null || destination == null || line == null) {
            return false
        }
        return routePatternsForLine(line).any { pattern ->
            val stations = pattern.stations
            val originIndex = stations.indexOf(origin)
            val destinationIndex = stations.indexOf(destination)
            val stationIndex = stations.indexOf(station)
            originIndex >= 0
                && destinationIndex >= 0
                && stationIndex >= 0
                && originIndex < destinationIndex
                && stationIndex > originIndex
                && stationIndex < destinationIndex
        }
    }

    fun routeIdForTrip(tripId: String?): String? = catalog.routeIdForTrip(tripId)

    fun getTransferRules(): List<TransferRule> = transferRules

    /**
     * Returns whether the feed explicitly permits changing between two lines
     * at a station. Missing transfer data is not treated as permission.
     */
    fun canTransfer(station: Station?, fromLine: Line?, toLine: Line?): Boolean {
        if (station == null || fromLine == null || toLine == null || fromLine == toLine) {
            return false
        }
        var matchedRule = false
        for (rule in transferRules) {
            if (rule.fromStation != station
                || rule.toStation != station
                || !lineMatches(rule.fromRouteId, rule.fromLine, fromLine)
                || !lineMatches(rule.toRouteId, rule.toLine, toLine)
            ) {
                continue
            }
            matchedRule = true
            if (rule.isForbidden()) {
                return false
            }
        }
        return matchedRule
    }

    /** Returns the smallest matching feed minimum in seconds. */
    fun minimumTransferSeconds(
        station: Station?,
        fromLine: Line?,
        toLine: Line?
    ): Int {
        if (!canTransfer(station, fromLine, toLine)) {
            return -1
        }
        var minimum = Int.MAX_VALUE
        for (rule in transferRules) {
            if (rule.fromStation != station
                || rule.toStation != station
                || !lineMatches(rule.fromRouteId, rule.fromLine, fromLine)
                || !lineMatches(rule.toRouteId, rule.toLine, toLine)
                || rule.isForbidden()
            ) {
                continue
            }
            val seconds = rule.minimumTransferSeconds
            if (seconds != null && seconds >= 0) {
                minimum = minOf(minimum, seconds)
            }
        }
        return if (minimum == Int.MAX_VALUE) 0 else minimum
    }

    fun validationErrors(): List<String> {
        val errors = catalog.validationErrors().toMutableList()
        for ((routeId, route) in catalog.routesById) {
            if (routeId !in linesByRouteId && !isExplicitlyUnsupportedRoute(route)) {
                errors += "No BART line mapping for route $routeId"
            }
            if (routeId in linesByRouteId && directionForRouteId(routeId) == null) {
                errors += "No BART direction mapping for route $routeId"
            }
        }
        for (stop in catalog.stopsById.values) {
            if (stop.stopId !in stationsByStopId && !isNonRevenueOaklandAirportStop(stop)) {
                errors += "No BART station mapping for stop ${stop.stopId}"
            }
        }
        return immutableList(errors)
    }

    private fun stationsForPattern(pattern: GtfsRoutePattern): List<Station> {
        val stations = mutableListOf<Station>()
        for (stopId in pattern.stopIds) {
            val station = stationForStopId(stopId)
            if (station != null && station != Station.SPCL
                && (stations.isEmpty() || stations.last() != station)
            ) {
                stations += station
            }
        }
        return stations
    }

    companion object {
        @JvmStatic
        fun fromCatalog(catalog: GtfsNetworkCatalog?): BartGtfsNetwork {
            requireNotNull(catalog) { "A GTFS catalog is required" }

            val stationsByStopId = LinkedHashMap<String, Station>()
            for (stop in catalog.stopsById.values) {
                val station = stationForStop(catalog, stop) ?: continue
                stationsByStopId[stop.stopId] = station
            }

            val linesByRouteId = routeLineMappings(catalog)
            return BartGtfsNetwork(
                catalog,
                stationsByStopId,
                linesByRouteId,
                transferRules(catalog, stationsByStopId, linesByRouteId)
            )
        }

        private fun stationForStop(
            catalog: GtfsNetworkCatalog,
            stop: GtfsStop
        ): Station? {
            var station = Station.getByAbbreviation(stop.zoneId)
            if (station != null && station != Station.SPCL) {
                return station
            }
            station = stationForName(stop.name)
            if (station != null) {
                return station
            }
            val parentId = stop.parentStationId ?: return null
            val parent = catalog.stopsById[parentId] ?: return null
            station = Station.getByAbbreviation(parent.zoneId)
            if (station != null && station != Station.SPCL) {
                return station
            }
            return stationForName(parent.name)
        }

        private fun stationForName(name: String?): Station? {
            if (name.isNullOrBlank()) {
                return null
            }
            val normalized = normalize(name)
            return Station.getStationList().firstOrNull { station ->
                normalized == normalize(station.toString())
                    || normalized == normalize(station.apiName)
                    || normalized.startsWith(normalize(station.toString()) + "platform")
            }
        }

        private fun normalize(value: String): String =
            value.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

        /** Route IDs are source-system aliases; topology comes from GTFS patterns. */
        private fun routeLineMappings(catalog: GtfsNetworkCatalog): Map<String, Line> =
            catalog.routesById.values.mapNotNull { route ->
                lineForRouteName(route.shortName)?.let { route.routeId to it }
            }.toMap(LinkedHashMap())

        private fun lineForRouteName(routeName: String?): Line? {
            val normalized = routeName?.lowercase() ?: return null
            return when {
                normalized.startsWith("yellow") -> Line.YELLOW
                normalized.startsWith("orange") -> Line.ORANGE
                normalized.startsWith("green") -> Line.GREEN
                normalized.startsWith("red") -> Line.RED
                normalized.startsWith("blue") -> Line.BLUE
                else -> null
            }
        }

        private fun isExplicitlyUnsupportedRoute(route: GtfsRoute): Boolean {
            val normalized = route.shortName?.lowercase() ?: return false
            return normalized.startsWith("grey") || normalized.startsWith("bridge")
        }

        private fun lineMatches(
            ruleRouteId: String?,
            ruleLine: Line?,
            requestedLine: Line?
        ): Boolean = if (ruleRouteId == null) {
            ruleLine == null || ruleLine == requestedLine
        } else {
            ruleLine == requestedLine
        }

        private fun transferRules(
            catalog: GtfsNetworkCatalog,
            stationsByStopId: Map<String, Station>,
            linesByRouteId: Map<String, Line>
        ): List<TransferRule> = catalog.transfers.mapNotNull { transfer ->
            val fromStation = stationsByStopId[transfer.fromStopId] ?: return@mapNotNull null
            val toStation = stationsByStopId[transfer.toStopId] ?: return@mapNotNull null
            TransferRule.create(
                fromStation,
                toStation,
                transfer.fromRouteId,
                transfer.toRouteId,
                linesByRouteId[transfer.fromRouteId],
                linesByRouteId[transfer.toRouteId],
                transfer.transferType,
                transfer.minimumTransferSeconds
            )
        }

        private fun isNonRevenueOaklandAirportStop(stop: GtfsStop): Boolean =
            listOf(stop.stopId, stop.parentStationId, stop.zoneId)
                .any { it.equals("OAKL", ignoreCase = true) }
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
