package `in`.izyum.bart.transit.normalization

import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StaticTransitNormalizerTest {
    @Test
    fun retainsPlatformParentStationAndAfterMidnightServiceTime() {
        val catalog = GtfsNetworkCatalog.fromFiles(
            mapOf(
                "stops.txt" to "stop_id,stop_name,parent_station,zone_id\nMLBR,Millbrae,,MLBR\nMLBR-3,Millbrae Platform 3,MLBR,MLBR\n",
                "routes.txt" to "route_id,route_short_name\n2,Yellow-N\n",
                "trips.txt" to "route_id,service_id,trip_id\n2,weekday,overnight\n",
                "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\novernight,24:15:00,24:16:00,MLBR-3,1\n",
                "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nweekday,1,1,1,1,1,1,1,20260901,20260930\n",
            )
        )

        val trip = StaticTransitNormalizer.normalize(catalog, LocalDate.of(2026, 9, 8)).single()
        val stop = trip.stops.single()

        assertEquals(StaticTripIdentity(LocalDate.of(2026, 9, 8), "overnight"), trip.identity)
        assertEquals("MLBR-3", stop.rawStopId)
        assertEquals("MLBR", stop.parentStationId)
        assertEquals("3", stop.platformCode)
        assertEquals(24 * 60 * 60 + 15 * 60, stop.arrivalSecondsAfterServiceMidnight)
        assertEquals(24 * 60 * 60 + 16 * 60, stop.departureSecondsAfterServiceMidnight)
    }
}
