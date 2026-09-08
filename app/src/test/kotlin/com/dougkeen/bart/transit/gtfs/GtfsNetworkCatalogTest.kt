package com.dougkeen.bart.transit.gtfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GtfsNetworkCatalogTest {
    @Test
    fun parsesStopsRoutesTripsAndOrderedPatterns() {
        val catalog = GtfsNetworkCatalog.fromFiles(files())
        assertEquals("Oakland, Main", catalog.stopsById["A"]?.name)
        assertEquals("A", catalog.stopsById["A1"]?.parentStationId)
        assertEquals("Red", catalog.routesById["R"]?.longName)
        assertEquals("R", catalog.routeIdForTrip("trip-1"))
        assertEquals("B", catalog.stopIdsByTripId["trip-1"]?.get(1))
        assertTrue(catalog.validationErrors().isEmpty())
        val patterns = catalog.patternsForRoute("R")
        assertEquals(1, patterns.size)
        assertEquals("0", patterns[0].directionId)
        assertEquals(listOf("A", "B", "C"), patterns[0].stopIds)
        assertEquals(2, patterns[0].tripIds.size)
        assertTrue("Downtown" in patterns[0].headsigns)
        assertTrue("Downtown, East" in patterns[0].headsigns)
    }

    @Test
    fun keepsDistinctRoutePatternsAndIgnoresUnknownTrips() {
        val updated = files().toMutableMap()
        updated["stop_times.txt"] = "trip_id,stop_id,stop_sequence\ntrip-1,C,3\ntrip-1,A,1\ntrip-1,B,2\ntrip-2,A,1\ntrip-2,B,2\ntrip-2,C,3\ntrip-3,C,3\ntrip-3,B,2\ntrip-3,A,1\nunknown,C,1\n"
        val catalog = GtfsNetworkCatalog.fromFiles(updated)
        assertEquals(1, catalog.patternsForRoute("R").size)
        assertEquals(1, catalog.patternsForRoute("B").size)
        assertFalse(catalog.stopIdsByTripId.containsKey("unknown"))
    }

    @Test
    fun exposesImmutableSnapshots() {
        val catalog = GtfsNetworkCatalog.fromFiles(files())
        assertThrows(UnsupportedOperationException::class.java) { (catalog.patterns as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (catalog.stopsById as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (catalog.stopIdsByTripId["trip-1"] as MutableList).add("D") }
        assertThrows(UnsupportedOperationException::class.java) { (catalog.patterns[0].stopIds as MutableList).add("D") }
    }

    @Test
    fun reportsStructuralFeedDrift() {
        val updated = files().toMutableMap()
        updated["stop_times.txt"] = "trip_id,stop_id,stop_sequence\ntrip-1,A,1\ntrip-1,missing,2\n"
        assertTrue(GtfsNetworkCatalog.fromFiles(updated).validationErrors().toString().contains("unknown stop missing"))
    }

    @Test
    fun parsesOptionalTransferEdges() {
        val updated = files().toMutableMap()
        updated["transfers.txt"] = "from_stop_id,to_stop_id,transfer_type,min_transfer_time,from_route_id,to_route_id\nA,B,2,90,R,B\n"
        val catalog = GtfsNetworkCatalog.fromFiles(updated)
        assertEquals(1, catalog.transfers.size)
        assertEquals("A", catalog.transfers[0].fromStopId)
        assertEquals(90, catalog.transfers[0].minimumTransferSeconds)
        assertTrue(catalog.validationErrors().isEmpty())
    }

    @Test
    fun rejectsMissingRequiredFiles() {
        val updated = files().toMutableMap()
        updated.remove("routes.txt")
        val exception = assertThrows(IllegalArgumentException::class.java) {
            GtfsNetworkCatalog.fromFiles(updated)
        }
        assertTrue(exception.message!!.contains("routes.txt"))
    }

    private fun files() = mutableMapOf(
        "stops.txt" to "stop_id,stop_name,parent_station\nA1,Oakland Main Platform,A\nA,\"Oakland, Main\",\nB,Central,\nC,Airport,\n",
        "routes.txt" to "route_id,route_short_name,route_long_name,route_type,route_color,route_text_color\nR,Red,Red,1,FF0000,FFFFFF\nB,Blue,Blue,1,0000FF,FFFFFF\n",
        "trips.txt" to "route_id,service_id,trip_id,direction_id,trip_headsign\nR,weekday,trip-1,0,Downtown\nR,weekday,trip-2,0,\"Downtown, East\"\nB,weekday,trip-3,1,Airport\n",
        "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\ntrip-1,08:00:00,08:00:00,A,1\ntrip-1,08:05:00,08:05:00,B,2\ntrip-1,08:10:00,08:10:00,C,3\ntrip-2,09:00:00,09:00:00,A,1\ntrip-2,09:05:00,09:05:00,B,2\ntrip-2,09:10:00,09:10:00,C,3\ntrip-3,10:00:00,10:00:00,C,3\ntrip-3,10:05:00,10:05:00,B,2\ntrip-3,10:10:00,10:10:00,A,3\n",
    )
}
