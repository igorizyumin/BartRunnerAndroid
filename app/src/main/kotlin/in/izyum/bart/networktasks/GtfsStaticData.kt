package `in`.izyum.bart.networktasks

import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.SystemTimeSource
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.performance.PerformanceTrace
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsCalendar
import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog
import `in`.izyum.bart.transit.gtfs.GtfsRoute
import `in`.izyum.bart.transit.gtfs.GtfsRoutePattern
import `in`.izyum.bart.transit.gtfs.GtfsScheduledTrip
import `in`.izyum.bart.transit.gtfs.GtfsStop
import `in`.izyum.bart.transit.gtfs.GtfsTransfer
import `in`.izyum.bart.transit.gtfs.GtfsTrip
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.zip.ZipInputStream

data class RiderCategory(
    val id: String,
    val description: String,
)

/** Loads BART's static GTFS feed at most once per seven days. */
class GtfsStaticData @JvmOverloads constructor(
    context: Context,
    private val timeSource: TimeSource = SystemTimeSource,
) {
    private val applicationContext = context.applicationContext
    private val lock = Any()
    private var cachedData: LoadedData? = null
    private var cachedAt = 0L
    private var database: GtfsStaticDatabase? = null

    @Throws(IOException::class)
    fun getBartGtfsNetwork(): BartGtfsNetwork = load().bartGtfsNetwork

    @Throws(IOException::class)
    fun getFare(
        origin: Station,
        destination: Station,
        riderCategoryId: String? = null,
    ): String? {
        load()
        val fareKey = key(origin, destination)
        val dao = openDatabase().dao()
        val categoryFare = riderCategoryId
            ?.takeIf { it.isNotEmpty() }
            ?.let { dao.fare(fareKey, it)?.price }
        return categoryFare ?: dao.fare(fareKey, BASE_RIDER_CATEGORY_ID)?.price
    }

    @Throws(IOException::class)
    fun getRiderCategories(): List<RiderCategory> {
        load()
        return openDatabase().dao().riderCategories()
            .map { RiderCategory(it.riderCategoryId, it.description) }
    }

    /** Ensures the weekly static-feed refresh has completed off the UI thread. */
    @Throws(IOException::class)
    fun warmUp() {
        loadLocked(refreshStale = true)
    }

    /** Returns true once the first normalized static-feed database exists. */
    fun hasDatabaseCache(): Boolean =
        applicationContext.getDatabasePath(DATABASE_FILE_NAME).isFile

    @Throws(IOException::class)
    private fun load(): LoadedData = PerformanceTrace.section("BART static data load") {
        loadLocked(refreshStale = false)
    }

    @Throws(IOException::class)
    private fun loadLocked(refreshStale: Boolean): LoadedData {
        synchronized(lock) {
            val now = timeSource.nowMillis()
            if (cachedData != null && now - cachedAt < CACHE_MILLIS) {
                return cachedData!!
            }

            val cacheFile = File(applicationContext.filesDir, CACHE_FILE_NAME)
            val preferences = applicationContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val lastSuccess = preferences.getLong(LAST_SUCCESS, 0L)
            val databaseFile = applicationContext.getDatabasePath(DATABASE_FILE_NAME)
            var cachedResult: LoadedData? = null
            if (databaseFile.isFile) {
                cachedResult = readCached(lastSuccess, now)
                if (cachedResult == null) {
                    // A schema upgrade or damaged cache must be allowed to retry
                    // immediately instead of being held by the weekly backoff.
                    preferences.edit { remove(LAST_ATTEMPT) }
                }
                if (cachedResult != null
                    && (!refreshStale || now - lastSuccess < CACHE_MILLIS)
                ) {
                    return cachedResult
                }
            }

            // Import the previous ZIP cache once after upgrading from the
            // file-backed format. Future process starts use only SQLite.
            if (!databaseFile.isFile && cacheFile.isFile) {
                val importedAt = lastSuccess.takeIf { it > 0 } ?: now
                importFeed(cacheFile, importedAt)
                preferences.edit { putLong(LAST_SUCCESS, importedAt) }
                cachedResult = readCached(importedAt, now)
                if (cachedResult != null
                    && (!refreshStale || now - importedAt < CACHE_MILLIS)
                ) {
                    return cachedResult
                }
            }

            if (!refreshStale && cachedResult != null) {
                return cachedResult
            }

            val lastAttempt = preferences.getLong(LAST_ATTEMPT, 0L)
            if (now - lastAttempt < CACHE_MILLIS) {
                cachedResult?.let { return it }
                throw IOException("Static GTFS refresh already attempted")
            }

            preferences.edit { putLong(LAST_ATTEMPT, now) }
            val temporaryFile = File(applicationContext.filesDir, "$CACHE_FILE_NAME.tmp")
            try {
                download(temporaryFile)
                val downloadedVersion = readFeedVersion(temporaryFile)
                val storedVersion = cachedResult?.let {
                    openDatabase().dao().metadata()?.feedVersion
                }
                if (cachedResult != null
                    && downloadedVersion != null
                    && downloadedVersion == storedVersion
                ) {
                    temporaryFile.delete()
                    preferences.edit { putLong(LAST_SUCCESS, now) }
                    cachedAt = now
                    return cachedResult
                }
                importFeed(temporaryFile, now)
                temporaryFile.delete()
                preferences.edit { putLong(LAST_SUCCESS, now) }
                readCached(now, now)?.let { return it }
                throw IOException("Could not load imported static GTFS database")
            } catch (exception: IOException) {
                temporaryFile.delete()
                cachedResult?.let { return it }
                readCached(lastSuccess, now)?.let { return it }
                throw exception
            }
        }
    }

    private fun readCached(
        lastSuccess: Long,
        now: Long,
    ): LoadedData? = PerformanceTrace.section("BART static database load") {
        try {
            val db = openDatabase()
            val parts = db.dao().readNetworkParts()
            buildLoadedData(db, parts).also {
                cachedData = it
                cachedAt = if (lastSuccess > 0) lastSuccess else now
            }
        } catch (exception: Exception) {
            invalidateDatabase()
            null
        }
    }

    private fun importFeed(file: File, importedAtMillis: Long) {
        PerformanceTrace.section("BART static database import") {
            val parsed = parse(file)
            openDatabase().dao().replace(
                GtfsDatabaseSnapshot.fromCatalog(
                    parsed.catalog,
                    parsed.faresByStationPair,
                    parsed.feedVersion,
                    importedAtMillis,
                    parsed.discountedFaresByStationPair,
                    parsed.riderCategories.map { category ->
                        GtfsRiderCategoryEntity(category.id, category.description)
                    },
                )
            )
        }
        cachedData = null
    }

    private fun openDatabase(): GtfsStaticDatabase =
        database ?: Room.databaseBuilder(
            applicationContext,
            GtfsStaticDatabase::class.java,
            DATABASE_FILE_NAME,
        ).fallbackToDestructiveMigration(dropAllTables = true).build().also { database = it }

    private fun invalidateDatabase() {
        database?.close()
        database = null
        applicationContext.getDatabasePath(DATABASE_FILE_NAME).delete()
    }

    private fun buildLoadedData(
        db: GtfsStaticDatabase,
        parts: GtfsNetworkParts,
    ): LoadedData {
        val catalog = catalogFromDatabase(parts)
        val network = PerformanceTrace.section("BART network mapping") {
            BartGtfsNetwork.fromCatalog(catalog) { serviceDate, routeIds, start, end ->
                scheduledTripsFor(db.dao(), catalog, serviceDate, routeIds, start, end)
            }
        }
        PerformanceTrace.section("BART network validation") {
            network.validationErrors().firstOrNull()
        }?.let { throw IOException("Static GTFS BART validation failed: $it") }
        return LoadedData(bartGtfsNetwork = network)
    }

    private fun catalogFromDatabase(parts: GtfsNetworkParts): GtfsNetworkCatalog {
        val stops = parts.stops.associate { entity ->
            entity.stopId to GtfsStop(
                entity.stopId,
                entity.name,
                entity.parentStationId,
                entity.zoneId,
            )
        }
        val routes = parts.routes.associate { entity ->
            entity.routeId to GtfsRoute(
                entity.routeId,
                entity.shortName,
                entity.longName,
                entity.color,
                entity.textColor,
                entity.routeType,
            )
        }
        val trips = parts.trips.associate { entity ->
            entity.tripId to GtfsTrip(
                entity.tripId,
                entity.routeId,
                entity.serviceId,
                entity.directionId,
                entity.headsign,
            )
        }
        val stopsByPattern = parts.patternStops.groupBy { it.patternId }
            .mapValues { (_, values) -> values.sortedBy { it.sequence }.map { it.stopId } }
        val tripIdsByPattern = parts.patternTrips.groupBy { it.patternId }
            .mapValues { (_, values) -> values.map { it.tripId }.toSet() }
        val headsignsByPattern = parts.patternHeadsigns.groupBy { it.patternId }
            .mapValues { (_, values) -> values.map { it.headsign }.toSet() }
        val patterns = parts.patterns.map { pattern ->
            GtfsRoutePattern(
                routeId = pattern.routeId,
                directionId = pattern.directionId,
                stopIds = stopsByPattern[pattern.patternId].orEmpty(),
                headsigns = headsignsByPattern[pattern.patternId].orEmpty(),
                tripIds = tripIdsByPattern[pattern.patternId].orEmpty(),
            )
        }
        val stopIdsByTripId = parts.patternTrips.associate { patternTrip ->
            patternTrip.tripId to stopsByPattern[patternTrip.patternId].orEmpty()
        }
        val calendars = parts.calendars.associate { calendar ->
            calendar.serviceId to GtfsCalendar(
                calendar.serviceId,
                calendar.monday,
                calendar.tuesday,
                calendar.wednesday,
                calendar.thursday,
                calendar.friday,
                calendar.saturday,
                calendar.sunday,
                LocalDate.parse(calendar.startDate),
                LocalDate.parse(calendar.endDate),
            )
        }
        val calendarDates = parts.calendarDates.groupBy { it.serviceId }
            .mapValues { (_, values) ->
                values.associate { LocalDate.parse(it.date) to it.exceptionType }
            }
        val transfers = parts.transfers.map { transfer ->
            GtfsTransfer(
                transfer.fromStopId,
                transfer.toStopId,
                transfer.transferType,
                transfer.minimumTransferSeconds,
                transfer.fromRouteId,
                transfer.toRouteId,
            )
        }
        return GtfsNetworkCatalog.fromPersisted(
            stops,
            routes,
            trips,
            stopIdsByTripId,
            calendars,
            calendarDates,
            patterns,
            transfers,
        )
    }

    private fun scheduledTripsFor(
        dao: GtfsStaticDao,
        catalog: GtfsNetworkCatalog,
        serviceDate: LocalDate,
        routeIds: Set<String>,
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): List<GtfsScheduledTrip> {
        if (routeIds.isEmpty()) return emptyList()
        val serviceIds = catalog.activeServiceIdsFor(serviceDate)
        if (serviceIds.isEmpty()) return emptyList()
        val serviceStartMillis = serviceDate.atStartOfDay(PACIFIC_ZONE)
            .toInstant().toEpochMilli()
        val minimumSeconds = Math.floorDiv(
            windowStartMillis - serviceStartMillis,
            1000L,
        ).toInt()
        val maximumSeconds = Math.floorDiv(
            windowEndMillis - serviceStartMillis,
            1000L,
        ).toInt() + 1
        val tripIds = dao.candidateTripIds(
            routeIds.toList(),
            serviceIds.toList(),
            minimumSeconds,
            maximumSeconds,
        )
        if (tripIds.isEmpty()) return emptyList()
        val stopTimesByTrip = dao.stopTimesByTripId(tripIds).groupBy { it.tripId }
        return tripIds.mapNotNull { tripId ->
            val trip = catalog.tripsById[tripId] ?: return@mapNotNull null
            val stopTimes = stopTimesByTrip[tripId].orEmpty().map { stopTime ->
                `in`.izyum.bart.transit.gtfs.GtfsStopTime(
                    stopTime.stopId,
                    stopTime.sequence,
                    stopTime.arrivalSeconds,
                    stopTime.departureSeconds,
                )
            }
            if (stopTimes.isEmpty()) null else GtfsScheduledTrip(trip, stopTimes)
        }
    }

    companion object {
        private const val FEED_URL = "https://www.bart.gov/dev/schedules/google_transit.zip"
        // BART recommends checking the static schedule feed weekly.
        private const val CACHE_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private const val CACHE_FILE_NAME = "gtfs_static_schedule.zip"
        private const val DATABASE_FILE_NAME = "gtfs_static_schedule.db"
        private const val PREFS_NAME = "gtfs_static_schedule"
        private const val LAST_ATTEMPT = "last_attempt"
        private const val LAST_SUCCESS = "last_success"
        private val PACIFIC_ZONE = ZoneId.of("America/Los_Angeles")
        private val CLIENT: OkHttpClient = NetworkUtils.makeHttpClient()

        @Throws(IOException::class)
        private fun download(destination: File) =
            PerformanceTrace.section("BART static feed download") {
                val request = Request.Builder().url(FEED_URL)
                    .header("Accept", "application/zip")
                    .build()
                CLIENT.newCall(request).execute().use { response ->
                    if (!response.isSuccessful || response.body == null) {
                        throw IOException("Static GTFS returned ${response.code}")
                    }
                    FileOutputStream(destination, false).use { output ->
                        response.body!!.byteStream().use { input ->
                            input.copyTo(output, 8192)
                        }
                    }
                }
            }

        @Throws(IOException::class)
        private fun readFeedFiles(file: File): Map<String, String> {
            val files = HashMap<String, String>()
            ZipInputStream(FileInputStream(file)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !isRelevantFeedFile(entry.name)) {
                        continue
                    }
                    val output = ByteArrayOutputStream()
                    zip.copyTo(output, 8192)
                    files[entry.name] = output.toString(StandardCharsets.UTF_8.name())
                }
            }
            return files
        }

        @Throws(IOException::class)
        private fun readFeedVersion(file: File): String? {
            ZipInputStream(FileInputStream(file)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: return null
                    if (entry.isDirectory || entry.name != "feed_info.txt") {
                        continue
                    }
                    val output = ByteArrayOutputStream()
                    zip.copyTo(output, 8192)
                    return parseFeedVersion(
                        output.toString(StandardCharsets.UTF_8.name())
                    )
                }
            }
        }

        private fun isRelevantFeedFile(name: String): Boolean = name in setOf(
            "routes.txt", "trips.txt",
            "stops.txt", "stop_times.txt", "calendar.txt", "calendar_dates.txt",
            "transfers.txt", "fare_attributes.txt",
            "fare_rules.txt", "fare_rider_categories.txt", "rider_categories.txt",
            "feed_info.txt"
        )

        private fun input(value: String): InputStream =
            ByteArrayInputStream(value.toByteArray(StandardCharsets.UTF_8))

        @Throws(IOException::class)
        private fun parse(file: File): ParsedFeed =
            PerformanceTrace.section("BART static feed parse") {
            val feedFiles = PerformanceTrace.section("BART static zip read") {
                readFeedFiles(file)
            }
        val feedVersion = feedFiles["feed_info.txt"]?.let(::parseFeedVersion)
        val farePrices = HashMap<String, String>()
        val fareRules = mutableListOf<FareRule>()
        val riderCategories = HashMap<String, String>()
        val discountedFarePrices = HashMap<String, MutableMap<String, String>>()

        feedFiles["fare_attributes.txt"]?.let { parseFareAttributes(input(it), farePrices) }
        feedFiles["fare_rules.txt"]?.let { parseFareRules(input(it), fareRules) }
        feedFiles["rider_categories.txt"]?.let {
            parseRiderCategories(input(it), riderCategories)
        }
        feedFiles["fare_rider_categories.txt"]?.let {
            parseFareRiderCategories(input(it), discountedFarePrices)
        }

            val fares = HashMap<String, String>()
            for (rule in fareRules) {
                val price = farePrices[rule.fareId]
                if (price != null && rule.origin.isNotEmpty() && rule.destination.isNotEmpty()) {
                    fares["${rule.origin}>${rule.destination}"] = "$$price"
                }
            }

            val discountedFares = HashMap<String, MutableMap<String, String>>()
            for (rule in fareRules) {
                if (rule.origin.isEmpty() || rule.destination.isEmpty()) continue
                val fareKey = "${rule.origin}>${rule.destination}"
                discountedFarePrices[rule.fareId].orEmpty().forEach { (categoryId, price) ->
                    if (categoryId in riderCategories) {
                        discountedFares
                            .getOrPut(fareKey) { HashMap() }[categoryId] = "$$price"
                    }
                }
            }

            for (requiredFile in listOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt")) {
                if (requiredFile !in feedFiles) {
                    throw IOException("Static GTFS is missing $requiredFile")
                }
            }

            val networkCatalog: GtfsNetworkCatalog
            try {
                networkCatalog = PerformanceTrace.section("BART catalog build") {
                    GtfsNetworkCatalog.fromFiles(feedFiles)
                }
                PerformanceTrace.section("BART catalog validation") {
                    networkCatalog.validationErrors().firstOrNull()
                }?.let {
                    throw IOException("Static GTFS catalog validation failed: $it")
                }
                PerformanceTrace.section("BART network validation") {
                    BartGtfsNetwork.fromCatalog(networkCatalog).validationErrors().firstOrNull()
                }?.let {
                    throw IOException("Static GTFS BART validation failed: $it")
                }
            } catch (exception: IllegalArgumentException) {
                throw IOException("Could not parse static GTFS network catalog", exception)
            }
            ParsedFeed(
                networkCatalog,
                Collections.unmodifiableMap(HashMap(fares)),
                feedVersion,
                Collections.unmodifiableMap(
                    discountedFares.mapValues { (_, values) ->
                        Collections.unmodifiableMap(HashMap(values))
                    }
                ),
                riderCategories.map { (id, description) -> RiderCategory(id, description) },
            )
            }

        @Throws(IOException::class)
        private fun parseFareAttributes(input: InputStream, fares: MutableMap<String, String>) {
            input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val header = splitCsvLine(reader.readLine())
                val id = indexOf(header, "fare_id")
                val price = indexOf(header, "price")
                reader.forEachLine { line ->
                    val values = splitCsvLine(line)
                    if (id >= 0 && price >= 0 && id < values.size && price < values.size) {
                        fares[values[id]] = values[price]
                    }
                }
            }
        }

        @Throws(IOException::class)
        private fun parseFareRules(input: InputStream, rules: MutableList<FareRule>) {
            input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val header = splitCsvLine(reader.readLine())
                val fare = indexOf(header, "fare_id")
                val origin = indexOf(header, "origin_id")
                val destination = indexOf(header, "destination_id")
                reader.forEachLine { line ->
                    val values = splitCsvLine(line)
                    if (fare >= 0 && origin >= 0 && destination >= 0
                        && fare < values.size && origin < values.size && destination < values.size
                    ) {
                        rules += FareRule(values[fare], values[origin], values[destination])
                    }
                }
            }
        }

        private fun key(origin: Station, destination: Station): String =
            "${origin.abbreviation.uppercase(Locale.ROOT)}>${destination.abbreviation.uppercase(Locale.ROOT)}"

        private fun parseFeedVersion(input: String): String? {
            val lines = input.lineSequence().iterator()
            if (!lines.hasNext()) return null
            val versionIndex = indexOf(splitCsvLine(lines.next()), "feed_version")
            if (versionIndex < 0 || !lines.hasNext()) return null
            return splitCsvLine(lines.next())
                .getOrNull(versionIndex)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        @Throws(IOException::class)
        private fun parseRiderCategories(
            input: InputStream,
            categories: MutableMap<String, String>,
        ) {
            input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val header = splitCsvLine(reader.readLine())
                val id = indexOf(header, "rider_category_id")
                val description = indexOf(header, "rider_category_description")
                reader.forEachLine { line ->
                    val values = splitCsvLine(line)
                    if (id >= 0 && description >= 0 && id < values.size && description < values.size) {
                        categories[values[id]] = values[description]
                    }
                }
            }
        }

        @Throws(IOException::class)
        private fun parseFareRiderCategories(
            input: InputStream,
            prices: MutableMap<String, MutableMap<String, String>>,
        ) {
            input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val header = splitCsvLine(reader.readLine())
                val fare = indexOf(header, "fare_id")
                val category = indexOf(header, "rider_category_id")
                val price = indexOf(header, "price")
                reader.forEachLine { line ->
                    val values = splitCsvLine(line)
                    if (fare >= 0 && category >= 0 && price >= 0
                        && fare < values.size && category < values.size && price < values.size
                    ) {
                        prices.getOrPut(values[fare]) { HashMap() }[values[category]] = values[price]
                    }
                }
            }
        }

        private fun indexOf(values: Array<String>, value: String): Int = values.indexOf(value)

        private fun splitCsvLine(line: String?): Array<String> {
            if (line == null) return emptyArray()
            val values = mutableListOf<String>()
            val value = StringBuilder()
            var quoted = false
            var index = 0
            while (index < line.length) {
                val character = line[index]
                if (character == '"') {
                    if (quoted && index + 1 < line.length && line[index + 1] == '"') {
                        value.append('"')
                        index++
                    } else {
                        quoted = !quoted
                    }
                } else if (character == ',' && !quoted) {
                    values += value.toString()
                    value.setLength(0)
                } else {
                    value.append(character)
                }
                index++
            }
            values += value.toString()
            return values.toTypedArray()
        }

        private data class FareRule(
            val fareId: String,
            val origin: String,
            val destination: String
        )

        private data class ParsedFeed(
            val catalog: GtfsNetworkCatalog,
            val faresByStationPair: Map<String, String>,
            val feedVersion: String?,
            val discountedFaresByStationPair: Map<String, Map<String, String>>,
            val riderCategories: List<RiderCategory>,
        )

        private data class LoadedData(
            val bartGtfsNetwork: BartGtfsNetwork,
        )
    }
}
