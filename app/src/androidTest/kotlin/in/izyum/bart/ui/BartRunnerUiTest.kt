package `in`.izyum.bart.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.izyum.bart.activities.DeparturesViewModel
import `in`.izyum.bart.activities.RoutesListActivity
import `in`.izyum.bart.activities.RoutesUiState
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.networktasks.RiderCategory
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
    fun homeScreenShowsPlanTripShortcutInAppBar() {
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

        composeRule.onNodeWithContentDescription("Plan a trip").performClick()
        composeRule.onNodeWithText("From").assertIsDisplayed()
        composeRule.onNodeWithText("To").assertIsDisplayed()
    }

    @Test
    fun homeScreenShowsOfflineWarningInsteadOfNoDelays() {
        setTestContent {
            BartRunnerTheme {
                HomeScreen(
                    state = RoutesUiState(isLoading = false, isOffline = true),
                    isOffline = true,
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

        composeRule.onNodeWithText(
            "Offline — using stored schedules. Realtime updates and service alerts are unavailable.",
        ).assertIsDisplayed()
    }

    @Test
    fun homeScreenOpensFareDiscountSettingFromOverflowMenu() {
        setTestContent {
            var selectedId by remember { mutableStateOf<String?>(null) }
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
                    fareDiscountId = selectedId,
                    fareDiscountOptions = listOf(RiderCategory("5", "Youth Clipper")),
                    onFareDiscountChanged = { selectedId = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Fare discount").assertIsDisplayed()
        composeRule.onNodeWithText("None").performClick()
        composeRule.onNodeWithText("Youth Clipper").performClick()
        composeRule.onNodeWithText("Youth Clipper").assertIsDisplayed()
    }

    @Test
    fun homeScreenOpensElevatorStatusPopup() {
        setTestContent {
            BartRunnerTheme {
                HomeScreen(
                    state = RoutesUiState(
                        isLoading = false,
                        elevatorDescription = "There is 1 elevator out of service at this time.",
                    ),
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

        composeRule.onNodeWithContentDescription("Elevator status").performClick()
        composeRule.onNodeWithText("There is 1 elevator out of service at this time.").assertIsDisplayed()
    }

    @Test
    fun aboutScreenShowsBuildAndCopyrightInformation() {
        setTestContent {
            BartRunnerTheme {
                AboutScreen(
                    versionName = "2.2.21",
                    gitBuildHash = "abc123",
                    onBack = {},
                    onOpenGithub = {},
                    onOpenLicenses = {},
                    onFeedback = {},
                )
            }
        }

        composeRule.onNodeWithText("Version 2.2.21").assertIsDisplayed()
        composeRule.onNodeWithText("Build ID: abc123").assertIsDisplayed()
        composeRule.onNodeWithText("A streamlined BART companion that delivers real-time departures, arrival predictions, fares, offline maps, and alerts for your regular routes.").assertIsDisplayed()
        composeRule.onNodeWithText("Apache 2.0", substring = true).assertExists()
        composeRule.onNodeWithText("Copyright © Igor Izyumin 2026").assertIsDisplayed()
        composeRule.onNodeWithText("Copyright © Doug Keen 2012–2026").assertIsDisplayed()
        composeRule.onNodeWithText("Open source licenses").assertIsDisplayed()
        composeRule.onNodeWithText("Feedback").assertIsDisplayed()
    }

    @Test
    fun tripScreenShowsRouteAndFollowControl() {
        setTestContent {
            BartRunnerTheme {
                TripScreen(
                    departure = testDeparture(),
                    route = `in`.izyum.bart.model.StationPair(Station.CAST, Station.MLPT),
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
                    route = `in`.izyum.bart.model.StationPair(Station.CAST, Station.MLPT),
                    state = DeparturesViewModel.State.empty(),
                    timeSource = timeSource,
                    fare = null,
                    onBack = {},
                    onOpenTrip = {},
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
                    route = `in`.izyum.bart.model.StationPair(Station.CAST, Station.MLPT),
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
