package com.dougkeen.bart.activities;

import android.content.Context;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.Lifecycle;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.R;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.TripLeg;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class TripInProgressActivityTest {
    private BartRunnerApplication application;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        application = (BartRunnerApplication) context;
        application.setActivityTimestamp(System.currentTimeMillis());
        application.getFollowedTripRepository().clearFollowedDeparture();
    }

    @After
    public void tearDown() {
        application.getFollowedTripRepository().clearFollowedDeparture();
    }

    @Test
    public void followedTripScreenRestoresItsRouteAfterRecreation() {
        application.getFollowedTripRepository().setFollowedDeparture(makeDeparture());

        try (ActivityScenario<TripInProgressActivity> scenario =
                     ActivityScenario.launch(TripInProgressActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertRouteIsVisible(scenario);

            scenario.recreate();

            assertRouteIsVisible(scenario);
        }
    }

    private void assertRouteIsVisible(ActivityScenario<TripInProgressActivity> scenario) {
        scenario.onActivity(activity -> assertEquals(
                "Castro Valley → Milpitas",
                ((TextView) activity.findViewById(R.id.tripRoute)).getText().toString()));
    }

    private Departure makeDeparture() {
        long departureTime = System.currentTimeMillis() + 10 * 60 * 1000L;
        long arrivalTime = departureTime + 30 * 60 * 1000L;

        return Departure.builder()
                .setOrigin(Station.CAST)
                .setTrainDestination(Station.MLPT)
                .setPassengerDestination(Station.MLPT)
                .setLine(Line.ORANGE)
                .setTrainDestinationColorHex("#ff8c00")
                .setDirection("test")
                .setMinEstimate(departureTime - 30_000L)
                .setMaxEstimate(departureTime + 30_000L)
                .setTripLegs(Collections.singletonList(new TripLeg(
                        Line.ORANGE,
                        Station.CAST,
                        Station.MLPT,
                        Station.MLPT,
                        "instrumented-test-trip",
                        departureTime,
                        arrivalTime,
                        Collections.emptyList())))
                .build();
    }
}
