package `in`.izyum.bart.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.TripStop

class DepartureAlarmPolicyTest {
    @Test
    fun onlyValidPendingAlarmsAreRestored() {
        assertTrue(DepartureAlarmPolicy.shouldRestore(true, false, false))
        assertFalse(DepartureAlarmPolicy.shouldRestore(false, false, false))
        assertFalse(DepartureAlarmPolicy.shouldRestore(true, true, false))
        assertFalse(DepartureAlarmPolicy.shouldRestore(true, false, true))
    }

    @Test
    fun alarmTimeAndCountdownUseTheSameLeadTimeCalculation() {
        val latestEstimate = 1_030_000L
        val now = 700_000L
        assertEquals(730_000L, DepartureAlarmPolicy.alarmTime(latestEstimate, 5))
        assertEquals(30, DepartureAlarmPolicy.secondsUntilAlarm(latestEstimate, 5, now))
        assertEquals(-30, DepartureAlarmPolicy.secondsUntilAlarm(latestEstimate, 6, now))
    }

    @Test
    fun alarmUsesTheSameInitialArrivalTimeAsTheDisplayCountdown() {
        val departure = Departure.builder()
            .setOrigin(Station.CAST)
            .setMinEstimate(1_000_000L)
            .setMaxEstimate(1_060_000L)
            .setTripLegs(listOf(TripLeg(
                Line.ORANGE,
                Station.CAST,
                Station.MLPT,
                Station.MLPT,
                "trip-1",
                1_000_000L,
                2_000_000L,
                listOf(TripStop(Station.CAST, 940_000L, 1_000_000L)),
            )))
            .build()

        assertEquals(610_000L, DepartureAlarmPolicy.alarmTime(departure, 5))
    }

    @Test
    fun pollingCadenceMovesTowardTheAlarmAndIsBounded() {
        assertEquals(
            29 * 60_000L,
            DepartureAlarmPolicy.nextPollingDelayMillis(60 * 60_000L),
        )
        assertEquals(
            4 * 60_000L,
            DepartureAlarmPolicy.nextPollingDelayMillis(10 * 60_000L),
        )
        assertEquals(
            DepartureAlarmPolicy.MIN_POLLING_DELAY_MILLIS,
            DepartureAlarmPolicy.nextPollingDelayMillis(90_000L),
        )
    }
}
