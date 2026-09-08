package com.dougkeen.bart.platform

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
        val meanEstimate = 1_000_000L
        val now = 700_000L
        assertEquals(700_000L, DepartureAlarmPolicy.alarmTime(meanEstimate, 5))
        assertEquals(0, DepartureAlarmPolicy.secondsUntilAlarm(meanEstimate, 5, now))
        assertEquals(-60, DepartureAlarmPolicy.secondsUntilAlarm(meanEstimate, 6, now))
    }
}
