package `in`.izyum.bart.model

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationPairTest {
    @Test
    fun jsonRoundTripContainsOnlyRouteEndpoints() {
        val original = StationPair(Station.MONT, Station.RICH)
        val mapper = ObjectMapper()
        val json = mapper.readTree(mapper.writeValueAsString(original))
        val restored = mapper.readValue(mapper.writeValueAsString(original), StationPair::class.java)
        assertEquals(original.origin, restored.origin)
        assertEquals(original.destination, restored.destination)
        assertEquals(setOf("origin", "destination"), json.fieldNames().asSequence().toSet())
        assertTrue(json.get("origin").isTextual)
        assertTrue(json.get("destination").isTextual)
    }
}
