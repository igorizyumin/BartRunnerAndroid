package `in`.izyum.bart.transit.gtfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.RealTimeDepartures
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.backend.TransitFeedSnapshot
import `in`.izyum.bart.backend.RouteDepartureProjection
import `in`.izyum.bart.backend.TripProgressProjection
import `in`.izyum.bart.backend.Schedule
import `in`.izyum.bart.networktasks.GtfsRealtimeContentHandler
import `in`.izyum.bart.networktasks.GtfsRealtimeFeedIndex
import com.google.transit.realtime.GtfsRealtime

import org.junit.Test
import org.junit.Assume

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.EnumMap
import java.util.IdentityHashMap
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
    private val ROUTING_SCHEDULES: MutableMap<BartGtfsNetwork, Schedule> = IdentityHashMap()

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
                assertTrue(pattern.routeId != null
                        && !pattern.routeId.isEmpty())
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
    fun everyColorLineHasDayAndNightServiceAndCrossLineRoutes() {
        val servicePeriods: Map<String, ServicePeriod> = servicePeriods()
        for (period in ServicePeriod.values()) {
            val representatives: Map<Line, Station> = representativeStations(period, servicePeriods)
            for (originLine in COLOR_LINES) {
                for (destinationLine in COLOR_LINES) {
                    if (originLine == destinationLine) {
                        continue
                    }
                    val origin = representatives.getValue(originLine)
                    val destination = representatives.getValue(destinationLine)
                    val routes: List<Route> = routesFor(origin,
                            destination, NETWORK)
                    assertTrue("$period route " + originLine + " " + origin
                                    + " -> " + destinationLine + " " + destination
                                    + " routes=" + routes,
                            hasServiceableRoute(routes, period, servicePeriods))
                }
            }
        }
    }

    @Test
    fun castroValleyToPittsburgUsesTheExpectedThreeLegRoute() {
        val routes: List<Route> = routesFor(Station.CAST, Station.PITT,
                NETWORK)

        assertFalse("routes=" + routes, routes.isEmpty())
        val route = routes[0]
        assertEquals(listOf(Line.BLUE, Line.ORANGE, Line.YELLOW),
                route.lines)
        assertEquals(listOf(Station.LAKE, Station._19TH),
                route.transferStations)
        assertTrue(route.hasTransfer())
    }

    @Test
    fun sfoToCastroValleyUsesRedToBlueAtBalboaPark() {
        val routes: List<Route> = routesFor(Station.SFIA, Station.CAST,
                NETWORK)

        assertFalse("routes=" + routes, routes.isEmpty())
        val route = routes[0]
        assertEquals("routes=" + routes, listOf(Line.RED, Line.BLUE),
                route.lines)
        assertEquals("routes=" + routes, listOf(Station.BALB),
                route.transferStations)
    }

    @Test
    fun ashbyToBalboaParkIsRouteableOnNightSchedule() {
        val routes: List<Route> = routesFor(Station.ASHB, Station.BALB,
                NIGHT_NETWORK)

        assertFalse("routes=" + routes + " blue="
                        + NIGHT_NETWORK.stationPatternsForLine(Line.BLUE),
                routes.isEmpty())
        assertTrue(routes.get(0).lines.contains(Line.RED)
                || routes.get(0).lines.contains(Line.BLUE))
    }

    @Test
    fun milpitasToCastroValleyUsesGreenToBlueAtBayFair() {
        val routes: List<Route> = routesFor(Station.MLPT, Station.CAST,
                NETWORK)

        assertFalse("routes=" + routes, routes.isEmpty())
        var greenRoute: Route? = null
        for (route in routes) {
            if (route.lines.equals(listOf(Line.GREEN, Line.BLUE))) {
                greenRoute = route
                break
            }
        }
        assertTrue("routes=" + routeLines(routes), greenRoute != null)
        assertEquals("routes=" + routes,
                listOf(Station.LAKE), greenRoute!!.transferStations)
    }

    @Test
    fun currentGreenDepartureFromMilpitasUsesBlueAtBayFair(){
        val handler = GtfsRealtimeContentHandler(
                Station.MLPT, Station.CAST,
                routesFor(Station.MLPT, Station.CAST, NETWORK),
                false, NETWORK)
        val departures = handler.getRealTimeDepartures(
                currentTripUpdates())

        var greenDeparture: Departure? = null
        for (departure in departures.getDepartures()) {
            if (departure.line == Line.GREEN) {
                greenDeparture = departure
                break
            }
        }
        assertTrue("departures=" + departures.getDepartures(),
                greenDeparture != null)
        assertEquals(listOf(Line.GREEN, Line.BLUE),
                linesOf(greenDeparture!!.tripLegs))
        assertEquals(listOf(Station.BAYF),
                transferStationsOf(greenDeparture!!.tripLegs))
    }

    @Test
    fun castroValleyToPittsburgCenterRouteReachesTheTerminal() {
        val routes: List<Route> = routesFor(Station.CAST, Station.PCTR,
                NETWORK)

        assertFalse("routes=" + routes, routes.isEmpty())
        val route = routes[0]
        assertEquals("routes=" + routes,
                listOf(Line.BLUE, Line.ORANGE, Line.YELLOW),
                route.lines)
        assertEquals(listOf(Station.LAKE, Station._19TH),
                route.transferStations)
        assertEquals(Station.PCTR, route.destination)
    }

    @Test
    fun pleasantHillToPittsburgCenterUsesTheTerminalShuttle() {
        val routes: List<Route> = routesFor(Station.PHIL, Station.PCTR,
                NETWORK)

        assertFalse("routes=" + routes, routes.isEmpty())
        val route = routes[0]
        assertEquals(listOf(Line.YELLOW),
                route.lines)
        assertEquals(emptyList<Station>(), route.transferStations)
        assertEquals(Station.PCTR, route.destination)
    }

    @Test
    fun currentFeedKeepsPittsburgCenterAsTheFinalStop(){
        val handler = GtfsRealtimeContentHandler(
                Station.CAST, Station.PCTR,
                routesFor(Station.CAST, Station.PCTR, NETWORK),
                false, NETWORK)
        val departures = handler.getRealTimeDepartures(
                currentTripUpdates())

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        for (departure in departures.getDepartures()) {
            val terminal = departure.tripLegs
                    .get(departure.tripLegs.size - 1)
            assertEquals(Station.PCTR, terminal.destination)
            assertEquals(Line.YELLOW, terminal.line)
            assertEquals(3, departure.tripLegs.size)
            assertFalse(linesOf(departure.tripLegs).contains(Line.YELLOW_DMU))
            assertTrue("terminal=" + terminal.tripId + " scheduled="
                            + terminal.scheduledDepartureTime + " effective="
                            + terminal.departureTime,
                    terminal.departureTime > 0L)
        }
    }

    @Test
    fun currentFeedConnectsPleasantHillToPittsburgCenterShuttle(){
        val handler = GtfsRealtimeContentHandler(
                Station.PHIL, Station.PCTR,
                routesFor(Station.PHIL, Station.PCTR, NETWORK),
                false, NETWORK)
        val departures = handler.getRealTimeDepartures(
                currentTripUpdates())

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
    fun castroValleyToAntiochRouteReachesTheTerminal() {
        val routes: List<Route> = routesFor(Station.CAST, Station.ANTC,
                NETWORK)

        assertFalse("routes=" + routes, routes.isEmpty())
        val route = routes[0]
                assertEquals(listOf(Line.BLUE, Line.ORANGE, Line.YELLOW),
                route.lines)
        assertEquals(listOf(Station.LAKE, Station._19TH),
                route.transferStations)
        assertEquals(Station.ANTC, route.destination)
    }

    @Test
    fun currentFeedKeepsAntiochAsTheFinalStop(){
        val handler = GtfsRealtimeContentHandler(
                Station.CAST, Station.ANTC,
                routesFor(Station.CAST, Station.ANTC, NETWORK),
                false, NETWORK)
        val departures = handler.getRealTimeDepartures(
                currentTripUpdates())

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        for (departure in departures.getDepartures()) {
            val terminal = departure.tripLegs
                    .get(departure.tripLegs.size - 1)
            assertEquals(Station.ANTC, terminal.destination)
            assertEquals(Line.YELLOW, terminal.line)
            assertEquals(3, departure.tripLegs.size)
            assertFalse(linesOf(departure.tripLegs).contains(Line.YELLOW_DMU))
            assertTrue("terminal=" + terminal.tripId + " scheduled="
                            + terminal.scheduledDepartureTime + " effective="
                            + terminal.departureTime,
                    terminal.departureTime > 0L)
        }
    }

    @Test
    fun currentFeedReportsPittsburgToAntiochDepartures(){
        val handler = GtfsRealtimeContentHandler(
                Station.PITT, Station.ANTC,
                routesFor(Station.PITT, Station.ANTC, NETWORK),
                false, NETWORK)
        val departures = handler.getRealTimeDepartures(
                currentTripUpdates())

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        for (departure in departures.getDepartures()) {
            assertEquals(Line.YELLOW, departure.line)
            assertEquals(Station.ANTC, departure.tripLegs.get(0)
                    .destination)
        }
    }

    @Test
    fun currentFeedReportsAntiochToPittsburgDepartures(){
        val handler = GtfsRealtimeContentHandler(
                Station.ANTC, Station.PITT,
                routesFor(Station.ANTC, Station.PITT, NETWORK),
                false, NETWORK)
        val departures = handler.getRealTimeDepartures(
                currentTripUpdates())

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        for (departure in departures.getDepartures()) {
            assertEquals(Line.YELLOW, departure.line)
            assertEquals(Station.PITT, departure.tripLegs.get(0)
                    .destination)
        }
    }

    @Test
    fun capturedAntiochFeedBuildsPassengerDeparturesFromNormalTrips(){
        val handler = GtfsRealtimeContentHandler(
                Station.ANTC, null,
                routesFor(Station.ANTC, null, NETWORK),
                false, NETWORK)
        val departures = handler.getRealTimeDepartures(
                antiochLiveTripUpdates())

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        val departureTimes: MutableSet<Long> = HashSet()
        for (departure in departures.getDepartures()) {
            assertEquals(Station.ANTC, departure.origin)
            assertEquals(Line.YELLOW, departure.line)
            assertFalse("technical terminal feed leaked into passenger output",
                    linesOf(departure.tripLegs).contains(Line.YELLOW_DMU))
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
    fun capturedAntiochFeedUsesTerminalUpdatesWithoutExtraTrips(){
        val feed = antiochLiveTripUpdates()
        val snapshot = TransitFeedSnapshot(
                feed, emptyFeed(), feed.getHeader().getTimestamp() * 1000L)
        val projection = RouteDepartureProjection(
                StationPair(Station.ANTC, null), NETWORK)
        val base = projection.project(snapshot)
        assertEquals("captured Antioch departures=" + base.getDepartures().map {
                        it.tripLegs[0].tripId
                    },
                7, base.getDepartures().size)
        assertEquals(setOf(
                        "1965196", "1965197", "1965198", "1965199", "1965200", "1965201", "1965202"),
                base.getDepartures().map { it.tripLegs[0].tripId }.toSet())
        for (tripId in listOf("1965196", "1965197", "1965198", "1965199", "1965200", "1965201")) {
            val terminalTrip = snapshot.getCorrectedSchedule(NETWORK).trips
                .firstOrNull { it.key.tripId == tripId }
                ?: throw AssertionError()
            assertEquals(tripId,
                    PredictionSource.REALTIME,
                    terminalTrip.stopAt(Station.ANTC)!!.arrivalSource)
        }
        val finalScheduledTrip = snapshot.getCorrectedSchedule(NETWORK).trips
            .firstOrNull { it.key.tripId == "1965202" }
            ?: throw AssertionError()
        assertEquals(PredictionSource.SCHEDULE,
                finalScheduledTrip.stopAt(Station.ANTC)!!.arrivalSource)
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
    fun currentTerminalFeedDoesNotShowStaticDuplicateNearRealtimeDeparture(){
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
        assertEquals(setOf(
                        "1965118", "1965119", "1965207", "1965208",
                        "1965209", "1965210"), tripIds)
        assertFalse("static duplicate beside realtime terminal departure",
                tripIds.contains("1965206"))
        val matchedRealtime = departures.firstOrNull {
            it.tripLegs[0].tripId == "1965119"
        } ?: throw AssertionError()
        assertEquals(PredictionSource.REALTIME,
                matchedRealtime.tripLegs.get(0).departureSource)
    }

    @Test
    fun unknownDmuTripIdDoesNotCreatePassengerDeparture() {
        val handler = GtfsRealtimeContentHandler(
                Station.PITT, Station.PCTR,
                routesFor(Station.PITT, Station.PCTR, NETWORK),
                false, NETWORK)
        val feed = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(900L))
                .addEntity(trip("628", "",
                        arrayOf("PITT", "E20-1"),
                        longArrayOf(1000L, 1100L)))
                .build()

        val departures = handler.getRealTimeDepartures(feed)
        assertTrue("technical DMU updates must not create a passenger trip",
                departures.getDepartures().isEmpty())
    }

    @Test
    fun separatePittsburgPlatformAndDmuUpdatesDoNotBuildPassengerDeparture() {
        val handler = GtfsRealtimeContentHandler(
                Station.PITT, Station.ANTC,
                routesFor(Station.PITT, Station.ANTC, NETWORK),
                false, NETWORK)
        val feed = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(900L))
                .addEntity(trip("pitt-platform", "",
                        arrayOf("C80-1"), longArrayOf(1000L)))
                .addEntity(trip("634", "",
                        arrayOf("E20-1"), longArrayOf(1706L)))
                .build()

        val departures = handler.getRealTimeDepartures(feed)
        assertTrue("technical DMU updates must not create a passenger trip",
                departures.getDepartures().isEmpty())
    }

    @Test
    fun separateReversePlatformAndDmuUpdatesDoNotBuildPassengerDeparture() {
        val handler = GtfsRealtimeContentHandler(
                Station.ANTC, Station.PITT,
                routesFor(Station.ANTC, Station.PITT, NETWORK),
                false, NETWORK)
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

        val departures = handler.getRealTimeDepartures(feed)
        assertTrue("technical DMU updates must not create a passenger trip",
                departures.getDepartures().isEmpty())
    }

    @Test
    fun currentRedDepartureFromSfoUsesBlueAtBalboa(){
        val handler = GtfsRealtimeContentHandler(
                Station.SFIA, Station.CAST,
                routesFor(Station.SFIA, Station.CAST, NETWORK),
                false, NETWORK)
        val departures = handler.getRealTimeDepartures(
                currentTripUpdates())

        var redDeparture: Departure? = null
        for (departure in departures.getDepartures()) {
            if (departure.line == Line.RED) {
                redDeparture = departure
                break
            }
        }
        assertTrue("departures=" + departures.getDepartures(),
                redDeparture != null)
        assertEquals("routes=" + routeLines(
                        routesFor(Station.SFIA, Station.CAST, NETWORK)),
                listOf(Line.RED, Line.BLUE),
                linesOf(redDeparture!!.tripLegs))
        assertEquals(listOf(Station.BALB),
                transferStationsOf(redDeparture!!.tripLegs))
    }

    @Test
    fun realtimeItineraryIncludesThePittsburgLeg() {
        val handler = GtfsRealtimeContentHandler(
                Station.CAST, Station.PITT,
                routesFor(Station.CAST, Station.PITT, NETWORK),
                false, NETWORK)
        val feed = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(900L))
                .addEntity(trip("blue", "11",
                        arrayOf("CAST", "BAYF", "DALY"),
                        longArrayOf(1000L, 1100L, 2000L)))
                .addEntity(trip("orange", "3",
                        arrayOf("BAYF", "19TH", "RICH"),
                        longArrayOf(1200L, 1300L, 1400L)))
                .addEntity(trip("yellow", "2",
                        arrayOf("19TH", "PITT", "ANTC"),
                        longArrayOf(1500L, 1600L, 1700L)))
                .build()

        val departures = handler.getRealTimeDepartures(feed)
        val legs: List<TripLeg> = departures.getDepartures().get(0).tripLegs

        assertEquals(3, legs.size)
        assertEquals(Station.PITT, legs.get(2).destination)

        val refreshed: List<TripLeg> = handler.updateTripLegs(feed,
                legs.subList(0, 2), 900_000L)
        assertEquals(3, refreshed.size)
        assertEquals(Station.PITT, refreshed.get(2).destination)
    }

    @Test
    fun currentTripUpdatesBuildCompleteCastroValleyToPittsburgItinerary(){
        val feed = currentTripUpdates()
        val handler = GtfsRealtimeContentHandler(
                Station.CAST, Station.PITT,
                routesFor(Station.CAST, Station.PITT, NETWORK),
                false, NETWORK)
        val departures = handler.getRealTimeDepartures(feed)
        assertTrue(feed.getEntityCount() > 0)
        assertEquals("departures=" + departures.getDepartures(),
                listOf("1973728", "1973729", "1973730", "1973731",
                        "1973732", "1973733", "1973734"),
                departures.getDepartures().map { it.tripLegs[0].tripId })
        for (departure in departures.getDepartures()) {
            assertEquals(3, departure.tripLegs.size)
            assertEquals(Station.LAKE, departure.tripLegs.get(0).destination)
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
        val handler = GtfsRealtimeContentHandler(
                Station.CAST, Station.PITT,
                routesFor(Station.CAST, Station.PITT, NETWORK),
                false, NETWORK)
        val selected = handler.getRealTimeDepartures(feed).getDepartures()[0]
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
        val routes: List<Route> = routesFor(Station.BALB, Station.DALY,
                NIGHT_NETWORK)
        val handler = GtfsRealtimeContentHandler(
                Station.BALB, Station.DALY, routes, false, NIGHT_NETWORK)
        val departures = handler.getRealTimeDepartures(
                nightTripUpdates())

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
        val routes: List<Route> = routesFor(Station._12TH, Station.SFIA,
                NETWORK)
        val departures = RouteDepartureProjection(
                StationPair(Station._12TH, Station.SFIA), NETWORK)
                .project(TransitFeedSnapshot(
                        currentTripUpdates(), emptyFeed(), 0L))

        assertFalse("routes=" + routes + " departures="
                        + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        var hasSfo = false
        for (departure in departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station._12TH, Station.SFIA)
            if (departure.trainDestination == Station.SFIA) {
                hasSfo = true
                break
            }
        }
        assertTrue("routes=" + routes + " departures="
                        + departures.getDepartures(), hasSfo)
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
                GtfsRealtimeFeedIndex.from(feed))

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
        val routes: List<Route> = routesFor(Station._12TH, Station._16TH,
                NIGHT_NETWORK)
        val departures = RouteDepartureProjection(
                StationPair(Station._12TH, Station._16TH), NIGHT_NETWORK)
                .project(TransitFeedSnapshot(
                        latest12th16thTripUpdates(), emptyFeed(), 0L))

        assertFalse("routes=" + routes + " departures="
                        + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        for (departure in departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station._12TH, Station._16TH)
            assertEquals(Station._16TH,
                    departure.tripLegs.get(departure.tripLegs.size - 1)
                            .destination)
        }
    }

    @Test
    fun nightFixtureRoutesTwelfthStreetToMillbraeViaSfo(){
        val departures = RouteDepartureProjection(
                StationPair(Station._12TH, Station.MLBR), NIGHT_NETWORK)
                .project(TransitFeedSnapshot(
                        nightTripUpdates(), emptyFeed(), 0L))

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty())
        for (departure in departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station._12TH, Station.MLBR)
            assertEquals("departure=" + linesOf(departure.tripLegs)
                            + ":" + departure.trainDestination,
                    2, departure.tripLegs.size)
            assertEquals(Station.SFIA,
                    departure.tripLegs.get(0).destination)
            assertEquals(Line.YELLOW,
                    departure.tripLegs.get(0).line)
            assertEquals(Line.YELLOW_LATE_NIGHT,
                    departure.tripLegs.get(1).line)
            assertEquals(Station.MLBR,
                    departure.tripLegs.get(departure.tripLegs.size - 1)
                            .destination)
        }
    }

    @Test
    fun fixtureRoutesCoverLineEndpointsTransfersAndAntioch() {
        assertRoute(NETWORK, Station.RICH, Station.SFIA,
                listOf(Line.RED), emptyList())
        assertRoute(NETWORK, Station.SFIA, Station.RICH,
                listOf(Line.RED), emptyList())
        assertRoute(NETWORK, Station.BERY, Station.RICH,
                listOf(Line.ORANGE), emptyList())
        assertRoute(NETWORK, Station.RICH, Station.BERY,
                listOf(Line.ORANGE), emptyList())
        assertRoute(NETWORK, Station.BERY, Station.DALY,
                listOf(Line.GREEN), emptyList())
        assertRoute(NETWORK, Station.DALY, Station.BERY,
                listOf(Line.GREEN), emptyList())
        assertRoute(NETWORK, Station.DUBL, Station.DALY,
                listOf(Line.BLUE), emptyList())
        assertRoute(NETWORK, Station.DALY, Station.DUBL,
                listOf(Line.BLUE), emptyList())
        assertRoute(NETWORK, Station.SFIA, Station.PITT,
                listOf(Line.YELLOW), emptyList())
        assertRoute(NETWORK, Station.PITT, Station.SFIA,
                listOf(Line.YELLOW), emptyList())
        assertRoute(NETWORK, Station.PITT, Station.ANTC,
                listOf(Line.YELLOW), emptyList())
        assertRoute(NETWORK, Station.ANTC, Station.PITT,
                listOf(Line.YELLOW), emptyList())
        assertRoute(NETWORK, Station.CAST, Station.PITT,
                listOf(Line.BLUE, Line.ORANGE, Line.YELLOW),
                listOf(Station.LAKE, Station._19TH))
        assertRoute(NETWORK, Station.CAST, Station.ANTC,
                listOf(Line.BLUE, Line.ORANGE, Line.YELLOW),
                listOf(Station.LAKE, Station._19TH))
        assertRoute(NIGHT_NETWORK, Station.ASHB, Station.DALY,
                listOf(Line.RED), emptyList())
    }

    @Test
    fun antiochEastBayTransfersUseMacArthurOnTheLiveStaticSchedule() {
        for (destination in listOf(Station.BERY, Station.DUBL)) {
            val routes = routesFor(Station.ANTC, destination, NETWORK)
            assertFalse("ANTC -> $destination routes=$routes", routes.isEmpty())
            val expectedLines = if (destination == Station.DUBL) {
                listOf(Line.YELLOW, Line.ORANGE, Line.BLUE)
            } else {
                listOf(Line.YELLOW, Line.ORANGE)
            }
            val expectedTransfers = if (destination == Station.DUBL) {
                listOf(Station.MCAR, Station.LAKE)
            } else {
                listOf(Station.MCAR)
            }
            assertEquals("ANTC -> $destination routes=" + routes.map {
                it.lines to it.transferStations
            }, expectedLines,
                    routes[0].lines)
            assertEquals("ANTC -> $destination routes=" + routes.map {
                it.lines to it.transferStations
            }, expectedTransfers,
                    routes[0].transferStations)
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
                .applyRealtime(GtfsRealtimeFeedIndex.from(currentTripUpdates()))
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
        assertScheduleStop(dayLogicalTrip, Station.PCTR,
                1788804300L * 1000L, 1788804300L * 1000L,
                1788804359L * 1000L, 1788804389L * 1000L,
                PredictionSource.REALTIME)
        assertScheduleStop(dayLogicalTrip, Station.ANTC,
                1788804720L * 1000L, 1788804780L * 1000L,
                1788804809L * 1000L, 1788804869L * 1000L,
                PredictionSource.ESTIMATE)

        val correctedNight = Schedule.fromStatic(
                NIGHT_NETWORK, 1788846725L * 1000L,
                COLOR_LINES.toSet())
                .applyRealtime(GtfsRealtimeFeedIndex.from(nightTripUpdates()))
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
        assertEquals(1788804720L * 1000L, found.scheduledArrivalTime)
        assertEquals(1788804809L * 1000L, found.arrivalTime)
        assertEquals(PredictionSource.REALTIME, found.departureSource)
        assertEquals(PredictionSource.ESTIMATE, found.arrivalSource)
    }

    @Test
    fun fixtureProtobufsProduceValidRoutingForEveryStationPair(){
        Assume.assumeTrue(
                "All-pairs fixture audit is manual: run with -DrunAllPairs=true",
                java.lang.Boolean.getBoolean("runAllPairs"))
        assertFixtureRouting("day", NETWORK, currentTripUpdates())
        assertFixtureRouting("night", NIGHT_NETWORK, nightTripUpdates())
    }

    private fun assertRoute(
        network: BartGtfsNetwork,
        origin: Station,
        destination: Station,
        lines: List<Line>,
        transfers: List<Station>,
    ) {
        val feedTime = if (network == NIGHT_NETWORK) {
            1788846725L * 1000L
        } else {
            1788801718L * 1000L
        }
        val schedule = Schedule.fromStatic(network, feedTime,
                COLOR_LINES.toSet())
        val routes = schedule.routesFor(origin, destination)
        assertFalse("$origin -> $destination routes=$routes",
                routes.isEmpty())
        val route = routes[0]
        assertEquals(origin, route.origin)
        assertEquals(destination, route.destination)
        assertEquals(lines, route.lines)
        assertEquals(transfers, route.transferStations)
    }

    private fun routesFor(
        origin: Station,
        destination: Station?,
        network: BartGtfsNetwork,
    ): List<Route> {
        var schedule = ROUTING_SCHEDULES[network]
        if (schedule == null) {
            val feedTime = if (network == NIGHT_NETWORK) {
                1788846725L * 1000L
            } else {
                1788801718L * 1000L
            }
            schedule = Schedule.fromStatic(network, feedTime,
                    COLOR_LINES.toSet())
            ROUTING_SCHEDULES[network] = schedule
        }
        return schedule.routesFor(origin, destination)
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

    private fun assertFixtureRouting(
        fixtureName: String,
        network: BartGtfsNetwork,
        feed: GtfsRealtime.FeedMessage,
    ) {
        for (origin in Station.getStationList()) {
            for (destination in Station.getStationList()) {
                if (origin == destination) {
                    continue
                }
                val routes: List<Route> = routesFor(origin, destination,
                        network)
                assertFalse(fixtureName + " has no static route " + origin
                                + " -> " + destination,
                        routes.isEmpty())
                for (route in routes) {
                    assertEquals(fixtureName + " route origin", origin,
                            route.origin)
                    assertEquals(fixtureName + " route destination", destination,
                            route.destination)
                    assertFalse(fixtureName + " route has no lines", route.lines.isEmpty())
                    var legOrigin = origin
                    for (index in 0 until route.lines.size) {
                        val legDestination = if (index < route.transferStations.size) {
                            route.transferStations[index]
                        } else {
                            destination
                        }
                        val sequence: List<Station> = route.getStationSequence(
                                route.lines.get(index))
                        assertTrue(fixtureName + " route misses leg origin",
                                sequence.indexOf(legOrigin) >= 0)
                        assertTrue(fixtureName + " route has reversed leg",
                                sequence.indexOf(legOrigin)
                                        < sequence.indexOf(legDestination))
                        legOrigin = legDestination
                    }
                }
            }
        }

        val stationRoutes: List<Route> = routesFor(Station.ASHB, null, network)
        val departures = GtfsRealtimeContentHandler(
                Station.ASHB, null, stationRoutes, false, network)
                .getRealTimeDepartures(feed)
        for (departure in departures.getDepartures()) {
            assertEquals(fixtureName + " departure origin", Station.ASHB,
                    departure.origin)
            assertFalse(fixtureName + " station-only departure has no legs",
                    departure.tripLegs.isEmpty())
            assertTrue(fixtureName + " station-only departure has no destination",
                    departure.tripLegs.get(0).destination != null)
        }
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

    private fun routeLines(routes: List<Route>): List<List<Line>> = routes.map { it.lines }

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

    private fun representativeStations(
        period: ServicePeriod,
        servicePeriods: Map<String, ServicePeriod>,
    ): Map<Line, Station> {
        val result: MutableMap<Line, Station> = EnumMap<Line, Station>(Line::class.java)
        val usedStations: MutableSet<Station> = HashSet()
        for (line in COLOR_LINES) {
            for (pattern in NETWORK.routePatternsForLine(line)) {
                if (!hasService(pattern, period, servicePeriods)) {
                    continue
                }
                for (station in pattern.stations) {
                    if (usedStations.add(station)) {
                        result.put(line, station)
                        break
                    }
                }
                if (result.containsKey(line)) {
                    break
                }
            }
            assertTrue("missing " + period + " service for " + line,
                    result.containsKey(line))
        }
        return result
    }

    private fun hasService(
        pattern: BartGtfsNetwork.StationPattern,
        period: ServicePeriod,
        servicePeriods: Map<String, ServicePeriod>,
    ): Boolean {
        for (tripId in pattern.tripIds) {
            if (period == servicePeriods.get(tripId)) {
                return true
            }
        }
        return false
    }

    private fun hasServiceableRoute(
        routes: List<Route>,
        period: ServicePeriod,
        servicePeriods: Map<String, ServicePeriod>,
    ): Boolean {
        for (route in routes) {
            if (routeHasService(route, period, servicePeriods)) {
                return true
            }
        }
        return false
    }

    private fun routeHasService(
        route: Route,
        period: ServicePeriod,
        servicePeriods: Map<String, ServicePeriod>,
    ): Boolean {
        val lines = route.lines
        val transfers = route.transferStations
        for (i in lines.indices) {
            val origin = if (i == 0) route.origin else transfers[i - 1]
            val destination = if (i == lines.lastIndex) route.destination else transfers[i]
            val sequence = route.getStationSequence(lines[i])
            if (!sequenceHasService(lines[i], sequence, origin,
                    destination, period, servicePeriods)) {
                return false
            }
        }
        return true
    }

    private fun sequenceHasService(
        line: Line,
        sequence: List<Station>,
        origin: Station?,
        destination: Station?,
        period: ServicePeriod,
        servicePeriods: Map<String, ServicePeriod>,
    ): Boolean {
        val originIndex = sequence.indexOf(origin)
        val destinationIndex = sequence.indexOf(destination)
        if (originIndex < 0 || destinationIndex <= originIndex) {
            return false
        }
        for (pattern in NETWORK.routePatternsForLine(line)) {
            val patternStations = pattern.stations
            val patternOriginIndex = patternStations.indexOf(origin)
            val patternDestinationIndex = patternStations.indexOf(destination)
            if (patternOriginIndex >= 0
                    && patternDestinationIndex > patternOriginIndex
                    && hasService(pattern, period, servicePeriods)) {
                return true
            }
        }
        return false
    }

    private fun assertNoRepeatedStations(stations: List<Station>) {
        val unique = stations.toSet()
        assertTrue("pattern repeats a station: " + stations,
                unique.size == stations.size)
    }

    private fun servicePeriods(): Map<String, ServicePeriod> {
        val result: MutableMap<String, ServicePeriod> = HashMap()
        val firstSequence: MutableMap<String, Int> = HashMap()
        val rows = lines(FILES["stop_times.txt"]!!)
        val header = rows[0].split(",")
        val tripIndex = indexOf(header, "trip_id")
        val timeIndex = indexOf(header, "departure_time")
        val sequenceIndex = indexOf(header, "stop_sequence")
        for (row in rows.drop(1)) {
            val values = row.split(",")
            val tripId = values[tripIndex]
            val sequence = values[sequenceIndex].toInt()
            val previous = firstSequence[tripId]
            if (previous != null && previous <= sequence) {
                continue
            }
            firstSequence[tripId] = sequence
            val hour = values[timeIndex].split(":")[0].toInt()
            result[tripId] = if (hour >= 5 && hour < 21) {
                ServicePeriod.DAY
            } else {
                ServicePeriod.NIGHT
            }
        }
        return result
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

    private enum class ServicePeriod {
        DAY,
        NIGHT
    }
}
