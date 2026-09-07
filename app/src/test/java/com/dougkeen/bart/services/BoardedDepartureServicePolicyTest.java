package com.dougkeen.bart.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BoardedDepartureServicePolicyTest {
    @Test
    public void serviceStopsOnlyWhenThereIsNoDepartureOrItHasDeparted() {
        assertTrue(BoardedDepartureServicePolicy.shouldStop(false, false));
        assertTrue(BoardedDepartureServicePolicy.shouldStop(true, true));
        assertFalse(BoardedDepartureServicePolicy.shouldStop(true, false));
    }

    @Test
    public void pollingGetsFasterAtTheAlarmWindow() {
        assertEquals(15_000,
                BoardedDepartureServicePolicy.pollIntervalMillis(181));
        assertEquals(6_000,
                BoardedDepartureServicePolicy.pollIntervalMillis(180));
        assertEquals(6_000,
                BoardedDepartureServicePolicy.pollIntervalMillis(-10));
    }
}
