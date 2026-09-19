package `in`.izyum.bart.data

import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Itinerary
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

        assertEquals(Station.BALB, record.toItinerary().destination)
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
            .setPlatform("2")
            .setTrainLength("10")
            .setCanceled(false).setMinutes(8)
            .setMinEstimate(1_000_000L).setMaxEstimate(1_060_000L)
            .setArrivalTimeOverride(1_800_000L).setEstimatedTripTime(600)
            .setTripLegs(listOf(leg)).build()
        val itinerary = Itinerary.fromDeparture(original)!!
        val record = ObjectMapper().readValue(
            ObjectMapper().writeValueAsBytes(FollowedTripRecord.fromItinerary(itinerary)),
            FollowedTripRecord::class.java,
        )
        val restored = record.toItinerary()
        assertEquals(FollowedTripRecord.CURRENT_VERSION, record.version)
        assertEquals(Station.MONT, restored.origin)
        assertEquals(Station.DUBL, restored.destination)
        assertEquals(Line.RED, restored.line)
        assertEquals(1_000_000L, restored.getInitialDepartureTime())
        assertEquals(1, restored.legs.size)
        assertEquals("red-1", restored.legs[0].tripId)
        assertEquals(LocalDate.of(2026, 9, 8), restored.legs[0].serviceDate)
        assertNotNull(restored.legs[0].stops)
        assertEquals(1, restored.legs[0].stops.size)
        assertEquals(Station.EMBR, restored.legs[0].stops[0].station)
    }

    @Test
    fun rejectsPreCanonicalPersistenceSchema() {
        val record = FollowedTripRecord().apply {
            version = 1
            tripLegs += FollowedTripRecord.TripLegRecord().apply { tripId = "legacy" }
        }

        assertThrows(IllegalArgumentException::class.java) { record.toItinerary() }
    }
}
