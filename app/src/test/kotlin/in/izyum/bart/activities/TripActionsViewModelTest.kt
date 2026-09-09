package `in`.izyum.bart.activities

import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import org.junit.Assert.assertEquals
import org.junit.Test

class TripActionsViewModelTest {
    @Test
    fun followingDepartureUsesTrainDestinationWhenPassengerDestinationIsMissing() {
        val departure = Departure.builder()
            .setOrigin(Station.CAST)
            .setTrainDestination(Station.BALB)
            .setLine(Line.BLUE)
            .setDirection("south")
            .build()

        val prepared = prepareDepartureForFollowing(departure)

        assertEquals(Station.BALB, prepared.passengerDestination)
    }
}
