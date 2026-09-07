package com.dougkeen.bart.transit.gtfs

/** A canonical stop from stops.txt. */
data class GtfsStop(
    val stopId: String,
    val name: String?,
    val parentStationId: String?,
    val zoneId: String?
)

/** Route metadata from routes.txt. */
data class GtfsRoute(
    val routeId: String,
    val shortName: String?,
    val longName: String?,
    val color: String?,
    val textColor: String?,
    val routeType: Int?
)

/** Trip metadata from trips.txt. */
data class GtfsTrip(
    val tripId: String,
    val routeId: String,
    val serviceId: String?,
    val directionId: String?,
    val headsign: String?
)

/** A platform or route-specific transfer edge from transfers.txt. */
data class GtfsTransfer(
    val fromStopId: String,
    val toStopId: String,
    val transferType: Int,
    val minimumTransferSeconds: Int?,
    val fromRouteId: String?,
    val toRouteId: String?
)

/** A unique ordered stop sequence used by one or more trips. */
class GtfsRoutePattern(
    val routeId: String,
    val directionId: String?,
    stopIds: List<String>,
    headsigns: Set<String>,
    tripIds: Set<String>
) {
    val stopIds: List<String> = immutableList(stopIds)
    val headsigns: Set<String> = immutableSet(headsigns)
    val tripIds: Set<String> = immutableSet(tripIds)

    override fun equals(other: Any?): Boolean =
        other is GtfsRoutePattern
            && routeId == other.routeId
            && directionId == other.directionId
            && stopIds == other.stopIds

    override fun hashCode(): Int =
        ((((routeId.hashCode() * 31) + (directionId?.hashCode() ?: 0)) * 31)
            + stopIds.hashCode())
}

/**
 * Immutable, platform-independent network facts extracted from static GTFS.
 *
 * This deliberately contains no Station or Line enums. Mapping feed IDs to
 * stable app identities is an adapter concern and must remain explicit.
 */
class GtfsNetworkCatalog private constructor(
    stopsById: Map<String, GtfsStop>,
    routesById: Map<String, GtfsRoute>,
    tripsById: Map<String, GtfsTrip>,
    stopIdsByTripId: Map<String, List<String>>,
    patterns: List<GtfsRoutePattern>,
    transfers: List<GtfsTransfer>
) {
    val stopsById: Map<String, GtfsStop> = immutableMap(stopsById)
    val routesById: Map<String, GtfsRoute> = immutableMap(routesById)
    val tripsById: Map<String, GtfsTrip> = immutableMap(tripsById)
    val stopIdsByTripId: Map<String, List<String>> = immutableMap(
        stopIdsByTripId.mapValues { (_, value) -> immutableList(value) }
    )
    val patterns: List<GtfsRoutePattern> = immutableList(patterns)
    val transfers: List<GtfsTransfer> = immutableList(transfers)

    fun routeIdForTrip(tripId: String?): String? =
        tripId?.let { tripsById[it]?.routeId }

    fun patternsForRoute(routeId: String): List<GtfsRoutePattern> =
        patterns.filter { it.routeId == routeId }

    /** Returns structural feed errors without applying BART-specific policy. */
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        tripsById.forEach { (tripId, trip) ->
            if (trip.routeId !in routesById) {
                errors.add("Trip $tripId references unknown route ${trip.routeId}")
            }
            val stopIds = stopIdsByTripId[tripId].orEmpty()
            if (stopIds.size < 2) {
                errors.add("Trip $tripId has fewer than two stops")
            }
            stopIds.forEach { stopId ->
                if (stopId !in stopsById) {
                    errors.add("Trip $tripId references unknown stop $stopId")
                }
            }
        }
        patterns.forEach { pattern ->
            if (pattern.routeId !in routesById) {
                errors.add("Pattern references unknown route ${pattern.routeId}")
            }
            if (pattern.stopIds.size < 2) {
                errors.add("Pattern ${pattern.routeId} has fewer than two stops")
            }
            pattern.stopIds.forEach { stopId ->
                if (stopId !in stopsById) {
                    errors.add("Pattern ${pattern.routeId} references unknown stop $stopId")
                }
            }
            pattern.tripIds.forEach { tripId ->
                if (tripId !in tripsById) {
                    errors.add("Pattern references unknown trip $tripId")
                }
            }
        }
        transfers.forEach { transfer ->
            if (transfer.fromStopId !in stopsById) {
                errors.add("Transfer references unknown from-stop ${transfer.fromStopId}")
            }
            if (transfer.toStopId !in stopsById) {
                errors.add("Transfer references unknown to-stop ${transfer.toStopId}")
            }
            if (transfer.fromRouteId != null && transfer.fromRouteId !in routesById) {
                errors.add("Transfer references unknown from-route ${transfer.fromRouteId}")
            }
            if (transfer.toRouteId != null && transfer.toRouteId !in routesById) {
                errors.add("Transfer references unknown to-route ${transfer.toRouteId}")
            }
        }
        return immutableList(errors)
    }

    companion object {
        private const val STOPS = "stops.txt"
        private const val ROUTES = "routes.txt"
        private const val TRIPS = "trips.txt"
        private const val STOP_TIMES = "stop_times.txt"
        private const val TRANSFERS = "transfers.txt"

        @JvmStatic
        fun fromFiles(files: Map<String, String>): GtfsNetworkCatalog {
            val stops = parseStops(requiredFile(files, STOPS))
            val routes = parseRoutes(requiredFile(files, ROUTES))
            val trips = parseTrips(requiredFile(files, TRIPS))
            val stopIdsByTrip = parseStopTimes(
                requiredFile(files, STOP_TIMES),
                trips.keys
            )
            val transfers = files[TRANSFERS]?.let(::parseTransfers).orEmpty()

            val patterns = buildPatterns(trips, stopIdsByTrip)
            return GtfsNetworkCatalog(
                stopsById = stops.toMap(),
                routesById = routes.toMap(),
                tripsById = trips.toMap(),
                stopIdsByTripId = stopIdsByTrip.mapValues { it.value.toList() }.toMap(),
                patterns = patterns.toList(),
                transfers = transfers
            )
        }

        private fun requiredFile(files: Map<String, String>, name: String): String =
            files[name] ?: throw IllegalArgumentException("GTFS file is missing: $name")

        private fun parseStops(input: String): Map<String, GtfsStop> =
            rows(input).mapNotNull { row ->
                val id = row["stop_id"].orEmpty()
                if (id.isEmpty()) {
                    null
                } else {
                    GtfsStop(
                        stopId = id,
                        name = row.optional("stop_name"),
                        parentStationId = row.optional("parent_station"),
                        zoneId = row.optional("zone_id")
                    )
                }
            }.associateBy { it.stopId }

        private fun parseRoutes(input: String): Map<String, GtfsRoute> =
            rows(input).mapNotNull { row ->
                val id = row["route_id"].orEmpty()
                if (id.isEmpty()) {
                    null
                } else {
                    GtfsRoute(
                        routeId = id,
                        shortName = row.optional("route_short_name"),
                        longName = row.optional("route_long_name"),
                        color = row.optional("route_color"),
                        textColor = row.optional("route_text_color"),
                        routeType = row.optional("route_type")?.toIntOrNull()
                    )
                }
            }.associateBy { it.routeId }

        private fun parseTrips(input: String): Map<String, GtfsTrip> =
            rows(input).mapNotNull { row ->
                val tripId = row["trip_id"].orEmpty()
                val routeId = row["route_id"].orEmpty()
                if (tripId.isEmpty() || routeId.isEmpty()) {
                    null
                } else {
                    GtfsTrip(
                        tripId = tripId,
                        routeId = routeId,
                        serviceId = row.optional("service_id"),
                        directionId = row.optional("direction_id"),
                        headsign = row.optional("trip_headsign")
                    )
                }
            }.associateBy { it.tripId }

        private fun parseStopTimes(
            input: String,
            knownTripIds: Set<String>
        ): Map<String, List<String>> {
            data class StopTime(val stopId: String, val sequence: Int, val rowOrder: Int)

            val byTrip = linkedMapOf<String, MutableList<StopTime>>()
            rows(input).forEachIndexed { rowOrder, row ->
                val tripId = row["trip_id"].orEmpty()
                val stopId = row["stop_id"].orEmpty()
                val sequence = row["stop_sequence"]?.toIntOrNull()
                if (tripId in knownTripIds && stopId.isNotEmpty() && sequence != null) {
                    byTrip.getOrPut(tripId) { mutableListOf() }
                        .add(StopTime(stopId, sequence, rowOrder))
                }
            }
            return byTrip.mapValues { (_, stopTimes) ->
                stopTimes.sortedWith(compareBy<StopTime> { it.sequence }.thenBy { it.rowOrder })
                    .map { it.stopId }
            }
        }

        private fun parseTransfers(input: String): List<GtfsTransfer> =
            rows(input).mapNotNull { row ->
                val fromStopId = row["from_stop_id"].orEmpty()
                val toStopId = row["to_stop_id"].orEmpty()
                val transferType = row["transfer_type"]?.toIntOrNull()
                if (fromStopId.isEmpty() || toStopId.isEmpty() || transferType == null) {
                    null
                } else {
                    GtfsTransfer(
                        fromStopId = fromStopId,
                        toStopId = toStopId,
                        transferType = transferType,
                        minimumTransferSeconds = row.optional("min_transfer_time")
                            ?.toIntOrNull(),
                        fromRouteId = row.optional("from_route_id"),
                        toRouteId = row.optional("to_route_id")
                    )
                }
            }

        private fun buildPatterns(
            trips: Map<String, GtfsTrip>,
            stopIdsByTrip: Map<String, List<String>>
        ): List<GtfsRoutePattern> {
            data class PatternKey(
                val routeId: String,
                val directionId: String?,
                val stopIds: List<String>
            )

            data class PatternBuilder(
                val routeId: String,
                val directionId: String?,
                val stopIds: List<String>,
                val headsigns: MutableSet<String> = linkedSetOf(),
                val tripIds: MutableSet<String> = linkedSetOf()
            )

            val builders = linkedMapOf<PatternKey, PatternBuilder>()
            trips.values.forEach { trip ->
                val stopIds = stopIdsByTrip[trip.tripId].orEmpty()
                if (stopIds.isEmpty()) {
                    return@forEach
                }
                val key = PatternKey(trip.routeId, trip.directionId, stopIds)
                val builder = builders.getOrPut(key) {
                    PatternBuilder(trip.routeId, trip.directionId, stopIds)
                }
                trip.headsign?.let { builder.headsigns.add(it) }
                builder.tripIds.add(trip.tripId)
            }
            return builders.values
                .sortedWith(compareBy<PatternBuilder> { it.routeId }
                    .thenBy { it.directionId ?: "" }
                    .thenBy { it.stopIds.joinToString("\u0000") })
                .map {
                    GtfsRoutePattern(
                        routeId = it.routeId,
                        directionId = it.directionId,
                        stopIds = it.stopIds.toList(),
                        headsigns = it.headsigns.toSet(),
                        tripIds = it.tripIds.toSet()
                    )
                }
        }

        private fun rows(input: String): List<Map<String, String>> {
            val records = parseCsvRecords(input)
            if (records.isEmpty()) {
                return emptyList()
            }
            val headers = records.first().mapIndexed { index, value ->
                if (index == 0) value.removePrefix("\uFEFF") else value
            }
            return records.drop(1)
                .filter { record -> record.any { it.isNotEmpty() } }
                .map { record ->
                    headers.mapIndexedNotNull { index, header ->
                        if (header.isEmpty()) {
                            null
                        } else {
                            header to record.getOrElse(index) { "" }
                        }
                    }.toMap()
                }
        }

        private fun Map<String, String>.optional(key: String): String? =
            this[key]?.trim()?.takeIf { it.isNotEmpty() }

        private fun parseCsvRecords(input: String): List<List<String>> {
            val result = mutableListOf<List<String>>()
            var record = mutableListOf<String>()
            var field = StringBuilder()
            var quoted = false
            var index = 0

            fun finishField() {
                record.add(field.toString())
                field = StringBuilder()
            }

            fun finishRecord() {
                finishField()
                if (record.any { it.isNotEmpty() }) {
                    result.add(record)
                }
                record = mutableListOf()
            }

            while (index < input.length) {
                val character = input[index]
                if (quoted) {
                    if (character == '"') {
                        if (index + 1 < input.length && input[index + 1] == '"') {
                            field.append('"')
                            index++
                        } else {
                            quoted = false
                        }
                    } else {
                        field.append(character)
                    }
                } else {
                    when (character) {
                        '"' -> quoted = true
                        ',' -> finishField()
                        '\r' -> {
                            finishRecord()
                            if (index + 1 < input.length && input[index + 1] == '\n') {
                                index++
                            }
                        }
                        '\n' -> finishRecord()
                        else -> field.append(character)
                    }
                }
                index++
            }
            if (field.isNotEmpty() || record.isNotEmpty()) {
                finishRecord()
            }
            return result
        }
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    java.util.Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    java.util.Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    java.util.Collections.unmodifiableMap(LinkedHashMap(values))
