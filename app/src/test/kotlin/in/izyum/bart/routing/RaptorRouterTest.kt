package `in`.izyum.bart.routing

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaptorRouterTest {
    @Test
    fun rejectsConnectionsBelowTheFeedMinimumAtBusyStations() {
        val network = transferNetwork()
        val router = RaptorRouter(
            trips = listOf(
                trip("yellow", Line.YELLOW, listOf(Station.RICH, Station.MONT), 100_000L, 500_000L),
                trip("blue", Line.BLUE, listOf(Station.MONT, Station.SFIA), 589_999L, 900_000L),
            ),
            network = network,
        )

        assertTrue(router.journeys(Station.RICH, Station.SFIA, 100_000L).isEmpty())
    }

    @Test
    fun permitsTheFeedMinimumAtBusyStations() {
        val network = transferNetwork()
        val router = RaptorRouter(
            trips = listOf(
                trip("yellow", Line.YELLOW, listOf(Station.RICH, Station.MONT), 100_000L, 500_000L),
                trip("blue", Line.BLUE, listOf(Station.MONT, Station.SFIA), 590_000L, 900_000L),
            ),
            network = network,
        )

        val journey = router.journeys(Station.RICH, Station.SFIA, 100_000L).single()
        assertEquals(1, journey.transferCount)
        assertEquals(900_000L, journey.arrivalTime)
    }

    @Test
    fun usesStationPreferenceOnlyToBreakAnEquivalentJourneyTie() {
        val network = preferenceNetwork()
        val router = RaptorRouter(
            trips = listOf(
                trip("yellow-mont", Line.YELLOW, listOf(Station.RICH, Station.MONT), 100_000L, 500_000L),
                trip("blue-mont", Line.BLUE, listOf(Station.MONT, Station.SFIA), 800_000L, 1_100_000L),
                trip("yellow-lake", Line.YELLOW, listOf(Station.RICH, Station.LAKE), 100_000L, 500_000L),
                trip("blue-lake", Line.BLUE, listOf(Station.LAKE, Station.SFIA), 800_000L, 1_100_000L),
            ),
            network = network,
        )

        val journey = router.journeys(Station.RICH, Station.SFIA, 100_000L).single()
        assertEquals(1_100_000L, journey.arrivalTime)
        assertEquals(Station.LAKE, journey.legs[0].destination)
    }

    @Test
    fun permitsChangingTrainsOnTheSameLineAtASharedStation() {
        val network = sameLineNetwork()
        val router = RaptorRouter(
            trips = listOf(
                trip("short", Line.BLUE, listOf(Station.RICH, Station.DALY), 100_000L, 500_000L),
                trip("continuing", Line.BLUE, listOf(Station.DALY, Station.MONT), 800_000L, 1_100_000L),
            ),
            network = network,
        )

        assertTrue(router.journeys(
            Station.RICH,
            Station.MONT,
            100_000L,
            maxBoardings = 1,
        ).isEmpty())

        val journey = router.journeys(Station.RICH, Station.MONT, 100_000L).single()
        assertEquals(1, journey.transferCount)
        assertEquals(1_100_000L, journey.arrivalTime)
    }

    @Test
    fun separatesOvertakingTripsIntoIndependentRaptorRouteGroups() {
        val pattern = listOf(Station.RICH, Station.DALY, Station.MONT)
        val router = RaptorRouter(
            trips = listOf(
                timedTrip(
                    "slower-later",
                    Line.BLUE,
                    pattern,
                    listOf(100_000L to 100_000L, 500_000L to 510_000L, 900_000L to 900_000L),
                ),
                timedTrip(
                    "overtakes",
                    Line.BLUE,
                    pattern,
                    listOf(200_000L to 200_000L, 300_000L to 310_000L, 600_000L to 600_000L),
                ),
            ),
            network = sameLineNetwork(),
        )

        val journey = router.journeys(Station.RICH, Station.MONT, 100_000L).single()
        assertEquals("overtakes", journey.legs.single().trip.id)
        assertEquals(600_000L, journey.arrivalTime)
    }

    private fun trip(
        id: String,
        line: Line,
        stations: List<Station>,
        departureTime: Long,
        arrivalTime: Long,
    ): RaptorRouter.Trip = RaptorRouter.Trip(
        id = id,
        routeKey = "$line:${stations.joinToString(",")}",
        line = line,
        direction = null,
        trainDestination = stations.last(),
        stops = stations.mapIndexed { index, station ->
            val time = when (index) {
                0 -> departureTime
                stations.lastIndex -> arrivalTime
                else -> departureTime + (arrivalTime - departureTime) / 2
            }
            RaptorRouter.StopTime(station, time, time)
        },
    )

    private fun timedTrip(
        id: String,
        line: Line,
        stations: List<Station>,
        times: List<Pair<Long, Long>>,
    ): RaptorRouter.Trip = RaptorRouter.Trip(
        id = id,
        routeKey = "$line:${stations.joinToString(",")}",
        line = line,
        direction = null,
        trainDestination = stations.last(),
        stops = stations.mapIndexed { index, station ->
            RaptorRouter.StopTime(
                station = station,
                arrivalTime = times[index].first,
                departureTime = times[index].second,
            )
        },
    )

    private fun transferNetwork(): BartGtfsNetwork {
        val files = mutableMapOf(
            "stops.txt" to "stop_id,stop_name,zone_id\nRICH,Richmond,RICH\nMONT,Montgomery St.,MONT\nSFIA,SFO Airport,SFIA\n",
            "routes.txt" to "route_id,route_short_name\n1,Yellow-N\n12,Blue-N\n",
            "trips.txt" to "route_id,service_id,trip_id\n1,weekday,yellow\n12,weekday,blue\n",
            "stop_times.txt" to "trip_id,stop_id,stop_sequence\nyellow,RICH,1\nyellow,MONT,2\nblue,MONT,1\nblue,SFIA,2\n",
            "transfers.txt" to "from_stop_id,to_stop_id,transfer_type,min_transfer_time,from_route_id,to_route_id\nMONT,MONT,2,90,1,12\n",
        )
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files))
    }

    private fun sameLineNetwork(): BartGtfsNetwork {
        val files = mutableMapOf(
            "stops.txt" to "stop_id,stop_name,zone_id\nRICH,Richmond,RICH\nDALY,Daly City,DALY\nMONT,Montgomery St.,MONT\n",
            "routes.txt" to "route_id,route_short_name\n12,Blue-N\n13,Blue-N\n",
            "trips.txt" to "route_id,service_id,trip_id\n12,weekday,short\n13,weekday,continuing\n",
            "stop_times.txt" to "trip_id,stop_id,stop_sequence\nshort,RICH,1\nshort,DALY,2\ncontinuing,DALY,1\ncontinuing,MONT,2\n",
        )
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files))
    }

    private fun preferenceNetwork(): BartGtfsNetwork {
        val files = mutableMapOf(
            "stops.txt" to "stop_id,stop_name,zone_id\nRICH,Richmond,RICH\nMONT,Montgomery St.,MONT\nLAKE,Lake Merritt,LAKE\nSFIA,SFO Airport,SFIA\n",
            "routes.txt" to "route_id,route_short_name\n1,Yellow-N\n2,Yellow-N\n12,Blue-N\n13,Blue-N\n",
            "trips.txt" to "route_id,service_id,trip_id\n1,weekday,yellow-mont\n12,weekday,blue-mont\n2,weekday,yellow-lake\n13,weekday,blue-lake\n",
            "stop_times.txt" to "trip_id,stop_id,stop_sequence\nyellow-mont,RICH,1\nyellow-mont,MONT,2\nblue-mont,MONT,1\nblue-mont,SFIA,2\nyellow-lake,RICH,1\nyellow-lake,LAKE,2\nblue-lake,LAKE,1\nblue-lake,SFIA,2\n",
        )
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files))
    }
}
