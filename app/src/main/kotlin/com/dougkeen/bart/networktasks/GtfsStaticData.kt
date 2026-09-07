package com.dougkeen.bart.networktasks

import android.content.Context
import com.dougkeen.bart.model.ScheduleInformation
import com.dougkeen.bart.model.ScheduleItem
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import com.dougkeen.bart.transit.gtfs.GtfsNetworkCatalog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/** Loads BART's static GTFS feed once per service day. */
class GtfsStaticData private constructor(
    private val stopTimesByTripId: Map<String, List<StopTime>>,
    private val faresByStationPair: Map<String, String>,
    private val networkCatalog: GtfsNetworkCatalog,
    private val bartGtfsNetwork: BartGtfsNetwork
) : com.dougkeen.bart.backend.StaticScheduleSource {

    override fun getSchedule(origin: Station, destination: Station): ScheduleInformation {
        val now = Calendar.getInstance(PACIFIC_TIME, Locale.US)
        val nowMillis = now.timeInMillis
        val day = now.clone() as Calendar
        day.set(Calendar.HOUR_OF_DAY, 0)
        day.set(Calendar.MINUTE, 0)
        day.set(Calendar.SECOND, 0)
        day.set(Calendar.MILLISECOND, 0)

        val trips = mutableListOf<ScheduleItem>()
        for (stopTimes in stopTimesByTripId.values) {
            var originStop: StopTime? = null
            var destinationStop: StopTime? = null
            var terminal: Station? = null
            for (stopTime in stopTimes) {
                val station = bartGtfsNetwork.stationForStopId(stopTime.stopId)
                if (station != null && station != Station.SPCL) {
                    terminal = station
                }
                if (station == origin && originStop == null) {
                    originStop = stopTime
                }
                if (station == destination && destinationStop == null) {
                    destinationStop = stopTime
                }
            }
            if (originStop == null || destinationStop == null
                || destinationStop!!.sequence <= originStop!!.sequence
                || originStop!!.departureSeconds < 0
                || destinationStop!!.arrivalSeconds < 0
            ) {
                continue
            }

            val departureTime = day.timeInMillis + originStop!!.departureSeconds * 1000L
            if (departureTime < nowMillis) {
                continue
            }
            trips += ScheduleItem(
                origin = origin,
                destination = destination,
                departureTime = departureTime,
                arrivalTime = day.timeInMillis + destinationStop!!.arrivalSeconds * 1000L,
                bikesAllowed = true,
                trainHeadStation = (terminal ?: destination).apiName
            )
        }
        trips.sortBy { it.departureTime }

        return ScheduleInformation(origin, destination, nowMillis, trips.take(4))
    }

    fun getNetworkCatalog(): GtfsNetworkCatalog = networkCatalog

    fun getBartGtfsNetwork(): BartGtfsNetwork = bartGtfsNetwork

    fun getFare(origin: Station, destination: Station): String? =
        faresByStationPair[key(origin, destination)]

    companion object {
        private const val FEED_URL = "https://www.bart.gov/dev/schedules/google_transit.zip"
        private const val CACHE_MILLIS = 24L * 60L * 60L * 1000L
        private const val CACHE_FILE_NAME = "gtfs_static_schedule.zip"
        private const val PREFS_NAME = "gtfs_static_schedule"
        private const val LAST_ATTEMPT = "last_attempt"
        private const val LAST_SUCCESS = "last_success"
        private val PACIFIC_TIME = TimeZone.getTimeZone("America/Los_Angeles")
        private val LOCK = Any()
        private val CLIENT: OkHttpClient = NetworkUtils.makeHttpClient()

        private var cachedData: GtfsStaticData? = null
        private var cachedAt = 0L
        private var cachedServiceDate: String? = null

        @JvmStatic
        @Throws(IOException::class)
        fun get(context: Context?): GtfsStaticData {
            val applicationContext = context?.applicationContext
                ?: throw IOException("Application context is unavailable")
            synchronized(LOCK) {
                val now = System.currentTimeMillis()
                val serviceDate = dateCode(Calendar.getInstance(PACIFIC_TIME, Locale.US))
                if (cachedData != null && now - cachedAt < CACHE_MILLIS
                    && serviceDate == cachedServiceDate
                ) {
                    return cachedData!!
                }

                val cacheFile = File(applicationContext.filesDir, CACHE_FILE_NAME)
                val preferences = applicationContext.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                val lastSuccess = preferences.getLong(LAST_SUCCESS, 0L)
                if (cacheFile.isFile && now - lastSuccess < CACHE_MILLIS) {
                    cachedData = parse(cacheFile)
                    cachedAt = if (lastSuccess > 0) lastSuccess else now
                    cachedServiceDate = serviceDate
                    return cachedData!!
                }

                val lastAttempt = preferences.getLong(LAST_ATTEMPT, 0L)
                if (now - lastAttempt < CACHE_MILLIS) {
                    if (cacheFile.isFile) {
                        cachedData = parse(cacheFile)
                        cachedAt = if (lastSuccess > 0) lastSuccess else now
                        cachedServiceDate = serviceDate
                        return cachedData!!
                    }
                    throw IOException("Static GTFS refresh already attempted")
                }

                preferences.edit().putLong(LAST_ATTEMPT, now).apply()
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
                    preferences.edit().putLong(LAST_SUCCESS, now).apply()
                    cachedData = result
                    cachedAt = now
                    cachedServiceDate = serviceDate
                    return result
                } catch (exception: IOException) {
                    temporaryFile.delete()
                    if (cacheFile.isFile) {
                        cachedData = parse(cacheFile)
                        cachedAt = if (lastSuccess > 0) lastSuccess else now
                        cachedServiceDate = serviceDate
                        return cachedData!!
                    }
                    throw exception
                }
            }
        }

        @Throws(IOException::class)
        private fun download(destination: File) {
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
            "calendar.txt", "calendar_dates.txt", "routes.txt", "trips.txt",
            "stops.txt", "stop_times.txt", "transfers.txt", "fare_attributes.txt",
            "fare_rules.txt"
        )

        private fun input(value: String): InputStream =
            ByteArrayInputStream(value.toByteArray(StandardCharsets.UTF_8))

        @Throws(IOException::class)
        private fun parse(file: File): GtfsStaticData {
            val feedFiles = readFeedFiles(file)
            val trips = HashMap<String, Trip>()
            val calendars = HashMap<String, ServiceCalendar>()
            val exceptions = HashMap<String, MutableMap<String, Int>>()
            val farePrices = HashMap<String, String>()
            val fareRules = mutableListOf<FareRule>()
            val today = Calendar.getInstance(PACIFIC_TIME, Locale.US)
            val dateCode = dateCode(today)

            feedFiles["calendar.txt"]?.let { parseCalendars(input(it), calendars) }
            feedFiles["calendar_dates.txt"]?.let { parseExceptions(input(it), exceptions) }
            feedFiles["trips.txt"]?.let { parseTrips(input(it), trips) }
            feedFiles["fare_attributes.txt"]?.let { parseFareAttributes(input(it), farePrices) }
            feedFiles["fare_rules.txt"]?.let { parseFareRules(input(it), fareRules) }

            val activeServices = activeServices(calendars, exceptions, dateCode, today)
            val stopTimesByTripId = HashMap<String, MutableList<StopTime>>()
            feedFiles["stop_times.txt"]?.let {
                parseStopTimes(input(it), trips, activeServices, stopTimesByTripId)
            }

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
                networkCatalog = GtfsNetworkCatalog.fromFiles(feedFiles)
                networkCatalog.validationErrors().firstOrNull()?.let {
                    throw IOException("Static GTFS catalog validation failed: $it")
                }
                bartGtfsNetwork = BartGtfsNetwork.fromCatalog(networkCatalog)
                bartGtfsNetwork.validationErrors().firstOrNull()?.let {
                    throw IOException("Static GTFS BART validation failed: $it")
                }
            } catch (exception: IllegalArgumentException) {
                throw IOException("Could not parse static GTFS network catalog", exception)
            }
            return GtfsStaticData(
                stopTimesByTripId.mapValues { (_, value) -> Collections.unmodifiableList(ArrayList(value)) },
                Collections.unmodifiableMap(HashMap(fares)),
                networkCatalog,
                bartGtfsNetwork
            )
        }

        @Throws(IOException::class)
        private fun parseCalendars(input: InputStream, calendars: MutableMap<String, ServiceCalendar>) {
            input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val header = splitCsvLine(reader.readLine())
                val service = indexOf(header, "service_id")
                val start = indexOf(header, "start_date")
                val end = indexOf(header, "end_date")
                val days = arrayOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
                val dayColumns = days.map { indexOf(header, it) }
                reader.forEachLine { line ->
                    val values = splitCsvLine(line)
                    if (service < 0 || service >= values.size) return@forEachLine
                    val calendar = ServiceCalendar(
                        startDate = value(values, start),
                        endDate = value(values, end),
                        days = BooleanArray(7) { "1" == value(values, dayColumns[it]) }
                    )
                    calendars[values[service]] = calendar
                }
            }
        }

        @Throws(IOException::class)
        private fun parseExceptions(input: InputStream, exceptions: MutableMap<String, MutableMap<String, Int>>) {
            input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val header = splitCsvLine(reader.readLine())
                val service = indexOf(header, "service_id")
                val date = indexOf(header, "date")
                val type = indexOf(header, "exception_type")
                reader.forEachLine { line ->
                    val values = splitCsvLine(line)
                    if (service >= 0 && date >= 0 && type >= 0
                        && service < values.size && date < values.size && type < values.size
                    ) {
                        exceptions.getOrPut(values[service]) { HashMap() }[values[date]] = values[type].toInt()
                    }
                }
            }
        }

        @Throws(IOException::class)
        private fun parseTrips(input: InputStream, trips: MutableMap<String, Trip>) {
            input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val header = splitCsvLine(reader.readLine())
                val route = indexOf(header, "route_id")
                val service = indexOf(header, "service_id")
                val trip = indexOf(header, "trip_id")
                reader.forEachLine { line ->
                    val values = splitCsvLine(line)
                    if (route >= 0 && service >= 0 && trip >= 0
                        && route < values.size && service < values.size && trip < values.size
                    ) {
                        trips[values[trip]] = Trip(values[trip], values[route], values[service])
                    }
                }
            }
        }

        @Throws(IOException::class)
        private fun parseStopTimes(
            input: InputStream,
            trips: Map<String, Trip>,
            activeServices: Set<String>,
            result: MutableMap<String, MutableList<StopTime>>
        ) {
            input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val header = splitCsvLine(reader.readLine())
                val trip = indexOf(header, "trip_id")
                val arrival = indexOf(header, "arrival_time")
                val departure = indexOf(header, "departure_time")
                val stop = indexOf(header, "stop_id")
                val sequence = indexOf(header, "stop_sequence")
                reader.forEachLine { line ->
                    val values = splitCsvLine(line)
                    if (trip < 0 || trip >= values.size) return@forEachLine
                    val tripValue = trips[values[trip]] ?: return@forEachLine
                    if (tripValue.serviceId !in activeServices) return@forEachLine
                    result.getOrPut(tripValue.tripId) { mutableListOf() } += StopTime(
                        stopId = value(values, stop),
                        arrivalSeconds = parseGtfsTime(value(values, arrival)),
                        departureSeconds = parseGtfsTime(value(values, departure)),
                        sequence = parseInt(value(values, sequence))
                    )
                }
            }
            result.values.forEach { it.sortBy(StopTime::sequence) }
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

        private fun activeServices(
            calendars: Map<String, ServiceCalendar>,
            exceptions: Map<String, Map<String, Int>>,
            date: String,
            today: Calendar
        ): Set<String> {
            val active = HashSet<String>()
            val day = today.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
            for ((serviceId, calendar) in calendars) {
                var isActive = date >= calendar.startDate && date <= calendar.endDate && calendar.days[day]
                exceptions[serviceId]?.get(date)?.let { isActive = it == 1 }
                if (isActive) active += serviceId
            }
            return active
        }

        private fun key(origin: Station, destination: Station): String =
            "${origin.abbreviation.uppercase(Locale.ROOT)}>${destination.abbreviation.uppercase(Locale.ROOT)}"

        private fun dateCode(date: Calendar): String = String.format(
            Locale.US,
            "%04d%02d%02d",
            date.get(Calendar.YEAR),
            date.get(Calendar.MONTH) + 1,
            date.get(Calendar.DAY_OF_MONTH)
        )

        private fun parseGtfsTime(time: String): Int {
            if (time.isEmpty()) return -1
            val parts = time.split(":")
            if (parts.size != 3) return -1
            return parseInt(parts[0]) * 3600 + parseInt(parts[1]) * 60 + parseInt(parts[2])
        }

        private fun parseInt(value: String): Int = value.toIntOrNull() ?: -1

        private fun value(values: Array<String>, index: Int): String =
            if (index in values.indices) values[index] else ""

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

        private data class Trip(val tripId: String, val routeId: String, val serviceId: String)

        private data class StopTime(
            val stopId: String,
            val arrivalSeconds: Int,
            val departureSeconds: Int,
            val sequence: Int
        )

        private data class ServiceCalendar(
            val startDate: String,
            val endDate: String,
            val days: BooleanArray
        )

        private data class FareRule(
            val fareId: String,
            val origin: String,
            val destination: String
        )
    }
}
