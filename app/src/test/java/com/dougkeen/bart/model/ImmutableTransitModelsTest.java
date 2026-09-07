package com.dougkeen.bart.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;

import org.junit.Test;

public class ImmutableTransitModelsTest {
    @Test
    public void alertValuesAndAlertListsAreImmutable() {
        Alert alert = new Alert("alert-1", "DETOUR", "Description",
                "posted", "expires");
        Alert.AlertList alerts = new Alert.AlertList(
                Arrays.asList(alert), false);

        assertEquals("alert-1", alert.getId());
        assertEquals("DETOUR", alert.getType());
        assertEquals("Description", alert.getDescription());
        assertEquals(1, alerts.getAlerts().size());
        assertThrows(UnsupportedOperationException.class,
                () -> alerts.getAlerts().clear());
    }

    @Test
    public void scheduleValuesAndContainersAreImmutable() {
        ScheduleItem trip = new ScheduleItem(
                Station.MONT,
                Station.POWL,
                "$3.00",
                1_000L,
                2_000L,
                true,
                "powl");
        ScheduleInformation schedule = new ScheduleInformation(
                Station.MONT,
                Station.POWL,
                3_000L,
                Arrays.asList(trip));

        assertEquals(1_000, trip.getTripLength());
        assertEquals(3_000L, schedule.getDate());
        assertEquals(1, schedule.getTrips().size());
        assertThrows(UnsupportedOperationException.class,
                () -> schedule.getTrips().clear());
    }
}
