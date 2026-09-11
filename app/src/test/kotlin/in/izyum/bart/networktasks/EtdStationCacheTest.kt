package `in`.izyum.bart.networktasks

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Test

class EtdStationCacheTest {
    @Test
    fun cachesUntilTheEarliestReportedDeparture() {
        var now = 1_000L
        var fetches = 0
        val client = object : EtdClient {
            override fun fetch(station: Station, receivedAtMillis: Long): EtdStationBoard {
                fetches++
                return EtdStationBoard(
                    station,
                    receivedAtMillis,
                    listOf(
                        EtdDeparture(
                            Station.BERY,
                            Line.ORANGE,
                            receivedAtMillis + 60_000L,
                            "1",
                            "South",
                            false,
                        )
                    ),
                )
            }
        }
        val cache = EtdStationCache(client, TimeSource { now })

        cache.get(Station.MLPT)
        now += 30_000L
        cache.get(Station.MLPT)
        assertEquals(1, fetches)

        now += 30_000L
        cache.get(Station.MLPT)
        assertEquals(2, fetches)
    }
}
