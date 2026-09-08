package com.dougkeen.bart.activities

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.StationPair
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewDeparturesActivityTest {
    @Before
    fun setUp() {
        val application: BartRunnerApplication = ApplicationProvider.getApplicationContext()
        application.activityTimestamp = System.currentTimeMillis()
    }

    @Test
    fun recreationRestoresRouteQueryWithoutDepartureSnapshot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, ViewDeparturesActivity::class.java)
        RouteArguments.putRoute(intent, StationPair(Station.CAST, Station.MLPT))
        ActivityScenario.launch<ViewDeparturesActivity>(intent).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTitle(scenario, "Castro Valley to Milpitas")
            val savedState = Bundle()
            scenario.onActivity { activity -> activity.onSaveInstanceState(savedState) }
            assertTrue(savedState.containsKey(RouteArguments.ORIGIN))
            assertFalse(savedState.containsKey("departures"))
            scenario.recreate()
            assertTitle(scenario, "Castro Valley to Milpitas")
        }
    }

    private fun assertTitle(scenario: ActivityScenario<ViewDeparturesActivity>, expected: String) {
        scenario.onActivity { activity ->
            val actual = activity.findViewById<TextView>(R.id.listTitle).text.toString()
            if (expected != actual) throw AssertionError("Expected title '$expected' but was '$actual'")
        }
    }
}
