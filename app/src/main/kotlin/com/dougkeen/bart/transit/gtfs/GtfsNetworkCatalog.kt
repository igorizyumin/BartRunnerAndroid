package com.dougkeen.bart.transit.gtfs

import com.dougkeen.bart.performance.PerformanceTrace
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate

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

/** One ordered stop-time row from stop_times.txt. */
data class GtfsStopTime(
    val stopId: String,
    val sequence: Int,
    val arrivalSeconds: Int?,
    val departureSeconds: Int?
)

/** A trip's static schedule for one service date. */
data class GtfsScheduledTrip(
    val trip: GtfsTrip,
    val stopTimes: List<GtfsStopTime>
)

internal data class GtfsCalendar(
    val serviceId: String,
    val monday: Boolean,
    val tuesday: Boolean,
    val wednesday: Boolean,
    val thursday: Boolean,
    val friday: Boolean,
    val saturday: Boolean,
    val sunday: Boolean,
    val startDate: LocalDate,
    val endDate: LocalDate
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
    stopTimesByTripId: Map<String, List<GtfsStopTime>>,
    calendarsByServiceId: Map<String, GtfsCalendar>,
    calendarDatesByServiceId: Map<String, Map<LocalDate, Int>>,
    patterns: List<GtfsRoutePattern>,
    transfers: List<GtfsTransfer>
) {
    val stopsById: Map<String, GtfsStop> = immutableMap(stopsById)
    val routesById: Map<String, GtfsRoute> = immutableMap(routesById)
    val tripsById: Map<String, GtfsTrip> = immutableMap(tripsById)
    val stopIdsByTripId: Map<String, List<String>> = immutableMap(
        stopIdsByTripId.mapValues { (_, value) -> immutableList(value) }
    )
    val stopTimesByTripId: Map<String, List<GtfsStopTime>> = immutableMap(
        stopTimesByTripId.mapValues { (_, value) -> immutableList(value) }
    )
    private val calendarsByServiceId = immutableMap(calendarsByServiceId)
    private val calendarDatesByServiceId = immutableMap(
        calendarDatesByServiceId.mapValues { (_, value) -> immutableMap(value) }
    )
    val patterns: List<GtfsRoutePattern> = immutableList(patterns)
    val transfers: List<GtfsTransfer> = immutableList(transfers)

    fun routeIdForTrip(tripId: String?): String? =
        tripId?.let { tripsById[it]?.routeId }

    fun patternsForRoute(routeId: String): List<GtfsRoutePattern> =
        patterns.filter { it.routeId == routeId }

    /** Returns trips running on a service date, including GTFS exceptions. */
    fun scheduledTripsFor(serviceDate: LocalDate): List<GtfsScheduledTrip> =
        PerformanceTrace.section("BART scheduled trips") {
            tripsById.values.mapNotNull { trip ->
                val serviceId = trip.serviceId ?: return@mapNotNull null
                if (!isServiceActive(serviceId, serviceDate)) {
                    return@mapNotNull null
                }
                val stopTimes = stopTimesByTripId[trip.tripId].orEmpty()
                if (stopTimes.isEmpty()) null else GtfsScheduledTrip(trip, stopTimes)
            }
        }

    private fun isServiceActive(serviceId: String, date: LocalDate): Boolean {
        val exception = calendarDatesByServiceId[serviceId]?.get(date)
        if (exception != null) {
            return exception == 1
        }
        val calendar = calendarsByServiceId[serviceId] ?: return false
        if (date < calendar.startDate || date > calendar.endDate) {
            return false
        }
        return when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> calendar.monday
            DayOfWeek.TUESDAY -> calendar.tuesday
            DayOfWeek.WEDNESDAY -> calendar.wednesday
            DayOfWeek.THURSDAY -> calendar.thursday
            DayOfWeek.FRIDAY -> calendar.friday
            DayOfWeek.SATURDAY -> calendar.saturday
            DayOfWeek.SUNDAY -> calendar.sunday
        }
    }

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
        private const val CALENDAR = "calendar.txt"
        private const val CALENDAR_DATES = "calendar_dates.txt"

        @JvmStatic
        fun fromFiles(files: Map<String, String>): GtfsNetworkCatalog {
            val stops = PerformanceTrace.section("BART parse stops") {
                parseStops(requiredFile(files, STOPS))
            }
            val routes = PerformanceTrace.section("BART parse routes") {
                parseRoutes(requiredFile(files, ROUTES))
            }
            val trips = PerformanceTrace.section("BART parse trips") {
                parseTrips(requiredFile(files, TRIPS))
            }
            val stopTimesByTrip = PerformanceTrace.section("BART parse stop times") {
                parseStopTimes(
                    requiredFile(files, STOP_TIMES),
                    trips.keys
                )
            }
            val stopIdsByTrip = PerformanceTrace.section("BART derive trip stop IDs") {
                stopTimesByTrip.mapValues { (_, stopTimes) ->
                    stopTimes.map { it.stopId }
                }
            }
            val calendars = PerformanceTrace.section("BART parse calendars") {
                files[CALENDAR]?.let(::parseCalendars).orEmpty()
            }
            val calendarDates = PerformanceTrace.section("BART parse calendar dates") {
                files[CALENDAR_DATES]?.let(::parseCalendarDates).orEmpty()
            }
            val transfers = PerformanceTrace.section("BART parse transfers") {
                files[TRANSFERS]?.let(::parseTransfers).orEmpty()
            }

            val patterns = PerformanceTrace.section("BART build patterns") {
                buildPatterns(trips, stopIdsByTrip)
            }
            return PerformanceTrace.section("BART freeze catalog") {
                GtfsNetworkCatalog(
                    stopsById = stops.toMap(),
                    routesById = routes.toMap(),
                    tripsById = trips.toMap(),
                    stopIdsByTripId = stopIdsByTrip.mapValues { it.value.toList() }.toMap(),
                    stopTimesByTripId = stopTimesByTrip.mapValues { it.value.toList() }.toMap(),
                    calendarsByServiceId = calendars,
                    calendarDatesByServiceId = calendarDates,
                    patterns = patterns.toList(),
                    transfers = transfers
                )
            }
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
            withCsv(input) { parser ->
                val tripIdIndex = parser.columnIndex("trip_id")
                val routeIdIndex = parser.columnIndex("route_id")
                val serviceIdIndex = parser.columnIndex("service_id")
                val directionIdIndex = parser.columnIndex("direction_id")
                val headsignIndex = parser.columnIndex("trip_headsign")
                val trips = linkedMapOf<String, GtfsTrip>()
                for (row in parser) {
                    val tripId = row.valueAt(tripIdIndex).orEmpty()
                    val routeId = row.valueAt(routeIdIndex).orEmpty()
                    if (tripId.isNotEmpty() && routeId.isNotEmpty()) {
                        trips[tripId] = GtfsTrip(
                            tripId = tripId,
                            routeId = routeId,
                            serviceId = row.optionalAt(serviceIdIndex),
                            directionId = row.optionalAt(directionIdIndex),
                            headsign = row.optionalAt(headsignIndex)
                        )
                    }
                }
                trips
            }

        private fun parseStopTimes(
            input: String,
            knownTripIds: Set<String>
        ): Map<String, List<GtfsStopTime>> {
            data class StopTime(val value: GtfsStopTime, val rowOrder: Int)

            val byTrip = linkedMapOf<String, MutableList<StopTime>>()
            withCsv(input) { parser ->
                val tripIdIndex = parser.columnIndex("trip_id")
                val stopIdIndex = parser.columnIndex("stop_id")
                val sequenceIndex = parser.columnIndex("stop_sequence")
                val arrivalIndex = parser.columnIndex("arrival_time")
                val departureIndex = parser.columnIndex("departure_time")
                for ((rowOrder, row) in parser.withIndex()) {
                    val tripId = row.valueAt(tripIdIndex).orEmpty()
                    val stopId = row.valueAt(stopIdIndex).orEmpty()
                    val sequence = row.valueAt(sequenceIndex)?.toIntOrNull()
                    if (tripId in knownTripIds && stopId.isNotEmpty() && sequence != null) {
                        byTrip.getOrPut(tripId) { mutableListOf() }.add(
                            StopTime(
                                GtfsStopTime(
                                    stopId,
                                    sequence,
                                    parseGtfsTime(row.optionalAt(arrivalIndex)),
                                    parseGtfsTime(row.optionalAt(departureIndex))
                                ),
                                rowOrder
                            )
                        )
                    }
                }
            }
            return byTrip.mapValues { (_, stopTimes) ->
                stopTimes.sortedWith(compareBy<StopTime> { it.value.sequence }.thenBy { it.rowOrder })
                    .map { it.value }
            }
        }

        private fun parseCalendars(input: String): Map<String, GtfsCalendar> =
            rows(input).mapNotNull { row ->
                val serviceId = row["service_id"].orEmpty()
                val startDate = parseDate(row.optional("start_date"))
                val endDate = parseDate(row.optional("end_date"))
                if (serviceId.isEmpty() || startDate == null || endDate == null) {
                    null
                } else {
                    GtfsCalendar(
                        serviceId,
                        row.optional("monday") == "1",
                        row.optional("tuesday") == "1",
                        row.optional("wednesday") == "1",
                        row.optional("thursday") == "1",
                        row.optional("friday") == "1",
                        row.optional("saturday") == "1",
                        row.optional("sunday") == "1",
                        startDate,
                        endDate
                    )
                }
            }.associateBy { it.serviceId }

        private fun parseCalendarDates(input: String): Map<String, Map<LocalDate, Int>> =
            rows(input).mapNotNull { row ->
                val serviceId = row["service_id"].orEmpty()
                val date = parseDate(row.optional("date"))
                val exceptionType = row.optional("exception_type")?.toIntOrNull()
                if (serviceId.isEmpty() || date == null || exceptionType == null) {
                    null
                } else {
                    Triple(serviceId, date, exceptionType)
                }
            }.groupBy { it.first }
                .mapValues { (_, values) -> values.associate { it.second to it.third } }

        private fun parseGtfsTime(value: String?): Int? {
            if (value.isNullOrBlank()) return null
            val firstColon = value.indexOf(':')
            val secondColon = value.indexOf(':', firstColon + 1)
            if (firstColon <= 0 || secondColon <= firstColon + 1
                || secondColon == value.lastIndex
                || value.indexOf(':', secondColon + 1) >= 0
            ) {
                return null
            }
            val hours = parseDigits(value, 0, firstColon) ?: return null
            val minutes = parseDigits(value, firstColon + 1, secondColon) ?: return null
            val seconds = parseDigits(value, secondColon + 1, value.length) ?: return null
            if (hours < 0 || minutes !in 0..59 || seconds !in 0..59) return null
            return hours * 3600 + minutes * 60 + seconds
        }

        private fun parseDigits(value: String, start: Int, end: Int): Int? {
            var result = 0
            for (index in start until end) {
                val digit = value[index] - '0'
                if (digit !in 0..9) return null
                result = result * 10 + digit
            }
            return result
        }

        private fun parseDate(value: String?): LocalDate? = try {
            if (value.isNullOrBlank() || value.length != 8) null
            else LocalDate.of(
                value.substring(0, 4).toInt(),
                value.substring(4, 6).toInt(),
                value.substring(6, 8).toInt()
            )
        } catch (_: RuntimeException) {
            null
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
            return withCsv(input) { parser ->
                parser.filter { record -> record.any { it.isNotEmpty() } }
                    .map { it.toMap() }
            }
        }

        private fun <T> withCsv(input: String, block: (CSVParser) -> T): T {
            try {
                return CSVParser.parse(
                    input.removePrefix("\uFEFF"),
                    CSV_FORMAT
                ).use(block)
            } catch (exception: IOException) {
                throw IllegalArgumentException("Could not parse CSV", exception)
            }
        }

        private fun CSVParser.columnIndex(name: String): Int =
            headerMap[name] ?: -1

        private fun CSVRecord.valueAt(index: Int): String? =
            if (index >= 0 && index < size()) get(index) else null

        private fun CSVRecord.optionalAt(index: Int): String? =
            valueAt(index)?.trim()?.takeIf { it.isNotEmpty() }

        private val CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get()

        private fun Map<String, String>.optional(key: String): String? =
            this[key]?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    java.util.Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    java.util.Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    java.util.Collections.unmodifiableMap(LinkedHashMap(values))
