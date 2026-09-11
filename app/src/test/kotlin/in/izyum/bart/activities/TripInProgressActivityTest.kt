package `in`.izyum.bart.activities

import `in`.izyum.bart.model.Departure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripInProgressActivityTest {
    @Test
    fun followedModeReturnsToRoutesWhenTheFollowedTripWasClearedAsStale() {
        assertTrue(
            TripInProgressActivity.shouldReturnToRoutes(
                RouteArguments.MODE_FOLLOWED,
                null,
            ),
        )
    }

    @Test
    fun scheduleModeStillAllowsTheRequestedTripWithoutAFollowedTrip() {
        assertFalse(
            TripInProgressActivity.shouldReturnToRoutes(
                RouteArguments.MODE_SCHEDULE,
                null,
            ),
        )
    }

    @Test
    fun followedModeStillShowsAnAvailableFollowedTrip() {
        assertFalse(
            TripInProgressActivity.shouldReturnToRoutes(
                RouteArguments.MODE_FOLLOWED,
                Departure.builder().build(),
            ),
        )
    }
}
