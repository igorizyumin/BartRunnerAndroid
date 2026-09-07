package com.dougkeen.bart.networktasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.RealTimeDepartures;
import com.dougkeen.bart.model.Route;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.routing.TripPlanner;
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork;
import com.dougkeen.bart.transit.gtfs.GtfsNetworkCatalog;
import com.google.transit.realtime.GtfsRealtime;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GtfsRealtimeContentHandlerTest {
    @Test
    public void keepsConnectingTripsThatDoNotMeetFeedMinimum() {
        BartGtfsNetwork network = network();
        Route route = TripPlanner.routesFor(Station.LAKE, Station.DALY,
                network).get(0);
        assertTrue("route=" + route + " lines=" + route.getLines()
                        + " transfers=" + route.getTransferStations()
                        + " yellow=" + route.getStationSequence(Line.YELLOW),
                route.trainDestinationIsApplicable(Station.MONT, Line.YELLOW));
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                Station.LAKE, Station.DALY, Arrays.asList(route), false,
                network);

        RealTimeDepartures departures = handler.getRealTimeDepartures(feed());

        assertEquals(1, departures.getDepartures().size());
        List<TripLeg> legs = departures.getDepartures().get(0).getTripLegs();
        assertEquals(2, legs.size());
        assertEquals("blue-early", legs.get(1).getTripId());
        assertEquals(1_100_000L, legs.get(1).getDepartureTime());
        assertEquals(1_160_000L, legs.get(1).getArrivalTime());
        assertEquals(90, legs.get(0).getMinimumTransferSecondsAfter());
    }

    private static GtfsRealtime.FeedMessage feed() {
        return GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(900L))
                .addEntity(entity("1", "yellow-first", new String[]{"LAKE", "MONT"},
                        new long[]{1_000L, 1_060L}))
                .addEntity(entity("12", "blue-early", new String[]{"MONT", "DALY"},
                        new long[]{1_100L, 1_160L}))
                .addEntity(entity("12", "blue-valid", new String[]{"MONT", "DALY"},
                        new long[]{1_150L, 1_210L}))
                .build();
    }

    private static GtfsRealtime.FeedEntity entity(String routeId, String tripId,
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
                    .newBuilder().setStopId(stops[i])
                    .setStopSequence(i + 1).setArrival(event)
                    .setDeparture(event));
        }
        return GtfsRealtime.FeedEntity.newBuilder()
                .setId(tripId).setTripUpdate(update).build();
    }

    private static BartGtfsNetwork network() {
        Map<String, String> files = new HashMap<String, String>();
        files.put("stops.txt", "stop_id,stop_name,zone_id\n"
                + "LAKE,Lake Merritt,LAKE\n"
                + "MONT,Montgomery St.,MONT\n"
                + "DALY,Daly City,DALY\n");
        files.put("routes.txt", "route_id,route_short_name\n"
                + "1,Yellow-N\n12,Blue-N\n");
        files.put("trips.txt", "route_id,service_id,trip_id\n"
                + "1,weekday,yellow-first\n"
                + "12,weekday,blue-early\n"
                + "12,weekday,blue-valid\n");
        files.put("stop_times.txt", "trip_id,stop_id,stop_sequence\n"
                + "yellow-first,LAKE,1\nyellow-first,MONT,2\n"
                + "blue-early,MONT,1\nblue-early,DALY,2\n"
                + "blue-valid,MONT,1\nblue-valid,DALY,2\n");
        files.put("transfers.txt", "from_stop_id,to_stop_id,transfer_type,"
                + "min_transfer_time,from_route_id,to_route_id\n"
                + "MONT,MONT,2,90,1,12\n");
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files));
    }
}
