package `in`.izyum.bart.transit.gtfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.RealTimeDepartures
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.backend.TransitFeedSnapshot
import `in`.izyum.bart.backend.RouteDepartureProjection
import `in`.izyum.bart.backend.TripProgressProjection
import `in`.izyum.bart.backend.Schedule
import `in`.izyum.bart.transit.normalization.RealtimeFeedNormalizer
import com.google.transit.realtime.GtfsRealtime

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Regression coverage against the checked-in official BART static feed. */
class LiveGtfsRoutingTest {
    private val FILES: Map<String, String> = loadFiles()
    private val CATALOG: GtfsNetworkCatalog =
            GtfsNetworkCatalog.fromFiles(FILES)
    private val NETWORK: BartGtfsNetwork =
            BartGtfsNetwork.fromCatalog(CATALOG)
    private val NIGHT_FILES: Map<String, String> = loadFiles(
            "/gtfs/bart_google_transit_night.zip")
    private val NIGHT_NETWORK: BartGtfsNetwork =
            BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(NIGHT_FILES))
    private val COLOR_LINES: List<Line> = listOf(
            Line.RED, Line.ORANGE, Line.YELLOW, Line.BLUE, Line.GREEN)

    @Test
    fun liveFeedSatisfiesGenericAndBartInvariants() {
        assertTrue(CATALOG.validationErrors().toString(),
                CATALOG.validationErrors().isEmpty())
        assertTrue(NETWORK.validationErrors().toString(),
                NETWORK.validationErrors().isEmpty())
        assertFalse("live feed has no transfer rules", CATALOG.transfers.isEmpty())
        for (transfer in CATALOG.transfers) {
            assertTrue("invalid transfer type " + transfer.transferType,
                    transfer.transferType >= 0 && transfer.transferType <= 3)
        }

        for (line in COLOR_LINES) {
            val patterns: List<BartGtfsNetwork.StationPattern> = NETWORK.routePatternsForLine(line)
            assertFalse("missing GTFS pattern for " + line, patterns.isEmpty())
            for (pattern in patterns) {
                assertTrue(pattern.stations.size >= 2)
                assertTrue(!pattern.routeId.isEmpty())
                assertTrue("pattern has no trips: " + pattern.routeId,
                        !pattern.tripIds.isEmpty())
                assertTrue("unexpected direction " + pattern.direction,
                        "n".equals(pattern.direction)
                                || "s".equals(pattern.direction))
                assertNoRepeatedStations(pattern.stations)
                for (tripId in pattern.tripIds) {
                    assertTrue("pattern references no trip: " + tripId,
                            CATALOG.tripsById.containsKey(tripId))
                    assertTrue("trip is on another route: " + tripId,
                            pattern.routeId.equals(
                                    CATALOG.routeIdForTrip(tripId)))
                }
            }
        }
    }

    @Test
    fun currentGreenDepartureFromMilpitasUsesBlueAtBayFair(){
        val departures = projectDepartures(Station.MLPT, Station.CAST, currentTripUpdates(), NETWORK)

        var greenDeparture: Departure? = null
        for (departure in departures.getDepartures()) {
            if (departure.line == Line.GREEN) {
                greenDeparture = departure
                break
            }
        }
        val selectedGreenDeparture = checkNotNull(greenDeparture) {
            "departures=" + departures.getDepartures()
        }
        assertEquals("selected=" + describe(selectedGreenDeparture),
                listOf(Line.GREEN, Line.BLUE),
                linesOf(selectedGreenDeparture.tripLegs))
        assertEquals("selected=" + describe(selectedGreenDeparture),
                listOf(Station.BAYF),
                transferStationsOf(selectedGreenDeparture.tripLegs))
    }

    @Test
    fun currentFeedKeepsPittsburgCenterAsTheFinalStop(){
        val departures = projectDepartures(Station.CAST, Station.PCTR, currentTripUpdates(), NETWORK)

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        val terminalDepartures = departures.getDepartures().filter {
            it.tripLegs.lastOrNull()?.line == Line.YELLOW
        }
        assertFalse("terminal departures=" + departures.getDepartures(),
            terminalDepartures.isEmpty())
        for (departure in terminalDepartures) {
            val terminal = departure.tripLegs
                    .get(departure.tripLegs.size - 1)
            assertEquals(Station.PCTR, terminal.destination)
            assertEquals(Line.YELLOW, terminal.line)
            assertEquals(3, departure.tripLegs.size)
            assertTrue("terminal=" + terminal.tripId + " scheduled="
                            + terminal.scheduledDepartureTime + " effective="
                            + terminal.departureTime,
                    terminal.departureTime > 0L)
        }
    }

    @Test
    fun currentFeedConnectsPleasantHillToPittsburgCenterShuttle(){
        val departures = projectDepartures(Station.PHIL, Station.PCTR, currentTripUpdates(), NETWORK)

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        var hasShuttle = false
        for (departure in departures.getDepartures()) {
            if (linesOf(departure.tripLegs).contains(Line.YELLOW)) {
                hasShuttle = true
                val leg = departure.tripLegs
                        .get(departure.tripLegs.size - 1)
                assertTrue("departure=" + departure,
                        leg.departureTime > 0)
                assertTrue(stationsOf(leg.stops).contains(Station.PCTR))
                break
            }
        }
        assertTrue("departures=" + departures.getDepartures(), hasShuttle)
    }

    @Test
    fun currentFeedKeepsAntiochAsTheFinalStop(){
        val departures = projectDepartures(Station.CAST, Station.ANTC, currentTripUpdates(), NETWORK)

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        for (departure in departures.getDepartures()) {
            val terminal = departure.tripLegs
                    .get(departure.tripLegs.size - 1)
            assertEquals(Station.ANTC, terminal.destination)
            assertEquals(Line.YELLOW, terminal.line)
            assertEquals(3, departure.tripLegs.size)
            assertTrue("terminal=" + terminal.tripId + " scheduled="
                            + terminal.scheduledDepartureTime + " effective="
                            + terminal.departureTime,
                    terminal.departureTime > 0L)
        }
    }

    @Test
    fun currentFeedReportsPittsburgToAntiochDepartures(){
        val departures = projectDepartures(Station.PITT, Station.ANTC, currentTripUpdates(), NETWORK)

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        for (departure in departures.getDepartures()) {
            assertEquals(Line.YELLOW, departure.line)
            assertEquals("departure=" + describe(departure), Station.ANTC, departure.tripLegs.get(0)
                    .destination)
        }
    }

    @Test
    fun currentFeedReportsAntiochToPittsburgDepartures(){
        val departures = projectDepartures(Station.ANTC, Station.PITT, currentTripUpdates(), NETWORK)

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        for (departure in departures.getDepartures()) {
            assertEquals(Line.YELLOW, departure.line)
            assertEquals(Station.PITT, departure.tripLegs.get(0)
                    .destination)
        }
    }

    @Test
    fun currentCanonicalProjectionCarriesRealtimePittArrivalWhenFeedHasIt(){
        val feed = currentTripUpdates()
        val snapshot = TransitFeedSnapshot(
                feed, emptyFeed(), feed.header.timestamp * 1000L)
        val departures = RouteDepartureProjection(
                StationPair(Station.ANTC, Station.PITT), NETWORK)
                .project(snapshot)

        val legsToPitt = departures.getDepartures().flatMap { it.tripLegs }
                .filter { it.destination == Station.PITT }
        assertTrue("departures=$departures", legsToPitt.isNotEmpty())
        val matching = legsToPitt.first { it.tripId == "1973134" }
        assertEquals(1788803175L * 1000L, matching.arrivalTime)
        assertEquals(PredictionSource.REALTIME, matching.arrivalSource)
        assertEquals(
                PredictionSource.REALTIME,
                matching.stops.first { it.station == Station.PITT }.arrivalSource,
        )
    }

    @Test
    fun capturedAntiochFeedBuildsPassengerDeparturesFromNormalTrips(){
        val departures = projectDepartures(Station.ANTC, null, antiochLiveTripUpdates(), NETWORK)

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        val departureTimes: MutableSet<Long> = HashSet()
        for (departure in departures.getDepartures()) {
            assertEquals(Station.ANTC, departure.origin)
            assertEquals(Line.YELLOW, departure.line)
            assertEquals(1, departure.tripLegs.size)
            assertEquals(Station.ANTC, departure.tripLegs.get(0).origin)
            assertTrue("departure=" + departure,
                    departure.tripLegs.get(0).departureTime > 0L)
            assertTrue("normal Yellow service lacks its static schedule: "
                            + departure,
                    departure.tripLegs.get(0).scheduledDepartureTime
                            > 0L)
            assertTrue("duplicate Antioch departure: " + departure,
                    departureTimes.add(
                            departure.tripLegs.get(0).departureTime))
        }
    }

    @Test
    fun capturedAntiochFeedPreservesScheduleOnlyTripsWhenRealtimeIsAbsent(){
        val feed = antiochLiveTripUpdates()
        val snapshot = TransitFeedSnapshot(
                feed, emptyFeed(), feed.getHeader().getTimestamp() * 1000L)
        val projection = RouteDepartureProjection(
                StationPair(Station.ANTC, null), NETWORK)
        val base = projection.project(snapshot)
        assertTrue("captured Antioch departures=" + base.getDepartures(),
                base.getDepartures().isNotEmpty())
        val departureTimes: MutableSet<Long> = HashSet()
        for (departure in base.getDepartures()) {
            assertTrue("normal Yellow service lacks its static schedule: "
                            + departure,
                    departure.tripLegs.get(0).scheduledDepartureTime
                            > 0L)
            assertTrue("duplicate Antioch departure: " + departure,
                    departureTimes.add(
                            departure.tripLegs.get(0).departureTime))
        }
    }

    @Test
    fun currentTerminalFeedKeepsScheduleOnlyTripsDistinctFromRealtimeTrips(){
        val feed = tripUpdates(
                "/bart_live_terminals_20260911_183436/trip_updates.pb")
        val snapshot = TransitFeedSnapshot(
                feed, emptyFeed(), feed.getHeader().getTimestamp() * 1000L)
        val departures: List<Departure> = RouteDepartureProjection(
                StationPair(Station.ANTC, null), NETWORK).project(snapshot)
                .getDepartures()
        val tripIds: MutableSet<String> = HashSet()
        for (departure in departures) {
        tripIds.add(departure.tripLegs.get(0).tripId!!)
        }
        assertTrue("expected a schedule-only terminal trip", tripIds.contains("1965206"))
        assertEquals("trip identity must be unique", departures.size, tripIds.size)
        val matchedRealtime = departures.firstOrNull {
            it.tripLegs[0].tripId == "1965119"
        } ?: throw AssertionError()
        assertEquals(PredictionSource.REALTIME,
                matchedRealtime.tripLegs.get(0).departureSource)
    }

    @Test
    fun unknownDmuTripIdDoesNotCreatePassengerDeparture() {
        val feed = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(900L))
                .addEntity(trip("628", "",
                        arrayOf("PITT", "E20-1"),
                        longArrayOf(1000L, 1100L)))
                .build()

        val departures = projectDepartures(Station.PITT, Station.PCTR, feed, NETWORK)
        assertTrue("technical DMU updates must not create a passenger trip",
                departures.getDepartures().isEmpty())
    }

    @Test
    fun separatePittsburgPlatformAndDmuUpdatesDoNotBuildPassengerDeparture() {
        val feed = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(900L))
                .addEntity(trip("pitt-platform", "",
                        arrayOf("C80-1"), longArrayOf(1000L)))
                .addEntity(trip("634", "",
                        arrayOf("E20-1"), longArrayOf(1706L)))
                .build()

        val departures = projectDepartures(Station.PITT, Station.ANTC, feed, NETWORK)
        assertTrue("technical DMU updates must not create a passenger trip",
                departures.getDepartures().isEmpty())
    }

    @Test
    fun separateReversePlatformAndDmuUpdatesDoNotBuildPassengerDeparture() {
        val feed = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(790L))
                .addEntity(trip("pitt-platform", "",
                        arrayOf("C80-2"), longArrayOf(1706L)))
                .addEntity(trip("634", "",
                        arrayOf("E30-2", "E20-2"),
                        longArrayOf(800L, 1000L)))
                .build()

        val departures = projectDepartures(Station.ANTC, Station.PITT, feed, NETWORK)
        assertTrue("technical DMU updates must not create a passenger trip",
                departures.getDepartures().isEmpty())
    }

    @Test
    fun currentSfoProjectionDoesNotFabricateARealtimeRedTrip(){
        val departures = projectDepartures(Station.SFIA, Station.CAST, currentTripUpdates(), NETWORK)

        assertFalse("departures=" + departures.getDepartures(), departures.getDepartures().isEmpty())
        assertTrue(departures.getDepartures().all { departure ->
            departure.tripLegs.none { it.tripId?.toIntOrNull() in 600..799 }
        })
    }

    @Test
    fun currentTripUpdatesBuildCompleteCastroValleyToPittsburgItinerary(){
        val feed = currentTripUpdates()
        val departures = projectDepartures(Station.CAST, Station.PITT, feed, NETWORK)
        assertTrue(feed.getEntityCount() > 0)
        assertEquals("departures=" + departures.getDepartures(),
                listOf("1973728", "1973729", "1973730", "1973731",
                        "1973732", "1973733", "1973734"),
                departures.getDepartures().map { it.tripLegs[0].tripId })
        for (departure in departures.getDepartures()) {
            assertEquals(3, departure.tripLegs.size)
            assertTrue(
                "unexpected first transfer: ${departure.tripLegs[0].destination}",
                departure.tripLegs[0].destination in setOf(Station.LAKE, Station.BAYF),
            )
            assertEquals(Station._19TH, departure.tripLegs.get(1).destination)
            assertEquals(Station.PITT, departure.tripLegs.get(2).destination)
            for (index in 0 until departure.tripLegs.size - 1) {
                assertTrue("selected connection goes backwards in time",
                        departure.tripLegs.get(index + 1).departureTime
                                >= departure.tripLegs.get(index).arrivalTime)
            }
        }
    }

    @Test
    fun progressProjectionRestoresMissingPittsburgLegFromCurrentFeed(){
        val feed = currentTripUpdates()
        val selected = projectDepartures(Station.CAST, Station.PITT, feed, NETWORK)
                .getDepartures()[0]
        val partial: List<TripLeg> = selected.tripLegs.subList(0, 2)

        val refreshed: List<TripLeg> = TripProgressProjection(
                Station.CAST, Station.PITT, partial, NETWORK)
                .project(TransitFeedSnapshot(
                        feed, emptyFeed(), System.currentTimeMillis()))

        assertEquals("refreshed=" + refreshed.map {
                        "${it.line}:${it.origin}->${it.destination}:${it.tripId}"
                    },
                3, refreshed.size)
        assertEquals(Station.PITT, refreshed.get(2).destination)
    }

    @Test
    fun nightTripUpdatesUseStaticTerminalWhenRealtimeStopsAtBalboa(){
        val departures = projectDepartures(Station.BALB, Station.DALY, nightTripUpdates(), NIGHT_NETWORK)

        val matching = departures.getDepartures().firstOrNull {
            it.tripLegs[0].tripId == "1973764"
        } ?: throw AssertionError("departures=${departures.getDepartures()}")
        assertEquals(Station.DALY, matching.trainDestination)
        assertEquals(Station.DALY, matching.tripLegs.get(0).destination)
    }

    @Test
    fun nightTripUpdatesDoNotShowTripsThatAlreadyLeftAshby(){
        val departures = RouteDepartureProjection(
                StationPair(Station.ASHB, Station.DALY), NIGHT_NETWORK)
                .project(TransitFeedSnapshot(
                        nightTripUpdates(), emptyFeed(), 0L))

        assertFalse("expected future static Ashby departures",
                departures.getDepartures().isEmpty())
        val staleCutoff = 1788846725L * 1000L - 45L * 1000L
        for (departure in departures.getDepartures()) {
            assertTrue("stale departure=" + departure,
                    departure.tripLegs.get(0).departureTime >= staleCutoff)
        }
    }

    @Test
    fun currentTripUpdatesProvideTwelfthStreetToSfoRouting(){
        val departures = RouteDepartureProjection(
                StationPair(Station._12TH, Station.SFIA), NETWORK)
                .project(TransitFeedSnapshot(
                        currentTripUpdates(), emptyFeed(), 0L))

        assertFalse("departures=" + departures.getDepartures(),
                        departures.getDepartures().isEmpty())
        var hasSfo = false
        for (departure in departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station._12TH, Station.SFIA)
            if (departure.trainDestination == Station.SFIA) {
                hasSfo = true
                break
            }
        }
        assertTrue("departures=" + departures.getDepartures(), hasSfo)
    }

    @Test
    fun liveSnapshotRetriesAnAlternativeLineWhenThePreferredTransferMisses(){
        val departures = RouteDepartureProjection(
                StationPair(Station.CAST, Station.SFIA), NETWORK)
                .project(TransitFeedSnapshot(
                        liveCastroSfoTripUpdates(), emptyFeed(), 0L))

        var hasBlueToYellow = false
        for (departure in departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station.CAST, Station.SFIA)
            if (linesOf(departure.tripLegs).equals(
                    listOf(Line.BLUE, Line.YELLOW))
                    && departure.tripLegs.get(
                            departure.tripLegs.size - 1).destination
                            == Station.SFIA) {
                hasBlueToYellow = true
                break
            }
        }
                assertTrue("expected Blue to Yellow fallback: "
                        + departures.getDepartures().map {
                            "${linesOf(it.tripLegs)}:${it.trainDestination}"
                        },
                hasBlueToYellow)
    }

    @Test
    fun refreshedTripUpdatesPreserveExplicitCancellations(){
        val feed = refreshedTripUpdates()
        val schedule = Schedule.fromStatic(NETWORK,
                feed.getHeader().getTimestamp() * 1000L,
                COLOR_LINES.toSet()).applyRealtime(
                RealtimeFeedNormalizer.normalize(feed))

        for (tripId in listOf(
                "1965275", "1965700", "1965654", "1965658", "1965850")) {
            val trip: Schedule.Trip = trip(schedule, tripId)
            assertTrue("trip should be canceled: " + tripId, trip.canceled)
        }
    }

    @Test
    fun refreshedTripUpdatesShowDirectAndTransferDepartures(){
        val departures = RouteDepartureProjection(
                StationPair(Station.DBRK, Station.POWL), NETWORK)
                .project(TransitFeedSnapshot(
                        refreshedTripUpdates(), emptyFeed(), 0L))

        var hasDirectRed = false
        var hasOrangeToYellow = false
        for (departure in departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station.DBRK, Station.POWL)
            if (departure.line == Line.RED && !departure.hasTransfers()) {
                hasDirectRed = true
            }
            if (departure.line == Line.ORANGE
                    && linesOf(departure.tripLegs).equals(
                    listOf(Line.ORANGE, Line.YELLOW))
                    && transferStationsOf(departure.tripLegs).equals(
                    listOf(Station.MCAR))) {
                hasOrangeToYellow = true
            }
        }
        assertTrue("missing direct red departure: " + departures.getDepartures(),
                hasDirectRed)
        assertTrue("missing Orange to Yellow departure: "
                        + departures.getDepartures(),
                hasOrangeToYellow)
    }

    @Test
    fun currentTripUpdatesShowSfoFromTwelfthStreetStationBoard(){
        val departures = RouteDepartureProjection(
                StationPair(Station._12TH, null), NETWORK)
                .project(TransitFeedSnapshot(
                        currentTripUpdates(), emptyFeed(), 0L))

        var hasSfo = false
        for (departure in departures.getDepartures()) {
            if (departure.trainDestination == Station.SFIA) {
                hasSfo = true
                break
            }
        }
        assertTrue("departures=" + departures.getDepartures(), hasSfo)
    }

    @Test
    fun latestTripUpdatesRouteTwelfthStreetTo16thStreet(){
        val departures = RouteDepartureProjection(
                StationPair(Station._12TH, Station._16TH), NIGHT_NETWORK)
                .project(TransitFeedSnapshot(
                        latest12th16thTripUpdates(), emptyFeed(), 0L))

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        for (departure in departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station._12TH, Station._16TH)
            assertEquals(Station._16TH,
                    departure.tripLegs.get(departure.tripLegs.size - 1)
                            .destination)
        }
    }

    @Test
    fun nightFixtureRoutesTwelfthStreetToMillbraeUsesStaticSfoMillbraeService(){
        val nightUpdates = nightTripUpdates()
        val feedTime = 1788846725L * 1000L
        val departures = RouteDepartureProjection(
                StationPair(Station._12TH, Station.MLBR), NIGHT_NETWORK)
                .project(TransitFeedSnapshot(
                        nightUpdates, emptyFeed(), feedTime))
        val schedule = Schedule.fromStatic(
            NIGHT_NETWORK, feedTime, COLOR_LINES.toSet()
        )
        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        val millbraeDepartures = departures.getDepartures().filter {
            it.tripLegs.lastOrNull()?.destination == Station.MLBR
        }
        assertFalse("departures=" + departures.getDepartures(),
                millbraeDepartures.isEmpty())
        assertTrue("schedule has no static SFO/Millbrae pattern: " + schedule.trips,
            schedule.trips.any { trip ->
                trip.stopAt(Station.SFIA) != null
                    && trip.stopAt(Station.MLBR) != null
            })
        for (departure in millbraeDepartures) {
            assertFeasibleItinerary(departure, Station._12TH, Station.MLBR)
            assertTrue("generated identity in $departure", departure.tripLegs.none {
                it.tripId?.contains("late-night-sfo-millbrae") == true
            })
            assertEquals(Station.MLBR,
                    departure.tripLegs.get(departure.tripLegs.size - 1)
                            .destination)
        }
    }

    @Test
    fun currentAntiochToCastroValleyDeparturesUseMacArthurThenLakeMerritt() {
        val updates = currentTripUpdates()
        val departures = RouteDepartureProjection(
            StationPair(Station.ANTC, Station.CAST), NETWORK
        ).project(TransitFeedSnapshot(updates, emptyFeed(), 1788801718L * 1000L))

        assertFalse("departures=" + departures.getDepartures(),
            departures.getDepartures().isEmpty())
        for (departure in departures.getDepartures()) {
            assertEquals("departure=" + departure + " legs=" + departure.tripLegs.map {
                    "${it.tripId}:${it.line} ${it.origin}->${it.destination} " +
                        "${it.departureTime}->${it.arrivalTime}"
                },
                listOf(Line.YELLOW, Line.ORANGE, Line.BLUE),
                linesOf(departure.tripLegs))
            assertEquals("legs=" + departure.tripLegs.map {
                "${it.tripId}:${it.line} ${it.origin}->${it.destination} " +
                    "${it.departureTime}->${it.arrivalTime}"
            }, Station.MCAR, departure.tripLegs[0].destination)
            assertEquals(Station.LAKE, departure.tripLegs[1].destination)
            assertEquals(Station.CAST, departure.tripLegs[2].destination)
        }
    }

    /**
     * Exact oracle checks transcribed from the checked-in JSON and static GTFS
     * fixture. These intentionally assert both source and epoch milliseconds:
     * a plausible-looking route is not enough for prediction correctness.
     */
    @Test
    fun fixturePredictionsMatchStaticAndRealtimeOracleAtAntioch(){
        val correctedDay = Schedule.fromStatic(
                NETWORK, 1788801718L * 1000L, COLOR_LINES.toSet())
                .applyRealtime(RealtimeFeedNormalizer.normalize(currentTripUpdates()))
        val dayTrip: Schedule.Trip = trip(correctedDay, "1973133")

        assertScheduleStop(dayTrip, Station.ANTC,
                1788800820L * 1000L, 1788800820L * 1000L,
                1788800820L * 1000L, 1788800820L * 1000L,
                PredictionSource.SCHEDULE)
        assertScheduleStop(dayTrip, Station.PITT,
                1788801900L * 1000L, 1788801960L * 1000L,
                1788801975L * 1000L, 1788801999L * 1000L,
                PredictionSource.REALTIME)

        val dayLogicalTrip: Schedule.Trip = trip(correctedDay, "1973207")
        assertScheduleStop(dayLogicalTrip, Station.PITT,
                1788803580L * 1000L, 1788803640L * 1000L,
                1788803659L * 1000L, 1788803683L * 1000L,
                PredictionSource.REALTIME)
        val pctr = dayLogicalTrip.stopAt(Station.PCTR) ?: throw AssertionError()
        assertEquals(PredictionSource.ESTIMATE, pctr.arrivalSource)
        val antioch = dayLogicalTrip.stopAt(Station.ANTC) ?: throw AssertionError()
        assertEquals(PredictionSource.ESTIMATE, antioch.arrivalSource)
        assertTrue("partial RT must not truncate the published terminal",
                antioch.arrivalTime > pctr.departureTime)

        val correctedNight = Schedule.fromStatic(
                NIGHT_NETWORK, 1788846725L * 1000L,
                COLOR_LINES.toSet())
                .applyRealtime(RealtimeFeedNormalizer.normalize(nightTripUpdates()))
        val nightTrip: Schedule.Trip = trip(correctedNight, "1973170")
        assertScheduleStop(nightTrip, Station.PITT,
                1788847500L * 1000L, 1788847560L * 1000L,
                1788847575L * 1000L, 1788847599L * 1000L,
                PredictionSource.REALTIME)
        assertScheduleStop(nightTrip, Station.SBRN,
                1788852540L * 1000L, 1788852540L * 1000L,
                1788852584L * 1000L, 1788852602L * 1000L,
                PredictionSource.REALTIME)
    }

    @Test
    fun routeProjectionUsesNormalYellowPredictionThroughAntioch(){
        val departures = RouteDepartureProjection(
                StationPair(Station.PITT, Station.ANTC), NETWORK)
                .project(TransitFeedSnapshot(
                        currentTripUpdates(), emptyFeed(), 0L))

        var matching: TripLeg? = null
        for (departure in departures.getDepartures()) {
            for (leg in departure.tripLegs) {
                if ("1973207".equals(leg.tripId)) {
                    matching = leg
                    break
                }
            }
        }
        assertTrue("departures=" + departures.getDepartures(), matching != null)
        val found = matching ?: throw AssertionError("missing matching leg")
        assertEquals(Line.YELLOW, found.line)
        assertEquals(Station.PITT, found.origin)
        assertEquals(Station.ANTC, found.destination)
        assertEquals(1788803640L * 1000L, found.scheduledDepartureTime)
        assertEquals(1788803683L * 1000L, found.departureTime)
        assertEquals(1788803683L * 1000L,
                found.stops.first { it.station == Station.PITT }.departureTime)
        assertEquals(PredictionSource.REALTIME,
                found.stops.first { it.station == Station.PITT }.departureSource)
        assertEquals(1788804389L * 1000L,
                found.stops.first { it.station == Station.PCTR }.departureTime)
        assertEquals(PredictionSource.REALTIME,
                found.stops.first { it.station == Station.PCTR }.departureSource)
        assertEquals(1788804720L * 1000L, found.scheduledArrivalTime)
        // The canonical snapshot merges the electric PITT update with the
        // DMU-only PCTR/ANTC timing instead of projecting either feed alone.
        assertEquals(1788804809L * 1000L, found.arrivalTime)
        assertEquals(PredictionSource.REALTIME, found.departureSource)
        assertEquals(PredictionSource.ESTIMATE, found.arrivalSource)
    }

    private fun trip(schedule: Schedule, tripId: String): Schedule.Trip {
        for (trip in schedule.trips) {
            if (trip.key.tripId == tripId) {
                return trip
            }
        }
        throw AssertionError("missing schedule trip $tripId")
    }

    private fun assertScheduleStop(
        trip: Schedule.Trip,
        station: Station,
        scheduledArrival: Long,
        scheduledDeparture: Long,
        arrival: Long,
        departure: Long,
        source: PredictionSource,
    ) {
        val stop = trip.stops.firstOrNull { it.station == station }
            ?: throw AssertionError("missing $station in ${trip.key}")
        assertEquals("${trip.key} $station scheduled arrival",
            scheduledArrival, stop.scheduledArrivalTime)
        assertEquals("${trip.key} $station scheduled departure",
            scheduledDeparture, stop.scheduledDepartureTime)
        assertEquals("${trip.key} $station arrival", arrival, stop.arrivalTime)
        assertEquals("${trip.key} $station departure", departure, stop.departureTime)
        assertEquals("${trip.key} $station arrival source", source, stop.arrivalSource)
        assertEquals("${trip.key} $station departure source", source, stop.departureSource)
    }

    private fun currentTripUpdates(): GtfsRealtime.FeedMessage =
        tripUpdates("/gtfsrt/bart_trip_updates.pb")

    private fun antiochLiveTripUpdates(): GtfsRealtime.FeedMessage =
        tripUpdates("/gtfsrt/bart_trip_updates_antioch_live_20260911_153326.pb")

    private fun refreshedTripUpdates(): GtfsRealtime.FeedMessage =
        tripUpdates("/gtfsrt/bart_trip_updates_current.pb")

    private fun liveCastroSfoTripUpdates(): GtfsRealtime.FeedMessage =
        tripUpdates("/gtfsrt/bart_trip_updates_live_20260908_202339.pb")

    private fun nightTripUpdates(): GtfsRealtime.FeedMessage =
        tripUpdates("/gtfsrt/bart_trip_updates_night.pb")

    private fun latest12th16thTripUpdates(): GtfsRealtime.FeedMessage =
        tripUpdates("/gtfsrt/bart_trip_updates_12th_16th_now.pb")

    private fun tripUpdates(resource: String): GtfsRealtime.FeedMessage {
        val input = LiveGtfsRoutingTest::class.java.getResourceAsStream(resource)
            ?: throw AssertionError("trip-update fixture is missing: $resource")
        return input.use { GtfsRealtime.FeedMessage.parseFrom(it) }
    }

    private fun emptyFeed(): GtfsRealtime.FeedMessage =
        GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0"))
                .build()

    private fun projectDepartures(
        origin: Station,
        destination: Station?,
        feed: GtfsRealtime.FeedMessage,
        network: BartGtfsNetwork,
    ): RealTimeDepartures = RouteDepartureProjection(
        StationPair(origin, destination), network,
    ).project(TransitFeedSnapshot(
        feed,
        emptyFeed(),
        feed.header.timestamp * 1000L,
    ))

    private fun linesOf(legs: List<TripLeg>): List<Line> = legs.map { it.line!! }

    private fun assertFeasibleItinerary(
        departure: Departure,
        expectedOrigin: Station,
        expectedDestination: Station,
    ) {
        val legs = departure.tripLegs
        assertFalse("departure has no legs: " + describe(departure), legs.isEmpty())
        assertEquals("wrong itinerary origin: " + describe(departure),
                expectedOrigin, legs.get(0).origin)
        assertEquals("wrong itinerary destination: " + describe(departure),
                expectedDestination, legs.get(legs.size - 1).destination)
        for (index in legs.indices) {
            val leg = legs[index]
            assertTrue("leg departs after it arrives: " + describe(departure),
                    leg.arrivalTime <= 0L
                            || leg.departureTime <= leg.arrivalTime)
            if (index == 0) {
                continue
            }
            val previous = legs[index - 1]
            assertEquals("legs do not meet: " + describe(departure),
                    previous.destination, leg.origin)
            if (previous.arrivalTime > 0L && leg.departureTime > 0L) {
                assertTrue("connection goes backwards in time: "
                                + describe(departure),
                        leg.departureTime >= previous.arrivalTime)
            }
        }
    }

    private fun describe(departure: Departure): String =
        "${linesOf(departure.tripLegs)} trips=${departure.tripLegs.map { it.tripId }} " +
            "times=${departure.tripLegs.map { "${it.departureTime}->${it.arrivalTime}" }}"

    private fun transferStationsOf(legs: List<TripLeg>): List<Station> =
        legs.dropLast(1).map { it.destination!! }

    private fun stationsOf(stops: List<`in`.izyum.bart.model.TripStop>): List<Station> =
        stops.mapNotNull { it.station }

    private fun trip(
        tripId: String,
        routeId: String,
        stops: Array<String>,
        times: LongArray,
    ): GtfsRealtime.FeedEntity {
        val update = GtfsRealtime.TripUpdate
                .newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                        .setRouteId(routeId).setTripId(tripId))
        for (i in stops.indices) {
            val event = GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                            .setTime(times[i]).build()
            update.addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate
                    .newBuilder().setStopId(stops[i]).setStopSequence(i + 1)
                    .setArrival(event).setDeparture(event).build())
        }
        return GtfsRealtime.FeedEntity.newBuilder().setId(tripId)
                .setTripUpdate(update).build()
    }

    private fun assertNoRepeatedStations(stations: List<Station>) {
        val unique = stations.toSet()
        assertTrue("pattern repeats a station: " + stations,
                unique.size == stations.size)
    }

    private fun loadFiles(): Map<String, String> =
        loadFiles("/gtfs/bart_google_transit.zip")

    private fun loadFiles(resource: String): Map<String, String> {
        val result = HashMap<String, String>()
        val input = LiveGtfsRoutingTest::class.java.getResourceAsStream(resource)
            ?: throw AssertionError("GTFS fixture is missing: $resource")
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && isRequired(entry.name)) {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                        result[entry.name] = output.toString(StandardCharsets.UTF_8.name())
                    }
                }
            }
        } catch (exception: Exception) {
            throw AssertionError("could not load live GTFS fixture", exception)
        } finally {
            input.close()
        }
        return result
    }

    private fun isRequired(name: String): Boolean {
        return name == "routes.txt" || name == "trips.txt" || name == "stops.txt" ||
            name == "stop_times.txt" || name == "calendar.txt" ||
            name == "calendar_dates.txt" || name == "transfers.txt"
    }

    private fun lines(input: String?): List<String> =
        input?.split(Regex("\\r?\\n"))?.dropLastWhile { it.isEmpty() } ?: emptyList()
    private fun indexOf(values: List<String>, target: String): Int {
        for (i in values.indices) {
            if (target == values[i]) {
                return i
            }
        }
        throw AssertionError("missing column $target")
    }

}
