package com.dougkeen.bart.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

public class StationPairTest {
    @Test
    public void fareUpdatesCreateAReplacementValue() {
        StationPair original = new StationPair(Station.MONT, Station.RICH);

        StationPair updated = original.withFare("$5.00", 123L);

        assertNull(original.getFare());
        assertEquals(0L, original.getFareLastUpdated());
        assertEquals("$5.00", updated.getFare());
        assertEquals(123L, updated.getFareLastUpdated());
        assertEquals(original.getOrigin(), updated.getOrigin());
        assertEquals(original.getDestination(), updated.getDestination());
    }

    @Test
    public void jsonRoundTripPreservesFareMetadata() throws Exception {
        StationPair original = new StationPair(Station.MONT, Station.RICH,
                "$5.00", 123L, 42, 7);
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        false);

        StationPair restored = mapper.readValue(mapper.writeValueAsString(original),
                StationPair.class);

        assertEquals(original.getOrigin(), restored.getOrigin());
        assertEquals(original.getDestination(), restored.getDestination());
        assertEquals(original.getFare(), restored.getFare());
        assertEquals(original.getFareLastUpdated(), restored.getFareLastUpdated());
        assertEquals(original.getAverageTripLength(), restored.getAverageTripLength());
        assertEquals(original.getAverageTripSampleCount(),
                restored.getAverageTripSampleCount());
    }
}
