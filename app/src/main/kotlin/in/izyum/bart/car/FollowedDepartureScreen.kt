package `in`.izyum.bart.car

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.R
import `in`.izyum.bart.model.Departure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Screen rendering full-screen departure & platform info for a followed ride in Android Auto.
 * Displays "X mins to departure" countdown at the top level of the row in large font.
 * Automatically shown when a ride is followed on handset or selected in Android Auto.
 */
class FollowedDepartureScreen(
    carContext: CarContext,
) : Screen(carContext), DefaultLifecycleObserver {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifiedMilestones = mutableSetOf<String>()
    private var observerJob: Job? = null

    private var _audioGuidanceManager: AudioGuidanceManager? = null
    private fun getAudioGuidanceManager(): AudioGuidanceManager {
        if (_audioGuidanceManager == null) {
            _audioGuidanceManager = AudioGuidanceManager(carContext)
        }
        return _audioGuidanceManager!!
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            runCatching { invalidate() }
            mainHandler.postDelayed(this, TICKER_INTERVAL_MILLIS)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        val app = carContext.applicationContext as? BartRunnerApplication ?: return
        app.transitRepository.setAppInForeground(true)
        runCatching { app.transitRepository.refreshNow() }

        observerJob?.cancel()
        observerJob = lifecycleScope.launch {
            app.transitRepository.state.collectLatest {
                runCatching { invalidate() }
            }
        }

        mainHandler.removeCallbacks(tickerRunnable)
        mainHandler.postDelayed(tickerRunnable, TICKER_INTERVAL_MILLIS)
    }

    override fun onPause(owner: LifecycleOwner) {
        observerJob?.cancel()
        observerJob = null
        mainHandler.removeCallbacks(tickerRunnable)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        mainHandler.removeCallbacks(tickerRunnable)
        _audioGuidanceManager?.shutdown()
        _audioGuidanceManager = null
    }

    override fun onGetTemplate(): Template {
        return runCatching { buildTemplate() }.getOrElse { error ->
            Log.e("FollowedDepartureScreen", "Error building template", error)
            buildFallbackTemplate()
        }
    }

    private fun buildTemplate(): Template {
        val app = carContext.applicationContext as? BartRunnerApplication
            ?: return buildFallbackTemplate()

        val departure = app.followedTripRepository.getFollowedDeparture()
            ?: return buildNoFollowedRideTemplate()

        val nowMillis = app.timeSource.nowMillis()
        val minutesLeft = getMinutesLeft(departure, nowMillis)
        val isAudioGuidanceEnabled = CarPreferences.getDefaultAudioGuidance(carContext)

        val listBuilder = ItemList.Builder()

        // Row 1: Primary Countdown (Top-level Big Font) & Train Destination
        val lineName = departure.line?.getDisplayName() ?: "BART Train"
        val destinationName = departure.getTrainDestinationName() ?: "Destination"
        val countdownText = formatCountdownText(minutesLeft)
        val routeSubtitle = "${departure.origin?.getName() ?: "Origin"} → ${departure.passengerDestination?.getName() ?: destinationName}"

        listBuilder.addItem(
            Row.Builder()
                .setTitle(countdownText)
                .addText("$lineName to $destinationName")
                .addText(routeSubtitle)
                .build(),
        )

        // Row 2: Boarding Platform & Train Specs
        val platformText = departure.platform?.let { "Platform $it" } ?: "Platform Info Pending"
        val carLengthText = departure.trainLength?.let { "$it Cars" } ?: "Standard Train"
        val transferText = if (departure.requiresTransfer || departure.hasTransfers()) "Transfer Required" else "Direct Route"

        listBuilder.addItem(
            Row.Builder()
                .setTitle(platformText)
                .addText("$carLengthText • $transferText")
                .build(),
        )

        // Row 3: Estimated Arrival / Trip Info
        val arrivalMins = departure.getEstimatedArrivalMinutesLeft(nowMillis)
        val arrivalText = if (arrivalMins > 0) "Est. Trip Time: $arrivalMins mins" else "Realtime tracking active"
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Trip Status")
                .addText(arrivalText)
                .build(),
        )

        // Row 4: Stop Following Action Row
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Stop Following Ride")
                .addText("Tap to stop tracking this trip and return to main menu")
                .setOnClickListener {
                    runCatching {
                        app.followedTripRepository.clearFollowedDeparture()
                        navigateBackToMainMenu()
                    }
                }
                .build(),
        )

        runCatching {
            checkMilestoneAlerts(departure, minutesLeft, isAudioGuidanceEnabled)
        }

        val refreshIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_refresh)
        ).build()

        val header = Header.Builder()
            .setTitle("Followed Ride")
            .setStartHeaderAction(Action.BACK)
            .addEndHeaderAction(
                Action.Builder()
                    .setIcon(refreshIcon)
                    .setTitle("Refresh")
                    .setOnClickListener {
                        runCatching { app.transitRepository.refreshNow() }
                        runCatching { invalidate() }
                    }
                    .build(),
            )
            .build()

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun navigateBackToMainMenu() {
        runCatching {
            if (screenManager.stackSize > 1) {
                screenManager.pop()
            } else {
                screenManager.push(SavedRoutePickerScreen(carContext))
            }
        }
    }

    private fun buildNoFollowedRideTemplate(): Template {
        val header = Header.Builder()
            .setTitle("Followed Ride")
            .setStartHeaderAction(Action.BACK)
            .build()

        val listBuilder = ItemList.Builder().addItem(
            Row.Builder()
                .setTitle("No Active Ride Followed")
                .addText("Select a train ride to follow it in Android Auto.")
                .build(),
        )

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun buildFallbackTemplate(): Template {
        val refreshIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_refresh)
        ).build()

        val header = Header.Builder()
            .setTitle("Followed Ride")
            .setStartHeaderAction(Action.BACK)
            .addEndHeaderAction(
                Action.Builder()
                    .setIcon(refreshIcon)
                    .setTitle("Refresh")
                    .setOnClickListener {
                        val app = carContext.applicationContext as? BartRunnerApplication
                        runCatching { app?.transitRepository?.refreshNow() }
                        runCatching { invalidate() }
                    }
                    .build(),
            )
            .build()

        val listBuilder = ItemList.Builder().addItem(
            Row.Builder()
                .setTitle("Followed Ride Details")
                .addText("Unable to load ride info.")
                .build(),
        )

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun checkMilestoneAlerts(departure: Departure, minutesLeft: Int, isAudioGuidanceEnabled: Boolean) {
        val trainKey = departure.identity
        val milestoneKey = "${trainKey}_$minutesLeft"

        if ((minutesLeft in listOf(5, 2, 0)) && !notifiedMilestones.contains(milestoneKey)) {
            notifiedMilestones.add(milestoneKey)

            val destinationName = departure.getTrainDestinationName() ?: "Destination"
            val platformText = departure.platform?.let { " on platform $it" } ?: ""

            val announcement = when (minutesLeft) {
                0 -> "Train to $destinationName is boarding now$platformText"
                1 -> "Train to $destinationName: 1 minute to departure"
                else -> "Train to $destinationName: $minutesLeft minutes to departure"
            }

            CarNotificationHelper.postDepartureMilestoneNotification(
                carContext,
                "${departure.origin?.abbreviation ?: "BART"} Departure Alert",
                announcement,
            )

            getAudioGuidanceManager().speakAnnouncement(announcement, isAudioGuidanceEnabled)
        }
    }

    private fun getMinutesLeft(departure: Departure, nowMillis: Long): Int {
        val seconds = departure.getMeanSecondsLeft(nowMillis)
        return (seconds / 60).coerceAtLeast(0)
    }

    private fun formatCountdownText(minutesLeft: Int): String {
        return when {
            minutesLeft <= 0 -> "Boarding Now"
            minutesLeft == 1 -> "1 min to departure"
            else -> "$minutesLeft mins to departure"
        }
    }

    companion object {
        private const val TICKER_INTERVAL_MILLIS = 30_000L
    }
}
