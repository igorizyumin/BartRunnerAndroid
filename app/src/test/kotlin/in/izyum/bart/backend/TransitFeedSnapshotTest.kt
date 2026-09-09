package `in`.izyum.bart.backend

import `in`.izyum.bart.networktasks.GtfsRealtimeFeedIndex
import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TransitFeedSnapshotTest {
    @Test
    fun indexesEachFeedOnceAndSeparatesTripAndAlertEntities() {
        val trip = GtfsRealtime.FeedEntity.newBuilder()
            .setId("trip-1")
            .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                    .setTripId("trip-1").setRouteId("8").buildPartial())
                .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                    .setStopSequence(1).setStopId("M20-1")
                    .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(1_000L).build())
                    .setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(1_000L).build())
                    .buildPartial())
                .buildPartial())
            .buildPartial()
        val alert = GtfsRealtime.FeedEntity.newBuilder()
            .setId("alert-1")
            .setAlert(GtfsRealtime.Alert.newBuilder().buildPartial())
            .buildPartial()
        val snapshot = TransitFeedSnapshot(
            GtfsRealtime.FeedMessage.newBuilder().addEntity(trip).buildPartial(),
            GtfsRealtime.FeedMessage.newBuilder().addEntity(alert).buildPartial(),
            123L,
        )
        val tripIndex: GtfsRealtimeFeedIndex = snapshot.getTripUpdateIndex()
        val alertIndex: GtfsRealtimeFeedIndex = snapshot.getAlertIndex()
        assertSame(tripIndex, snapshot.getTripUpdateIndex())
        assertSame(alertIndex, snapshot.getAlertIndex())
        assertEquals(1, tripIndex.tripUpdateEntities.size)
        assertEquals(trip, tripIndex.tripUpdatesById["trip-1"])
        assertEquals(1, alertIndex.alertEntities.size)
        assertEquals(alert, alertIndex.alertsById["alert-1"])
    }

    @Test(expected = UnsupportedOperationException::class)
    fun tripEntityIndexCannotBeMutatedByAConsumer() {
        (TransitFeedSnapshot(emptyFeed(), emptyFeed(), 123L)
            .getTripUpdateIndex().tripUpdateEntities as MutableList<GtfsRealtime.FeedEntity>).clear()
    }

    private fun emptyFeed() = GtfsRealtime.FeedMessage.newBuilder()
        .setHeader(GtfsRealtime.FeedHeader.newBuilder()
            .setGtfsRealtimeVersion("2.0").setTimestamp(1L).build())
        .build()
}
