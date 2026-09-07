package com.dougkeen.bart.transit.gtfs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Route;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.routing.TripPlanner;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import org.junit.Test;

public class BartGtfsNetworkTest {
    @Test
    public void resolvesFeedStopsByExactStopId() {
        BartGtfsNetwork network = BartGtfsNetwork.fromCatalog(
                GtfsNetworkCatalog.fromFiles(files()));

        assertEquals(Station.LAKE, network.stationForStopId("A10-1"));
        assertEquals(Station.LAKE, network.stationForStopId("A10-2"));
        assertEquals(Station.MLBR, network.stationForStopId("W40-3"));
        assertEquals(Station.SFIA, network.stationForStopId("Y10-1"));
        assertNull(network.stationForStopId("UNKNOWN-1"));
    }

    @Test
    public void mapsRouteIdsAndInfersSharedStationTransfers() {
        GtfsNetworkCatalog catalog = GtfsNetworkCatalog.fromFiles(files());
        BartGtfsNetwork network = BartGtfsNetwork.fromCatalog(catalog);

        assertEquals(Line.YELLOW, network.lineForRouteId("1"));
        assertEquals(Line.BLUE, network.lineForRouteId("12"));
        assertEquals("s", network.directionForRouteId("1"));
        assertEquals("n", network.directionForRouteId("12"));
        assertTrue(network.validationErrors().isEmpty());
        assertTrue(network.canTransfer(Station.LAKE, Line.YELLOW, Line.BLUE));
    }

    @Test
    public void usesRouteSpecificTransferRulesAndRejectsForbiddenRules() {
        Map<String, String> files = files();
        files.put("transfers.txt", "from_stop_id,to_stop_id,transfer_type,"
                + "min_transfer_time,from_route_id,to_route_id\n"
                + "A10-1,A10-2,2,90,1,12\n"
                + "A10-2,A10-1,3,0,12,1\n");

        BartGtfsNetwork network = BartGtfsNetwork.fromCatalog(
                GtfsNetworkCatalog.fromFiles(files));

        assertTrue(network.canTransfer(Station.LAKE, Line.YELLOW, Line.BLUE));
        assertFalse(network.canTransfer(Station.LAKE, Line.BLUE, Line.YELLOW));
        assertEquals(90, network.getTransferRules().get(0)
                .getMinimumTransferSeconds().intValue());
    }

    @Test
    public void plannerUsesAFeedPatternForAStationPair() {
        BartGtfsNetwork network = BartGtfsNetwork.fromCatalog(
                GtfsNetworkCatalog.fromFiles(files()));

        List<Route> routes = TripPlanner.routesFor(Station.LAKE, Station.SFIA,
                network);

        assertEquals(1, routes.size());
        assertEquals(Line.YELLOW, routes.get(0).getDirectLine());
        assertEquals("s", routes.get(0).getDirection());
        assertTrue(routes.get(0).trainDestinationIsApplicable(
                Station.SFIA, Line.YELLOW));
    }

    private static Map<String, String> files() {
        Map<String, String> files = new HashMap<>();
        files.put("stops.txt", "stop_id,stop_name,parent_station,zone_id\n"
                + "A10-1,Lake Merritt,,LAKE\n"
                + "A10-2,Lake Merritt,,LAKE\n"
                + "W40-1,Millbrae,,MLBR\n"
                + "Y10-1,San Francisco International Airport,,SFIA\n"
                + "W40-3,Millbrae (Caltrain Transfer Platform),,MLBR\n");
        files.put("routes.txt", "route_id,route_short_name,route_long_name\n"
                + "1,Yellow-S,Pittsburg/Bay Point - SFO\n"
                + "12,Blue-N,Daly City - Dublin/Pleasanton\n");
        files.put("trips.txt", "route_id,service_id,trip_id,direction_id,trip_headsign\n"
                + "1,weekday,yellow-trip,0,SFO\n"
                + "1,weekday,yellow-branch,1,Millbrae\n"
                + "12,weekday,blue-trip,1,Dublin/Pleasanton\n");
        files.put("stop_times.txt", "trip_id,stop_id,stop_sequence\n"
                + "yellow-trip,A10-1,1\n"
                + "yellow-trip,Y10-1,2\n"
                + "yellow-branch,Y10-1,1\n"
                + "yellow-branch,W40-3,2\n"
                + "blue-trip,W40-1,1\n"
                + "blue-trip,A10-2,2\n");
        return files;
    }
}
