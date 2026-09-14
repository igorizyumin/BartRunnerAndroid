package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.networktasks.EtdClient
import `in`.izyum.bart.networktasks.EtdDeparture
import `in`.izyum.bart.networktasks.EtdStationBoard
import `in`.izyum.bart.networktasks.EtdStationCache
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog
import com.google.transit.realtime.GtfsRealtime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Exhaustive, fixture-backed comparison of the app station boards to BART's
 * raw XML ETD boards. The capture is intentionally named in the test so a
 * refreshed fixture is a visible, reviewable change.
 */
class LiveEtdStationBoardAuditTest {
    @Test
    fun capturedFixturesHaveNoEtdOnlyDepartures() {
        val network = BartGtfsNetwork.fromCatalog(
            GtfsNetworkCatalog.fromFiles(loadGtfsFiles())
        )
        auditFixtures().forEach { fixtureName ->
            val tripUpdates = resourceFeed(fixture(fixtureName, "trip_updates.pb"))
            val alerts = resourceFeed(fixture(fixtureName, "alerts.pb"))
            val feedTime = tripUpdates.header.timestamp * 1000L
            val snapshot = TransitFeedSnapshot(tripUpdates, alerts, feedTime)
            val base = RouteDepartureProjection(
                StationPair(Station.ANTC, null), network
            ).project(snapshot).getDepartures()
            val uncut = RouteDepartureProjection(
                StationPair(Station.ANTC, null), network
            ).projectForEtd(snapshot).getDepartures()
            val expected = parseEtd(
                Station.ANTC,
                resourceText(fixture(fixtureName, "etd/antc.xml")),
            )
            fun matches(departures: List<Departure>, etd: EtdPrediction): Boolean =
                departures.any { departure ->
                    departure.line == etd.line &&
                        normalizeDestination(departure.trainDestination, departure.line) ==
                        normalizeDestination(etd.destination, etd.line) &&
                        kotlin.math.abs(
                            departure.getMeanEstimate() - etd.departureTimeMillis
                        ) <= MATCH_TOLERANCE_MILLIS
                }
            assertTrue("$fixtureName base projection has ETD-only departures", expected.all { matches(base, it) })
            assertTrue("$fixtureName uncut projection has ETD-only departures", expected.all { matches(uncut, it) })
        }
    }

    @Test
    fun appStationBoardsMatchCapturedXmlEtdAcrossEveryStation() {
        val network = BartGtfsNetwork.fromCatalog(
            GtfsNetworkCatalog.fromFiles(loadGtfsFiles())
        )
        auditFixtures().forEach { fixtureName ->
            val tripUpdates = resourceFeed(fixture(fixtureName, "trip_updates.pb"))
            val alerts = resourceFeed(fixture(fixtureName, "alerts.pb"))
            val feedTime = tripUpdates.header.timestamp * 1000L
            assertTrue("$fixtureName has no feed timestamp", feedTime > 0L)
            val snapshot = TransitFeedSnapshot(tripUpdates, alerts, feedTime)
            val etdCache = fixtureEtdCache(fixtureName, feedTime)
            val stationResults = Station.getStationList().map { station ->
                val expected = parseEtd(
                    station,
                    resourceText(fixture(fixtureName, "etd/${station.abbreviation}.xml"))
                )
                val actual = runBlocking {
                    EtdAwareRouteDepartureProjection(
                        RouteDepartureProjection(StationPair(station, null), network),
                        etdCache,
                    ).project(snapshot).getDepartures()
                }
                compare(station, expected, actual, network)
            }

            println(
                "Live ETD audit: fixture=$fixtureName " +
                    "feedTime=${Instant.ofEpochMilli(feedTime)}, " +
                    "stations=${stationResults.size}, " +
                    "expected=${stationResults.sumOf { it.expectedCount }}, " +
                    "app=${stationResults.sumOf { it.actualCount }}, " +
                    "matched=${stationResults.sumOf { it.matchedCount }}, " +
                    "missing=${stationResults.sumOf { it.missingCount }}, " +
                    "timeMismatches=${stationResults.sumOf { it.timeMismatchCount }}, " +
                    "destinationMismatches=${stationResults.sumOf { it.destinationMismatchCount }}, " +
                    "cancellationMismatches=${stationResults.sumOf { it.cancellationMismatchCount }}",
            )
            stationResults.filter { it.hasDifferences }.forEach { result ->
                println(result.describe())
            }
            stationResults.forEach { result ->
                result.timeMismatchTargets.forEach { target ->
                    println(feedCoverage(tripUpdates, network, result.station, target))
                }
            }

            assertEquals(
                "$fixtureName station count",
                Station.getStationList().size,
                stationResults.size,
            )
            assertEquals(
                "$fixtureName ETD predictions with no matching app prediction",
                0,
                stationResults.sumOf { it.missingCount },
            )
            assertEquals(
                "$fixtureName ETD destination labels are the ground truth",
                0,
                stationResults.sumOf { it.destinationMismatchCount },
            )
            assertTrue(
                "$fixtureName produced no comparable ETD predictions",
                stationResults.sumOf { it.expectedCount } > 0,
            )
        }
    }

    private fun compare(
        station: Station,
        expected: List<EtdPrediction>,
        actual: List<Departure>,
        network: BartGtfsNetwork,
    ): StationAudit {
        val unmatchedActual = actual.indices.toMutableSet()
        var matched = 0
        var timeMismatches = 0
        var destinationMismatches = 0
        var cancellationMismatches = 0
        val missing = mutableListOf<EtdPrediction>()
        val timeMismatchDetails = mutableListOf<String>()
        val destinationMismatchDetails = mutableListOf<String>()
        val timeMismatchTargets = mutableListOf<Long>()
        expected.forEach { expectedValue ->
            val best = unmatchedActual.mapNotNull { actualIndex ->
                val actualValue = actual[actualIndex]
                if (normalizeLine(expectedValue.line) != normalizeLine(actualValue.line)) {
                    null
                } else {
                    Match(
                        expectedIndex = 0,
                        actualIndex = actualIndex,
                        deltaMillis = kotlin.math.abs(
                            expectedValue.departureTimeMillis - actualValue.getMeanEstimate()
                        ),
                        platformAndDirectionMatch =
                            expectedValue.platform == actualValue.platform &&
                                normalizeDirection(expectedValue.direction) ==
                                    normalizeDirection(actualValue.direction),
                    )
                }
            }.minWithOrNull(
                compareByDescending<Match> { it.platformAndDirectionMatch }
                    .thenBy { it.deltaMillis }
            )
            if (best == null || best.deltaMillis > MATCH_TOLERANCE_MILLIS) {
                if (best != null) {
                    timeMismatches++
                    timeMismatchTargets += expectedValue.departureTimeMillis
                    timeMismatchDetails += "time ${expectedValue.describe()} nearest=" +
                        describeActual(actual[best.actualIndex]) +
                        " delta=${best.deltaMillis / 1000}s"
                }
                if (best == null) missing += expectedValue
                return@forEach
            }
            unmatchedActual.remove(best.actualIndex)
            matched++
            val actualValue = actual[best.actualIndex]
            if (normalizeDestination(expectedValue.destination, expectedValue.line) !=
                normalizeDestination(actualValue.trainDestination, actualValue.line)
            ) {
                destinationMismatches++
                destinationMismatchDetails += "destination ${expectedValue.describe()} " +
                    "expected=${expectedValue.destination?.abbreviation} " +
                    "actual=${actualValue.trainDestination?.abbreviation} " +
                    "trip=${actualValue.tripLegs.firstOrNull()?.tripId} " +
                    "static=${actualValue.tripLegs.firstOrNull()?.tripId?.let(network::stationsForTrip)?.lastOrNull()?.abbreviation}"
            }
            if (expectedValue.canceled != actualValue.canceled) cancellationMismatches++
        }
        return StationAudit(
            station = station,
            expectedCount = expected.size,
            actualCount = actual.size,
            matchedCount = matched,
            missingCount = missing.size,
            timeMismatchCount = timeMismatches,
            destinationMismatchCount = destinationMismatches,
            cancellationMismatchCount = cancellationMismatches,
            details = timeMismatchDetails + destinationMismatchDetails +
                missing.map { "missing ${it.describe()}" },
            timeMismatchTargets = timeMismatchTargets,
        )
    }

    private fun normalizeLine(line: Line?): Line? = when (line) {
        Line.YELLOW_LATE_NIGHT -> Line.YELLOW
        else -> line
    }

    private fun normalizeDestination(destination: Station?, line: Line?): Station? =
        if (normalizeLine(line) == Line.YELLOW &&
            destination in setOf(Station.MLBR, Station.SFIA)
        ) Station.SFIA else destination

    private fun describeActual(departure: Departure): String =
        "${departure.line}:${departure.trainDestination?.abbreviation} " +
            departure.getMeanEstimate() / 1000

    private fun parseEtd(station: Station, xml: String): List<EtdPrediction> {
        val documentFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = documentFactory.newDocumentBuilder().parse(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )
        val root = document.documentElement
        val baseTime = LocalDateTime.of(
            LocalDate.parse(text(root, "date"), DATE_FORMAT),
            LocalTime.parse(text(root, "time").substringBeforeLast(' '), TIME_FORMAT),
        ).atZone(PACIFIC_ZONE).toInstant().toEpochMilli()
        val stationNode = document.getElementsByTagName("station").item(0) as Element
        val result = mutableListOf<EtdPrediction>()
        childElements(stationNode, "etd").forEach { etd ->
            val destination = Station.getByAbbreviation(text(etd, "abbreviation"))
            childElements(etd, "estimate").forEach { estimate ->
                val minutes = text(estimate, "minutes").let {
                    if (it.equals("leaving", ignoreCase = true)) 0L
                    else it.toLongOrNull() ?: return@forEach
                }
                result += EtdPrediction(
                    destination = destination,
                    line = lineForColor(text(estimate, "color")),
                    departureTimeMillis = baseTime + minutes * 60_000L,
                    canceled = text(estimate, "cancelflag") == "1",
                    source = "${station.abbreviation}:${text(etd, "destination")}",
                    platform = text(estimate, "platform"),
                    direction = text(estimate, "direction"),
                )
            }
        }
        return result.filter { it.line != null && it.destination != null }
    }

    private fun fixtureEtdCache(fixtureName: String, feedTime: Long): EtdStationCache {
        val boards = Station.getStationList().associateWith { station ->
            parseEtd(
                station,
                resourceText(fixture(fixtureName, "etd/${station.abbreviation}.xml")),
            ).map { prediction ->
                EtdDeparture(
                    prediction.destination,
                    prediction.line,
                    prediction.departureTimeMillis,
                    prediction.platform,
                    prediction.direction,
                    prediction.canceled,
                )
            }
        }
        return EtdStationCache(
            object : EtdClient {
                override fun fetch(
                    station: Station,
                    receivedAtMillis: Long,
                ): EtdStationBoard = EtdStationBoard(
                    station,
                    receivedAtMillis,
                    boards.getValue(station),
                )
            },
            TimeSource { feedTime },
        )
    }

    private fun text(parent: Element, name: String): String =
        parent.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()

    private fun childElements(parent: Element, name: String): List<Element> =
        (0 until parent.childNodes.length).mapNotNull { index ->
            parent.childNodes.item(index) as? Element
        }.filter { it.tagName == name }

    private fun lineForColor(color: String): Line? = when (color.uppercase(Locale.ROOT)) {
        "RED" -> Line.RED
        "ORANGE" -> Line.ORANGE
        "YELLOW" -> Line.YELLOW
        "BLUE" -> Line.BLUE
        "GREEN" -> Line.GREEN
        else -> null
    }

    private fun normalizeDirection(direction: String?): String? = when (
        direction?.lowercase(Locale.ROOT)
    ) {
        "north", "n" -> "n"
        "south", "s" -> "s"
        else -> direction?.lowercase(Locale.ROOT)
    }

    private fun feedCoverage(
        feed: GtfsRealtime.FeedMessage,
        network: BartGtfsNetwork,
        station: Station,
        targetMillis: Long,
    ): String {
        val rows = feed.entityList.flatMap { entity ->
            if (!entity.hasTripUpdate()) return@flatMap emptyList()
            val tripUpdate = entity.tripUpdate
            val tripId = tripUpdate.trip.tripId
            val routeId = tripUpdate.trip.routeId.takeIf { it.isNotEmpty() }
                ?: network.routeIdForTrip(tripId).orEmpty()
            val line = network.lineForRouteId(routeId)
            val destination = network.stationsForTrip(tripId).lastOrNull()
            tripUpdate.stopTimeUpdateList.mapNotNull { stopUpdate ->
                if (network.stationForStopId(stopUpdate.stopId) != station) {
                    return@mapNotNull null
                }
                val timeMillis = when {
                    stopUpdate.hasDeparture() && stopUpdate.departure.hasTime() ->
                        stopUpdate.departure.time * 1000L
                    stopUpdate.hasArrival() && stopUpdate.arrival.hasTime() ->
                        stopUpdate.arrival.time * 1000L
                    else -> 0L
                }
                if (timeMillis == 0L ||
                    kotlin.math.abs(timeMillis - targetMillis) > 15 * 60_000L
                ) {
                    return@mapNotNull null
                }
                "${tripId}/${line}:${destination?.abbreviation} @" +
                    Instant.ofEpochMilli(timeMillis)
            }
        }.distinct()
        return "GTFS-RT coverage ${station.abbreviation} target=" +
            Instant.ofEpochMilli(targetMillis) + " rows=$rows"
    }

    private fun resourceFeed(path: String): GtfsRealtime.FeedMessage =
        GtfsRealtime.FeedMessage.parseFrom(
            checkNotNull(javaClass.getResourceAsStream(path)) { path }
        )

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { path }
            .bufferedReader().use { it.readText() }

    private fun fixture(fixtureName: String, name: String): String =
        "/$fixtureName/$name"

    private fun auditFixtures(): List<String> {
        val resourceRoot = Paths.get(
            checkNotNull(javaClass.getResource("/bart_live_fixture.txt")) {
                "BART live fixture pointer"
            }
                .toURI()
        ).parent
        val fixtures = Files.list(resourceRoot).use { entries ->
            entries
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { it.startsWith("bart_live_") }
                .filter { fixtureName -> isCompleteFixture(resourceRoot, fixtureName) }
                .sorted()
                .toList()
        }
        assertTrue("no complete BART live fixtures found", fixtures.isNotEmpty())
        val selected = fixtures.filterNot { it in EXCLUDED_FIXTURES }
        assertTrue("no non-disruption BART live fixtures found", selected.isNotEmpty())
        return selected
    }

    private fun isCompleteFixture(resourceRoot: java.nio.file.Path, fixtureName: String): Boolean {
        val fixtureRoot = resourceRoot.resolve(fixtureName)
        val etdRoot = fixtureRoot.resolve("etd")
        return Files.isRegularFile(fixtureRoot.resolve("trip_updates.pb")) &&
            Files.isRegularFile(fixtureRoot.resolve("alerts.pb")) &&
            Station.getStationList().all { station ->
                Files.isRegularFile(etdRoot.resolve("${station.abbreviation}.xml"))
            }
    }

    private fun loadGtfsFiles(): Map<String, String> {
        val files = linkedMapOf<String, String>()
        ZipInputStream(
            checkNotNull(javaClass.getResourceAsStream("/gtfs/bart_google_transit.zip"))
        ).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                val current = entry ?: continue
                if (!current.isDirectory && current.name in REQUIRED_GTFS_FILES) {
                    files[current.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return files
    }

    private data class EtdPrediction(
        val destination: Station?,
        val line: Line?,
        val departureTimeMillis: Long,
        val canceled: Boolean,
        val source: String,
        val platform: String?,
        val direction: String?,
    ) {
        fun describe(): String = "$source ${departureTimeMillis / 1000}"
    }

    private data class Match(
        val expectedIndex: Int,
        val actualIndex: Int,
        val deltaMillis: Long,
        val platformAndDirectionMatch: Boolean,
    )

    private data class StationAudit(
        val station: Station,
        val expectedCount: Int,
        val actualCount: Int,
        val matchedCount: Int,
        val missingCount: Int,
        val timeMismatchCount: Int,
        val destinationMismatchCount: Int,
        val cancellationMismatchCount: Int,
        val details: List<String>,
        val timeMismatchTargets: List<Long>,
    ) {
        val hasDifferences: Boolean
            get() = missingCount > 0 || timeMismatchCount > 0 ||
                destinationMismatchCount > 0 || cancellationMismatchCount > 0

        fun describe(): String =
            "${station.abbreviation}: expected=$expectedCount actual=$actualCount " +
                "matched=$matchedCount missing=$missingCount " +
                "timeMismatches=$timeMismatchCount " +
                "destinationMismatches=$destinationMismatchCount " +
                "cancellationMismatches=$cancellationMismatchCount " +
                "${details.take(8)}"
    }

    companion object {
        private val PACIFIC_ZONE = ZoneId.of("America/Los_Angeles")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.US)
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.US)
        private const val MATCH_TOLERANCE_MILLIS = 2 * 60_000L
        private val EXCLUDED_FIXTURES = setOf(
            // Split Orange service: ETD shows through terminals while static
            // GTFS correctly exposes the temporary Union City/Warm Springs legs.
            "bart_live_20260912_071605",
            "bart_live_20260912_192815",
            // Weekend bus bridge: ETD destination labels do not describe the
            // normal rail trips represented by the static GTFS snapshot.
            "bart_live_20260913_182435",
            "bart_live_20260913_201652",
        )
        private val REQUIRED_GTFS_FILES = setOf(
            "routes.txt", "trips.txt", "stops.txt", "stop_times.txt",
            "calendar.txt", "calendar_dates.txt", "transfers.txt",
        )
    }
}
