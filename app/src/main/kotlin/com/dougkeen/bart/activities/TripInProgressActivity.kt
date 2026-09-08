package com.dougkeen.bart.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dougkeen.bart.R
import com.dougkeen.bart.databinding.TripInProgressBinding
import com.dougkeen.bart.controls.ScreenTicker
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.model.TripLeg
import com.dougkeen.bart.model.TripStop
import com.dougkeen.bart.presentation.DepartureTextFormatter
import com.dougkeen.bart.services.BoardedDepartureService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Shows the live state of a selected, possibly multi-train trip. */
class TripInProgressActivity : AbstractViewActivity() {
    companion object {
        private const val POST_NOTIFICATIONS_REQUEST_CODE = 1002
    }

    private lateinit var binding: TripInProgressBinding
    private lateinit var timeSource: TimeSource
    private var departure: Departure? = null
    private lateinit var tripProgressViewModel: TripProgressViewModel
    private lateinit var tripActionsViewModel: TripActionsViewModel
    private var isFollowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = TripInProgressBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.followTripButton.setOnClickListener { followTrip() }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val application = application as com.dougkeen.bart.BartRunnerApplication
        timeSource = application.timeSource
        tripActionsViewModel = ViewModelProvider(this)[TripActionsViewModel::class.java]
        val followedDeparture = tripActionsViewModel.getFollowedDeparture()
        val route = RouteArguments.readRoute(intent)
        var departureIdentity = RouteArguments.readDepartureIdentity(intent)
        val screenMode = RouteArguments.readScreenMode(intent)
        val effectiveRoute = if (
            route == null && followedDeparture != null &&
            (screenMode == null || screenMode == RouteArguments.MODE_FOLLOWED)
        ) {
            departureIdentity = followedDeparture.identity
            followedDeparture.getStationPair()
        } else {
            route
        }
        if (effectiveRoute == null || departureIdentity == null) {
            finish()
            return
        }

        isFollowing = followedDeparture != null && departureIdentity == followedDeparture.identity
        tripProgressViewModel = ViewModelProvider(this)[TripProgressViewModel::class.java]
        tripProgressViewModel.setQuery(effectiveRoute, departureIdentity, timeSource)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tripProgressViewModel.departureState.collectLatest { updatedDeparture ->
                    if (updatedDeparture != null) {
                        departure = updatedDeparture
                        if (isFollowing) tripActionsViewModel.updateFollowedTrip(updatedDeparture)
                        renderTrip()
                        invalidateOptionsMenu()
                    }
                }
            }
        }
        ScreenTicker(lifecycle) { renderTrip() }
        updateFollowState()
        renderTrip()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.trip_in_progress_menu, menu)
        updateMenu(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        updateMenu(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun updateMenu(menu: Menu) {
        val currentDeparture = departure ?: return
        val alarmPending = tripActionsViewModel.isAlarmPending()
        menu.findItem(R.id.cancel_alarm_button)?.isVisible = isFollowing && alarmPending
        menu.findItem(R.id.set_alarm_button)?.isVisible = isFollowing && !alarmPending &&
            currentDeparture.getMeanSecondsLeft(
                currentDeparture.minEstimate,
                currentDeparture.maxEstimate,
                timeSource.nowMillis(),
            ) > 60
        menu.findItem(R.id.delete)?.isVisible = isFollowing
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        val currentDeparture = departure ?: return super.onOptionsItemSelected(item)
        return when (item.itemId) {
            R.id.set_alarm_button -> {
                TrainAlarmDialogFragment().show(supportFragmentManager, TrainAlarmDialogFragment.TAG)
                true
            }
            R.id.cancel_alarm_button -> {
                sendServiceAction(tripActionsViewModel.cancelAlarm())
                true
            }
            R.id.share_arrival -> {
                val destination = requireNotNull(currentDeparture.getStationPair()?.destination)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_trip_subject))
                    putExtra(
                        Intent.EXTRA_TEXT,
                        getString(
                            R.string.arrival_message,
                            destination.getName(),
                            DepartureTextFormatter.estimatedArrivalTime(this@TripInProgressActivity, currentDeparture, false),
                        ),
                    )
                }
                startActivity(Intent.createChooser(share, getString(R.string.share_arrival_time)))
                true
            }
            R.id.delete -> {
                AlertDialog.Builder(this)
                    .setMessage(R.string.clear_trip_confirmation)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        sendServiceAction(tripActionsViewModel.clearTrip())
                        finish()
                    }
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun sendServiceAction(action: String) {
        startForegroundService(Intent(this, BoardedDepartureService::class.java).setAction(action))
    }

    private fun followTrip() {
        val currentDeparture = departure ?: return
        if (isFollowing) return
        val action = tripActionsViewModel.followTrip(currentDeparture)
        requestNotificationPermissionIfNeeded()
        startBoardedDepartureService(action)
        isFollowing = true
        updateFollowState()
        invalidateOptionsMenu()
    }

    private fun startBoardedDepartureService(action: String) {
        startForegroundService(Intent(this, BoardedDepartureService::class.java).setAction(action))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), POST_NOTIFICATIONS_REQUEST_CODE)
        }
    }

    private fun updateFollowState() {
        binding.followTripButton.visibility = if (isFollowing) View.GONE else View.VISIBLE
        supportActionBar?.setTitle(if (isFollowing) R.string.trip_in_progress else R.string.train_schedule)
    }

    private fun renderTrip() {
        val currentDeparture = departure ?: return
        val pair = currentDeparture.getStationPair() ?: return
        val origin = pair.origin ?: return
        val destination = pair.destination ?: return
        binding.tripRoute.text = getString(R.string.route_title_arrow, origin.getName(), destination.getName())
        binding.tripStatus.text = getTripStatus(currentDeparture)
        val estimatedArrival = DepartureTextFormatter.estimatedArrivalTime(this, currentDeparture, false)
        binding.tripArrival.text = if (estimatedArrival.isEmpty()) {
            getString(R.string.trip_final_arrival_unknown, destination.getName())
        } else {
            getString(R.string.trip_final_arrival, destination.getName(), estimatedArrival)
        }

        binding.tripTimeline.removeAllViews()
        val legs = currentDeparture.tripLegs
        if (legs.isEmpty()) {
            addText(R.string.trip_details_unavailable, false)
            return
        }
        val firstActiveLeg = getFirstActiveLegIndex(legs)
        for (index in firstActiveLeg until legs.size) {
            val leg = legs[index]
            addLeg(leg, index == firstActiveLeg)
            if (index + 1 < legs.size) addConnection(leg, legs[index + 1])
        }
    }

    private fun getTripStatus(currentDeparture: Departure): String {
        if (currentDeparture.isCanceled()) return getString(R.string.trip_canceled)
        val nowMillis = timeSource.nowMillis()
        if (!currentDeparture.hasDeparted(nowMillis)) {
            return getString(
                R.string.trip_leaves_in,
                DepartureTextFormatter.countdown(this, currentDeparture, nowMillis),
            )
        }
        getWaitingConnectionStatus(currentDeparture)?.let { return it }
        getNextStop(currentDeparture)?.let {
            return getString(R.string.trip_next_stop, it.station?.getName(), formatEta(it.arrivalTime))
        }
        val arrival = currentDeparture.getEstimatedArrivalTime()
        return when {
            arrival > 0 && arrival <= nowMillis -> getString(R.string.trip_arrived)
            else -> getString(R.string.trip_current_train)
        }
    }

    private fun getFirstActiveLegIndex(legs: List<TripLeg>): Int {
        val now = timeSource.nowMillis()
        return legs.indexOfFirst { !hasPassedAllStops(it, now) }.let { if (it < 0) legs.size else it }
    }

    private fun hasPassedAllStops(leg: TripLeg, now: Long): Boolean {
        if (leg.stops.isNotEmpty()) {
            return leg.stops.all { it.arrivalTime > 0 && it.arrivalTime <= now }
        }
        return leg.arrivalTime > 0 && leg.arrivalTime <= now
    }

    private fun getNextStop(currentDeparture: Departure): TripStop? {
        val now = timeSource.nowMillis()
        val legs = currentDeparture.tripLegs
        val firstActiveLeg = getFirstActiveLegIndex(legs)
        for (index in firstActiveLeg until legs.size) {
            val leg = legs[index]
            leg.stops.firstOrNull { it.station != leg.origin && it.arrivalTime > now }?.let { return it }
        }
        return null
    }

    private fun getWaitingConnectionStatus(currentDeparture: Departure): String? {
        val now = timeSource.nowMillis()
        val legs = currentDeparture.tripLegs
        for (index in 0 until legs.lastIndex) {
            val arrivingLeg = legs[index]
            val nextLeg = legs[index + 1]
            if (arrivingLeg.arrivalTime > 0 && arrivingLeg.arrivalTime <= now) {
                if (nextLeg.departureTime > now) {
                    val lineName = nextLeg.line?.getDisplayName() ?: getString(R.string.train)
                    return getString(R.string.trip_transfer_now, lineName)
                }
                if (nextLeg.departureTime <= 0) return getString(R.string.trip_no_departure_scheduled)
            }
        }
        return null
    }

    private fun addLeg(leg: TripLeg, isCurrentTrain: Boolean) {
        val heading = addText(null, true)
        val lineName = leg.line?.getDisplayName() ?: getString(R.string.train)
        val prefix = if (isCurrentTrain) getString(R.string.trip_current_train) else getString(R.string.trip_connection_train)
        heading.text = getString(R.string.trip_leg_heading, prefix, lineName)

        val route = addText(null, false)
        route.text = getString(R.string.route_title_arrow, leg.origin?.getName(), leg.destination?.getName())
        route.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        if (leg.stops.isEmpty()) {
            val arrival = addText(null, false)
            arrival.text = if (leg.departureTime > 0) {
                getString(R.string.trip_train_departure, formatTime(leg.departureTime))
            } else {
                getString(R.string.trip_no_departure_scheduled)
            }
            return
        }
        leg.stops.forEach { stop ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
            }
            val station = TextView(this).apply {
                text = stop.station?.getName()
                setTextColor(ContextCompat.getColor(this@TripInProgressActivity, R.color.text_primary))
                textSize = 16f
            }
            row.addView(station, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val time = TextView(this).apply {
                gravity = Gravity.RIGHT
                setTextColor(ContextCompat.getColor(this@TripInProgressActivity, R.color.text_secondary))
                val displayedTime = if (stop.station == leg.origin) stop.departureTime else stop.arrivalTime
                text = formatTime(displayedTime) + "\n" + formatEta(displayedTime)
            }
            row.addView(time, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            binding.tripTimeline.addView(row)
        }
    }

    private fun addConnection(arrivingLeg: TripLeg, nextLeg: TripLeg) {
        val connection = addText(null, true)
        connection.text = getString(R.string.trip_transfer_at, arrivingLeg.destination?.getName())
        connection.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
        val details = addText(null, false)
        val arrival = getArrivalAt(arrivingLeg, arrivingLeg.destination)
        val departure = getDepartureAt(nextLeg, nextLeg.origin)
        details.text = if (departure <= 0) {
            getString(R.string.trip_no_departure_scheduled)
        } else {
            getString(
                R.string.trip_connection_details,
                formatTime(arrival),
                formatEta(arrival),
                formatTime(departure),
                formatMargin(departure - arrival),
            )
        }
        details.setTextColor(ContextCompat.getColor(this,
            if (isConnectionWarning(arrivingLeg, departure - arrival)) {
                R.color.connection_warning
            } else {
                R.color.text_secondary
            },
        ))
    }

    private fun isConnectionWarning(arrivingLeg: TripLeg, margin: Long): Boolean {
        val minimumSeconds = arrivingLeg.minimumTransferSecondsAfter
        return margin < 0 || (minimumSeconds > 0 && margin < minimumSeconds * 1000L)
    }

    private fun getArrivalAt(leg: TripLeg, station: com.dougkeen.bart.model.Station?): Long =
        leg.stops.firstOrNull { it.station == station }?.arrivalTime ?: leg.arrivalTime

    private fun getDepartureAt(leg: TripLeg, station: com.dougkeen.bart.model.Station?): Long =
        leg.stops.firstOrNull { it.station == station }?.departureTime ?: leg.departureTime

    private fun addText(resource: Int?, heading: Boolean): TextView = TextView(this).apply {
        setPadding(dp(8), if (heading) dp(12) else dp(3), dp(8), dp(3))
        textSize = if (heading) 18f else 14f
        setTextColor(ContextCompat.getColor(
            this@TripInProgressActivity,
            if (heading) R.color.text_primary else R.color.text_secondary,
        ))
        if (heading) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        resource?.let(::setText)
        binding.tripTimeline.addView(this)
    }

    private fun formatTime(time: Long): String =
        if (time > 0) DepartureTextFormatter.formatTime(this, time) else "--"

    private fun formatEta(time: Long): String {
        if (time <= 0) return getString(R.string.trip_eta_unknown)
        val seconds = (time - timeSource.nowMillis()) / 1000L
        if (seconds <= 0) return getString(R.string.trip_stop_passed)
        return getString(R.string.trip_eta_minutes_seconds, seconds / 60L, seconds % 60L)
    }

    private fun formatMargin(margin: Long): String {
        if (margin < 0) return getString(R.string.trip_connection_missed)
        val seconds = margin / 1000L
        val minutes = seconds / 60L
        val remainingSeconds = seconds % 60L
        return when {
            minutes == 0L -> getString(R.string.trip_margin_seconds, remainingSeconds)
            remainingSeconds == 0L -> getString(R.string.trip_margin_minutes, minutes)
            else -> getString(R.string.trip_margin_minutes_seconds, minutes, remainingSeconds)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
