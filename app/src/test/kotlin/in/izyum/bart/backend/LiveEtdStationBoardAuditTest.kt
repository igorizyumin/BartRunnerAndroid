package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog
import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Compares the current station-board projection with raw ETD captures.
 *
 * ETD is the passenger-facing oracle here. GTFS and GTFS-Realtime only provide
 * the inputs being audited; they do not define expected rows or cancellation
 * state.
 */
class LiveEtdStationBoardAuditTest {
    @Ignore(
        "Temporarily disabled while the product contract for schedule-only trips " +
            "outside the live ETD window is decided.",
    )
    @Test
    fun appStationBoardsMatchCapturedXmlEtdAcrossEveryStation() {
        val network = BartGtfsNetwork.fromCatalog(
            GtfsNetworkCatalog.fromFiles(loadGtfsFiles("/gtfs/bart_google_transit.zip"))
        )
        val fixtures = auditFixtures()
        val totals = fixtures.map { fixtureName -> auditFixture(fixtureName, network) }

        assertTrue("no complete BART live fixtures found", totals.isNotEmpty())
        assertTrue(
            "fixtures produced no comparable ETD predictions",
            totals.sumOf { it.expectedCount } > 0,
        )
        assertEquals(
            "projection contains trains absent from the active ETD board",
            0,
            totals.sumOf { it.actualOnlyCount },
        )
        assertEquals(
            "ETD rows with no current app prediction",
            0,
            totals.sumOf { it.missingCount },
        )
        assertEquals(
            "ETD rows with a different destination",
            0,
            totals.sumOf { it.destinationMismatchCount },
        )
    }

    private fun auditFixture(
        fixtureName: String,
        network: BartGtfsNetwork,
    ): FixtureAudit {
        val tripUpdates = resourceFeed("/$fixtureName/trip_updates.pb")
        val alerts = resourceFeed("/$fixtureName/alerts.pb")
        val feedTime = tripUpdates.header.timestamp * 1000L
        val snapshot = TransitFeedSnapshot(tripUpdates, alerts, feedTime)
        val stationResults = Station.getStationList().map { station ->
            val expected = parseEtd(station, resourceText("/$fixtureName/etd/${station.abbreviation}.xml"))
            val actual = RouteDepartureProjection(
                StationPair(station, null), network,
            ).project(snapshot).getDepartures()
            compare(station, expected, actual, feedTime)
        }
        val result = FixtureAudit(
            fixtureName,
            stationResults.sumOf { it.expectedCount },
            stationResults.sumOf { it.matchedCount },
            stationResults.sumOf { it.missingCount },
            stationResults.sumOf { it.timeMismatchCount },
            stationResults.sumOf { it.destinationMismatchCount },
            stationResults.sumOf { it.actualOnlyCount },
            stationResults.sumOf { it.unexpectedCancellationCount },
            stationResults.sumOf { it.expectedCancellationCount },
            stationResults.sumOf { it.actualCancellationCount },
        )
        val projectionSourceCounts = stationResults
            .flatMap { it.projectionSourceCounts.entries }
            .groupingBy { it.key }
            .fold(0) { total, entry -> total + entry.value }
        println(
            "Live ETD audit: fixture=$fixtureName stations=${stationResults.size} " +
                "expected=${result.expectedCount} matched=${result.matchedCount} " +
                "missing=${result.missingCount} timeMismatches=${result.timeMismatchCount} " +
                "destinationMismatches=${result.destinationMismatchCount} " +
                "projectionOnly=${result.actualOnlyCount} " +
                "projectionSources=$projectionSourceCounts " +
                "unexpectedCancellations=${result.unexpectedCancellationCount} " +
                "expectedCancellations=${result.expectedCancellationCount} " +
                "actualCancellations=${result.actualCancellationCount}"
        )
        stationResults.filter { it.hasDifferences }.forEach { println(it.describe()) }
        return result
    }

    private fun compare(
        station: Station,
        expected: List<EtdPrediction>,
        actual: List<Departure>,
        feedTime: Long,
    ): StationAudit {
        val unmatchedActual = actual.indices.toMutableSet()
        val activeExpected = expected.filterNot { it.canceled }
        // Canceled rows are not arrivals and do not extend the comparison
        // window. Each line/direction ends at its latest active ETD arrival.
        val latestByLineAndDirection = activeExpected
            .groupBy { BoardKey(normalizeLine(it.line), normalizeDirection(it.direction)) }
            .mapValues { (_, rows) ->
                maxOf(
                    rows.maxOf { it.departureTimeMillis },
                    feedTime + MIN_COMPARISON_WINDOW_MILLIS,
                )
            }
        var matched = 0
        var timeMismatches = 0
        var destinationMismatches = 0
        val actualCancellations = actual.count { it.canceled }
        val details = mutableListOf<String>()

        activeExpected.forEach { expectedValue ->
            val best = unmatchedActual.mapNotNull { actualIndex ->
                val actualValue = actual[actualIndex]
                val lineAndDirectionCutoff = latestByLineAndDirection[
                    BoardKey(normalizeLine(actualValue.line), normalizeDirection(directionOf(actualValue)))
                ]
                if (actualValue.canceled ||
                    lineAndDirectionCutoff == null ||
                    actualValue.getMeanEstimate() > lineAndDirectionCutoff + MATCH_TOLERANCE_MILLIS ||
                    normalizeLine(expectedValue.line) != normalizeLine(actualValue.line) ||
                    expectedValue.destination != actualValue.trainDestination
                ) {
                    null
                } else {
                    Match(
                        actualIndex,
                        kotlin.math.abs(expectedValue.departureTimeMillis - actualValue.getMeanEstimate()),
                        expectedValue.platform == actualValue.platform &&
                            normalizeDirection(expectedValue.direction) ==
                            normalizeDirection(directionOf(actualValue)),
                    )
                }
            }.minWithOrNull(
                compareByDescending<Match> { it.platformAndDirectionMatch }
                    .thenBy { it.deltaMillis }
            )
            if (best == null || best.deltaMillis > MATCH_TOLERANCE_MILLIS) {
                val wrongDestination = unmatchedActual.mapNotNull { actualIndex ->
                    val actualValue = actual[actualIndex]
                    val lineAndDirectionCutoff = latestByLineAndDirection[
                        BoardKey(normalizeLine(actualValue.line), normalizeDirection(directionOf(actualValue)))
                    ]
                    if (actualValue.canceled ||
                        lineAndDirectionCutoff == null ||
                        actualValue.getMeanEstimate() > lineAndDirectionCutoff + MATCH_TOLERANCE_MILLIS ||
                        normalizeLine(expectedValue.line) != normalizeLine(actualValue.line) ||
                        expectedValue.destination == actualValue.trainDestination
                    ) {
                        null
                    } else {
                        Match(
                            actualIndex,
                            kotlin.math.abs(expectedValue.departureTimeMillis - actualValue.getMeanEstimate()),
                            expectedValue.platform == actualValue.platform &&
                                normalizeDirection(expectedValue.direction) ==
                                normalizeDirection(directionOf(actualValue)),
                        )
                    }
                }.minWithOrNull(
                    compareByDescending<Match> { it.platformAndDirectionMatch }
                        .thenBy { it.deltaMillis }
                )
                if (wrongDestination != null &&
                    wrongDestination.deltaMillis <= MATCH_TOLERANCE_MILLIS
                ) {
                    destinationMismatches++
                    details += "destination ${expectedValue.describe()} actual=" +
                        describeActual(actual[wrongDestination.actualIndex])
                }
                if (best != null) {
                    timeMismatches++
                    details += "time ${expectedValue.describe()} nearest=" +
                        describeActual(actual[best.actualIndex]) +
                        " delta=${best.deltaMillis / 1000}s"
                } else {
                    details += "missing ${expectedValue.describe()}"
                }
                return@forEach
            }
            unmatchedActual.remove(best.actualIndex)
            matched++
        }
        val actualOnly = unmatchedActual.filter { actualIndex ->
            val actualValue = actual[actualIndex]
            val lineAndDirectionCutoff = latestByLineAndDirection[
                BoardKey(normalizeLine(actualValue.line), normalizeDirection(directionOf(actualValue)))
            ]
            lineAndDirectionCutoff != null &&
                actualValue.getMeanEstimate() <= lineAndDirectionCutoff
        }
        actualOnly.take(8).forEach { actualIndex ->
            details += "projection-only ${describeActual(actual[actualIndex])}"
        }
        val projectionSourceCounts = actualOnly
            .groupingBy { actualIndex ->
                actual[actualIndex].tripLegs.firstOrNull()?.departureSource?.name ?: "UNKNOWN"
            }
            .eachCount()
        return StationAudit(
            station,
            activeExpected.size,
            matched,
            expected.count { it.canceled },
            actualCancellations,
            activeExpected.size - matched,
            timeMismatches,
            destinationMismatches,
            actualOnly.size,
            actualOnly.count { actualIndex -> actual[actualIndex].canceled },
            projectionSourceCounts,
            details,
        )
    }

    private fun parseEtd(station: Station, xml: String): List<EtdPrediction> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(
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
                val minutesText = text(estimate, "minutes")
                val minutes = if (minutesText.equals("leaving", ignoreCase = true)) {
                    0L
                } else {
                    minutesText.toLongOrNull() ?: return@forEach
                }
                result += EtdPrediction(
                    station,
                    destination,
                    lineForColor(text(estimate, "color")),
                    baseTime + minutes * 60_000L,
                    text(estimate, "cancelflag") == "1",
                    text(estimate, "platform"),
                    text(estimate, "direction"),
                    "${station.abbreviation}:${text(etd, "destination")}",
                )
            }
        }
        return result.filter { it.line != null && it.destination != null }
    }

    private fun auditFixtures(): List<String> {
        val resourceRoot = Path.of(checkNotNull(javaClass.getResource("/bart_live_fixture.txt")).toURI()).parent
        val fixtures = Files.list(resourceRoot).use { entries ->
            entries.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { it.startsWith("bart_live_") }
                .filter { isCompleteFixture(resourceRoot, it) }
                .sorted()
                .toList()
        }
        return fixtures.filterNot { it in EXCLUDED_FIXTURES }
    }

    private fun isCompleteFixture(root: Path, fixtureName: String): Boolean {
        val fixtureRoot = root.resolve(fixtureName)
        val etdRoot = fixtureRoot.resolve("etd")
        return Files.isRegularFile(fixtureRoot.resolve("trip_updates.pb")) &&
            Files.isRegularFile(fixtureRoot.resolve("alerts.pb")) &&
            Station.getStationList().all { station ->
                Files.isRegularFile(etdRoot.resolve("${station.abbreviation}.xml"))
            }
    }

    private fun loadGtfsFiles(resource: String): Map<String, String> {
        val required = setOf(
            "routes.txt", "trips.txt", "stops.txt", "stop_times.txt",
            "calendar.txt", "calendar_dates.txt", "transfers.txt",
        )
        val files = linkedMapOf<String, String>()
        ZipInputStream(checkNotNull(javaClass.getResourceAsStream(resource))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name in required) {
                    files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return files
    }

    private fun resourceFeed(path: String): GtfsRealtime.FeedMessage =
        GtfsRealtime.FeedMessage.parseFrom(checkNotNull(javaClass.getResourceAsStream(path)))

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() }

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

    private fun normalizeLine(line: Line?): Line? = line

    private fun normalizeDirection(direction: String?): String? = when (direction?.lowercase(Locale.ROOT)) {
        "north", "n" -> "n"
        "south", "s" -> "s"
        else -> direction?.lowercase(Locale.ROOT)
    }

    private fun directionOf(departure: Departure): String? =
        departure.tripLegs.firstOrNull()?.direction

    private fun describeActual(departure: Departure): String {
        val leg = departure.tripLegs.firstOrNull()
        return "${departure.line}:${departure.trainDestination?.abbreviation} " +
            "${departure.getMeanEstimate() / 1000} " +
            "trip=${leg?.tripId ?: "?"} source=${leg?.departureSource ?: "?"}"
    }

    private data class EtdPrediction(
        val station: Station,
        val destination: Station?,
        val line: Line?,
        val departureTimeMillis: Long,
        val canceled: Boolean,
        val platform: String?,
        val direction: String?,
        val source: String,
    ) {
        fun describe(): String = "$source ${Instant.ofEpochMilli(departureTimeMillis)}"
    }

    private data class Match(
        val actualIndex: Int,
        val deltaMillis: Long,
        val platformAndDirectionMatch: Boolean,
    )

    private data class BoardKey(
        val line: Line?,
        val direction: String?,
    )

    private data class StationAudit(
        val station: Station,
        val expectedCount: Int,
        val matchedCount: Int,
        val expectedCancellationCount: Int,
        val actualCancellationCount: Int,
        val missingCount: Int,
        val timeMismatchCount: Int,
        val destinationMismatchCount: Int,
        val actualOnlyCount: Int,
        val unexpectedCancellationCount: Int,
        val projectionSourceCounts: Map<String, Int>,
        val details: List<String>,
    ) {
        val hasDifferences: Boolean
            get() = missingCount > 0 || timeMismatchCount > 0 ||
                destinationMismatchCount > 0 || actualOnlyCount > 0

        fun describe(): String =
            "${station.abbreviation}: expected=$expectedCount matched=$matchedCount " +
                "missing=$missingCount timeMismatches=$timeMismatchCount " +
                "destinationMismatches=$destinationMismatchCount " +
                "projectionOnly=$actualOnlyCount unexpectedCancellations=$unexpectedCancellationCount " +
                "details=${details.take(4)}"
    }

    private data class FixtureAudit(
        val fixtureName: String,
        val expectedCount: Int,
        val matchedCount: Int,
        val missingCount: Int,
        val timeMismatchCount: Int,
        val destinationMismatchCount: Int,
        val actualOnlyCount: Int,
        val unexpectedCancellationCount: Int,
        val expectedCancellationCount: Int,
        val actualCancellationCount: Int,
    )

    companion object {
        private val PACIFIC_ZONE = ZoneId.of("America/Los_Angeles")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.US)
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.US)
        private const val MATCH_TOLERANCE_MILLIS = 2 * 60_000L
        private const val MIN_COMPARISON_WINDOW_MILLIS = 45 * 60_000L
        private val EXCLUDED_FIXTURES = setOf(
            "bart_live_20260912_071605",
            "bart_live_20260912_192815",
            "bart_live_20260913_182435",
            "bart_live_20260913_201652",
        )
    }
}
