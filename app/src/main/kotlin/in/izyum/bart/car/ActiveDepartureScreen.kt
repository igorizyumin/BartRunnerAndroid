package `in`.izyum.bart.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.backend.RouteDepartureProjection
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.StationPair

/**
 * Screen rendering live train departure countdowns in Android Auto.
 * Uses PaneTemplate to fit cleanly into split-screen (Coolwalk) dashboard layouts
 * alongside media players.
 *
 * Updates via invalidate() on a 30-second ticker to adhere to Car App Quality Guidelines
 * and avoid driver distraction lockouts.
 */
class ActiveDepartureScreen(
    carContext: CarContext,
    private val stationPair: StationPair,
) : Screen(carContext), DefaultLifecycleObserver {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifiedMilestones = mutableSetOf<String>()
    private val audioGuidanceManager by lazy { AudioGuidanceManager(carContext) }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            invalidate()
            mainHandler.postDelayed(this, TICKER_INTERVAL_MILLIS)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        val app = carContext.applicationContext as BartRunnerApplication
        if (app.transitRepository.getLatestSnapshot() == null) {
            app.transitRepository.refreshNow()
        }
        mainHandler.removeCallbacks(tickerRunnable)
        mainHandler.postDelayed(tickerRunnable, TICKER_INTERVAL_MILLIS)
    }

    override fun onPause(owner: LifecycleOwner) {
        mainHandler.removeCallbacks(tickerRunnable)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        mainHandler.removeCallbacks(tickerRunnable)
        audioGuidanceManager.shutdown()
    }

    override fun onGetTemplate(): Template {
        val app = carContext.applicationContext as BartRunnerApplication
        var snapshot = app.transitRepository.getLatestSnapshot()
        if (snapshot == null) {
            app.transitRepository.refreshNow()
            snapshot = app.transitRepository.getLatestSnapshot()
        }
        val showTransfers = CarPreferences.getShowTransfersForRoute(carContext, stationPair)
        val isAudioGuidanceEnabled = CarPreferences.getDefaultAudioGuidance(carContext)

        val allDepartures = if (snapshot != null) {
            val projection = RouteDepartureProjection(stationPair, app.bartGtfsNetworkSupplier)
            projection.project(snapshot).getDepartures()
        } else {
            emptyList()
        }

        val departures = if (showTransfers) {
            allDepartures
        } else {
            allDepartures.filter { !it.requiresTransfer && !it.hasTransfers() }
        }

        val paneBuilder = Pane.Builder()

        if (departures.isEmpty()) {
            val emptyMessage = if (allDepartures.isNotEmpty()) {
                "No direct trains found. Toggle 'Include Transfers' to see connecting routes."
            } else {
                "Connecting to BART realtime feeds..."
            }
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("No Matching Departures")
                    .addText(emptyMessage)
                    .build(),
            )
        } else {
            val primaryDeparture = departures.first()
            val minutesLeft = getMinutesLeft(primaryDeparture, app.timeSource.nowMillis())

            // Primary departure row
            val titleText = "${primaryDeparture.line?.name ?: "Train"} to ${primaryDeparture.getTrainDestinationName() ?: "Destination"}"
            val subtitleText = buildString {
                append(formatCountdownText(minutesLeft))
                if (primaryDeparture.requiresTransfer || primaryDeparture.hasTransfers()) {
                    append(" • Transfer Req")
                }
                primaryDeparture.platform?.let { append(" • Platform $it") }
                primaryDeparture.trainLength?.let { append(" • $it cars") }
            }

            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(titleText)
                    .addText(subtitleText)
                    .build(),
            )

            // Following train row
            if (departures.size > 1) {
                val nextDeparture = departures[1]
                val nextMinutes = getMinutesLeft(nextDeparture, app.timeSource.nowMillis())
                val transferSuffix = if (nextDeparture.requiresTransfer || nextDeparture.hasTransfers()) " (Transfer)" else ""
                paneBuilder.addRow(
                    Row.Builder()
                        .setTitle("Next Train")
                        .addText("${nextDeparture.getTrainDestinationName() ?: "Train"}$transferSuffix: ${formatCountdownText(nextMinutes)}")
                        .build(),
                )
            }

            // Check milestone notifications & audio ducking announcements
            checkMilestoneAlerts(primaryDeparture, minutesLeft, isAudioGuidanceEnabled)
        }

        // Action button 1: Toggle transfer filtering for this route
        val transferToggleTitle = if (showTransfers) "Direct Only" else "Include Transfers"
        paneBuilder.addAction(
            Action.Builder()
                .setTitle(transferToggleTitle)
                .setOnClickListener {
                    CarPreferences.setShowTransfersForRoute(carContext, stationPair, !showTransfers)
                    invalidate()
                }
                .build(),
        )

        // Action button 2: Toggle audio guidance (max 2 actions per Pane in Android Auto)
        paneBuilder.addAction(
            Action.Builder()
                .setTitle(if (isAudioGuidanceEnabled) "Mute Guidance" else "Unmute Guidance")
                .setOnClickListener {
                    CarPreferences.setDefaultAudioGuidance(carContext, !isAudioGuidanceEnabled)
                    invalidate()
                }
                .build(),
        )

        val routeTitle = "${stationPair.origin?.getName() ?: "Origin"} → ${stationPair.destination?.getName() ?: "All"}"

        val header = Header.Builder()
            .setTitle(routeTitle)
            .setStartHeaderAction(Action.BACK)
            .addEndHeaderAction(
                Action.Builder()
                    .setTitle("Refresh")
                    .setOnClickListener {
                        app.transitRepository.refreshNow()
                        invalidate()
                    }
                    .build(),
            )
            .build()

        return PaneTemplate.Builder(paneBuilder.build())
            .setHeader(header)
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
                else -> "Train to $destinationName departs in $minutesLeft minutes"
            }

            // Post Heads-Up Notification for background display
            CarNotificationHelper.postDepartureMilestoneNotification(
                carContext,
                "${stationPair.origin?.abbreviation ?: "BART"} Departure Alert",
                announcement,
            )

            // Audio ducking speech announcement
            audioGuidanceManager.speakAnnouncement(announcement, isAudioGuidanceEnabled)
        }
    }

    private fun getMinutesLeft(departure: Departure, nowMillis: Long): Int {
        val seconds = departure.getMeanSecondsLeft(nowMillis)
        return (seconds / 60).coerceAtLeast(0)
    }

    private fun formatCountdownText(minutesLeft: Int): String {
        return when {
            minutesLeft <= 0 -> "Boarding Now"
            minutesLeft == 1 -> "Departs in 1 min"
            else -> "Departs in $minutesLeft mins"
        }
    }

    companion object {
        // 30 second ticker interval per Car App Quality Guidelines
        private const val TICKER_INTERVAL_MILLIS = 30_000L
    }
}

