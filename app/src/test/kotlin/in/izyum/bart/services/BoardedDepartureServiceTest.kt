package `in`.izyum.bart.services

import `in`.izyum.bart.model.Departure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardedDepartureServiceTest {

    @Test
    fun pollingStopsWhenTheAlarmIsHandledOrTripDisappearsOrDeparts() {
        val service = BoardedDepartureService()

        assertTrue(service.shouldStopPolling(false, true, false))
        assertTrue(service.shouldStopPolling(true, false, false))
        assertTrue(service.shouldStopPolling(true, true, true))
        assertFalse(service.shouldStopPolling(true, true, false))
    }

    @Test
    fun pollingUsesFastCadenceAtOrInsideThreeMinutes() {
        val service = BoardedDepartureService()

        assertEquals(6_000L, service.pollIntervalMillisForAlarm(180))
        assertEquals(6_000L, service.pollIntervalMillisForAlarm(-1))
        assertEquals(15_000L, service.pollIntervalMillisForAlarm(181))
    }

    @Test
    fun realtimeEstimateChangesRequireARefresh() {
        val service = BoardedDepartureService()
        val previous = departureWithEstimates(100_000L, 160_000L)
        val same = departureWithEstimates(100_000L, 160_000L)
        val changed = departureWithEstimates(110_000L, 170_000L)

        assertFalse(service.shouldUpdateNotification(previous, same))
        assertTrue(service.shouldUpdateNotification(previous, changed))
    }

    @Test
    fun serviceCommandsAreExplicitActions() {
        assertNotEquals(
            BoardedDepartureService.ACTION_START_ALARM_TRACKING,
            BoardedDepartureService.ACTION_CANCEL_ALARM,
        )
        assertNotEquals(
            BoardedDepartureService.ACTION_CANCEL_ALARM,
            BoardedDepartureService.ACTION_CLEAR_DEPARTURE,
        )
    }

    private fun departureWithEstimates(minEstimate: Long, maxEstimate: Long): Departure =
        Departure.builder()
            .setMinEstimate(minEstimate)
            .setMaxEstimate(maxEstimate)
            .build()
}
