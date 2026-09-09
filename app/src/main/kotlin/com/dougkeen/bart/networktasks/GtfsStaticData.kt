package com.dougkeen.bart.networktasks

import android.content.Context
import androidx.core.content.edit
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.SystemTimeSource
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.performance.PerformanceTrace
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import com.dougkeen.bart.transit.gtfs.GtfsNetworkCatalog
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
import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.zip.ZipInputStream

/** Loads BART's static GTFS feed at most once per seven days. */
class GtfsStaticData @JvmOverloads constructor(
    context: Context,
    private val timeSource: TimeSource = SystemTimeSource,
) {
    private val applicationContext = context.applicationContext
    private val lock = Any()
    private var cachedData: LoadedData? = null
    private var cachedAt = 0L

    @Throws(IOException::class)
    fun getBartGtfsNetwork(): BartGtfsNetwork = load().bartGtfsNetwork

    @Throws(IOException::class)
    fun getFare(origin: Station, destination: Station): String? =
        load().faresByStationPair[key(origin, destination)]

    @Throws(IOException::class)
    private fun load(): LoadedData = PerformanceTrace.section("BART static data load") {
        loadLocked()
    }

    @Throws(IOException::class)
    private fun loadLocked(): LoadedData {
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
            if (cacheFile.isFile && now - lastSuccess < CACHE_MILLIS) {
                readCached(cacheFile, lastSuccess, now)?.let { return it }
                preferences.edit {
                    remove(LAST_SUCCESS)
                    remove(LAST_ATTEMPT)
                }
            }

            val lastAttempt = preferences.getLong(LAST_ATTEMPT, 0L)
            if (now - lastAttempt < CACHE_MILLIS) {
                if (cacheFile.isFile) {
                    readCached(cacheFile, lastSuccess, now)?.let { return it }
                }
                throw IOException("Static GTFS refresh already attempted")
            }

            preferences.edit { putLong(LAST_ATTEMPT, now) }
            val temporaryFile = File(applicationContext.filesDir, "$CACHE_FILE_NAME.tmp")
            try {
                download(temporaryFile)
                val result = parse(temporaryFile)
                if (cacheFile.exists() && !cacheFile.delete()) {
                    throw IOException("Could not replace static GTFS cache")
                }
                if (!temporaryFile.renameTo(cacheFile)) {
                    throw IOException("Could not save static GTFS cache")
                }
                preferences.edit { putLong(LAST_SUCCESS, now) }
                cachedData = result
                cachedAt = now
                return result
            } catch (exception: IOException) {
                temporaryFile.delete()
                readCached(cacheFile, lastSuccess, now)?.let { return it }
                throw exception
            }
        }
    }

    private fun readCached(
        cacheFile: File,
        lastSuccess: Long,
        now: Long,
    ): LoadedData? = PerformanceTrace.section("BART static cache parse") {
        try {
            parse(cacheFile).also {
                cachedData = it
                cachedAt = if (lastSuccess > 0) lastSuccess else now
            }
        } catch (exception: Exception) {
            cacheFile.delete()
            null
        }
    }

    companion object {
        private const val FEED_URL = "https://www.bart.gov/dev/schedules/google_transit.zip"
        // BART recommends checking the static schedule feed weekly.
        private const val CACHE_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private const val CACHE_FILE_NAME = "gtfs_static_schedule.zip"
        private const val PREFS_NAME = "gtfs_static_schedule"
        private const val LAST_ATTEMPT = "last_attempt"
        private const val LAST_SUCCESS = "last_success"
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

        private fun isRelevantFeedFile(name: String): Boolean = name in setOf(
            "routes.txt", "trips.txt",
            "stops.txt", "stop_times.txt", "calendar.txt", "calendar_dates.txt",
            "transfers.txt", "fare_attributes.txt",
            "fare_rules.txt"
        )

        private fun input(value: String): InputStream =
            ByteArrayInputStream(value.toByteArray(StandardCharsets.UTF_8))

        @Throws(IOException::class)
        private fun parse(file: File): LoadedData =
            PerformanceTrace.section("BART static feed parse") {
            val feedFiles = PerformanceTrace.section("BART static zip read") {
                readFeedFiles(file)
            }
            val farePrices = HashMap<String, String>()
            val fareRules = mutableListOf<FareRule>()

            feedFiles["fare_attributes.txt"]?.let { parseFareAttributes(input(it), farePrices) }
            feedFiles["fare_rules.txt"]?.let { parseFareRules(input(it), fareRules) }

            val fares = HashMap<String, String>()
            for (rule in fareRules) {
                val price = farePrices[rule.fareId]
                if (price != null && rule.origin.isNotEmpty() && rule.destination.isNotEmpty()) {
                    fares["${rule.origin}>${rule.destination}"] = "$$price"
                }
            }

            for (requiredFile in listOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt")) {
                if (requiredFile !in feedFiles) {
                    throw IOException("Static GTFS is missing $requiredFile")
                }
            }

            val networkCatalog: GtfsNetworkCatalog
            val bartGtfsNetwork: BartGtfsNetwork
            try {
                networkCatalog = PerformanceTrace.section("BART catalog build") {
                    GtfsNetworkCatalog.fromFiles(feedFiles)
                }
                PerformanceTrace.section("BART catalog validation") {
                    networkCatalog.validationErrors().firstOrNull()
                }?.let {
                    throw IOException("Static GTFS catalog validation failed: $it")
                }
                bartGtfsNetwork = PerformanceTrace.section("BART network mapping") {
                    BartGtfsNetwork.fromCatalog(networkCatalog)
                }
                PerformanceTrace.section("BART network validation") {
                    bartGtfsNetwork.validationErrors().firstOrNull()
                }?.let {
                    throw IOException("Static GTFS BART validation failed: $it")
                }
            } catch (exception: IllegalArgumentException) {
                throw IOException("Could not parse static GTFS network catalog", exception)
            }
            LoadedData(
                Collections.unmodifiableMap(HashMap(fares)),
                bartGtfsNetwork
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

        private data class LoadedData(
            val faresByStationPair: Map<String, String>,
            val bartGtfsNetwork: BartGtfsNetwork,
        )
    }
}
