package com.dougkeen.bart.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.text.util.Linkify
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R
import com.dougkeen.bart.data.DepartureArrayAdapter
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.presentation.DepartureTextFormatter
import com.dougkeen.bart.services.BoardedDepartureService
import com.dougkeen.bart.databinding.DeparturesBinding
import com.dougkeen.util.WakeLocker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ViewDeparturesActivity : AbstractViewActivity(), DepartureArrayAdapter.Listener {
    companion object {
        private const val STATE_SELECTED_DEPARTURE_ID = "selectedDepartureIdentity"
        private const val STATE_HAS_DEPARTURE_ACTION_MODE = "hasDepartureActionMode"
        private const val POST_NOTIFICATIONS_REQUEST_CODE = 1001
    }

    private lateinit var binding: DeparturesBinding
    private lateinit var stationPair: StationPair
    private lateinit var timeSource: TimeSource
    private var selectedDeparture: Departure? = null
    private var selectedDepartureIdentity: String? = null
    private var restoreDepartureActionMode = false
    private lateinit var departuresAdapter: DepartureArrayAdapter
    private lateinit var departuresViewModel: DeparturesViewModel
    private lateinit var tripActionsViewModel: TripActionsViewModel
    private var actionMode: ActionMode? = null
    private val handler = Handler(Looper.getMainLooper())
    private val clearKeepScreenOnRunnable = Runnable {
        if (!isFinishing) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DeparturesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as BartRunnerApplication
        timeSource = app.timeSource
        tripActionsViewModel = ViewModelProvider(this)[TripActionsViewModel::class.java]
        if (app.alarmController.isRingtoneRequested() || app.alarmController.isSounding()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        binding.empty.setText(R.string.departure_wait_message)
        binding.departuresList.layoutManager = LinearLayoutManager(this)
        binding.departuresList.setHasFixedSize(false)
        val itemSpacing = resources.getDimensionPixelSize(R.dimen.list_item_spacing)
        binding.departuresList.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State,
            ) {
                outRect.bottom = itemSpacing
            }
        })
        departuresAdapter = DepartureArrayAdapter(this, this, timeSource)
        binding.departuresList.adapter = departuresAdapter
        departuresViewModel = ViewModelProvider(this)[DeparturesViewModel::class.java]

        stationPair = if (savedInstanceState?.containsKey(RouteArguments.ORIGIN) == true) {
            RouteArguments.readRoute(savedInstanceState)
        } else {
            RouteArguments.readRoute(intent)
        } ?: run {
            finish()
            return
        }
        setListTitle()

        if (savedInstanceState != null) {
            selectedDepartureIdentity = savedInstanceState.getString(STATE_SELECTED_DEPARTURE_ID)
            restoreDepartureActionMode = savedInstanceState.getBoolean(STATE_HAS_DEPARTURE_ACTION_MODE)
        }

        updateEmptyState(departuresAdapter.itemCount == 0)
        departuresViewModel.setQuery(app.transitRepository, app.bartGtfsNetworkSupplier, stationPair)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                departuresViewModel.uiState.collectLatest(::renderState)
            }
        }

        requireNotNull(supportActionBar) { "Support action bar is unavailable" }.apply {
            setHomeButtonEnabled(true)
            setDisplayHomeAsUpEnabled(true)
        }
        if (app.alarmController.isRingtoneRequested()) {
            soundTheAlarm()
        }
        if (app.alarmController.isSounding()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.train_alarm_text)
                .setCancelable(false)
                .setNeutralButton(R.string.silence_alarm) { dialog, _ ->
                    silenceAlarm()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun setSelectedDeparture(departure: Departure?) {
        selectedDeparture = departure
        selectedDepartureIdentity = departure?.identity
        departuresAdapter.setSelectedDepartureIdentity(selectedDepartureIdentity)
    }

    private fun soundTheAlarm() {
        val app = application as BartRunnerApplication
        var alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (alarmSound == null || !tryToPlayRingtone(alarmSound)) {
            alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (alarmSound == null || !tryToPlayRingtone(alarmSound)) {
                alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
        }
        if (app.alarmController.getMediaPlayer() == null) {
            alarmSound?.let(::tryToPlayRingtone)
        }
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 1),
        )
        handler.postDelayed({ silenceAlarm() }, 20_000)
        app.alarmController.consumeRingtoneRequest()
        app.alarmController.setSounding(true)
    }

    private fun tryToPlayRingtone(alarmSound: Uri): Boolean {
        val mediaPlayer = MediaPlayer.create(this, alarmSound) ?: return false
        mediaPlayer.isLooping = true
        mediaPlayer.start()
        (application as BartRunnerApplication).alarmController.setMediaPlayer(mediaPlayer)
        return true
    }

    private fun silenceAlarm() {
        (application as BartRunnerApplication).alarmController.silence()
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
    }

    private fun setListTitle() {
        val origin = stationPair.origin
        val destination = stationPair.destination
        binding.listTitle.text = when {
            origin == null -> ""
            destination == null -> getString(
                R.string.arrivals_at_station,
                origin.getName(),
            )
            else -> getString(
                R.string.route_title,
                origin.getName(),
                destination.getName(),
            )
        }
    }

    private fun updateEmptyState(show: Boolean) {
        binding.empty.visibility = if (show) View.VISIBLE else View.GONE
        binding.departuresList.visibility = if (show) View.GONE else View.VISIBLE
    }

    override fun onDepartureClicked(departure: Departure) {
        if (actionMode != null) {
            actionMode?.finish()
        } else {
            openTripSchedule(departure)
        }
    }

    override fun onDepartureLongClicked(departure: Departure) {
        setSelectedDeparture(departure)
        startDepartureActionMode()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(clearKeepScreenOnRunnable)
        WakeLocker.release()
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::stationPair.isInitialized) {
            selectedDepartureIdentity?.let {
                outState.putString(STATE_SELECTED_DEPARTURE_ID, it)
            }
            outState.putBoolean(STATE_HAS_DEPARTURE_ACTION_MODE, actionMode != null)
            RouteArguments.putRoute(outState, stationPair)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            handler.removeCallbacks(clearKeepScreenOnRunnable)
            handler.postDelayed(clearKeepScreenOnRunnable, 10 * 60 * 1000L)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.route_menu, menu)
        menu.findItem(R.id.view_on_bart_site_button)?.isVisible = stationPair.destination != null
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.view_on_bart_site_button)?.isVisible = stationPair.destination != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            startActivity(Intent(this, RoutesListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            true
        }
        R.id.view_on_bart_site_button -> {
            val destination = stationPair.destination ?: return true
            startActivity(Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://m.bart.gov/schedules/qp_results.aspx?type=departure&date=today&time=" +
                        DepartureTextFormatter.formatBartScheduleTime(timeSource.nowMillis()) +
                        "&orig=" + stationPair.origin?.abbreviation +
                        "&dest=" + destination.abbreviation,
                ),
            ))
            true
        }
        R.id.view_system_map_button -> {
            startActivity(Intent(this, ViewMapActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun followDeparture(departure: Departure, openTripScreen: Boolean) {
        val preparedDeparture = prepareDepartureForTrip(departure)
        val action = tripActionsViewModel.followTrip(preparedDeparture)
        requestNotificationPermissionIfNeeded()
        startBoardedDepartureService(action)
        if (openTripScreen) {
            startActivity(Intent(this, TripInProgressActivity::class.java).apply {
                RouteArguments.putTrip(
                    this,
                    preparedDeparture.getStationPair(),
                    preparedDeparture.identity,
                    RouteArguments.MODE_FOLLOWED,
                )
            })
        }
    }

    private fun openTripSchedule(departure: Departure) {
        val preparedDeparture = prepareDepartureForTrip(departure)
        startActivity(Intent(this, TripInProgressActivity::class.java).apply {
            RouteArguments.putTrip(this, stationPair, preparedDeparture.identity, RouteArguments.MODE_SCHEDULE)
        })
    }

    private fun prepareDepartureForTrip(departure: Departure): Departure =
        departure.withPassengerDestination(stationPair.destination ?: departure.trainDestination)

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), POST_NOTIFICATIONS_REQUEST_CODE)
        }
    }

    private fun startBoardedDepartureService(action: String) {
        startForegroundService(Intent(this, BoardedDepartureService::class.java).setAction(action))
    }

    private fun startDepartureActionMode() {
        if (actionMode == null) {
            actionMode = startSupportActionMode(DepartureActionMode())
        }
        val mode = requireNotNull(actionMode)
        val departure = requireNotNull(selectedDeparture)
        mode.title = departure.getTrainDestinationName()
        mode.subtitle = DepartureTextFormatter.trainLengthAndPlatform(this, departure)
    }

    private inner class DepartureActionMode : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.departure_context_menu, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != R.id.boardTrain) return false
            followDeparture(requireNotNull(selectedDeparture), true)
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            setSelectedDeparture(null)
            actionMode = null
        }
    }

    private fun renderState(state: DeparturesViewModel.State) {
        when (state.status) {
            DeparturesViewModel.Status.LOADING -> {
                binding.empty.setText(R.string.departure_wait_message)
                updateEmptyState(true)
                binding.progress.visibility = View.VISIBLE
            }
            DeparturesViewModel.Status.CONTENT -> {
                updateEmptyState(false)
                binding.progress.visibility = View.GONE
                departuresAdapter.submitList(state.departures) { restoreSelectedDeparture() }
            }
            DeparturesViewModel.Status.EMPTY -> {
                departuresAdapter.submitList(state.departures) { restoreSelectedDeparture() }
                binding.empty.setText(R.string.no_data_message)
                updateEmptyState(true)
                binding.progress.visibility = View.GONE
                Linkify.addLinks(binding.empty, Linkify.WEB_URLS)
            }
            DeparturesViewModel.Status.ERROR -> {
                binding.empty.setText(R.string.could_not_connect)
                updateEmptyState(true)
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun restoreSelectedDeparture() {
        val identity = selectedDepartureIdentity ?: return
        for (index in 0 until departuresAdapter.itemCount) {
            val departure = departuresAdapter.itemAt(index)
            if (identity == departure.identity) {
                if (selectedDeparture !== departure) setSelectedDeparture(departure)
                if (restoreDepartureActionMode && actionMode == null) {
                    restoreDepartureActionMode = false
                    startDepartureActionMode()
                }
                return
            }
        }
        if (selectedDeparture != null) {
            actionMode?.finish() ?: setSelectedDeparture(null)
        }
        restoreDepartureActionMode = false
    }
}
