package com.dougkeen.bart.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex;
import com.google.transit.realtime.GtfsRealtime;

import org.junit.Test;

public class TransitFeedSnapshotTest {
    @Test
    public void indexesEachFeedOnceAndSeparatesTripAndAlertEntities() {
        GtfsRealtime.FeedEntity trip = GtfsRealtime.FeedEntity.newBuilder()
                .setId("trip-1")
                .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
                        .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                                .setTripId("trip-1")
                                .setRouteId("8")
                                .buildPartial())
                        .addStopTimeUpdate(GtfsRealtime.TripUpdate
                                .StopTimeUpdate.newBuilder()
                                .setStopSequence(1)
                                .setStopId("M20-1")
                                .setDeparture(GtfsRealtime.TripUpdate
                                        .StopTimeEvent.newBuilder()
                                        .setTime(1_000L).build())
                                .setArrival(GtfsRealtime.TripUpdate
                                        .StopTimeEvent.newBuilder()
                                        .setTime(1_000L).build())
                                .buildPartial())
                        .buildPartial())
                .buildPartial();
        GtfsRealtime.FeedEntity alert = GtfsRealtime.FeedEntity.newBuilder()
                .setId("alert-1")
                .setAlert(GtfsRealtime.Alert.newBuilder().buildPartial())
                .buildPartial();
        TransitFeedSnapshot snapshot = new TransitFeedSnapshot(
                GtfsRealtime.FeedMessage.newBuilder().addEntity(trip).buildPartial(),
                GtfsRealtime.FeedMessage.newBuilder().addEntity(alert).buildPartial(),
                123L);

        GtfsRealtimeFeedIndex tripIndex = snapshot.getTripUpdateIndex();
        GtfsRealtimeFeedIndex alertIndex = snapshot.getAlertIndex();

        assertSame(tripIndex, snapshot.getTripUpdateIndex());
        assertSame(alertIndex, snapshot.getAlertIndex());
        assertEquals(1, tripIndex.getTripUpdateEntities().size());
        assertEquals(trip, tripIndex.getTripUpdatesById().get("trip-1"));
        assertEquals(1, alertIndex.getAlertEntities().size());
        assertEquals(alert, alertIndex.getAlertsById().get("alert-1"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void tripEntityIndexCannotBeMutatedByAConsumer() {
        TransitFeedSnapshot snapshot = new TransitFeedSnapshot(
                emptyFeed(), emptyFeed(), 123L);
        snapshot.getTripUpdateIndex().getTripUpdateEntities().clear();
    }

    private static GtfsRealtime.FeedMessage emptyFeed() {
        return GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(1L)
                        .build())
                .build();
    }
}
