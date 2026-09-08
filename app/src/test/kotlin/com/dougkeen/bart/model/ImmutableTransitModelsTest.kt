package com.dougkeen.bart.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImmutableTransitModelsTest {
    @Test
    fun alertValuesAndAlertListsAreImmutable() {
        val alert = Alert("alert-1", "DETOUR", "Description", 1000L, 2000L)
        val alerts = Alert.AlertList(listOf(alert), false)

        assertEquals("alert-1", alert.id)
        assertEquals("DETOUR", alert.type)
        assertEquals("Description", alert.description)
        assertEquals(1, alerts.getAlerts().size)
        assertThrows(UnsupportedOperationException::class.java) {
            (alerts.getAlerts() as MutableList<Alert>).clear()
        }
    }
}
