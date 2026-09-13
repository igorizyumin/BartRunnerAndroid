package `in`.izyum.bart.presentation

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DepartureTextFormatterTest {

    @Test
    fun countdownFormatsMinutesAndSecondsUnderOneHour() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = 1_000_000L
        val minEstimate = now + (15 * 60 + 30) * 1000L
        val maxEstimate = minEstimate

        val departure = createDeparture(minEstimate, maxEstimate)
        val result = DepartureTextFormatter.countdown(context, departure, now)

        assertEquals("15m 30s", result)
    }

    @Test
    fun countdownFormatsHoursMinutesAndSecondsOverOneHour() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = 1_000_000L
        // 1 hour 52 minutes 0 seconds = 6720 seconds = 6,720,000 ms
        val minEstimate = now + (1 * 3600 + 52 * 60 + 0) * 1000L
        val maxEstimate = minEstimate

        val departure = createDeparture(minEstimate, maxEstimate)
        val result = DepartureTextFormatter.countdown(context, departure, now)

        assertEquals("1h 52m 0s", result)
    }

    @Test
    fun countdownFormatsExactlyOneHour() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = 1_000_000L
        // 1 hour = 3600 seconds = 3,600,000 ms
        val minEstimate = now + 3600 * 1000L
        val maxEstimate = minEstimate

        val departure = createDeparture(minEstimate, maxEstimate)
        val result = DepartureTextFormatter.countdown(context, departure, now)

        assertEquals("1h 0m 0s", result)
    }

    private fun createDeparture(minEstimate: Long, maxEstimate: Long): Departure =
        Departure.builder()
            .setOrigin(Station.CAST)
            .setTrainDestination(Station.MLPT)
            .setPassengerDestination(Station.MLPT)
            .setLine(Line.ORANGE)
            .setDirection("north")
            .setPlatform("1")
            .setListedInETDs(true)
            .setMinEstimate(minEstimate)
            .setMaxEstimate(maxEstimate)
            .setTripLegs(listOf(TripLeg(
                Line.ORANGE, Station.CAST, Station.MLPT, Station.MLPT,
                "trip-1", minEstimate, maxEstimate, emptyList(),
            ))).build()
}
