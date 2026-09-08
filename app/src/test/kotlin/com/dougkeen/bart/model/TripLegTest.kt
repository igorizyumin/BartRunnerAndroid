package com.dougkeen.bart.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TripLegTest {
    @Test
    fun tripLegCopiesAndProtectsStops() {
        val stop = TripStop(Station.EMBR, 1_000L, 1_100L)
        val source = mutableListOf(stop)
        val leg = TripLeg(Line.RED, Station.MONT, Station.RICH, Station.RICH, "red-1", 900L, 1_200L, source)
        source.clear()
        assertEquals(listOf(stop), leg.stops)
        assertThrows(UnsupportedOperationException::class.java) {
            (leg.stops as MutableList<TripStop>).add(stop)
        }
        assertTrue(leg.hasArrivalTime())
    }

    @Test
    fun partialItineraryDoesNotBecomeFinalArrivalEstimate() {
        var departure = Departure.builder()
            .setOrigin(Station.CAST)
            .setPassengerDestination(Station.PITT)
            .setMinEstimate(1_000L)
            .setMaxEstimate(2_000L)
            .setTripLegs(listOf(TripLeg(
                Line.BLUE, Station.CAST, Station.BAYF, Station.DUBL,
                "blue-1", 1_000L, 3_000L, emptyList(),
            )))
            .build()
        assertFalse(departure.hasAnyArrivalEstimate())

        departure = departure.withTripLegs(listOf(
            TripLeg(Line.BLUE, Station.CAST, Station.BAYF, Station.DUBL, "blue-1", 1_000L, 3_000L, emptyList()),
            TripLeg(Line.YELLOW, Station.BAYF, Station.PITT, Station.ANTC, "yellow-1", 4_000L, 6_000L, emptyList()),
        ))
        assertTrue(departure.hasAnyArrivalEstimate())
        assertEquals(6_000L, departure.getEstimatedArrivalTime())
    }
}
