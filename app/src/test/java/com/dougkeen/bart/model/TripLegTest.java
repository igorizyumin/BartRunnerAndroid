package com.dougkeen.bart.model;

import static org.junit.Assert.assertEquals;
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
}
