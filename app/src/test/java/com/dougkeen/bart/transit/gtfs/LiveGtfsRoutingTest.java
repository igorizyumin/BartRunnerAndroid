package com.dougkeen.bart.transit.gtfs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.PredictionSource;
import com.dougkeen.bart.model.RealTimeDepartures;
import com.dougkeen.bart.model.Route;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.backend.TransitFeedSnapshot;
import com.dougkeen.bart.backend.RouteDepartureProjection;
import com.dougkeen.bart.backend.TripProgressProjection;
import com.dougkeen.bart.backend.Schedule;
import com.dougkeen.bart.networktasks.GtfsRealtimeContentHandler;
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex;
import com.google.transit.realtime.GtfsRealtime;

import org.junit.Test;
import org.junit.Assume;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Regression coverage against the checked-in official BART static feed. */
public class LiveGtfsRoutingTest {
    private static final Map<String, String> FILES = loadFiles();
    private static final GtfsNetworkCatalog CATALOG =
            GtfsNetworkCatalog.fromFiles(FILES);
    private static final BartGtfsNetwork NETWORK =
            BartGtfsNetwork.fromCatalog(CATALOG);
    private static final Map<String, String> NIGHT_FILES = loadFiles(
            "/gtfs/bart_google_transit_night.zip");
    private static final BartGtfsNetwork NIGHT_NETWORK =
            BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(NIGHT_FILES));
    private static final List<Line> COLOR_LINES = Arrays.asList(
            Line.RED, Line.ORANGE, Line.YELLOW, Line.BLUE, Line.GREEN);
    private static final Map<BartGtfsNetwork, Schedule> ROUTING_SCHEDULES =
            new java.util.IdentityHashMap<>();

    @Test
    public void liveFeedSatisfiesGenericAndBartInvariants() {
        assertTrue(CATALOG.validationErrors().toString(),
                CATALOG.validationErrors().isEmpty());
        assertTrue(NETWORK.validationErrors().toString(),
                NETWORK.validationErrors().isEmpty());
        assertFalse("live feed has no transfer rules",
                NETWORK.getTransferRules().isEmpty());
        for (BartGtfsNetwork.TransferRule rule : NETWORK.getTransferRules()) {
            assertTrue(rule.getFromStation() != null);
            assertTrue(rule.getToStation() != null);
            assertTrue("invalid transfer type " + rule.getTransferType(),
                    rule.getTransferType() >= 0 && rule.getTransferType() <= 3);
        }

        for (Line line : COLOR_LINES) {
            List<BartGtfsNetwork.StationPattern> patterns =
                    NETWORK.routePatternsForLine(line);
            assertFalse("missing GTFS pattern for " + line, patterns.isEmpty());
            for (BartGtfsNetwork.StationPattern pattern : patterns) {
                assertTrue(pattern.getStations().size() >= 2);
                assertTrue(pattern.getRouteId() != null
                        && !pattern.getRouteId().isEmpty());
                assertTrue("pattern has no trips: " + pattern.getRouteId(),
                        !pattern.getTripIds().isEmpty());
                assertTrue("unexpected direction " + pattern.getDirection(),
                        "n".equals(pattern.getDirection())
                                || "s".equals(pattern.getDirection()));
                assertNoRepeatedStations(pattern.getStations());
                for (String tripId : pattern.getTripIds()) {
                    assertTrue("pattern references no trip: " + tripId,
                            CATALOG.getTripsById().containsKey(tripId));
                    assertTrue("trip is on another route: " + tripId,
                            pattern.getRouteId().equals(
                                    CATALOG.routeIdForTrip(tripId)));
                }
            }
        }
    }

    @Test
    public void everyColorLineHasDayAndNightServiceAndCrossLineRoutes() {
        Map<String, ServicePeriod> servicePeriods = servicePeriods();
        for (ServicePeriod period : ServicePeriod.values()) {
            Map<Line, Station> representatives =
                    representativeStations(period, servicePeriods);
            for (Line originLine : COLOR_LINES) {
                for (Line destinationLine : COLOR_LINES) {
                    if (originLine == destinationLine) {
                        continue;
                    }
                    Station origin = representatives.get(originLine);
                    Station destination = representatives.get(destinationLine);
                    List<Route> routes = routesFor(origin,
                            destination, NETWORK);
                    assertTrue(period + " route " + originLine + " " + origin
                                    + " -> " + destinationLine + " " + destination
                                    + " routes=" + routes,
                            hasServiceableRoute(routes, period, servicePeriods));
                }
            }
        }
    }

    @Test
    public void castroValleyToPittsburgUsesTheExpectedThreeLegRoute() {
        List<Route> routes = routesFor(Station.CAST, Station.PITT,
                NETWORK);

        assertFalse("routes=" + routes, routes.isEmpty());
        Route route = routes.get(0);
        assertEquals(Arrays.asList(Line.BLUE, Line.ORANGE, Line.YELLOW),
                route.getLines());
        assertEquals(Arrays.asList(Station.BAYF, Station._19TH),
                route.getTransferStations());
        assertTrue(route.hasTransfer());
    }

    @Test
    public void sfoToCastroValleyUsesRedToBlueAtBalboaPark() {
        List<Route> routes = routesFor(Station.SFIA, Station.CAST,
                NETWORK);

        assertFalse("routes=" + routes, routes.isEmpty());
        Route route = routes.get(0);
        assertEquals("routes=" + routes, Arrays.asList(Line.RED, Line.BLUE),
                route.getLines());
        assertEquals("routes=" + routes, Arrays.asList(Station.BALB),
                route.getTransferStations());
    }

    @Test
    public void ashbyToBalboaParkIsRouteableOnNightSchedule() {
        List<Route> routes = routesFor(Station.ASHB, Station.BALB,
                NIGHT_NETWORK);

        assertFalse("routes=" + routes + " blue="
                        + NIGHT_NETWORK.stationPatternsForLine(Line.BLUE),
                routes.isEmpty());
        assertTrue(routes.get(0).getLines().contains(Line.RED)
                || routes.get(0).getLines().contains(Line.BLUE));
    }

    @Test
    public void milpitasToCastroValleyUsesGreenToBlueAtBayFair() {
        List<Route> routes = routesFor(Station.MLPT, Station.CAST,
                NETWORK);

        assertFalse("routes=" + routes, routes.isEmpty());
        Route greenRoute = null;
        for (Route route : routes) {
            if (route.getLines().equals(Arrays.asList(Line.GREEN, Line.BLUE))) {
                greenRoute = route;
                break;
            }
        }
        assertTrue("routes=" + routeLines(routes), greenRoute != null);
        assertEquals("routes=" + routes,
                Arrays.asList(Station.BAYF), greenRoute.getTransferStations());
    }

    @Test
    public void currentGreenDepartureFromMilpitasUsesBlueAtBayFair()
            throws Exception {
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.MLPT, Station.CAST,
                routesFor(Station.MLPT, Station.CAST, NETWORK),
                false, NETWORK);
        RealTimeDepartures departures = handler.getRealTimeDepartures(
                currentTripUpdates());

        Departure greenDeparture = null;
        for (Departure departure : departures.getDepartures()) {
            if (departure.getLine() == Line.GREEN) {
                greenDeparture = departure;
                break;
            }
        }
        assertTrue("departures=" + departures.getDepartures(),
                greenDeparture != null);
        assertEquals(Arrays.asList(Line.GREEN, Line.BLUE),
                linesOf(greenDeparture.getTripLegs()));
        assertEquals(Arrays.asList(Station.BAYF),
                transferStationsOf(greenDeparture.getTripLegs()));
    }

    @Test
    public void castroValleyToPittsburgCenterRouteReachesTheTerminal() {
        List<Route> routes = routesFor(Station.CAST, Station.PCTR,
                NETWORK);

        assertFalse("routes=" + routes, routes.isEmpty());
        Route route = routes.get(0);
        assertEquals("routes=" + routes,
                Arrays.asList(Line.BLUE, Line.ORANGE, Line.YELLOW,
                        Line.YELLOW_DMU),
                route.getLines());
        assertEquals(Arrays.asList(Station.BAYF, Station._19TH, Station.PITT),
                route.getTransferStations());
        assertEquals(Station.PCTR, route.getDestination());
    }

    @Test
    public void pleasantHillToPittsburgCenterUsesTheTerminalShuttle() {
        List<Route> routes = routesFor(Station.PHIL, Station.PCTR,
                NETWORK);

        assertFalse("routes=" + routes, routes.isEmpty());
        Route route = routes.get(0);
        assertEquals(Arrays.asList(Line.YELLOW, Line.YELLOW_DMU),
                route.getLines());
        assertEquals(Arrays.asList(Station.PITT), route.getTransferStations());
        assertEquals(Station.PCTR, route.getDestination());
    }

    @Test
    public void currentFeedKeepsPittsburgCenterAsTheFinalStop() throws Exception {
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.CAST, Station.PCTR,
                routesFor(Station.CAST, Station.PCTR, NETWORK),
                false, NETWORK);
        RealTimeDepartures departures = handler.getRealTimeDepartures(
                currentTripUpdates());

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty());
        for (Departure departure : departures.getDepartures()) {
            TripLeg terminal = departure.getTripLegs()
                    .get(departure.getTripLegs().size() - 1);
            assertEquals(Station.PITT, departure.getTripLegs()
                    .get(departure.getTripLegs().size() - 2).getDestination());
            assertEquals(Station.PCTR, terminal.getDestination());
            assertEquals(Line.YELLOW_DMU, terminal.getLine());
            assertTrue("terminal=" + terminal.getTripId() + " scheduled="
                            + terminal.getScheduledDepartureTime() + " effective="
                            + terminal.getDepartureTime(),
                    terminal.getDepartureTime() > 0L);
        }
    }

    @Test
    public void currentFeedConnectsPleasantHillToPittsburgCenterShuttle()
            throws Exception {
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.PHIL, Station.PCTR,
                routesFor(Station.PHIL, Station.PCTR, NETWORK),
                false, NETWORK);
        RealTimeDepartures departures = handler.getRealTimeDepartures(
                currentTripUpdates());

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty());
        boolean hasShuttle = false;
        for (Departure departure : departures.getDepartures()) {
            if (linesOf(departure.getTripLegs()).contains(Line.YELLOW_DMU)) {
                hasShuttle = true;
                for (TripLeg leg : departure.getTripLegs()) {
                    if (leg.getLine() == Line.YELLOW_DMU) {
                        assertTrue("departure=" + departure,
                                leg.getDepartureTime() > 0);
                        assertEquals(Arrays.asList(Station.PITT, Station.PCTR),
                                stationsOf(leg.getStops()));
                    }
                }
                break;
            }
        }
        assertTrue("departures=" + departures.getDepartures(), hasShuttle);
    }

    @Test
    public void castroValleyToAntiochRouteReachesTheTerminal() {
        List<Route> routes = routesFor(Station.CAST, Station.ANTC,
                NETWORK);

        assertFalse("routes=" + routes, routes.isEmpty());
        Route route = routes.get(0);
        assertEquals(Arrays.asList(Line.BLUE, Line.ORANGE, Line.YELLOW,
                        Line.YELLOW_DMU),
                route.getLines());
        assertEquals(Arrays.asList(Station.BAYF, Station._19TH, Station.PITT),
                route.getTransferStations());
        assertEquals(Station.ANTC, route.getDestination());
    }

    @Test
    public void currentFeedKeepsAntiochAsTheFinalStop() throws Exception {
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.CAST, Station.ANTC,
                routesFor(Station.CAST, Station.ANTC, NETWORK),
                false, NETWORK);
        RealTimeDepartures departures = handler.getRealTimeDepartures(
                currentTripUpdates());

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty());
        for (Departure departure : departures.getDepartures()) {
            TripLeg terminal = departure.getTripLegs()
                    .get(departure.getTripLegs().size() - 1);
            assertEquals(Station.PITT, departure.getTripLegs()
                    .get(departure.getTripLegs().size() - 2).getDestination());
            assertEquals(Station.ANTC, terminal.getDestination());
            assertEquals(Line.YELLOW_DMU, terminal.getLine());
            assertTrue("terminal=" + terminal.getTripId() + " scheduled="
                            + terminal.getScheduledDepartureTime() + " effective="
                            + terminal.getDepartureTime(),
                    terminal.getDepartureTime() > 0L);
        }
    }

    @Test
    public void currentFeedReportsPittsburgToAntiochDepartures() throws Exception {
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.PITT, Station.ANTC,
                routesFor(Station.PITT, Station.ANTC, NETWORK),
                false, NETWORK);
        RealTimeDepartures departures = handler.getRealTimeDepartures(
                currentTripUpdates());

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty());
        for (Departure departure : departures.getDepartures()) {
            assertEquals(Line.YELLOW_DMU, departure.getLine());
            assertEquals(Station.ANTC, departure.getTripLegs().get(0)
                    .getDestination());
        }
    }

    @Test
    public void currentFeedReportsAntiochToPittsburgDepartures() throws Exception {
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.ANTC, Station.PITT,
                routesFor(Station.ANTC, Station.PITT, NETWORK),
                false, NETWORK);
        RealTimeDepartures departures = handler.getRealTimeDepartures(
                currentTripUpdates());

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty());
        for (Departure departure : departures.getDepartures()) {
            assertEquals(Line.YELLOW_DMU, departure.getLine());
            assertEquals(Station.PITT, departure.getTripLegs().get(0)
                    .getDestination());
        }
    }

    @Test
    public void unknownDmuTripIdCanSupplyPittsburgCenterDeparture() {
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.PITT, Station.PCTR,
                routesFor(Station.PITT, Station.PCTR, NETWORK),
                false, NETWORK);
        GtfsRealtime.FeedMessage feed = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(900L))
                .addEntity(trip("628", "",
                        new String[]{"PITT", "E20-1"},
                        new long[]{1000L, 1100L}))
                .build();

        RealTimeDepartures departures = handler.getRealTimeDepartures(feed);
        assertEquals(1, departures.getDepartures().size());
        assertEquals(Line.YELLOW_DMU,
                departures.getDepartures().get(0).getLine());
        assertEquals(Station.PCTR,
                departures.getDepartures().get(0).getTripLegs().get(0)
                        .getDestination());
    }

    @Test
    public void separatePittsburgPlatformAndDmuUpdatesBuildAntiochDeparture() {
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.PITT, Station.ANTC,
                routesFor(Station.PITT, Station.ANTC, NETWORK),
                false, NETWORK);
        GtfsRealtime.FeedMessage feed = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(900L))
                .addEntity(trip("pitt-platform", "",
                        new String[]{"C80-1"}, new long[]{1000L}))
                .addEntity(trip("634", "",
                        new String[]{"E20-1"}, new long[]{1706L}))
                .build();

        RealTimeDepartures departures = handler.getRealTimeDepartures(feed);
        assertEquals(1, departures.getDepartures().size());
        Departure departure = departures.getDepartures().get(0);
        assertEquals(Line.YELLOW_DMU, departure.getLine());
        assertEquals(1000_000L, departure.getTripLegs().get(0)
                .getDepartureTime());
        assertEquals(Station.ANTC, departure.getTripLegs().get(0)
                .getDestination());
    }

    @Test
    public void separateReversePlatformAndDmuUpdatesBuildPittsburgDeparture() {
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.ANTC, Station.PITT,
                routesFor(Station.ANTC, Station.PITT, NETWORK),
                false, NETWORK);
        GtfsRealtime.FeedMessage feed = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(790L))
                .addEntity(trip("pitt-platform", "",
                        new String[]{"C80-2"}, new long[]{1706L}))
                .addEntity(trip("634", "",
                        new String[]{"E30-2", "E20-2"},
                        new long[]{800L, 1000L}))
                .build();

        RealTimeDepartures departures = handler.getRealTimeDepartures(feed);
        assertEquals(1, departures.getDepartures().size());
        Departure departure = departures.getDepartures().get(0);
        assertEquals(Line.YELLOW_DMU, departure.getLine());
        assertEquals(800_000L, departure.getTripLegs().get(0)
                .getDepartureTime());
        assertEquals(Station.PITT, departure.getTripLegs().get(0)
                .getDestination());
    }

    @Test
    public void currentRedDepartureFromSfoUsesBlueAtBalboa() throws Exception {
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.SFIA, Station.CAST,
                routesFor(Station.SFIA, Station.CAST, NETWORK),
                false, NETWORK);
        RealTimeDepartures departures = handler.getRealTimeDepartures(
                currentTripUpdates());

        Departure redDeparture = null;
        for (Departure departure : departures.getDepartures()) {
            if (departure.getLine() == Line.RED) {
                redDeparture = departure;
                break;
            }
        }
        assertTrue("departures=" + departures.getDepartures(),
                redDeparture != null);
        assertEquals("routes=" + routeLines(
                        routesFor(Station.SFIA, Station.CAST, NETWORK)),
                Arrays.asList(Line.RED, Line.BLUE),
                linesOf(redDeparture.getTripLegs()));
        assertEquals(Arrays.asList(Station.BALB),
                transferStationsOf(redDeparture.getTripLegs()));
    }

    @Test
    public void realtimeItineraryIncludesThePittsburgLeg() {
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.CAST, Station.PITT,
                routesFor(Station.CAST, Station.PITT, NETWORK),
                false, NETWORK);
        GtfsRealtime.FeedMessage feed = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(900L))
                .addEntity(trip("blue", "11",
                        new String[]{"CAST", "BAYF", "DALY"},
                        new long[]{1000L, 1100L, 2000L}))
                .addEntity(trip("orange", "3",
                        new String[]{"BAYF", "19TH", "RICH"},
                        new long[]{1200L, 1300L, 1400L}))
                .addEntity(trip("yellow", "2",
                        new String[]{"19TH", "PITT", "ANTC"},
                        new long[]{1500L, 1600L, 1700L}))
                .build();

        RealTimeDepartures departures = handler.getRealTimeDepartures(feed);
        List<TripLeg> legs = departures.getDepartures().get(0).getTripLegs();

        assertEquals(3, legs.size());
        assertEquals(Station.PITT, legs.get(2).getDestination());

        List<TripLeg> refreshed = handler.updateTripLegs(feed,
                legs.subList(0, 2), 900_000L);
        assertEquals(3, refreshed.size());
        assertEquals(Station.PITT, refreshed.get(2).getDestination());
    }

    @Test
    public void currentTripUpdatesBuildCompleteCastroValleyToPittsburgItinerary()
            throws Exception {
        GtfsRealtime.FeedMessage feed = currentTripUpdates();
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.CAST, Station.PITT,
                routesFor(Station.CAST, Station.PITT, NETWORK),
                false, NETWORK);
        RealTimeDepartures departures = handler.getRealTimeDepartures(feed);
        assertTrue(feed.getEntityCount() > 0);
        assertEquals("departures=" + departures.getDepartures(),
                Arrays.asList("1973728", "1973729", "1973730", "1973731",
                        "1973732", "1973733", "1973734"),
                departures.getDepartures().stream()
                        .map(departure -> departure.getTripLegs().get(0).getTripId())
                        .collect(java.util.stream.Collectors.toList()));
        for (com.dougkeen.bart.model.Departure departure : departures.getDepartures()) {
            assertEquals(3, departure.getTripLegs().size());
            assertEquals(Station.BAYF, departure.getTripLegs().get(0).getDestination());
            assertEquals(Station._19TH, departure.getTripLegs().get(1).getDestination());
            assertEquals(Station.PITT, departure.getTripLegs().get(2).getDestination());
            for (int index = 0; index + 1 < departure.getTripLegs().size(); index++) {
                assertTrue("selected connection goes backwards in time",
                        departure.getTripLegs().get(index + 1).getDepartureTime()
                                >= departure.getTripLegs().get(index).getArrivalTime());
            }
        }
    }

    @Test
    public void progressProjectionRestoresMissingPittsburgLegFromCurrentFeed()
            throws Exception {
        GtfsRealtime.FeedMessage feed = currentTripUpdates();
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.CAST, Station.PITT,
                routesFor(Station.CAST, Station.PITT, NETWORK),
                false, NETWORK);
        Departure selected = handler.getRealTimeDepartures(feed)
                .getDepartures().get(0);
        List<TripLeg> partial = selected.getTripLegs().subList(0, 2);

        List<TripLeg> refreshed = new TripProgressProjection(
                Station.CAST, Station.PITT, partial, NETWORK)
                .project(new com.dougkeen.bart.backend.TransitFeedSnapshot(
                        feed, emptyFeed(), System.currentTimeMillis()));

        assertEquals("refreshed=" + refreshed.stream()
                        .map(leg -> leg.getLine() + ":" + leg.getOrigin()
                                + "->" + leg.getDestination() + ":" + leg.getTripId())
                        .collect(java.util.stream.Collectors.toList()),
                3, refreshed.size());
        assertEquals(Station.PITT, refreshed.get(2).getDestination());
    }

    @Test
    public void nightTripUpdatesUseStaticTerminalWhenRealtimeStopsAtBalboa()
            throws Exception {
        List<Route> routes = routesFor(Station.BALB, Station.DALY,
                NIGHT_NETWORK);
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.BALB, Station.DALY, routes, false, NIGHT_NETWORK);
        RealTimeDepartures departures = handler.getRealTimeDepartures(
                nightTripUpdates());

        Departure matching = null;
        for (Departure departure : departures.getDepartures()) {
            if ("1973764".equals(departure.getTripLegs().get(0).getTripId())) {
                matching = departure;
                break;
            }
        }
        assertTrue("departures=" + departures.getDepartures(), matching != null);
        assertEquals(Station.DALY, matching.getTrainDestination());
        assertEquals(Station.DALY, matching.getTripLegs().get(0).getDestination());
    }

    @Test
    public void nightTripUpdatesDoNotShowTripsThatAlreadyLeftAshby()
            throws Exception {
        RealTimeDepartures departures = new RouteDepartureProjection(
                new StationPair(Station.ASHB, Station.DALY), NIGHT_NETWORK)
                .project(new TransitFeedSnapshot(
                        nightTripUpdates(), emptyFeed(), 0L));

        assertFalse("expected future static Ashby departures",
                departures.getDepartures().isEmpty());
        long staleCutoff = 1788846725L * 1000L - 45L * 1000L;
        for (Departure departure : departures.getDepartures()) {
            assertTrue("stale departure=" + departure,
                    departure.getTripLegs().get(0).getDepartureTime() >= staleCutoff);
        }
    }

    @Test
    public void currentTripUpdatesProvideTwelfthStreetToSfoRouting()
            throws Exception {
        List<Route> routes = routesFor(Station._12TH, Station.SFIA,
                NETWORK);
        RealTimeDepartures departures = new RouteDepartureProjection(
                new StationPair(Station._12TH, Station.SFIA), NETWORK)
                .project(new TransitFeedSnapshot(
                        currentTripUpdates(), emptyFeed(), 0L));

        assertFalse("routes=" + routes + " departures="
                        + departures.getDepartures(),
                departures.getDepartures().isEmpty());
        boolean hasSfo = false;
        for (Departure departure : departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station._12TH, Station.SFIA);
            if (departure.getTrainDestination() == Station.SFIA) {
                hasSfo = true;
                break;
            }
        }
        assertTrue("routes=" + routes + " departures="
                        + departures.getDepartures(), hasSfo);
    }

    @Test
    public void liveSnapshotRetriesAnAlternativeLineWhenThePreferredTransferMisses()
            throws Exception {
        RealTimeDepartures departures = new RouteDepartureProjection(
                new StationPair(Station.CAST, Station.SFIA), NETWORK)
                .project(new TransitFeedSnapshot(
                        liveCastroSfoTripUpdates(), emptyFeed(), 0L));

        boolean hasBlueToYellow = false;
        for (Departure departure : departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station.CAST, Station.SFIA);
            if (linesOf(departure.getTripLegs()).equals(
                    Arrays.asList(Line.BLUE, Line.YELLOW))
                    && departure.getTripLegs().get(
                            departure.getTripLegs().size() - 1).getDestination()
                            == Station.SFIA) {
                hasBlueToYellow = true;
                break;
            }
        }
        assertTrue("expected Blue to Yellow fallback: "
                        + departures.getDepartures().stream()
                        .map(departure -> linesOf(departure.getTripLegs())
                                + ":" + departure.getTrainDestination())
                        .collect(java.util.stream.Collectors.toList()),
                hasBlueToYellow);
    }

    @Test
    public void refreshedTripUpdatesPreserveExplicitCancellations()
            throws Exception {
        GtfsRealtime.FeedMessage feed = refreshedTripUpdates();
        Schedule schedule = Schedule.fromStatic(NETWORK,
                feed.getHeader().getTimestamp() * 1000L,
                new HashSet<>(COLOR_LINES)).applyRealtime(
                GtfsRealtimeFeedIndex.from(feed));

        for (String tripId : Arrays.asList(
                "1965275", "1965700", "1965654", "1965658", "1965850")) {
            Schedule.Trip trip = trip(schedule, tripId);
            assertTrue("trip should be canceled: " + tripId, trip.getCanceled());
        }
    }

    @Test
    public void refreshedTripUpdatesShowDirectAndTransferDepartures()
            throws Exception {
        RealTimeDepartures departures = new RouteDepartureProjection(
                new StationPair(Station.DBRK, Station.POWL), NETWORK)
                .project(new TransitFeedSnapshot(
                        refreshedTripUpdates(), emptyFeed(), 0L));

        boolean hasDirectRed = false;
        boolean hasOrangeToYellow = false;
        for (Departure departure : departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station.DBRK, Station.POWL);
            if (departure.getLine() == Line.RED && !departure.hasTransfers()) {
                hasDirectRed = true;
            }
            if (departure.getLine() == Line.ORANGE
                    && linesOf(departure.getTripLegs()).equals(
                    Arrays.asList(Line.ORANGE, Line.YELLOW))
                    && transferStationsOf(departure.getTripLegs()).equals(
                    Arrays.asList(Station.MCAR))) {
                hasOrangeToYellow = true;
            }
        }
        assertTrue("missing direct red departure: " + departures.getDepartures(),
                hasDirectRed);
        assertTrue("missing Orange to Yellow departure: "
                        + departures.getDepartures(),
                hasOrangeToYellow);
    }

    @Test
    public void currentTripUpdatesShowSfoFromTwelfthStreetStationBoard()
            throws Exception {
        RealTimeDepartures departures = new RouteDepartureProjection(
                new StationPair(Station._12TH, null), NETWORK)
                .project(new TransitFeedSnapshot(
                        currentTripUpdates(), emptyFeed(), 0L));

        boolean hasSfo = false;
        for (Departure departure : departures.getDepartures()) {
            if (departure.getTrainDestination() == Station.SFIA) {
                hasSfo = true;
                break;
            }
        }
        assertTrue("departures=" + departures.getDepartures(), hasSfo);
    }

    @Test
    public void latestTripUpdatesRouteTwelfthStreetTo16thStreet()
            throws Exception {
        List<Route> routes = routesFor(Station._12TH, Station._16TH,
                NIGHT_NETWORK);
        RealTimeDepartures departures = new RouteDepartureProjection(
                new StationPair(Station._12TH, Station._16TH), NIGHT_NETWORK)
                .project(new TransitFeedSnapshot(
                        latest12th16thTripUpdates(), emptyFeed(), 0L));

        assertFalse("routes=" + routes + " departures="
                        + departures.getDepartures(),
                departures.getDepartures().isEmpty());
        for (Departure departure : departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station._12TH, Station._16TH);
            assertEquals(Station._16TH,
                    departure.getTripLegs().get(departure.getTripLegs().size() - 1)
                            .getDestination());
        }
    }

    @Test
    public void nightFixtureRoutesTwelfthStreetToMillbraeViaSfo()
            throws Exception {
        RealTimeDepartures departures = new RouteDepartureProjection(
                new StationPair(Station._12TH, Station.MLBR), NIGHT_NETWORK)
                .project(new TransitFeedSnapshot(
                        nightTripUpdates(), emptyFeed(), 0L));

        assertFalse("departures=" + departures.getDepartures(),
                departures.getDepartures().isEmpty());
        for (Departure departure : departures.getDepartures()) {
            assertFeasibleItinerary(departure, Station._12TH, Station.MLBR);
            assertEquals("departure=" + linesOf(departure.getTripLegs())
                            + ":" + departure.getTrainDestination(),
                    2, departure.getTripLegs().size());
            assertEquals(Station.SFIA,
                    departure.getTripLegs().get(0).getDestination());
            assertEquals(Line.YELLOW,
                    departure.getTripLegs().get(0).getLine());
            assertEquals(Line.YELLOW_LATE_NIGHT,
                    departure.getTripLegs().get(1).getLine());
            assertEquals(Station.MLBR,
                    departure.getTripLegs().get(departure.getTripLegs().size() - 1)
                            .getDestination());
        }
    }

    @Test
    public void fixtureRoutesCoverLineEndpointsTransfersAndAntioch() {
        assertRoute(NETWORK, Station.RICH, Station.SFIA,
                Arrays.asList(Line.RED), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.SFIA, Station.RICH,
                Arrays.asList(Line.RED), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.BERY, Station.RICH,
                Arrays.asList(Line.ORANGE), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.RICH, Station.BERY,
                Arrays.asList(Line.ORANGE), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.BERY, Station.DALY,
                Arrays.asList(Line.GREEN), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.DALY, Station.BERY,
                Arrays.asList(Line.GREEN), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.DUBL, Station.DALY,
                Arrays.asList(Line.BLUE), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.DALY, Station.DUBL,
                Arrays.asList(Line.BLUE), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.SFIA, Station.PITT,
                Arrays.asList(Line.YELLOW), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.PITT, Station.SFIA,
                Arrays.asList(Line.YELLOW), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.PITT, Station.ANTC,
                Arrays.asList(Line.YELLOW_DMU), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.ANTC, Station.PITT,
                Arrays.asList(Line.YELLOW_DMU), Collections.<Station>emptyList());
        assertRoute(NETWORK, Station.CAST, Station.PITT,
                Arrays.asList(Line.BLUE, Line.ORANGE, Line.YELLOW),
                Arrays.asList(Station.BAYF, Station._19TH));
        assertRoute(NETWORK, Station.CAST, Station.ANTC,
                Arrays.asList(Line.BLUE, Line.ORANGE, Line.YELLOW, Line.YELLOW_DMU),
                Arrays.asList(Station.BAYF, Station._19TH, Station.PITT));
        assertRoute(NIGHT_NETWORK, Station.ASHB, Station.DALY,
                Arrays.asList(Line.RED), Collections.<Station>emptyList());
    }

    /**
     * Exact oracle checks transcribed from the checked-in JSON and static GTFS
     * fixture. These intentionally assert both source and epoch milliseconds:
     * a plausible-looking route is not enough for prediction correctness.
     */
    @Test
    public void fixturePredictionsMatchStaticAndRealtimeOracleAtAntioch() throws Exception {
        Schedule correctedDay = Schedule.fromStatic(
                NETWORK, 1788801718L * 1000L, new HashSet<>(COLOR_LINES))
                .applyRealtime(GtfsRealtimeFeedIndex.from(currentTripUpdates()));
        Schedule.Trip dayTrip = trip(correctedDay, "1973133");

        assertScheduleStop(dayTrip, Station.ANTC,
                1788800820L * 1000L, 1788800820L * 1000L,
                1788800820L * 1000L, 1788800820L * 1000L,
                PredictionSource.SCHEDULE);
        assertScheduleStop(dayTrip, Station.PITT,
                1788801900L * 1000L, 1788801960L * 1000L,
                1788801975L * 1000L, 1788801999L * 1000L,
                PredictionSource.REALTIME);

        Schedule.Trip dayShuttle = trip(correctedDay, "1973207-after-dmu");
        assertScheduleStop(dayShuttle, Station.PITT,
                1788803580L * 1000L, 1788803640L * 1000L,
                1788803659L * 1000L, 1788803683L * 1000L,
                PredictionSource.REALTIME);
        assertScheduleStop(dayShuttle, Station.PCTR,
                1788804300L * 1000L, 1788804300L * 1000L,
                1788804343L * 1000L, 1788804343L * 1000L,
                PredictionSource.ESTIMATE);
        assertScheduleStop(dayShuttle, Station.ANTC,
                1788804720L * 1000L, 1788804780L * 1000L,
                1788804763L * 1000L, 1788804823L * 1000L,
                PredictionSource.ESTIMATE);

        Schedule correctedNight = Schedule.fromStatic(
                NIGHT_NETWORK, 1788846725L * 1000L,
                new HashSet<>(COLOR_LINES))
                .applyRealtime(GtfsRealtimeFeedIndex.from(nightTripUpdates()));
        Schedule.Trip nightTrip = trip(correctedNight, "1973170");
        assertScheduleStop(nightTrip, Station.PITT,
                1788847500L * 1000L, 1788847560L * 1000L,
                1788847575L * 1000L, 1788847599L * 1000L,
                PredictionSource.REALTIME);
        assertScheduleStop(nightTrip, Station.SBRN,
                1788852540L * 1000L, 1788852540L * 1000L,
                1788852584L * 1000L, 1788852602L * 1000L,
                PredictionSource.REALTIME);
    }

    @Test
    public void routeProjectionUsesExactCorrectedAntiochShuttlePrediction()
            throws Exception {
        RealTimeDepartures departures = new RouteDepartureProjection(
                new StationPair(Station.PITT, Station.ANTC), NETWORK)
                .project(new TransitFeedSnapshot(
                        currentTripUpdates(), emptyFeed(), 0L));

        TripLeg matching = null;
        for (Departure departure : departures.getDepartures()) {
            for (TripLeg leg : departure.getTripLegs()) {
                if ("1973207-after-dmu".equals(leg.getTripId())) {
                    matching = leg;
                    break;
                }
            }
        }
        assertTrue("departures=" + departures.getDepartures(), matching != null);
        assertEquals(Line.YELLOW_DMU, matching.getLine());
        assertEquals(Station.PITT, matching.getOrigin());
        assertEquals(Station.ANTC, matching.getDestination());
        assertEquals(1788803640L * 1000L, matching.getScheduledDepartureTime());
        assertEquals(1788803683L * 1000L, matching.getDepartureTime());
        assertEquals(1788804720L * 1000L, matching.getScheduledArrivalTime());
        assertEquals(1788804763L * 1000L, matching.getArrivalTime());
        assertEquals(PredictionSource.REALTIME, matching.getDepartureSource());
        assertEquals(PredictionSource.ESTIMATE, matching.getArrivalSource());
    }

    @Test
    public void fixtureProtobufsProduceValidRoutingForEveryStationPair() throws Exception {
        Assume.assumeTrue(
                "All-pairs fixture audit is manual: run with -DrunAllPairs=true",
                Boolean.getBoolean("runAllPairs"));
        assertFixtureRouting("day", NETWORK, currentTripUpdates());
        assertFixtureRouting("night", NIGHT_NETWORK, nightTripUpdates());
    }

    private static void assertRoute(BartGtfsNetwork network, Station origin,
                                    Station destination, List<Line> lines,
                                    List<Station> transfers) {
        long feedTime = network == NIGHT_NETWORK
                ? 1788846725L * 1000L : 1788801718L * 1000L;
        Schedule schedule = Schedule.fromStatic(network, feedTime,
                new HashSet<>(COLOR_LINES));
        List<Route> routes = schedule.routesFor(origin, destination);
        assertFalse(origin + " -> " + destination + " routes=" + routes,
                routes.isEmpty());
        Route route = routes.get(0);
        assertEquals(origin, route.getOrigin());
        assertEquals(destination, route.getDestination());
        assertEquals(lines, route.getLines());
        assertEquals(transfers, route.getTransferStations());
    }

    private static List<Route> routesFor(Station origin, Station destination,
                                         BartGtfsNetwork network) {
        Schedule schedule = ROUTING_SCHEDULES.get(network);
        if (schedule == null) {
            long feedTime = network == NIGHT_NETWORK
                    ? 1788846725L * 1000L : 1788801718L * 1000L;
            schedule = Schedule.fromStatic(network, feedTime,
                    new HashSet<>(COLOR_LINES));
            ROUTING_SCHEDULES.put(network, schedule);
        }
        return schedule.routesFor(origin, destination);
    }

    private static Schedule.Trip trip(Schedule schedule, String tripId) {
        for (Schedule.Trip trip : schedule.getTrips()) {
            if (trip.getKey().getTripId().equals(tripId)) {
                return trip;
            }
        }
        throw new AssertionError("missing schedule trip " + tripId);
    }

    private static void assertScheduleStop(
            Schedule.Trip trip,
            Station station,
            long scheduledArrival,
            long scheduledDeparture,
            long arrival,
            long departure,
            PredictionSource source) {
        Schedule.Stop stop = trip.getStops().stream()
                .filter(candidate -> candidate.getStation() == station)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing " + station + " in " + trip.getKey()));
        assertEquals(trip.getKey() + " " + station + " scheduled arrival",
                scheduledArrival, stop.getScheduledArrivalTime());
        assertEquals(trip.getKey() + " " + station + " scheduled departure",
                scheduledDeparture, stop.getScheduledDepartureTime());
        assertEquals(trip.getKey() + " " + station + " arrival",
                arrival, stop.getArrivalTime());
        assertEquals(trip.getKey() + " " + station + " departure",
                departure, stop.getDepartureTime());
        assertEquals(trip.getKey() + " " + station + " arrival source",
                source, stop.getArrivalSource());
        assertEquals(trip.getKey() + " " + station + " departure source",
                source, stop.getDepartureSource());
    }

    private static void assertFixtureRouting(
            String fixtureName,
            BartGtfsNetwork network,
            GtfsRealtime.FeedMessage feed
    ) {
        for (Station origin : Station.getStationList()) {
            for (Station destination : Station.getStationList()) {
                if (origin == destination) {
                    continue;
                }
                List<Route> routes = routesFor(origin, destination,
                        network);
                assertFalse(fixtureName + " has no static route " + origin
                                + " -> " + destination,
                        routes.isEmpty());
                for (Route route : routes) {
                    assertEquals(fixtureName + " route origin", origin,
                            route.getOrigin());
                    assertEquals(fixtureName + " route destination", destination,
                            route.getDestination());
                    assertFalse(fixtureName + " route has no lines", route.getLines().isEmpty());
                    Station legOrigin = origin;
                    for (int index = 0; index < route.getLines().size(); index++) {
                        Station legDestination = index < route.getTransferStations().size()
                                ? route.getTransferStations().get(index)
                                : destination;
                        List<Station> sequence = route.getStationSequence(
                                route.getLines().get(index));
                        assertTrue(fixtureName + " route misses leg origin",
                                sequence.indexOf(legOrigin) >= 0);
                        assertTrue(fixtureName + " route has reversed leg",
                                sequence.indexOf(legOrigin)
                                        < sequence.indexOf(legDestination));
                        legOrigin = legDestination;
                    }
                }
            }
        }

        List<Route> stationRoutes = routesFor(Station.ASHB, null, network);
        RealTimeDepartures departures = new GtfsRealtimeContentHandler(
                Station.ASHB, null, stationRoutes, false, network)
                .getRealTimeDepartures(feed);
        for (Departure departure : departures.getDepartures()) {
            assertEquals(fixtureName + " departure origin", Station.ASHB,
                    departure.getOrigin());
            assertFalse(fixtureName + " station-only departure has no legs",
                    departure.getTripLegs().isEmpty());
            assertTrue(fixtureName + " station-only departure has no destination",
                    departure.getTripLegs().get(0).getDestination() != null);
        }
    }

    private static GtfsRealtime.FeedMessage currentTripUpdates() throws Exception {
        return tripUpdates("/gtfsrt/bart_trip_updates.pb");
    }

    private static GtfsRealtime.FeedMessage refreshedTripUpdates()
            throws Exception {
        return tripUpdates("/gtfsrt/bart_trip_updates_current.pb");
    }

    private static GtfsRealtime.FeedMessage liveCastroSfoTripUpdates()
            throws Exception {
        return tripUpdates("/gtfsrt/bart_trip_updates_live_20260908_202339.pb");
    }

    private static GtfsRealtime.FeedMessage nightTripUpdates() throws Exception {
        return tripUpdates("/gtfsrt/bart_trip_updates_night.pb");
    }

    private static GtfsRealtime.FeedMessage latest12th16thTripUpdates()
            throws Exception {
        return tripUpdates("/gtfsrt/bart_trip_updates_12th_16th_now.pb");
    }

    private static GtfsRealtime.FeedMessage tripUpdates(String resource)
            throws Exception {
        try (InputStream input = LiveGtfsRoutingTest.class.getResourceAsStream(
                resource)) {
            assertTrue("trip-update fixture is missing: " + resource,
                    input != null);
            return GtfsRealtime.FeedMessage.parseFrom(input);
        }
    }

    private static GtfsRealtime.FeedMessage emptyFeed() {
        return GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0"))
                .build();
    }

    private static List<Line> linesOf(List<TripLeg> legs) {
        List<Line> result = new java.util.ArrayList<Line>();
        for (TripLeg leg : legs) {
            result.add(leg.getLine());
        }
        return result;
    }

    private static void assertFeasibleItinerary(
            Departure departure, Station expectedOrigin, Station expectedDestination) {
        List<TripLeg> legs = departure.getTripLegs();
        assertFalse("departure has no legs: " + describe(departure), legs.isEmpty());
        assertEquals("wrong itinerary origin: " + describe(departure),
                expectedOrigin, legs.get(0).getOrigin());
        assertEquals("wrong itinerary destination: " + describe(departure),
                expectedDestination, legs.get(legs.size() - 1).getDestination());
        for (int index = 0; index < legs.size(); index++) {
            TripLeg leg = legs.get(index);
            assertTrue("leg departs after it arrives: " + describe(departure),
                    leg.getArrivalTime() <= 0L
                            || leg.getDepartureTime() <= leg.getArrivalTime());
            if (index == 0) {
                continue;
            }
            TripLeg previous = legs.get(index - 1);
            assertEquals("legs do not meet: " + describe(departure),
                    previous.getDestination(), leg.getOrigin());
            if (previous.getArrivalTime() > 0L && leg.getDepartureTime() > 0L) {
                assertTrue("connection goes backwards in time: "
                                + describe(departure),
                        leg.getDepartureTime() >= previous.getArrivalTime());
            }
        }
    }

    private static String describe(Departure departure) {
        return linesOf(departure.getTripLegs()) + " trips="
                + departure.getTripLegs().stream()
                .map(TripLeg::getTripId)
                .collect(java.util.stream.Collectors.toList())
                + " times=" + departure.getTripLegs().stream()
                .map(leg -> leg.getDepartureTime() + "->" + leg.getArrivalTime())
                .collect(java.util.stream.Collectors.toList());
    }

    private static List<Station> transferStationsOf(List<TripLeg> legs) {
        List<Station> result = new java.util.ArrayList<Station>();
        for (int index = 0; index + 1 < legs.size(); index++) {
            result.add(legs.get(index).getDestination());
        }
        return result;
    }

    private static List<Station> stationsOf(List<com.dougkeen.bart.model.TripStop> stops) {
        List<Station> result = new java.util.ArrayList<Station>();
        for (com.dougkeen.bart.model.TripStop stop : stops) {
            result.add(stop.getStation());
        }
        return result;
    }

    private static List<List<Line>> routeLines(List<Route> routes) {
        List<List<Line>> result = new java.util.ArrayList<List<Line>>();
        for (Route route : routes) {
            result.add(route.getLines());
        }
        return result;
    }

    private static GtfsRealtime.FeedEntity trip(String tripId, String routeId,
                                                 String[] stops, long[] times) {
        GtfsRealtime.TripUpdate.Builder update = GtfsRealtime.TripUpdate
                .newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                        .setRouteId(routeId).setTripId(tripId));
        for (int i = 0; i < stops.length; i++) {
            GtfsRealtime.TripUpdate.StopTimeEvent event =
                    GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                            .setTime(times[i]).build();
            update.addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate
                    .newBuilder().setStopId(stops[i]).setStopSequence(i + 1)
                    .setArrival(event).setDeparture(event).build());
        }
        return GtfsRealtime.FeedEntity.newBuilder().setId(tripId)
                .setTripUpdate(update).build();
    }

    private static Map<Line, Station> representativeStations(
            ServicePeriod period, Map<String, ServicePeriod> servicePeriods) {
        Map<Line, Station> result = new EnumMap<Line, Station>(Line.class);
        Set<Station> usedStations = new HashSet<Station>();
        for (Line line : COLOR_LINES) {
            for (BartGtfsNetwork.StationPattern pattern
                    : NETWORK.routePatternsForLine(line)) {
                if (!hasService(pattern, period, servicePeriods)) {
                    continue;
                }
                for (Station station : pattern.getStations()) {
                    if (usedStations.add(station)) {
                        result.put(line, station);
                        break;
                    }
                }
                if (result.containsKey(line)) {
                    break;
                }
            }
            assertTrue("missing " + period + " service for " + line,
                    result.containsKey(line));
        }
        return result;
    }

    private static boolean hasService(BartGtfsNetwork.StationPattern pattern,
                                      ServicePeriod period,
                                      Map<String, ServicePeriod> servicePeriods) {
        for (String tripId : pattern.getTripIds()) {
            if (period == servicePeriods.get(tripId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasServiceableRoute(List<Route> routes,
                                               ServicePeriod period,
                                               Map<String, ServicePeriod> servicePeriods) {
        for (Route route : routes) {
            if (routeHasService(route, period, servicePeriods)) {
                return true;
            }
        }
        return false;
    }

    private static boolean routeHasService(Route route, ServicePeriod period,
                                           Map<String, ServicePeriod> servicePeriods) {
        List<Line> lines = route.getLines();
        List<Station> transfers = route.getTransferStations();
        for (int i = 0; i < lines.size(); i++) {
            Station origin = i == 0 ? route.getOrigin() : transfers.get(i - 1);
            Station destination = i == lines.size() - 1
                    ? route.getDestination() : transfers.get(i);
            List<Station> sequence = route.getStationSequence(lines.get(i));
            if (!sequenceHasService(lines.get(i), sequence, origin,
                    destination, period, servicePeriods)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sequenceHasService(Line line, List<Station> sequence,
                                              Station origin,
                                              Station destination,
                                              ServicePeriod period,
                                              Map<String, ServicePeriod> servicePeriods) {
        int originIndex = sequence.indexOf(origin);
        int destinationIndex = sequence.indexOf(destination);
        if (originIndex < 0 || destinationIndex <= originIndex) {
            return false;
        }
        for (BartGtfsNetwork.StationPattern pattern
                : NETWORK.routePatternsForLine(line)) {
            List<Station> patternStations = pattern.getStations();
            int patternOriginIndex = patternStations.indexOf(origin);
            int patternDestinationIndex = patternStations.indexOf(destination);
            if (patternOriginIndex >= 0
                    && patternDestinationIndex > patternOriginIndex
                    && hasService(pattern, period, servicePeriods)) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoRepeatedStations(List<Station> stations) {
        Set<Station> unique = new HashSet<Station>(stations);
        assertTrue("pattern repeats a station: " + stations,
                unique.size() == stations.size());
    }

    private static Map<String, ServicePeriod> servicePeriods() {
        Map<String, ServicePeriod> result = new HashMap<String, ServicePeriod>();
        Map<String, Integer> firstSequence = new HashMap<String, Integer>();
        String[] header = lines(FILES.get("stop_times.txt")).get(0)
                .split(",", -1);
        int tripIndex = indexOf(header, "trip_id");
        int timeIndex = indexOf(header, "departure_time");
        int sequenceIndex = indexOf(header, "stop_sequence");
        for (String row : lines(FILES.get("stop_times.txt")).subList(1,
                lines(FILES.get("stop_times.txt")).size())) {
            String[] values = row.split(",", -1);
            String tripId = values[tripIndex];
            int sequence = Integer.parseInt(values[sequenceIndex]);
            Integer previous = firstSequence.get(tripId);
            if (previous != null && previous <= sequence) {
                continue;
            }
            firstSequence.put(tripId, sequence);
            int hour = Integer.parseInt(values[timeIndex].split(":", -1)[0]);
            result.put(tripId, hour >= 5 && hour < 21
                    ? ServicePeriod.DAY : ServicePeriod.NIGHT);
        }
        return result;
    }

    private static Map<String, String> loadFiles() {
        return loadFiles("/gtfs/bart_google_transit.zip");
    }

    private static Map<String, String> loadFiles(String resource) {
        Map<String, String> result = new HashMap<String, String>();
        try (InputStream input = LiveGtfsRoutingTest.class.getResourceAsStream(
                resource)) {
            if (input == null) {
                throw new AssertionError("GTFS fixture is missing: " + resource);
            }
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && isRequired(entry.getName())) {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                        result.put(entry.getName(), output.toString(
                                StandardCharsets.UTF_8.name()));
                    }
                }
            }
        } catch (Exception exception) {
            throw new AssertionError("could not load live GTFS fixture", exception);
        }
        return result;
    }

    private static boolean isRequired(String name) {
        return "routes.txt".equals(name) || "trips.txt".equals(name)
                || "stops.txt".equals(name) || "stop_times.txt".equals(name)
                || "calendar.txt".equals(name) || "calendar_dates.txt".equals(name)
                || "transfers.txt".equals(name);
    }

    private static List<String> lines(String input) {
        return input == null ? Collections.<String>emptyList()
                : Arrays.asList(input.split("\\r?\\n"));
    }

    private static int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) {
            if (target.equals(values[i])) {
                return i;
            }
        }
        throw new AssertionError("missing column " + target);
    }

    private enum ServicePeriod {
        DAY,
        NIGHT
    }
}
