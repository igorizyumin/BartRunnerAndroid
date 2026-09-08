package com.dougkeen.bart.activities

import android.content.Context
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.Line
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.TripLeg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripInProgressActivityTest {
    private lateinit var application: BartRunnerApplication

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.activityTimestamp = System.currentTimeMillis()
        application.followedTripRepository.clearFollowedDeparture()
    }

    @After
    fun tearDown() {
        application.followedTripRepository.clearFollowedDeparture()
    }

    @Test
    fun followedTripScreenRestoresItsRouteAfterRecreation() {
        application.followedTripRepository.setFollowedDeparture(makeDeparture())
        ActivityScenario.launch(TripInProgressActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertRouteIsVisible(scenario)
            scenario.recreate()
            assertRouteIsVisible(scenario)
        }
    }

    private fun assertRouteIsVisible(scenario: ActivityScenario<TripInProgressActivity>) {
        scenario.onActivity { activity ->
            assertEquals("Castro Valley → Milpitas", activity.findViewById<TextView>(R.id.tripRoute).text.toString())
        }
    }

    private fun makeDeparture(): Departure {
        val departureTime = System.currentTimeMillis() + 10 * 60 * 1000L
        val arrivalTime = departureTime + 30 * 60 * 1000L
        return Departure.builder()
            .setOrigin(Station.CAST)
            .setTrainDestination(Station.MLPT)
            .setPassengerDestination(Station.MLPT)
            .setLine(Line.ORANGE)
            .setTrainDestinationColorHex("#ff8c00")
            .setDirection("test")
            .setMinEstimate(departureTime - 30_000L)
            .setMaxEstimate(departureTime + 30_000L)
            .setTripLegs(listOf(TripLeg(
                Line.ORANGE, Station.CAST, Station.MLPT, Station.MLPT,
                "instrumented-test-trip", departureTime, arrivalTime, emptyList(),
            )))
            .build()
    }
}
