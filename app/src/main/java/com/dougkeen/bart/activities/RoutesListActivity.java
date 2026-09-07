package com.dougkeen.bart.activities;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.fragment.app.DialogFragment;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.snackbar.Snackbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.lifecycle.ViewModelProvider;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.R;
import com.dougkeen.bart.data.FavoritesArrayAdapter;
import com.dougkeen.bart.data.LifecycleFlowCollector;
import com.dougkeen.bart.data.FavoritesRepository;
import com.dougkeen.bart.data.FavoritesUiState;
import com.dougkeen.bart.data.FavoritesViewModel;
import com.dougkeen.bart.data.FavoritesViewModelFactory;
import com.dougkeen.bart.model.Alert;
import com.dougkeen.bart.model.Alert.AlertList;
import com.dougkeen.bart.model.Constants;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.model.TimeSource;
import com.dougkeen.bart.networktasks.GtfsStaticData;
import com.dougkeen.bart.platform.StationPairParcel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class   RoutesListActivity extends AppCompatActivity implements
        FavoritesArrayAdapter.Listener,
        RoutesTransitViewModel.Listener {
    private static final String NO_DELAYS_REPORTED = "No delays reported";

    private static final TimeZone PACIFIC_TIME = TimeZone
            .getTimeZone("America/Los_Angeles");

    private static final String TAG = "RoutesListActivity";

    private TimeSource timeSource;

    StationPair mCurrentlySelectedStationPair;

    String mCurrentAlerts;

    private ActionMode mActionMode;

    private FavoritesArrayAdapter mRoutesAdapter;

    BartRunnerApplication app;

    private FavoritesRepository favoritesRepository;

    private FavoritesViewModel favoritesViewModel;

    private RoutesTransitViewModel routesTransitViewModel;

    RecyclerView listView;

    TextView alertMessages;

    CoordinatorLayout coordinatorLayout;

    TextView emptyView;

    private final ExecutorService staticDataExecutor =
            Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean destroyed;

    void quickLookupButtonClick() {
        DialogFragment dialog = new QuickRouteDialogFragment();
        try {
            dialog.show(getSupportFragmentManager(), QuickRouteDialogFragment.TAG);
        } catch (IllegalStateException e) {
            // Sometimes this gets called after onSaveInstanceState. We can ignore the resulting exception.
            Log.w(TAG, "Could not open quick lookup dialog", e);
        }
    }

    @Override
    public void onFavoriteClicked(StationPair item) {
        Intent intent = new Intent(RoutesListActivity.this,
                ViewDeparturesActivity.class);
        intent.putExtra(Constants.STATION_PAIR_EXTRA, new StationPairParcel(item));
        startActivity(intent);
    }

    @Override
    public void onFavoriteLongClicked(StationPair item) {
        if (mActionMode != null) {
            mActionMode.finish();
        }

        mCurrentlySelectedStationPair = item;

        startContextualActionMode();
    }

    void afterViews() {
        setTitle(R.string.favorite_routes);

        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setHasFixedSize(false);
        final int itemSpacing = getResources().getDimensionPixelSize(R.dimen.list_item_spacing);
        listView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                                       RecyclerView.State state) {
                outRect.bottom = itemSpacing;
            }
        });
        mRoutesAdapter = new FavoritesArrayAdapter(
                this, new ArrayList<>(), this, timeSource);

        setListAdapter(mRoutesAdapter);
        LifecycleFlowCollector.collect(this, favoritesViewModel.getUiState(),
                this::onFavoritesChanged);

        ItemTouchHelper touchHelper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(
                        ItemTouchHelper.UP | ItemTouchHelper.DOWN,
                        ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
                    @Override
                    public boolean onMove(RecyclerView recyclerView,
                                           RecyclerView.ViewHolder viewHolder,
                                           RecyclerView.ViewHolder target) {
                        int from = viewHolder.getBindingAdapterPosition();
                        int to = target.getBindingAdapterPosition();
                        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                            return false;
                        }
                        favoritesViewModel.moveFavorite(from, to);
                        return true;
                    }

                    @Override
                    public void onSwiped(RecyclerView.ViewHolder viewHolder,
                        int direction) {
                        int position = viewHolder.getBindingAdapterPosition();
                        if (position == RecyclerView.NO_POSITION) {
                            return;
                        }
                        StationPair stationPair = mRoutesAdapter.getItem(position);
                        favoritesViewModel.removeFavorite(stationPair);
                        showRouteDeletedSnackbar(position, stationPair);
                        updateEmptyState();
                    }
                });
        touchHelper.attachToRecyclerView(listView);

        if (mCurrentAlerts != null) {
            showAlertMessage(mCurrentAlerts);
        }

        refreshFares();
        updateEmptyState();
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        app = (BartRunnerApplication) getApplication();
        timeSource = app.getTimeSource();
        favoritesRepository = app.getFavoritesRepository();
        favoritesViewModel = new ViewModelProvider(this,
                new FavoritesViewModelFactory(favoritesRepository))
                .get(FavoritesViewModel.class);
        routesTransitViewModel = new ViewModelProvider(this)
                .get(RoutesTransitViewModel.class);
        routesTransitViewModel.configure(app.getTransitRepository(),
                getApplicationContext(), timeSource, this);
        if (savedInstanceState != null) {
            mCurrentAlerts = savedInstanceState.getString("currentAlerts");
        }
        setContentView(R.layout.main);
        listView = findViewById(R.id.favoritesList);
        emptyView = findViewById(android.R.id.empty);
        alertMessages = findViewById(R.id.alertMessages);
        coordinatorLayout = findViewById(R.id.coordinatorLayout);
        findViewById(R.id.quickLookupButton).setOnClickListener(
                view -> quickLookupButtonClick());
        afterViews();

    }

    protected FavoritesArrayAdapter getListAdapter() {
        return mRoutesAdapter;
    }

    protected void setListAdapter(FavoritesArrayAdapter adapter) {
        mRoutesAdapter = adapter;
        listView.setAdapter(mRoutesAdapter);
    }

    void addFavorite(StationPair pair) {
        favoritesViewModel.addFavorite(pair);
    }

    private void updateEmptyState() {
        if (emptyView == null || mRoutesAdapter == null) {
            return;
        }
        emptyView.setVisibility(mRoutesAdapter.isEmpty() ? View.VISIBLE : View.GONE);
        listView.setVisibility(mRoutesAdapter.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void refreshFares() {
        final List<StationPair> routesNeedingFares =
                new ArrayList<StationPair>();
        for (int i = getListAdapter().getCount() - 1; i >= 0; i--) {
            final StationPair stationPair = getListAdapter().getItem(i);

            if (stationPair.getDestination() == null) {
                continue;
            }

            Calendar now = Calendar.getInstance();
            Calendar lastUpdate = Calendar.getInstance();
            lastUpdate.setTimeInMillis(stationPair.getFareLastUpdated());

            now.setTimeZone(PACIFIC_TIME);
            lastUpdate.setTimeZone(PACIFIC_TIME);

            // Update every day
            if (now.get(Calendar.DAY_OF_YEAR) != lastUpdate.get(Calendar.DAY_OF_YEAR)
                    || now.get(Calendar.YEAR) != lastUpdate.get(Calendar.YEAR)) {
                routesNeedingFares.add(stationPair);
            }
        }
        if (routesNeedingFares.isEmpty()) {
            return;
        }
        staticDataExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final GtfsStaticData staticData = GtfsStaticData.get(
                            RoutesListActivity.this);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            long now = timeSource.nowMillis();
                            for (StationPair stationPair : routesNeedingFares) {
                                String fare = staticData.getFare(
                                        stationPair.getOrigin(),
                                        stationPair.getDestination());
                                if (fare != null) {
                                    favoritesViewModel.updateFare(stationPair,
                                            fare, now);
                                }
                            }
                        }
                    });
                } catch (IOException exception) {
                    Log.w(TAG, "Could not load static GTFS fares", exception);
                }
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("currentAlerts", mCurrentAlerts);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        staticDataExecutor.shutdownNow();
        super.onDestroy();
    }

    public void onFavoritesChanged(FavoritesUiState state) {
        mRoutesAdapter.submitList(state.getFavorites());
        routesTransitViewModel.setRoutes(state.getFavorites());
        updateEmptyState();
        if (!state.isLoading()) {
            refreshFares();
        }
    }

    @Override
    public void onFirstDeparturesChanged(
            java.util.Map<StationPair, com.dougkeen.bart.model.Departure> firstDepartures) {
        mRoutesAdapter.setFirstDepartures(firstDepartures);
    }

    @Override
    public void onAlertsChanged(AlertList alerts) {
        displayAlerts(alerts);
    }

    @Override
    public void onTransitError(Exception exception) {
        Log.w(TAG, "Could not update route screen transit data", exception);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.routes_list_menu, menu);
        MenuItem tripItem = menu.findItem(R.id.view_trip_in_progress);
        tripItem.setVisible(app.getFollowedTripRepository().getFollowedDeparture() != null);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem tripItem = menu.findItem(R.id.view_trip_in_progress);
        if (tripItem != null) {
            tripItem.setVisible(app.getFollowedTripRepository().getFollowedDeparture() != null);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.add_favorite_menu_button) {
            new AddRouteDialogFragment().show(getSupportFragmentManager(),
                    AddRouteDialogFragment.TAG);
            return true;
        } else if (itemId == R.id.view_system_map_button) {
            startActivity(new Intent(this, ViewMapActivity.class));
            return true;
        } else if (itemId == R.id.view_trip_in_progress) {
            startActivity(new Intent(this, TripInProgressActivity.class));
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void displayAlerts(AlertList alertList) {
        if (alertList.hasAlerts()) {
            StringBuilder alertText = new StringBuilder();
            boolean firstAlert = true;
            for (Alert alert : alertList.getAlerts()) {
                if (!firstAlert) {
                    alertText.append("\n\n");
                }
                if (alert.getPostedTime() != null
                        && !alert.getPostedTime().isEmpty()) {
                    alertText.append(alert.getPostedTime()).append("\n");
                }
                alertText.append(alert.getDescription());
                firstAlert = false;
            }
            showAlertMessage(alertText.toString());
        } else if (alertList.areNoDelaysReported()) {
            showAlertMessage(NO_DELAYS_REPORTED);
        } else {
            hideAlertMessage();
        }
    }

    void hideAlertMessage() {
        mCurrentAlerts = null;
        alertMessages.setVisibility(View.GONE);
    }

    void showAlertMessage(String messageText) {
        if (messageText == null) {
            hideAlertMessage();
            return;
        } else if (messageText.equals(NO_DELAYS_REPORTED)) {
            alertMessages.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_allgood, 0, 0, 0);
        } else {
            alertMessages.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_warn, 0, 0, 0);
        }
        mCurrentAlerts = messageText;
        alertMessages.setText(messageText);
        alertMessages.setVisibility(View.VISIBLE);
    }

    private void startContextualActionMode() {
        mActionMode = startSupportActionMode(new RouteActionMode());
        mActionMode.setTitle(mCurrentlySelectedStationPair.getOrigin().getName());
        if (mCurrentlySelectedStationPair.getDestination() != null) {
            mActionMode.setSubtitle("to "
                    + mCurrentlySelectedStationPair.getDestination().getName());
        } else {
            mActionMode.setSubtitle(getString(R.string.arrivals_at_station,
                    mCurrentlySelectedStationPair.getOrigin().getName()));
        }
    }

    private void showRouteDeletedSnackbar(final int which, final StationPair stationPair) {
        Snackbar.make(coordinatorLayout, R.string.snackbar_route_deleted, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        favoritesViewModel.insertFavorite(stationPair, which);
                    }
                })
                .show();
    }

    private final class RouteActionMode implements ActionMode.Callback {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.route_context_menu, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == R.id.view) {
                Intent intent = new Intent(RoutesListActivity.this,
                        ViewDeparturesActivity.class);
                intent.putExtra(Constants.STATION_PAIR_EXTRA,
                        new StationPairParcel(mCurrentlySelectedStationPair));
                startActivity(intent);
                mode.finish();
                return true;
            } else if (item.getItemId() == R.id.delete) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(
                        RoutesListActivity.this);
                builder.setCancelable(false);
                builder.setMessage("Are you sure you want to delete this route?");
                builder.setPositiveButton(R.string.yes,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                                int which) {
                                favoritesViewModel.removeFavorite(
                                        mCurrentlySelectedStationPair);
                                mCurrentlySelectedStationPair = null;
                                mActionMode.finish();
                                dialog.dismiss();
                            }
                        });
                builder.setNegativeButton(R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                                int which) {
                                dialog.cancel();
                            }
                        });
                builder.show();
                return false;
            }

            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mActionMode = null;
        }

    }

}
