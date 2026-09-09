package in.izyum.bart.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import in.izyum.bart.model.Line;
import in.izyum.bart.model.Route;
import in.izyum.bart.model.Station;
import in.izyum.bart.backend.Schedule;
import in.izyum.bart.transit.gtfs.BartGtfsNetwork;
import in.izyum.bart.transit.gtfs.GtfsNetworkCatalog;

import org.junit.Test;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

public class ScheduleRoutingTest {
    private static final BartGtfsNetwork TEST_NETWORK = testNetwork();

    @Test
    public void directRoutesIncludeAllUsableLinesAndPreserveEndpoints() {
        List<Route> routes = routesFor(Station.MONT,
                Station.RICH, TEST_NETWORK);

        assertTrue("routes=" + routes, containsDirectLine(routes, Line.RED));
        for (Route route : routes) {
            if (route.hasTransfer()) continue;
            assertEquals(Station.MONT, route.getOrigin());
            assertEquals(Station.RICH, route.getDestination());
            assertNotNull(route.getDirectLine());
            assertTrue(route.getStationSequence(route.getDirectLine())
                    .contains(Station.MONT));
            assertTrue(route.getStationSequence(route.getDirectLine())
                    .contains(Station.RICH));
        }
    }

    @Test
    public void directRoutesHaveValidDirectionForEveryStationPair() {
        for (Station origin : Station.values()) {
            for (Station destination : Station.values()) {
                if (origin == destination) {
                    continue;
                }
                for (Route route : routesFor(origin, destination,
                        TEST_NETWORK)) {
                    if (route.hasTransfer()) {
                        continue;
                    }
                    Line line = route.getDirectLine();
                    List<Station> sequence = route.getStationSequence(line);
                    int originIndex = sequence.indexOf(origin);
                    int destinationIndex = sequence.indexOf(destination);
                    assertTrue("direct route must contain origin", originIndex >= 0);
                    assertTrue("direct route must contain destination",
                            destinationIndex >= 0);
                    assertTrue("direction=" + route.getDirection(),
                            "n".equals(route.getDirection())
                                    || "s".equals(route.getDirection()));
                }
            }
        }
    }

    @Test
    public void transferRoutesAreValidPathsAndHaveNoDuplicates() {
        for (Station origin : Station.values()) {
            for (Station destination : Station.values()) {
                if (origin == destination) {
                    continue;
                }
                List<Route> routes = transferRoutes(origin,
                        destination, TEST_NETWORK);
                Set<String> signatures = new HashSet<String>();
                for (Route route : routes) {
                    assertTrue(route.hasTransfer());
                    assertEquals(origin, route.getOrigin());
                    assertEquals(destination, route.getDestination());
                    assertEquals(route.getTransferStations().size() + 1,
                            route.getLines().size());
                    assertEquals(route.getLines().get(0), route.getDirectLine());
                    assertFalse("transfer repeats origin: " + route,
                            route.getTransferStations().contains(origin));
                    assertFalse("transfer repeats destination: " + route
                                    + " lines=" + route.getLines()
                                    + " transfers=" + route.getTransferStations(),
                            route.getTransferStations().contains(destination));
                    assertTrue("direction=" + route.getDirection(),
                            "n".equals(route.getDirection())
                                    || "s".equals(route.getDirection()));

                    for (int i = 0; i < route.getLines().size(); i++) {
                        Station segmentOrigin = i == 0 ? origin
                                : route.getTransferStations().get(i - 1);
                        Station segmentDestination = i
                                == route.getLines().size() - 1
                                ? destination
                                : route.getTransferStations().get(i);
                        Line line = route.getLines().get(i);
                        List<Station> sequence = route.getStationSequence(line);
                        assertTrue(sequence.contains(segmentOrigin));
                        assertTrue(sequence.contains(segmentDestination));
                        assertTrue(sequence.indexOf(segmentOrigin)
                                != sequence.indexOf(segmentDestination));
                        if (i < route.getLines().size() - 1) {
                            assertFalse("destination is passed before final leg: "
                                            + route,
                                    sequence.contains(destination));
                        }
                        if (i > 0) {
                            assertFalse("origin is revisited after departure: "
                                            + route,
                                    sequence.contains(origin));
                        }
                    }

                    String signature = route.getLines().toString() + ":"
                            + route.getTransferStations();
                    assertTrue("duplicate route: " + signature,
                            signatures.add(signature));
                }
            }
        }
    }

    @Test
    public void preferredEastBayRouteUsesBayFairAndNineteenthStreet() {
        List<Route> routes = preferredTransferRoutes(Station.DUBL,
                Station.ANTC, TEST_NETWORK);

        assertFalse(routes.isEmpty());
        Route route = routes.get(0);
        assertEquals(asLines(Line.BLUE, Line.ORANGE, Line.YELLOW,
                        Line.YELLOW_DMU),
                route.getLines());
        assertEquals(asStations(Station.BAYF, Station._19TH, Station.PITT),
                route.getTransferStations());
    }

    @Test
    public void routesForUsesDirectRoutesBeforeTransferFallback() {
        BartGtfsNetwork network = TEST_NETWORK;
        List<Route> direct = routesFor(Station.MONT, Station.RICH,
                network);
        assertTrue(containsDirectLine(direct, Line.RED));

        List<Route> transfer = routesFor(Station.DUBL, Station.ANTC,
                network);
        assertFalse(transfer.isEmpty());
        assertTrue(transfer.get(0).hasTransfer());
    }

    @Test
    public void routesForIncludesTransferAlternativeAlongsideDirectRoute() {
        List<Route> routes = routesFor(Station.DBRK, Station.POWL, TEST_NETWORK);

        assertTrue("routes=" + routes,
                containsRoute(routes, Arrays.asList(Line.RED)));
        Route orangeYellow = routeWithLines(routes,
                Arrays.asList(Line.ORANGE, Line.YELLOW));
        assertNotNull("routes=" + routes, orangeYellow);
        assertEquals(Arrays.asList(Station.MCAR),
                orangeYellow.getTransferStations());
    }

    @Test
    public void stationOnlyAndInvalidQueriesReturnEmptyOrBoardingRoutes() {
        BartGtfsNetwork network = TEST_NETWORK;
        List<Route> stationOnly = routesFor(Station.MONT, null,
                network);
        assertFalse(stationOnly.isEmpty());
        for (Route route : stationOnly) {
            assertEquals(Station.MONT, route.getOrigin());
            assertNull(route.getDestination());
            assertFalse(route.hasTransfer());
            assertTrue(route.getStationSequence(route.getDirectLine())
                    .contains(Station.MONT));
        }

        assertTrue(routesFor(null, Station.MONT, network).isEmpty());
        assertTrue(transferRoutes(Station.MONT, null, network)
                .isEmpty());
        assertTrue(routesFor(Station.MONT, Station.MONT, network)
                .isEmpty());
        assertTrue(transferRoutes(Station.SFIA, Station.MLBR, network)
                .isEmpty());
    }

    @Test
    public void routeCopiesAndProtectsTopologyCollections() {
        List<Station> directSequence = new ArrayList<>(Arrays.asList(
                Station.MONT, Station.EMBR, Station.RICH));
        Route direct = Route.direct(Station.MONT, Station.RICH, Line.RED, "n",
                directSequence);
        directSequence.clear();

        assertEquals(3, direct.getStationSequence(Line.RED).size());
        assertThrows(UnsupportedOperationException.class,
                () -> direct.getLines().add(Line.BLUE));
        assertThrows(UnsupportedOperationException.class,
                () -> direct.getStationSequence(Line.RED).add(Station.DUBL));

        List<Line> lines = new ArrayList<>(Arrays.asList(Line.BLUE, Line.ORANGE));
        List<Station> transfers = new ArrayList<>(
                Collections.singletonList(Station.BAYF));
        Map<Line, List<Station>> sequences = new HashMap<>();
        sequences.put(Line.BLUE, new ArrayList<>(Arrays.asList(
                Station.MONT, Station.BAYF, Station.DUBL)));
        sequences.put(Line.ORANGE, new ArrayList<>(Arrays.asList(
                Station.BAYF, Station.RICH)));
        Route transfer = Route.transfer(Station.MONT, Station.RICH, lines,
                transfers, "n", sequences);
        lines.clear();
        transfers.clear();
        sequences.get(Line.BLUE).clear();

        assertEquals(Arrays.asList(Line.BLUE, Line.ORANGE), transfer.getLines());
        assertEquals(Collections.singletonList(Station.BAYF),
                transfer.getTransferStations());
        assertThrows(UnsupportedOperationException.class,
                () -> transfer.getTransferLines().add(Line.GREEN));
        assertThrows(UnsupportedOperationException.class,
                () -> transfer.getTransferStations().add(Station.MCAR));
        assertEquals(3, transfer.getStationSequence(Line.BLUE).size());
    }

    @Test
    public void plannerResultsAreImmutable() {
        List<Route> directRoutes = routesFor(Station.MONT,
                Station.RICH, TEST_NETWORK);
        assertThrows(UnsupportedOperationException.class,
                () -> directRoutes.clear());

        List<Route> transferRoutes = transferRoutes(Station.DUBL,
                Station.ANTC, TEST_NETWORK);
        assertThrows(UnsupportedOperationException.class,
                () -> transferRoutes.add(transferRoutes.get(0)));
    }

    private static boolean containsDirectLine(List<Route> routes, Line line) {
        for (Route route : routes) {
            if (!route.hasTransfer() && route.getDirectLine() == line) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRoute(List<Route> routes, List<Line> lines) {
        return routeWithLines(routes, lines) != null;
    }

    private static Route routeWithLines(List<Route> routes, List<Line> lines) {
        for (Route route : routes) {
            if (route.getLines().equals(lines)) return route;
        }
        return null;
    }

    private static List<Route> routesFor(Station origin, Station destination,
                                         BartGtfsNetwork network) {
        return Schedule.fromStatic(network, 0L,
                new java.util.HashSet<>(java.util.Arrays.asList(Line.values())))
                .routesFor(origin, destination);
    }

    private static List<Route> transferRoutes(Station origin, Station destination,
                                              BartGtfsNetwork network) {
        return Schedule.fromStatic(network, 0L,
                new java.util.HashSet<>(java.util.Arrays.asList(Line.values())))
                .transferRoutes(origin, destination);
    }

    private static List<Route> preferredTransferRoutes(Station origin,
                                                       Station destination,
                                                       BartGtfsNetwork network) {
        return Schedule.fromStatic(network, 0L,
                new java.util.HashSet<>(java.util.Arrays.asList(Line.values())))
                .preferredTransferRoutes(origin, destination);
    }

    private static List<Line> asLines(Line... lines) {
        return java.util.Arrays.asList(lines);
    }

    private static List<Station> asStations(Station... stations) {
        return java.util.Arrays.asList(stations);
    }

    private static BartGtfsNetwork testNetwork() {
        Map<String, String> files = new HashMap<>();
        StringBuilder stops = new StringBuilder("stop_id,stop_name,zone_id\n");
        for (Station station : Station.getStationList()) {
            stops.append(station.abbreviation).append(",")
                    .append(station.toString()).append(",")
                    .append(station.abbreviation).append("\n");
        }
        files.put("stops.txt", stops.toString());

        StringBuilder routes = new StringBuilder(
                "route_id,route_short_name,route_long_name\n");
        StringBuilder trips = new StringBuilder("route_id,service_id,trip_id\n");
        StringBuilder stopTimes = new StringBuilder(
                "trip_id,stop_id,stop_sequence\n");
        Map<Line, String> routeIdsByLine = new HashMap<Line, String>();
        int routeId = 1;
        for (Line line : new Line[]{Line.RED, Line.ORANGE, Line.YELLOW,
                Line.BLUE, Line.GREEN}) {
            for (String direction : new String[]{"N", "S"}) {
                String id = Integer.toString(routeId++);
                if ("N".equals(direction)) {
                    routeIdsByLine.put(line, id);
                }
                routes.append(id).append(",").append(lineName(line)).append("-")
                        .append(direction).append(",").append(line.name())
                        .append("\n");
                String tripId = line.name().toLowerCase() + "-test-"
                        + direction.toLowerCase();
                trips.append(id).append(",weekday,").append(tripId)
                        .append("\n");
                List<Station> pattern = testPattern(line);
                if ("S".equals(direction)) {
                    java.util.Collections.reverse(pattern);
                }
                int sequence = 1;
                for (Station station : pattern) {
                    stopTimes.append(tripId).append(",")
                            .append(station.abbreviation).append(",")
                            .append(sequence++).append("\n");
                }
            }
        }
        StringBuilder transfers = new StringBuilder(
                "from_stop_id,to_stop_id,transfer_type,min_transfer_time,"
                        + "from_route_id,to_route_id\n");
        Line[] lines = new Line[]{Line.RED, Line.ORANGE, Line.YELLOW,
                Line.BLUE, Line.GREEN};
        for (int first = 0; first < lines.length; first++) {
            for (int second = 0; second < lines.length; second++) {
                if (first == second) {
                    continue;
                }
                for (Station station : testPattern(lines[first])) {
                    if (testPattern(lines[second]).contains(station)) {
                        transfers.append(station.abbreviation).append(",")
                                .append(station.abbreviation).append(",2,0,")
                                .append(routeIdsByLine.get(lines[first])).append(",")
                                .append(routeIdsByLine.get(lines[second])).append("\n");
                    }
                }
            }
        }
        files.put("routes.txt", routes.toString());
        files.put("trips.txt", trips.toString());
        files.put("stop_times.txt", stopTimes.toString());
        files.put("transfers.txt", transfers.toString());
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files));
    }

    private static List<Station> testPattern(Line line) {
        String abbreviations;
        switch (line) {
            case RED:
                abbreviations = "sfia,mlbr,sbrn,ssan,colm,daly,balb,glen,24th,16th,civc,powl,mont,embr,woak,12th,19th,mcar,ashb,dbrk,nbrk,plza,deln,rich";
                break;
            case ORANGE:
                abbreviations = "bery,mlpt,warm,frmt,ucty,shay,hayw,bayf,sanl,cols,ftvl,lake,12th,19th,mcar,ashb,dbrk,nbrk,plza,deln,rich";
                break;
            case YELLOW:
                abbreviations = "sfia,sbrn,ssan,colm,daly,balb,glen,24th,16th,civc,powl,mont,embr,woak,12th,19th,mcar,rock,orin,lafy,wcrk,phil,conc,ncon,pitt,pctr,antc";
                break;
            case BLUE:
                abbreviations = "daly,balb,glen,24th,16th,civc,powl,mont,embr,woak,lake,ftvl,cols,sanl,bayf,cast,wdub,dubl";
                break;
            case GREEN:
                abbreviations = "daly,balb,glen,24th,16th,civc,powl,mont,embr,woak,lake,ftvl,cols,sanl,bayf,hayw,shay,ucty,frmt,warm,mlpt,bery";
                break;
            default:
                throw new AssertionError(line);
        }
        List<Station> result = new java.util.ArrayList<>();
        for (String abbreviation : abbreviations.split(",")) {
            result.add(Station.getByAbbreviation(abbreviation));
        }
        return result;
    }

    private static String lineName(Line line) {
        switch (line) {
            case RED:
                return "Red";
            case ORANGE:
                return "Orange";
            case YELLOW:
                return "Yellow";
            case BLUE:
                return "Blue";
            case GREEN:
                return "Green";
            default:
                throw new AssertionError(line);
        }
    }
}
