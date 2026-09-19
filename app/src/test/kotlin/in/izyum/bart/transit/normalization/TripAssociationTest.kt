package `in`.izyum.bart.transit.normalization

import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TripAssociationTest {
    @Test
    fun usesTripIdAndDescriptorServiceDateForExactAssociation() {
        val feed = normalized("electric", startDate = "20260908")
        val associations = TripAssociator.associate(
            listOf(
                StaticTripIdentity(LocalDate.of(2026, 9, 7), "electric"),
                StaticTripIdentity(LocalDate.of(2026, 9, 8), "electric"),
            ),
            feed,
        )

        val association = associations.single()
        assertEquals(AssociationStatus.EXACT, association.status)
        assertEquals(StaticTripIdentity(LocalDate.of(2026, 9, 8), "electric"), association.staticIdentity)
    }

    @Test
    fun rejectsTripIdThatExistsOnlyOnAnotherServiceDate() {
        val feed = normalized("electric", startDate = "20260908")
        val association = TripAssociator.associate(
            listOf(StaticTripIdentity(LocalDate.of(2026, 9, 7), "electric")),
            feed,
        ).single()

        assertEquals(AssociationStatus.REJECTED, association.status)
        assertNull(association.staticIdentity)
    }

    @Test
    fun classifies600SeriesRecordsAsOperationalTelemetryBeforeStaticLookup() {
        val feed = normalized("682", startDate = "20260908")
        val association = TripAssociator.associate(
            listOf(StaticTripIdentity(LocalDate.of(2026, 9, 8), "682")),
            feed,
        ).single()

        assertEquals(AssociationStatus.OPERATIONAL_TELEMETRY, association.status)
        assertNull(association.staticIdentity)
    }

    private fun normalized(tripId: String, startDate: String): NormalizedRealtimeFeed =
        RealtimeFeedNormalizer.normalize(
            GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                    .setGtfsRealtimeVersion("2.0").setTimestamp(1_789_000_000L))
                .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("entity-$tripId")
                    .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
                        .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                            .setTripId(tripId).setStartDate(startDate))))
                .build()
        )
}
