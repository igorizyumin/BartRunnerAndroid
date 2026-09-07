package com.dougkeen.bart.activities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.R;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.StationPair;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ViewDeparturesActivityTest {
    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        ((BartRunnerApplication) context).setActivityTimestamp(
                System.currentTimeMillis());
    }

    @Test
    public void recreationRestoresRouteQueryWithoutDepartureSnapshot() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, ViewDeparturesActivity.class);
        RouteArguments.putRoute(intent, new StationPair(
                Station.CAST, Station.MLPT));

        try (ActivityScenario<ViewDeparturesActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertTitle(scenario, "Castro Valley to Milpitas");

            Bundle savedState = new Bundle();
            scenario.onActivity(activity -> activity.onSaveInstanceState(savedState));
            assertTrue(savedState.containsKey(RouteArguments.ORIGIN));
            assertFalse(savedState.containsKey("departures"));

            scenario.recreate();
            assertTitle(scenario, "Castro Valley to Milpitas");
        }
    }

    private void assertTitle(ActivityScenario<ViewDeparturesActivity> scenario,
                              String expected) {
        scenario.onActivity(activity -> assertEqualsText(
                expected,
                ((TextView) activity.findViewById(R.id.listTitle)).getText().toString()));
    }

    private void assertEqualsText(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected title '" + expected
                    + "' but was '" + actual + "'");
        }
    }
}
