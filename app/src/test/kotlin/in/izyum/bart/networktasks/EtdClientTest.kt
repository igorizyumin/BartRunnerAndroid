package `in`.izyum.bart.networktasks

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtdClientTest {
    @Test
    fun parsesStationBoardsAndLeavingEstimates() {
        val board = HttpEtdClient.parseBoard(
            Station.MLPT,
            1_000L,
            """
                {"root":{"station":{"abbr":"MLPT","etd":
                [{"destination":"Berryessa","abbreviation":"BERY","color":"ORANGE",
                "estimate":[{"minutes":"Leaving","platform":"1","direction":"South","cancelflag":"0"},
                {"minutes":"12","platform":"1","direction":"South","cancelflag":"1"}]}]}}}
            """.trimIndent(),
        )

        assertEquals(Station.MLPT, board.station)
        assertEquals(2, board.departures.size)
        assertEquals(Line.ORANGE, board.departures[0].line)
        assertEquals(1_000L, board.departures[0].departureTimeMillis)
        assertTrue(board.departures[1].canceled)
        assertEquals(
            2,
            board.departuresFor(Line.ORANGE, Station.BERY).size,
        )
    }

    @Test
    fun parsesCapturedAntiochBoardAndItsSfoAlias() {
        val json = checkNotNull(
            javaClass.getResourceAsStream(
                "/etd/bart_etd_antioch_live_20260911_153326.json"
            )
        ).bufferedReader().use { it.readText() }
        val board = HttpEtdClient.parseBoard(
            Station.ANTC,
            1_789_166_006_000L,
            json,
        )

        assertEquals(4, board.departures.size)
        assertEquals(Line.YELLOW, board.departures.first().line)
        assertEquals(Station.ANTC, board.departures.first().destination)
        assertEquals(3, board.departuresFor(Line.YELLOW, Station.SFIA).size)
    }
}
