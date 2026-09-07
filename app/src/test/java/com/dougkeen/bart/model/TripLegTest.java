package com.dougkeen.bart.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TripLegTest {
    @Test
    public void tripLegCopiesAndProtectsStops() {
        TripStop stop = new TripStop(Station.EMBR, 1_000L, 1_100L);
        List<TripStop> source = new ArrayList<>();
        source.add(stop);

        TripLeg leg = new TripLeg(Line.RED, Station.MONT, Station.RICH,
                Station.RICH, "red-1", 900L, 1_200L, source);
        source.clear();

        assertEquals(Collections.singletonList(stop), leg.getStops());
        assertThrows(UnsupportedOperationException.class,
                () -> leg.getStops().add(stop));
        assertTrue(leg.hasArrivalTime());
    }

    @Test
    public void partialItineraryDoesNotBecomeFinalArrivalEstimate() {
        Departure departure = new Departure();
        departure.setOrigin(Station.CAST);
        departure.setPassengerDestination(Station.PITT);
        departure.setMinEstimate(1_000L);
        departure.setMaxEstimate(2_000L);
        departure.setTripLegs(Collections.singletonList(new TripLeg(
                Line.BLUE, Station.CAST, Station.BAYF, Station.DUBL,
                "blue-1", 1_000L, 3_000L, Collections.emptyList())));

        assertFalse(departure.hasAnyArrivalEstimate());

        departure.setTripLegs(java.util.Arrays.asList(
                new TripLeg(Line.BLUE, Station.CAST, Station.BAYF, Station.DUBL,
                        "blue-1", 1_000L, 3_000L, Collections.emptyList()),
                new TripLeg(Line.YELLOW, Station.BAYF, Station.PITT, Station.ANTC,
                        "yellow-1", 4_000L, 6_000L, Collections.emptyList())));

        assertTrue(departure.hasAnyArrivalEstimate());
        assertEquals(6_000L, departure.getEstimatedArrivalTime());
    }
}
