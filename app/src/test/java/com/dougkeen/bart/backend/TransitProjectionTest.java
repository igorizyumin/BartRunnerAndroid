package com.dougkeen.bart.backend;

import static org.junit.Assert.assertEquals;

import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.model.Alert;
import com.google.transit.realtime.GtfsRealtime;

import org.junit.Test;

import java.util.Collections;

public class TransitProjectionTest {
    @Test
    public void routeProjectionDerivesDeparturesFromOneCompleteFeed()
            throws Exception {
        TransitFeedSnapshot snapshot = snapshotWithTripUpdate();

        RouteDepartureProjection projection = new RouteDepartureProjection(
                new StationPair(Station.MONT, Station.RICH));
        Departure departure = projection.project(snapshot).getDepartures().get(0);

        assertEquals(Station.RICH, departure.getTrainDestination());
        assertEquals(1, projection.project(snapshot).getDepartures().size());
    }

    @Test
    public void alertProjectionConvertsTheLatestAlertFeed() {
        GtfsRealtime.TranslatedString text = GtfsRealtime.TranslatedString
                .newBuilder()
                .addTranslation(GtfsRealtime.TranslatedString.Translation
                        .newBuilder().setText("Delay at Montgomery").build())
                .build();
        GtfsRealtime.Alert alert = GtfsRealtime.Alert.newBuilder()
                .setHeaderText(text)
                .build();
        GtfsRealtime.FeedMessage alerts = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(header(1000L))
                .addEntity(GtfsRealtime.FeedEntity.newBuilder()
                        .setId("alert-1")
                        .setAlert(alert)
                        .build())
                .build();
        TransitFeedSnapshot snapshot = new TransitFeedSnapshot(
                emptyFeed(), alerts, 1_000_000L);

        Alert.AlertList result = new AlertProjection().project(snapshot);

        assertEquals(1, result.getAlerts().size());
        assertEquals("Delay at Montgomery",
                result.getAlerts().get(0).getDescription());
    }

    @Test
    public void tripProgressProjectionUpdatesExistingLegFromSameFeed()
            throws Exception {
        TransitFeedSnapshot snapshot = snapshotWithTripUpdate();
        TripLeg existingLeg = new TripLeg();
        existingLeg.setLine(Line.RED);
        existingLeg.setOrigin(Station.MONT);
        existingLeg.setDestination(Station.RICH);
        existingLeg.setTrainDestination(Station.RICH);
        existingLeg.setTripId("red-1");
        existingLeg.setDepartureTime(0L);
        existingLeg.setArrivalTime(0L);

        TripProgressProjection projection = new TripProgressProjection(
                Station.MONT, Station.RICH,
                Collections.singletonList(existingLeg),
                Collections.<String, String>emptyMap());

        TripLeg updated = projection.project(snapshot).get(0);

        assertEquals(1_200_000L, updated.getDepartureTime());
        assertEquals(1_800_000L, updated.getArrivalTime());
        assertEquals(Station.RICH, updated.getTrainDestination());
    }

    private static TransitFeedSnapshot snapshotWithTripUpdate() {
        GtfsRealtime.TripUpdate.StopTimeEvent montgomeryTime =
                GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                        .setTime(1200L).build();
        GtfsRealtime.TripUpdate.StopTimeEvent richmondTime =
                GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                        .setTime(1800L).build();
        GtfsRealtime.TripUpdate tripUpdate = GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                        .setTripId("red-1")
                        .setRouteId("8")
                        .build())
                .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate
                        .newBuilder()
                        .setStopSequence(1)
                        .setStopId("M20-1")
                        .setDeparture(montgomeryTime)
                        .setArrival(montgomeryTime)
                        .build())
                .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate
                        .newBuilder()
                        .setStopSequence(2)
                        .setStopId("R60-1")
                        .setDeparture(richmondTime)
                        .setArrival(richmondTime)
                        .build())
                .build();
        GtfsRealtime.FeedMessage updates = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(header(1000L))
                .addEntity(GtfsRealtime.FeedEntity.newBuilder()
                        .setId("red-1")
                        .setTripUpdate(tripUpdate)
                        .build())
                .build();
        return new TransitFeedSnapshot(updates, emptyFeed(), 1_000_000L);
    }

    private static GtfsRealtime.FeedMessage emptyFeed() {
        return GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(header(1000L)).build();
    }

    private static GtfsRealtime.FeedHeader header(long timestamp) {
        return GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0")
                .setTimestamp(timestamp)
                .build();
    }
}
