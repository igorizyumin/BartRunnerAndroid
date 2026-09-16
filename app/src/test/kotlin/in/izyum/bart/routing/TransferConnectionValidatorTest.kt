package `in`.izyum.bart.routing

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferPolicyTest {
    @Test
    fun exposesMinimumTimeWithoutRejectingTheTransfer() {
        val network = network()
        val policy = TransferPolicy(network)
        val arrival = 1_000_000L
        assertTrue(policy.canTransfer(arrival, arrival + 90_000L, Station.MONT, Line.YELLOW, Line.BLUE))
        assertTrue(policy.canTransfer(arrival, arrival + 89_999L, Station.MONT, Line.YELLOW, Line.BLUE))
        assertTrue(policy.canMakeTransfer(arrival, arrival + 90_000L, Station.MONT, Line.YELLOW, Line.BLUE))
        assertFalse(policy.canMakeTransfer(arrival, arrival + 89_999L, Station.MONT, Line.YELLOW, Line.BLUE))
        assertTrue(policy.meetsMinimumTransferTime(arrival, arrival + 90_000L, 90))
        assertFalse(policy.meetsMinimumTransferTime(arrival, arrival + 89_999L, 90))
    }

    @Test
    fun usesTheUnofficialBufferOnlyWhenTheFeedHasNoTransferTime() {
        val network = network(includeMontMinimum = false)
        val policy = TransferPolicy(network)
        val arrival = 1_000_000L

        assertFalse(policy.canMakeTransfer(
            arrival, arrival + TransferPolicy.EXTRA_MARGIN_SECONDS * 1000L - 1L,
            Station.MONT, Line.YELLOW, Line.BLUE,
        ))
        assertTrue(policy.canMakeTransfer(
            arrival, arrival + TransferPolicy.EXTRA_MARGIN_SECONDS * 1000L,
            Station.MONT, Line.YELLOW, Line.BLUE,
        ))
    }

    @Test
    fun timedTransfersDoNotNeedTheUnofficialSafetyBuffer() {
        val network = network(montTransferType = 1, includeMontMinimum = false)
        val policy = TransferPolicy(network)
        val arrival = 1_000_000L

        assertTrue(policy.canMakeTransfer(
            arrival, arrival, Station.MONT, Line.YELLOW, Line.BLUE,
        ))
        assertFalse(policy.canMakeTransfer(
            arrival, arrival - 1L, Station.MONT, Line.YELLOW, Line.BLUE,
        ))
    }

    @Test
    fun rejectsMissingTimesForbiddenAndNonSharedConnections() {
        val network = network()
        val policy = TransferPolicy(network)
        assertFalse(policy.canTransfer(0L, 1000L, null, null, null))
        assertFalse(policy.canTransfer(1000L, 0L, null, null, null))
        assertFalse(policy.canTransfer(1000L, 999L, Station.MONT, Line.YELLOW, Line.BLUE))
        assertFalse(policy.meetsMinimumTransferTime(1000L, 2000L, -1))
        assertFalse(policy.canTransfer(1_000_000L, 1_090_000L, Station.RICH, Line.BLUE, Line.YELLOW))
        assertTrue(policy.canTransfer(1_000_000L, 1_029_999L, Station.DALY, Line.BLUE, Line.RED))
        assertFalse(policy.meetsMinimumTransferTime(1_000_000L, 1_029_999L, 30))
    }

    @Test
    fun validatesEachConnectionInAMultiLegItinerary() {
        val network = network()
        val policy = TransferPolicy(network)
        val firstArrival = 1_000_000L
        val secondDeparture = firstArrival + 90_000L
        val secondArrival = secondDeparture + 300_000L
        val thirdDeparture = secondArrival + 30_000L
        assertTrue(policy.canTransfer(firstArrival, secondDeparture, Station.MONT, Line.YELLOW, Line.BLUE))
        assertTrue(policy.canTransfer(secondArrival, thirdDeparture, Station.DALY, Line.BLUE, Line.RED))
        assertTrue(policy.canTransfer(secondArrival, thirdDeparture - 1L, Station.DALY, Line.BLUE, Line.RED))
        assertFalse(policy.meetsMinimumTransferTime(secondArrival, thirdDeparture - 1L, 30))
    }

    private fun network(
        montTransferType: Int = 2,
        includeMontMinimum: Boolean = true,
    ): BartGtfsNetwork {
        val montRule = when {
            includeMontMinimum -> "MONT,MONT,$montTransferType,90,1,12\n"
            montTransferType == 1 -> "MONT,MONT,1,,1,12\n"
            else -> ""
        }
        val files = mutableMapOf(
            "stops.txt" to "stop_id,stop_name,zone_id\nLAKE,Lake Merritt,LAKE\nMONT,Montgomery St.,MONT\nDALY,Daly City,DALY\nRICH,Richmond,RICH\n",
            "routes.txt" to "route_id,route_short_name\n1,Yellow-N\n12,Blue-N\n7,Red-N\n",
            "trips.txt" to "route_id,service_id,trip_id\n1,weekday,yellow\n12,weekday,blue\n7,weekday,red\n",
            "stop_times.txt" to "trip_id,stop_id,stop_sequence\nyellow,LAKE,1\nyellow,MONT,2\nblue,MONT,1\nblue,DALY,2\nred,DALY,1\nred,RICH,2\n",
            "transfers.txt" to "from_stop_id,to_stop_id,transfer_type,min_transfer_time,from_route_id,to_route_id\n${montRule}DALY,DALY,2,30,12,7\n",
        )
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files))
    }
}
