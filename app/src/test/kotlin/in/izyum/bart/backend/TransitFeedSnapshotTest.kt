package `in`.izyum.bart.backend

import `in`.izyum.bart.transit.normalization.RealtimeFeedNormalizer
import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitFeedSnapshotTest {
    @Test
    fun cachesLosslessTripAndAlertNormalizations() {
        val trip = entity("trip-1", "trip-1")
        val alert = GtfsRealtime.FeedEntity.newBuilder()
            .setId("alert-1")
            .setAlert(GtfsRealtime.Alert.newBuilder().buildPartial())
            .buildPartial()
        val snapshot = TransitFeedSnapshot(
            feed(trip),
            feed(alert),
            123L,
        )

        val trips = snapshot.getNormalizedTripUpdates()
        val alerts = snapshot.getNormalizedAlerts()

        assertSame(trips, snapshot.getNormalizedTripUpdates())
        assertSame(alerts, snapshot.getNormalizedAlerts())
        assertEquals(listOf(trip), trips.entities)
        assertEquals(trip, trips.projectedTripUpdatesById["trip-1"]?.rawEntity)
        assertEquals(listOf(alert), alerts.entities)
    }

    @Test
    fun doesNotUseEntityIdAsTripIdWhenDescriptorOmitsTripId() {
        val sparse = GtfsRealtime.FeedEntity.newBuilder()
            .setId("scheduled-passenger-trip")
            .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setRouteId("8"))
                .addStopTimeUpdate(stop("ANTC-2", 1_000L))
                .buildPartial())
            .buildPartial()

        val normalized = RealtimeFeedNormalizer.normalize(feed(sparse))

        assertEquals(1, normalized.tripUpdates.size)
        assertEquals(null, normalized.tripUpdates.single().tripId)
        assertTrue(normalized.projectedTripUpdatesById.isEmpty())
    }

    @Test
    fun keepsDmuTelemetryInTheCanonicalFeedWithoutClaimingStaticIdentity() {
        val dmu = entity("682", "682")

        val normalized = RealtimeFeedNormalizer.normalize(feed(dmu))
        val observation = normalized.tripUpdates.single()

        assertTrue(observation.isOperationalTelemetry)
        assertEquals(dmu, normalized.projectedTripUpdatesById["682"]?.rawEntity)
    }

    @Test
    fun duplicateProjectionPrefersExplicitCancellationAndRecordsTheDecision() {
        val ordinary = entity("same-trip", "ordinary")
        val canceled = entity(
            "same-trip",
            "canceled",
            GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED,
        )

        val normalized = RealtimeFeedNormalizer.normalize(feed(ordinary, canceled))

        assertEquals("canceled", normalized.projectedTripUpdatesById["same-trip"]?.entityId)
        assertEquals(1, normalized.duplicateDecisions.size)
        assertEquals("explicit_cancellation", normalized.duplicateDecisions.single().reason)
        assertEquals(listOf("ordinary"), normalized.duplicateDecisions.single().discardedEntityIds)
    }

    @Test
    fun rawEntityAndStopCollectionsCannotBeMutatedByConsumers() {
        val normalized = RealtimeFeedNormalizer.normalize(feed(entity("trip-1", "trip-1")))

        assertThrows(UnsupportedOperationException::class.java) {
            (normalized.entities as MutableList<GtfsRealtime.FeedEntity>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (normalized.tripUpdates.single().stops as MutableList<*>).clear()
        }
    }

    private fun feed(vararg entities: GtfsRealtime.FeedEntity): GtfsRealtime.FeedMessage =
        GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0").setTimestamp(1L))
            .addAllEntity(entities.asList())
            .buildPartial()

    private fun entity(
        tripId: String,
        entityId: String,
        relationship: GtfsRealtime.TripDescriptor.ScheduleRelationship? = null,
    ): GtfsRealtime.FeedEntity {
        val descriptor = GtfsRealtime.TripDescriptor.newBuilder().setTripId(tripId)
        relationship?.let(descriptor::setScheduleRelationship)
        return GtfsRealtime.FeedEntity.newBuilder().setId(entityId)
            .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder().setTrip(descriptor)
                .addStopTimeUpdate(stop("M20-1", 1_000L)))
            .buildPartial()
    }

    private fun stop(stopId: String, timestamp: Long) =
        GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder().setStopId(stopId)
            .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(timestamp))
            .buildPartial()
}
