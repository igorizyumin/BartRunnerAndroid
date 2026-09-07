package com.dougkeen.bart.transit.gtfs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Route;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.routing.TripPlanner;

import org.junit.Test;

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
    private static final List<Line> COLOR_LINES = Arrays.asList(
            Line.RED, Line.ORANGE, Line.YELLOW, Line.BLUE, Line.GREEN);

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
                    List<Route> routes = TripPlanner.routesFor(origin,
                            destination, NETWORK);
                    assertTrue(period + " route " + originLine + " " + origin
                                    + " -> " + destinationLine + " " + destination
                                    + " routes=" + routes,
                            hasServiceableRoute(routes, period, servicePeriods));
                }
            }
        }
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
        Map<String, String> result = new HashMap<String, String>();
        try (InputStream input = LiveGtfsRoutingTest.class.getResourceAsStream(
                "/gtfs/bart_google_transit.zip")) {
            if (input == null) {
                throw new AssertionError("live GTFS fixture is missing");
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
