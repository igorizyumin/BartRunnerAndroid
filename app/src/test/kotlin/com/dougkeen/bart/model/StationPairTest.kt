package com.dougkeen.bart.model

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationPairTest {
    @Test
    fun fareUpdatesCreateAReplacementValue() {
        val original = StationPair(Station.MONT, Station.RICH)
        val updated = original.withFare("$5.00", 123L)
        assertNull(original.fare)
        assertEquals(0L, original.fareLastUpdated)
        assertEquals("$5.00", updated.fare)
        assertEquals(123L, updated.fareLastUpdated)
        assertEquals(original.origin, updated.origin)
        assertEquals(original.destination, updated.destination)
    }

    @Test
    fun jsonRoundTripPreservesFareMetadata() {
        val original = StationPair(Station.MONT, Station.RICH, "$5.00", 123L, 42, 7)
        val mapper = ObjectMapper().configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            false,
        )
        val restored = mapper.readValue(mapper.writeValueAsString(original), StationPair::class.java)
        assertEquals(original.origin, restored.origin)
        assertEquals(original.destination, restored.destination)
        assertEquals(original.fare, restored.fare)
        assertEquals(original.fareLastUpdated, restored.fareLastUpdated)
        assertEquals(original.averageTripLength, restored.averageTripLength)
        assertEquals(original.averageTripSampleCount, restored.averageTripSampleCount)
    }
}
