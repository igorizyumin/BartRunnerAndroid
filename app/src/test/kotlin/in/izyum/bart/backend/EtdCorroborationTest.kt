package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.networktasks.EtdDeparture
import `in`.izyum.bart.networktasks.EtdLookup
import `in`.izyum.bart.networktasks.EtdStationBoard
import org.junit.Assert.assertEquals
import org.junit.Test

class EtdCorroborationTest {
    private val now = 1_000_000L

    @Test
    fun matchingEtDDepartureKeepsTrip() {
        val leg = leg(now + 10 * 60_000L)
        val board = board(now + 11 * 60_000L)

        assertEquals(
            EtdMatch.MATCHED,
            EtdCorroborator.decide(
                listOf(leg),
                mapOf(Station.MLPT to EtdLookup(board)),
            )[EtdLegKey("trip-1", Station.MLPT, Station.BERY, leg.scheduledDepartureTime)],
        )
    }

    @Test
    fun absenceIsOnlyDecisiveWhenTheBoardCoversTheCandidateTime() {
        val covered = leg(now + 10 * 60_000L)
        val notCovered = leg(now + 40 * 60_000L)
        val board = board(now + 20 * 60_000L)
        val decisions = EtdCorroborator.decide(
            listOf(covered, notCovered),
            mapOf(Station.MLPT to EtdLookup(board)),
        )

        assertEquals(
            EtdMatch.ABSENT,
            decisions[EtdLegKey("trip-1", Station.MLPT, Station.BERY, covered.scheduledDepartureTime)],
        )
        assertEquals(
            EtdMatch.UNKNOWN,
            decisions[EtdLegKey("trip-1", Station.MLPT, Station.BERY, notCovered.scheduledDepartureTime)],
        )
    }

    @Test
    fun emptySuccessfulBoardSuppressesCandidateInSuspiciousWindow() {
        val leg = leg(now + 10 * 60_000L)
        val emptyBoard = EtdStationBoard(Station.MLPT, now, emptyList())

        assertEquals(
            EtdMatch.ABSENT,
            EtdCorroborator.decide(
                listOf(leg),
                mapOf(Station.MLPT to EtdLookup(emptyBoard)),
            )[EtdLegKey("trip-1", Station.MLPT, Station.BERY, leg.scheduledDepartureTime)],
        )
    }

    @Test
    fun failedBoardDoesNotSuppressCandidate() {
        val leg = leg(now + 10 * 60_000L)

        assertEquals(
            EtdMatch.UNKNOWN,
            EtdCorroborator.decide(
                listOf(leg),
                mapOf(Station.MLPT to EtdLookup(null, IllegalStateException("offline"))),
            )[EtdLegKey("trip-1", Station.MLPT, Station.BERY, leg.scheduledDepartureTime)],
        )
    }

    @Test
    fun legacyCancellationIsASeparateMatch() {
        val leg = leg(now + 10 * 60_000L)
        val canceled = EtdStationBoard(
            Station.MLPT,
            now,
            listOf(
                EtdDeparture(
                    Station.BERY,
                    Line.ORANGE,
                    leg.scheduledDepartureTime,
                    "1",
                    "South",
                    true,
                )
            ),
        )

        assertEquals(
            EtdMatch.CANCELED,
            EtdCorroborator.decide(
                listOf(leg),
                mapOf(Station.MLPT to EtdLookup(canceled)),
            )[EtdLegKey("trip-1", Station.MLPT, Station.BERY, leg.scheduledDepartureTime)],
        )
    }

    private fun leg(departureTime: Long) = TripLeg(
        line = Line.ORANGE,
        origin = Station.MLPT,
        destination = Station.BERY,
        trainDestination = Station.BERY,
        tripId = "trip-1",
        departureTime = departureTime,
        arrivalTime = departureTime + 5 * 60_000L,
        stops = emptyList(),
        scheduledDepartureTime = departureTime,
        scheduledArrivalTime = departureTime + 5 * 60_000L,
    )

    private fun board(departureTime: Long) = EtdStationBoard(
        Station.MLPT,
        now,
        listOf(EtdDeparture(Station.BERY, Line.ORANGE, departureTime, "1", "South", false)),
    )
}
