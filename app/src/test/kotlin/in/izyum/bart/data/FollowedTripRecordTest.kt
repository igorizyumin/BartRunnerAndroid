package `in`.izyum.bart.data

import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.TripStop
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class FollowedTripRecordTest {
    @Test
    fun missingPassengerDestinationRestoresFromTrainDestination() {
        val record = FollowedTripRecord().apply {
            origin = "cast"
            trainDestination = "balb"
            passengerDestination = null
        }

        assertEquals(Station.BALB, record.toDeparture().passengerDestination)
    }

    @Test
    fun jsonRoundTripRestoresFollowedTripState() {
        val leg = TripLeg(
            Line.RED, Station.MONT, Station.RICH, Station.RICH,
            "red-1", 1_000_000L, 1_500_000L,
            listOf(TripStop(Station.EMBR, 1_200_000L, 1_210_000L)),
            serviceDate = LocalDate.of(2026, 9, 8),
        )
        val original = Departure.builder()
            .setOrigin(Station.MONT).setTrainDestination(Station.RICH)
            .setPassengerDestination(Station.DUBL).setLine(Line.RED)
            .setTrainDestinationColorHex("#ff0000").setTrainDestinationColorText("Red")
            .setPlatform("2").setDirection("north").setBikeAllowed(true)
            .setTrainLength("10").setRequiresTransfer(true).setTransferScheduled(true)
            .setCanceled(false).setMinutes(8)
            .setMinEstimate(1_000_000L).setMaxEstimate(1_060_000L)
            .setArrivalTimeOverride(1_800_000L).setEstimatedTripTime(600)
            .setTripLegs(listOf(leg)).build()
        val record = ObjectMapper().readValue(
            ObjectMapper().writeValueAsBytes(FollowedTripRecord.fromDeparture(original)),
            FollowedTripRecord::class.java,
        )
        val restored = record.toDeparture()
        assertEquals(FollowedTripRecord.CURRENT_VERSION, record.version)
        assertEquals(Station.MONT, restored.origin)
        assertEquals(Station.DUBL, restored.passengerDestination)
        assertEquals(Line.RED, restored.line)
        assertEquals("#ff0000", restored.destinationColorHex)
        assertEquals("2", restored.platform)
        assertEquals(1_000_000L, restored.minEstimate)
        assertEquals(1, restored.tripLegs.size)
        assertEquals("red-1", restored.tripLegs[0].tripId)
        assertEquals(LocalDate.of(2026, 9, 8), restored.tripLegs[0].serviceDate)
        assertNotNull(restored.tripLegs[0].stops)
        assertEquals(1, restored.tripLegs[0].stops.size)
        assertEquals(Station.EMBR, restored.tripLegs[0].stops[0].station)
    }

    @Test
    fun rejectsPreCanonicalPersistenceSchema() {
        val record = FollowedTripRecord().apply {
            version = 1
            tripLegs += FollowedTripRecord.TripLegRecord().apply { tripId = "legacy" }
        }

        assertThrows(IllegalArgumentException::class.java) { record.toDeparture() }
    }
}
