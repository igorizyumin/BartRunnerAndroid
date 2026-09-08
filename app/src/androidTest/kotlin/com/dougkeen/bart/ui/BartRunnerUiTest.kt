package com.dougkeen.bart.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dougkeen.bart.activities.DeparturesViewModel
import com.dougkeen.bart.activities.RoutesListActivity
import com.dougkeen.bart.activities.RoutesUiState
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.Line
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.model.TripLeg
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BartRunnerUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<RoutesListActivity>()

    private val timeSource = TimeSource { System.currentTimeMillis() }

    @Test
    fun homeScreenShowsEmptyFavoritesAndPlanAction() {
        setTestContent {
            BartRunnerTheme {
                HomeScreen(
                    state = RoutesUiState(isLoading = false),
                    followedTrip = null,
                    timeSource = timeSource,
                    onRouteSelected = {},
                    onAddFavorite = {},
                    onRemoveFavorite = {},
                    onMoveFavorite = { _, _ -> },
                    onInsertFavorite = { _, _ -> },
                    onViewTrip = {},
                    onViewMap = {},
                )
            }
        }

        composeRule.onNodeWithText("Saved trips").assertIsDisplayed()
        composeRule.onNodeWithText("Your favorite trips will appear here").assertIsDisplayed()
        composeRule.onNodeWithText("Plan a trip").assertIsDisplayed()
    }

    @Test
    fun tripScreenShowsRouteAndFollowControl() {
        setTestContent {
            BartRunnerTheme {
                TripScreen(
                    departure = testDeparture(),
                    route = com.dougkeen.bart.model.StationPair(Station.CAST, Station.MLPT),
                    isFollowingInitially = false,
                    alarmVisible = false,
                    timeSource = timeSource,
                    alarmPending = false,
                    alarmLeadTimeMinutes = 0,
                    onBack = {},
                    onFollow = {},
                    onSetAlarm = {},
                    onCancelAlarm = {},
                    onClear = {},
                    onShare = {},
                    onSilenceAlarm = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Castro Valley → Milpitas").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Follow this trip").assertIsDisplayed()
    }

    @Test
    fun routePickerShowsOriginDestinationAndReturnOption() {
        setTestContent {
            BartRunnerTheme {
                RoutePickerDialog(
                    title = "Save a trip",
                    showReturn = true,
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("From").assertIsDisplayed()
        composeRule.onNodeWithText("To").assertIsDisplayed()
        composeRule.onNodeWithText("Also save the return trip").assertIsDisplayed()
    }

    @Test
    fun departuresScreenShowsEmptyStateFromComposeState() {
        setTestContent {
            BartRunnerTheme {
                DeparturesScreen(
                    route = com.dougkeen.bart.model.StationPair(Station.CAST, Station.MLPT),
                    state = DeparturesViewModel.State.empty(),
                    timeSource = timeSource,
                    onBack = {},
                    onOpenTrip = {},
                    onFollowTrip = {},
                    onMap = {},
                )
            }
        }

        composeRule.onNodeWithText("No departures found").assertIsDisplayed()
    }

    @Test
    fun followedTripShowsAlarmPickerControl() {
        setTestContent {
            BartRunnerTheme {
                TripScreen(
                    departure = testDeparture(),
                    route = com.dougkeen.bart.model.StationPair(Station.CAST, Station.MLPT),
                    isFollowingInitially = true,
                    alarmVisible = false,
                    timeSource = timeSource,
                    alarmPending = false,
                    alarmLeadTimeMinutes = 0,
                    onBack = {},
                    onFollow = {},
                    onSetAlarm = {},
                    onCancelAlarm = {},
                    onClear = {},
                    onShare = {},
                    onSilenceAlarm = {},
                )
            }
        }

        composeRule.onNodeWithText("Set alarm").performClick()
        composeRule.onNodeWithText("Set departure alarm").assertIsDisplayed()
    }

    @Test
    fun systemMapExposesZoomControls() {
        setTestContent {
            BartRunnerTheme { SystemMapScreen(onBack = {}) }
        }

        composeRule.onNodeWithContentDescription("Zoom in").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Zoom out").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Reset map zoom").assertIsDisplayed()
    }

    private fun testDeparture(): Departure {
        val departureTime = System.currentTimeMillis() + 10 * 60 * 1000L
        val arrivalTime = departureTime + 30 * 60 * 1000L
        return Departure.builder()
            .setOrigin(Station.CAST)
            .setTrainDestination(Station.MLPT)
            .setPassengerDestination(Station.MLPT)
            .setLine(Line.ORANGE)
            .setTrainDestinationColorHex("#ff8c00")
            .setDirection("instrumented-test")
            .setMinEstimate(departureTime - 30_000L)
            .setMaxEstimate(departureTime + 30_000L)
            .setTripLegs(listOf(TripLeg(
                Line.ORANGE, Station.CAST, Station.MLPT, Station.MLPT,
                "instrumented-test-trip", departureTime, arrivalTime, emptyList(),
            )))
            .build()
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.runOnUiThread {
            composeRule.activity.setContent { content() }
        }
    }

}
