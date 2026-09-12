package `in`.izyum.bart.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
