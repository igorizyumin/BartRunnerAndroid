package com.dougkeen.bart.transit.gtfs

import com.dougkeen.bart.model.Line
import com.dougkeen.bart.model.Route
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.backend.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BartGtfsNetworkTest {
    @Test
    fun resolvesFeedStopsByExactStopId() {
        val network = BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files()))
        assertEquals(Station.LAKE, network.stationForStopId("A10-1"))
        assertEquals(Station.LAKE, network.stationForStopId("A10-2"))
        assertEquals(Station.MLBR, network.stationForStopId("W40-3"))
        assertEquals(Station.SFIA, network.stationForStopId("Y10-1"))
        assertNull(network.stationForStopId("UNKNOWN-1"))
    }

    @Test
    fun mapsRouteIdsAndInfersSharedStationTransfers() {
        val network = BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files()))
        assertEquals(Line.YELLOW, network.lineForRouteId("1"))
        assertEquals(Line.BLUE, network.lineForRouteId("12"))
        assertEquals("s", network.directionForRouteId("1"))
        assertEquals("n", network.directionForRouteId("12"))
        assertTrue(network.validationErrors().isEmpty())
        assertTrue(network.canTransfer(Station.LAKE, Line.YELLOW, Line.BLUE))
    }

    @Test
    fun usesRouteSpecificTransferRulesAndRejectsForbiddenRules() {
        val updated = files().toMutableMap()
        updated["transfers.txt"] = "from_stop_id,to_stop_id,transfer_type,min_transfer_time,from_route_id,to_route_id\nA10-1,A10-2,2,90,1,12\nA10-2,A10-1,3,0,12,1\n"
        val network = BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(updated))
        assertTrue(network.canTransfer(Station.LAKE, Line.YELLOW, Line.BLUE))
        assertFalse(network.canTransfer(Station.LAKE, Line.BLUE, Line.YELLOW))
        assertEquals(90, network.getTransferRules()[0].minimumTransferSeconds)
    }

    @Test
    fun plannerUsesAFeedPatternForAStationPair() {
        val network = BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files()))
        val routes: List<Route> = Schedule.fromStatic(network, 0L)
            .routesFor(Station.LAKE, Station.SFIA)
        assertEquals(1, routes.size)
        assertEquals(Line.YELLOW, routes[0].directLine)
        assertEquals("s", routes[0].direction)
        assertTrue(routes[0].trainDestinationIsApplicable(Station.SFIA, Line.YELLOW))
    }

    private fun files() = mutableMapOf(
        "stops.txt" to "stop_id,stop_name,parent_station,zone_id\nA10-1,Lake Merritt,,LAKE\nA10-2,Lake Merritt,,LAKE\nW40-1,Millbrae,,MLBR\nY10-1,San Francisco International Airport,,SFIA\nW40-3,Millbrae (Caltrain Transfer Platform),,MLBR\n",
        "routes.txt" to "route_id,route_short_name,route_long_name\n1,Yellow-S,Pittsburg/Bay Point - SFO\n12,Blue-N,Daly City - Dublin/Pleasanton\n",
        "trips.txt" to "route_id,service_id,trip_id,direction_id,trip_headsign\n1,weekday,yellow-trip,0,SFO\n1,weekday,yellow-branch,1,Millbrae\n12,weekday,blue-trip,1,Dublin/Pleasanton\n",
        "stop_times.txt" to "trip_id,stop_id,stop_sequence\nyellow-trip,A10-1,1\nyellow-trip,Y10-1,2\nyellow-branch,Y10-1,1\nyellow-branch,W40-3,2\nblue-trip,W40-1,1\nblue-trip,A10-2,2\n",
    )
}
