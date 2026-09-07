package com.dougkeen.bart.routing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork;
import com.dougkeen.bart.transit.gtfs.GtfsNetworkCatalog;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class TransferConnectionValidatorTest {
    @Test
    public void enforcesMinimumTimeAtTheExactBoundary() {
        BartGtfsNetwork network = network();
        long arrival = 1_000_000L;

        assertTrue(TransferConnectionValidator.canConnect(arrival,
                arrival + 90_000L, Station.MONT, Line.YELLOW, Line.BLUE,
                network));
        assertFalse(TransferConnectionValidator.canConnect(arrival,
                arrival + 89_999L, Station.MONT, Line.YELLOW, Line.BLUE,
                network));
    }

    @Test
    public void rejectsMissingTimesAndForbiddenOrUnlistedConnections() {
        BartGtfsNetwork network = network();

        assertFalse(TransferConnectionValidator.canConnect(0L, 1000L, 0));
        assertFalse(TransferConnectionValidator.canConnect(1000L, 0L, 0));
        assertFalse(TransferConnectionValidator.canConnect(1000L, 2000L, -1));
        assertFalse(TransferConnectionValidator.canConnect(1_000_000L,
                1_090_000L, Station.MONT, Line.BLUE, Line.YELLOW, network));
        assertFalse(TransferConnectionValidator.canConnect(1_000_000L,
                1_029_999L, Station.DALY, Line.BLUE, Line.RED, network));
    }

    @Test
    public void validatesEachConnectionInAMultiLegItinerary() {
        BartGtfsNetwork network = network();
        long firstArrival = 1_000_000L;
        long secondDeparture = firstArrival + 90_000L;
        long secondArrival = secondDeparture + 300_000L;
        long thirdDeparture = secondArrival + 30_000L;

        assertTrue(TransferConnectionValidator.canConnect(firstArrival,
                secondDeparture, Station.MONT, Line.YELLOW, Line.BLUE,
                network));
        assertTrue(TransferConnectionValidator.canConnect(secondArrival,
                thirdDeparture, Station.DALY, Line.BLUE, Line.RED, network));
        assertFalse(TransferConnectionValidator.canConnect(secondArrival,
                thirdDeparture - 1L, Station.DALY, Line.BLUE, Line.RED,
                network));
    }

    private static BartGtfsNetwork network() {
        Map<String, String> files = new HashMap<String, String>();
        files.put("stops.txt", "stop_id,stop_name,zone_id\n"
                + "LAKE,Lake Merritt,LAKE\n"
                + "MONT,Montgomery St.,MONT\n"
                + "DALY,Daly City,DALY\n"
                + "RICH,Richmond,RICH\n");
        files.put("routes.txt", "route_id,route_short_name\n"
                + "1,Yellow-N\n12,Blue-N\n7,Red-N\n");
        files.put("trips.txt", "route_id,service_id,trip_id\n"
                + "1,weekday,yellow\n12,weekday,blue\n7,weekday,red\n");
        files.put("stop_times.txt", "trip_id,stop_id,stop_sequence\n"
                + "yellow,LAKE,1\nyellow,MONT,2\n"
                + "blue,MONT,1\nblue,DALY,2\n"
                + "red,DALY,1\nred,RICH,2\n");
        files.put("transfers.txt", "from_stop_id,to_stop_id,transfer_type,"
                + "min_transfer_time,from_route_id,to_route_id\n"
                + "MONT,MONT,2,90,1,12\n"
                + "DALY,DALY,2,30,12,7\n");
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files));
    }
}
