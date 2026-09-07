package com.dougkeen.bart.transit.gtfs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class GtfsNetworkCatalogTest {
    @Test
    public void parsesStopsRoutesTripsAndOrderedPatterns() {
        GtfsNetworkCatalog catalog = GtfsNetworkCatalog.fromFiles(files());

        assertEquals("Oakland, Main", catalog.getStopsById().get("A").getName());
        assertEquals("A", catalog.getStopsById().get("A1")
                .getParentStationId());
        assertEquals("Red", catalog.getRoutesById().get("R").getLongName());
        assertEquals("R", catalog.routeIdForTrip("trip-1"));
        assertEquals("B", catalog.getStopIdsByTripId().get("trip-1").get(1));
        assertTrue(catalog.validationErrors().isEmpty());

        List<GtfsRoutePattern> patterns = catalog.patternsForRoute("R");
        assertEquals(1, patterns.size());
        assertEquals("0", patterns.get(0).getDirectionId());
        assertEquals(asList("A", "B", "C"), patterns.get(0).getStopIds());
        assertEquals(2, patterns.get(0).getTripIds().size());
        assertTrue(patterns.get(0).getHeadsigns().contains("Downtown"));
        assertTrue(patterns.get(0).getHeadsigns().contains("Downtown, East"));
    }

    @Test
    public void keepsDistinctRoutePatternsAndIgnoresUnknownTrips() {
        Map<String, String> files = files();
        files.put("stop_times.txt", "trip_id,stop_id,stop_sequence\n"
                + "trip-1,C,3\ntrip-1,A,1\ntrip-1,B,2\n"
                + "trip-2,A,1\ntrip-2,B,2\ntrip-2,C,3\n"
                + "trip-3,C,3\ntrip-3,B,2\ntrip-3,A,1\n"
                + "unknown,C,1\n");

        GtfsNetworkCatalog catalog = GtfsNetworkCatalog.fromFiles(files);

        assertEquals(1, catalog.patternsForRoute("R").size());
        assertEquals(1, catalog.patternsForRoute("B").size());
        assertFalse(catalog.getStopIdsByTripId().containsKey("unknown"));
    }

    @Test
    public void exposesImmutableSnapshots() {
        GtfsNetworkCatalog catalog = GtfsNetworkCatalog.fromFiles(files());

        assertThrows(UnsupportedOperationException.class,
                () -> catalog.getPatterns().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.getStopsById().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.getStopIdsByTripId().get("trip-1").add("D"));
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.getPatterns().get(0).getStopIds().add("D"));
    }

    @Test
    public void reportsStructuralFeedDrift() {
        Map<String, String> files = files();
        files.put("stop_times.txt", "trip_id,stop_id,stop_sequence\n"
                + "trip-1,A,1\ntrip-1,missing,2\n");

        GtfsNetworkCatalog catalog = GtfsNetworkCatalog.fromFiles(files);

        assertTrue(catalog.validationErrors().toString()
                .contains("unknown stop missing"));
    }

    @Test
    public void parsesOptionalTransferEdges() {
        Map<String, String> files = files();
        files.put("transfers.txt", "from_stop_id,to_stop_id,transfer_type,"
                + "min_transfer_time,from_route_id,to_route_id\n"
                + "A,B,2,90,R,B\n");

        GtfsNetworkCatalog catalog = GtfsNetworkCatalog.fromFiles(files);

        assertEquals(1, catalog.getTransfers().size());
        GtfsTransfer transfer = catalog.getTransfers().get(0);
        assertEquals("A", transfer.getFromStopId());
        assertEquals(90, transfer.getMinimumTransferSeconds().intValue());
        assertTrue(catalog.validationErrors().isEmpty());
    }

    @Test
    public void rejectsMissingRequiredFiles() {
        Map<String, String> files = files();
        files.remove("routes.txt");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> GtfsNetworkCatalog.fromFiles(files));
        assertTrue(exception.getMessage().contains("routes.txt"));
    }

    private static Map<String, String> files() {
        Map<String, String> files = new HashMap<>();
        files.put("stops.txt", "stop_id,stop_name,parent_station\n"
                + "A1,Oakland Main Platform,A\n"
                + "A,\"Oakland, Main\",\n"
                + "B,Central,\nC,Airport,\n");
        files.put("routes.txt", "route_id,route_short_name,route_long_name,"
                + "route_type,route_color,route_text_color\n"
                + "R,Red,Red,1,FF0000,FFFFFF\n"
                + "B,Blue,Blue,1,0000FF,FFFFFF\n");
        files.put("trips.txt", "route_id,service_id,trip_id,direction_id,trip_headsign\n"
                + "R,weekday,trip-1,0,Downtown\n"
                + "R,weekday,trip-2,0,\"Downtown, East\"\n"
                + "B,weekday,trip-3,1,Airport\n");
        files.put("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
                + "trip-1,08:00:00,08:00:00,A,1\n"
                + "trip-1,08:05:00,08:05:00,B,2\n"
                + "trip-1,08:10:00,08:10:00,C,3\n"
                + "trip-2,09:00:00,09:00:00,A,1\n"
                + "trip-2,09:05:00,09:05:00,B,2\n"
                + "trip-2,09:10:00,09:10:00,C,3\n"
                + "trip-3,10:00:00,10:00:00,C,3\n"
                + "trip-3,10:05:00,10:05:00,B,2\n"
                + "trip-3,10:10:00,10:10:00,A,1\n");
        return files;
    }

    private static List<String> asList(String... values) {
        return java.util.Arrays.asList(values);
    }
}
