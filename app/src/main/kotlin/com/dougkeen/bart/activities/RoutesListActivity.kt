package com.dougkeen.bart.activities

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R
import com.dougkeen.bart.controls.ScreenTicker
import com.dougkeen.bart.data.FavoritesArrayAdapter
import com.dougkeen.bart.databinding.MainBinding
import com.dougkeen.bart.model.Alert
import com.dougkeen.bart.model.StationPair
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class RoutesListActivity : AppCompatActivity(), FavoritesArrayAdapter.Listener {
    companion object {
        private const val TAG = "RoutesListActivity"
    }

    private lateinit var routesAdapter: FavoritesArrayAdapter
    private lateinit var routesViewModel: RoutesViewModel
    private lateinit var binding: MainBinding
    private var actionMode: ActionMode? = null
    private var currentlySelectedStationPair: StationPair? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routesViewModel = androidx.lifecycle.ViewModelProvider(this)[RoutesViewModel::class.java]
        binding = MainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.quickLookupButton.setOnClickListener {
            quickLookupButtonClick()
        }
        setTitle(R.string.favorite_routes)
        binding.favoritesList.layoutManager = LinearLayoutManager(this)
        binding.favoritesList.setHasFixedSize(false)
        val itemSpacing = resources.getDimensionPixelSize(R.dimen.list_item_spacing)
        binding.favoritesList.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State,
            ) {
                outRect.bottom = itemSpacing
            }
        })

        val app = application as BartRunnerApplication
        routesAdapter = FavoritesArrayAdapter(this, emptyList(), this, app.timeSource)
        binding.favoritesList.adapter = routesAdapter
        ScreenTicker(lifecycle) { tick -> routesAdapter.setTick(tick) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                routesViewModel.uiState.collectLatest(::renderState)
            }
        }

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                routesViewModel.moveFavorite(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val stationPair = routesAdapter.itemAt(position)
                routesViewModel.removeFavorite(stationPair)
                showRouteDeletedSnackbar(position, stationPair)
            }
        }).attachToRecyclerView(binding.favoritesList)
        updateEmptyState()
    }

    fun quickLookupButtonClick() {
        try {
            QuickRouteDialogFragment().show(
                supportFragmentManager,
                QuickRouteDialogFragment.TAG,
            )
        } catch (exception: IllegalStateException) {
            Log.w(TAG, "Could not open quick lookup dialog", exception)
        }
    }

    override fun onFavoriteClicked(pair: StationPair) {
        startActivity(Intent(this, ViewDeparturesActivity::class.java).apply {
            RouteArguments.putRoute(this, pair)
        })
    }

    override fun onFavoriteLongClicked(pair: StationPair) {
        actionMode?.finish()
        currentlySelectedStationPair = pair
        startContextualActionMode()
    }

    fun addFavorite(pair: StationPair) {
        routesViewModel.addFavorite(pair)
    }

    private fun renderState(state: RoutesUiState) {
        routesAdapter.submitList(state.favorites)
        routesAdapter.setFirstDepartures(state.firstDepartures)
        updateEmptyState()
        state.error?.let { Log.w(TAG, "Could not update route screen data", it) }
        renderAlert(state)
    }

    private fun updateEmptyState() {
        if (!::routesAdapter.isInitialized) return
        val empty = routesAdapter.itemCount == 0
        binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.favoritesList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.routes_list_menu, menu)
        menu.findItem(R.id.view_trip_in_progress)?.isVisible =
            (application as BartRunnerApplication).followedTripRepository.getFollowedDeparture() != null
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.view_trip_in_progress)?.isVisible =
            (application as BartRunnerApplication).followedTripRepository.getFollowedDeparture() != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.add_favorite_menu_button -> {
            AddRouteDialogFragment().show(supportFragmentManager, AddRouteDialogFragment.TAG)
            true
        }
        R.id.view_system_map_button -> {
            startActivity(Intent(this, ViewMapActivity::class.java))
            true
        }
        R.id.view_trip_in_progress -> {
            startActivity(Intent(this, TripInProgressActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun renderAlert(state: RoutesUiState) {
        when (state.alertKind) {
            RoutesUiState.AlertKind.HIDDEN -> hideAlertMessage()
            RoutesUiState.AlertKind.NO_DELAYS -> showAlertMessage(
                getString(R.string.no_delays_reported),
                true,
            )
            RoutesUiState.AlertKind.WARNING -> showAlertMessage(formatAlertMessage(state.alerts), false)
        }
    }

    private fun formatAlertMessage(alerts: Alert.AlertList?): String? {
        if (alerts == null || !alerts.hasAlerts()) return null
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT)
            .withLocale(resources.configuration.locales[0])
            .withZone(ZoneId.systemDefault())
        return alerts.getAlerts().joinToString("\n\n") { alert ->
            buildString {
                alert.postedAtMillis?.let {
                    append(formatter.format(Instant.ofEpochMilli(it)))
                    append('\n')
                }
                append(alert.description ?: "")
            }
        }
    }

    fun hideAlertMessage() {
        binding.alertMessages.visibility = View.GONE
    }

    fun showAlertMessage(messageText: String?, noDelays: Boolean) {
        if (messageText == null) {
            hideAlertMessage()
            return
        }
        binding.alertMessages.setCompoundDrawablesWithIntrinsicBounds(
            if (noDelays) R.drawable.ic_allgood else R.drawable.ic_warn,
            0,
            0,
            0,
        )
        binding.alertMessages.text = messageText
        binding.alertMessages.visibility = View.VISIBLE
    }

    private fun startContextualActionMode() {
        actionMode = startSupportActionMode(RouteActionMode())
        val pair = requireNotNull(currentlySelectedStationPair)
        val mode = requireNotNull(actionMode)
        mode.title = pair.origin?.getName()
        mode.subtitle = if (pair.destination != null) {
            getString(R.string.to_station, pair.destination.getName())
        } else {
            getString(R.string.arrivals_at_station, pair.origin?.getName())
        }
    }

    private fun showRouteDeletedSnackbar(position: Int, stationPair: StationPair) {
        Snackbar.make(binding.coordinatorLayout, R.string.snackbar_route_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { routesViewModel.insertFavorite(stationPair, position) }
            .show()
    }

    private inner class RouteActionMode : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.route_context_menu, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val pair = requireNotNull(currentlySelectedStationPair)
            return when (item.itemId) {
                R.id.view -> {
                    startActivity(Intent(this@RoutesListActivity, ViewDeparturesActivity::class.java).apply {
                        RouteArguments.putRoute(this, pair)
                    })
                    mode.finish()
                    true
                }
                R.id.delete -> {
                    AlertDialog.Builder(this@RoutesListActivity)
                        .setCancelable(false)
                        .setMessage(R.string.route_delete_confirmation)
                        .setPositiveButton(R.string.yes) { dialog, _ ->
                            routesViewModel.removeFavorite(pair)
                            currentlySelectedStationPair = null
                            actionMode?.finish()
                            dialog.dismiss()
                        }
                        .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
                        .show()
                    false
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
        }
    }
}
