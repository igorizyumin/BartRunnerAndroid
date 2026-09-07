package com.dougkeen.bart.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DepartureAlarmPolicyTest {
    @Test
    public void onlyValidPendingAlarmsAreRestored() {
        assertTrue(DepartureAlarmPolicy.INSTANCE.shouldRestore(true, false,
                false));
        assertFalse(DepartureAlarmPolicy.INSTANCE.shouldRestore(false, false,
                false));
        assertFalse(DepartureAlarmPolicy.INSTANCE.shouldRestore(true, true,
                false));
        assertFalse(DepartureAlarmPolicy.INSTANCE.shouldRestore(true, false,
                true));
    }

    @Test
    public void alarmTimeAndCountdownUseTheSameLeadTimeCalculation() {
        long meanEstimate = 1_000_000L;
        long now = 700_000L;

        assertEquals(700_000L,
                DepartureAlarmPolicy.INSTANCE.alarmTime(meanEstimate, 5));
        assertEquals(0,
                DepartureAlarmPolicy.INSTANCE.secondsUntilAlarm(meanEstimate,
                        5, now));
        assertEquals(-60,
                DepartureAlarmPolicy.INSTANCE.secondsUntilAlarm(meanEstimate,
                        6, now));
    }
}
