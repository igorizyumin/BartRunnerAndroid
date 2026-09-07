package com.dougkeen.bart.activities;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
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
import com.dougkeen.bart.controls.ScreenTicker;
import com.dougkeen.bart.data.FavoritesArrayAdapter;
import com.dougkeen.bart.data.LifecycleFlowCollector;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.model.TimeSource;
import java.util.ArrayList;


public class   RoutesListActivity extends AppCompatActivity implements
        FavoritesArrayAdapter.Listener {
    private static final String TAG = "RoutesListActivity";

    private TimeSource timeSource;

    StationPair mCurrentlySelectedStationPair;

    private ActionMode mActionMode;

    private FavoritesArrayAdapter mRoutesAdapter;

    BartRunnerApplication app;

    private RoutesViewModel routesViewModel;

    private ScreenTicker screenTicker;

    RecyclerView listView;

    TextView alertMessages;

    CoordinatorLayout coordinatorLayout;

    TextView emptyView;

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
        RouteArguments.putRoute(intent, item);
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
        screenTicker = new ScreenTicker(getLifecycle(),
                tick -> mRoutesAdapter.setTick(tick));
        LifecycleFlowCollector.collect(this, routesViewModel.getUiState(),
                this::renderState);

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
                        routesViewModel.moveFavorite(from, to);
                        return true;
                    }

                    @Override
                    public void onSwiped(RecyclerView.ViewHolder viewHolder,
                        int direction) {
                        int position = viewHolder.getBindingAdapterPosition();
                        if (position == RecyclerView.NO_POSITION) {
                            return;
                        }
                        StationPair stationPair = mRoutesAdapter.itemAt(position);
                        routesViewModel.removeFavorite(stationPair);
                        showRouteDeletedSnackbar(position, stationPair);
                    }
                });
        touchHelper.attachToRecyclerView(listView);

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
        routesViewModel = new ViewModelProvider(this,
                new RoutesViewModelFactory(app))
                .get(RoutesViewModel.class);
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
        routesViewModel.addFavorite(pair);
    }

    private void updateEmptyState() {
        if (emptyView == null || mRoutesAdapter == null) {
            return;
        }
        boolean empty = mRoutesAdapter.getItemCount() == 0;
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void renderState(RoutesUiState state) {
        mRoutesAdapter.submitList(state.getFavorites());
        mRoutesAdapter.setFirstDepartures(state.getFirstDepartures());
        updateEmptyState();
        if (state.getError() != null) {
            Log.w(TAG, "Could not update route screen data", state.getError());
        }
        renderAlert(state);
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

    private void renderAlert(RoutesUiState state) {
        if (state.getAlertKind() == RoutesUiState.AlertKind.HIDDEN) {
            hideAlertMessage();
        } else {
            showAlertMessage(
                    state.getAlertMessage(),
                    state.getAlertKind() == RoutesUiState.AlertKind.NO_DELAYS);
        }
    }

    void hideAlertMessage() {
        alertMessages.setVisibility(View.GONE);
    }

    void showAlertMessage(String messageText, boolean noDelays) {
        if (messageText == null) {
            hideAlertMessage();
            return;
        } else if (noDelays) {
            alertMessages.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_allgood, 0, 0, 0);
        } else {
            alertMessages.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_warn, 0, 0, 0);
        }
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
                        routesViewModel.insertFavorite(stationPair, which);
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
                RouteArguments.putRoute(intent, mCurrentlySelectedStationPair);
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
                                routesViewModel.removeFavorite(
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
