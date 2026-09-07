package com.dougkeen.bart.activities;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.VibratorManager;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ActionMode;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.format.DateFormat;
import android.text.util.Linkify;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.R;
import com.dougkeen.bart.controls.ScreenTicker;
import com.dougkeen.bart.data.DepartureArrayAdapter;
import com.dougkeen.bart.data.LifecycleFlowCollector;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.model.TimeSource;
import com.dougkeen.bart.services.BoardedDepartureService;
import com.dougkeen.util.Assert;
import com.dougkeen.util.WakeLocker;

public class ViewDeparturesActivity extends AbstractViewActivity implements
        DepartureArrayAdapter.Listener {

    private static final String STATE_SELECTED_DEPARTURE_ID =
            "selectedDepartureIdentity";
    private static final String STATE_HAS_DEPARTURE_ACTION_MODE =
            "hasDepartureActionMode";

    private StationPair mStationPair;

    private TimeSource mTimeSource;

    private Departure mSelectedDeparture;

    private String mSelectedDepartureIdentity;

    private boolean mRestoreDepartureActionMode;

    private DepartureArrayAdapter mDeparturesAdapter;

    private DeparturesViewModel mDeparturesViewModel;

    private TripActionsViewModel mTripActionsViewModel;

    private TextView mEmptyView;
    private ProgressBar mProgress;
    private RecyclerView mListView;

    private ActionMode mActionMode;

    private ScreenTicker mScreenTicker;

    private final Handler mHandler = new Handler(android.os.Looper.getMainLooper());

    private final Runnable mClearKeepScreenOnRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing()) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
    };

    private static final int POST_NOTIFICATIONS_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.departures);

        final Intent intent = getIntent();

        final BartRunnerApplication bartRunnerApplication = (BartRunnerApplication) getApplication();
        mTimeSource = bartRunnerApplication.getTimeSource();
        mTripActionsViewModel = new ViewModelProvider(this)
                .get(TripActionsViewModel.class);

        if (bartRunnerApplication.getAlarmController().isRingtoneRequested()
                || bartRunnerApplication.getAlarmController().isSounding()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            }
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        mEmptyView = (TextView) findViewById(android.R.id.empty);
        mEmptyView.setText(R.string.departure_wait_message);

        mProgress = (ProgressBar) findViewById(android.R.id.progress);

        mListView = findViewById(R.id.departuresList);
        mListView.setLayoutManager(new LinearLayoutManager(this));
        mListView.setHasFixedSize(false);
        final int itemSpacing = getResources().getDimensionPixelSize(R.dimen.list_item_spacing);
        mListView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                                       RecyclerView.State state) {
                outRect.bottom = itemSpacing;
            }
        });
        mDeparturesAdapter = new DepartureArrayAdapter(this, this, mTimeSource);
        setListAdapter(mDeparturesAdapter);
        mDeparturesViewModel = new ViewModelProvider(this,
                new DeparturesViewModelFactory(mTimeSource))
                .get(DeparturesViewModel.class);

        if (savedInstanceState != null
                && savedInstanceState.containsKey(RouteArguments.ORIGIN)) {
            mStationPair = RouteArguments.readRoute(savedInstanceState);
            setListTitle();
        } else {
            mStationPair = RouteArguments.readRoute(intent);
            setListTitle();
        }

        if (savedInstanceState != null) {
            mSelectedDepartureIdentity = savedInstanceState.getString(
                    STATE_SELECTED_DEPARTURE_ID);
            mRestoreDepartureActionMode = savedInstanceState.getBoolean(
                    STATE_HAS_DEPARTURE_ACTION_MODE);
        }

        updateEmptyState(mDeparturesAdapter.getItemCount() == 0);

        if (mStationPair == null) {
            finish();
            return;
        }

        mDeparturesViewModel.setQuery(
                bartRunnerApplication.getTransitRepository(),
                getApplicationContext(),
                mStationPair);
        LifecycleFlowCollector.collect(this, mDeparturesViewModel.getUiState(),
                this::renderState);
        mScreenTicker = new ScreenTicker(getLifecycle(),
                tick -> mDeparturesAdapter.setTick(tick));

        ActionBar supportActionBar = Assert.notNull(getSupportActionBar());
        supportActionBar.setHomeButtonEnabled(true);
        supportActionBar.setDisplayHomeAsUpEnabled(true);

        if (bartRunnerApplication.getAlarmController().isRingtoneRequested()) {
            soundTheAlarm();
        }

        if (bartRunnerApplication.getAlarmController().isSounding()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.train_alarm_text)
                    .setCancelable(false)
                    .setNeutralButton(R.string.silence_alarm,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog,
                                                    int which) {
                                    silenceAlarm();
                                    dialog.dismiss();
                                }
                            }).show();
        }
    }

    private void setSelectedDeparture(Departure departure) {
        mSelectedDeparture = departure;
        mSelectedDepartureIdentity = departure == null
                ? null : departure.getIdentity();
        mDeparturesAdapter.setSelectedDepartureIdentity(mSelectedDepartureIdentity);
    }

    private void soundTheAlarm() {
        final BartRunnerApplication application = (BartRunnerApplication) getApplication();

        Uri alarmSound = RingtoneManager
                .getDefaultUri(RingtoneManager.TYPE_ALARM);

        if (alarmSound == null || !tryToPlayRingtone(alarmSound)) {
            alarmSound = RingtoneManager
                    .getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (alarmSound == null || !tryToPlayRingtone(alarmSound)) {
                alarmSound = RingtoneManager
                        .getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
        }
        if (application.getAlarmController().getMediaPlayer() == null) {
            tryToPlayRingtone(alarmSound);
        }
        final Vibrator vibrator;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            vibrator = vibratorManager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 500}, 1));
        } else {
            vibrator.vibrate(new long[]{0, 500, 500}, 1);
        }
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                silenceAlarm();
            }
        }, 20000);

        application.getAlarmController().consumeRingtoneRequest();
        application.getAlarmController().setSounding(true);
    }

    private boolean tryToPlayRingtone(Uri alertSound) {
        MediaPlayer mediaPlayer = MediaPlayer.create(this, alertSound);
        if (mediaPlayer == null)
            return false;
        mediaPlayer.setLooping(true);
        mediaPlayer.start();
        ((BartRunnerApplication) getApplication()).getAlarmController()
                .setMediaPlayer(mediaPlayer);
        return true;
    }

    private void silenceAlarm() {
        final BartRunnerApplication application = (BartRunnerApplication) getApplication();
        application.getAlarmController().silence();
        final Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vibrator.cancel();
    }

    private void setListTitle() {
        String listTitle;
        if (mStationPair == null || mStationPair.getOrigin() == null || mStationPair.getDestination() == null) {
            listTitle = mStationPair != null && mStationPair.getOrigin() != null
                    ? getString(R.string.arrivals_at_station,
                    mStationPair.getOrigin().getName()) : "";
        } else {
            listTitle = mStationPair.getOrigin().getName() + " to " + mStationPair.getDestination().getName();
        }
        ((TextView) findViewById(R.id.listTitle)).setText(listTitle);
    }

    private RecyclerView getListView() {
        return mListView;
    }

    private void updateEmptyState(boolean show) {
        if (mEmptyView != null) {
            mEmptyView.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (mListView != null) {
            mListView.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void onDepartureClicked(Departure departure) {
        if (mActionMode != null) {
            mActionMode.finish();
        } else {
            openTripSchedule(departure);
        }
    }

    @Override
    public void onDepartureLongClicked(Departure departure) {
        setSelectedDeparture(departure);
        startDepartureActionMode();
    }

    protected DepartureArrayAdapter getListAdapter() {
        return mDeparturesAdapter;
    }

    protected void setListAdapter(DepartureArrayAdapter adapter) {
        mDeparturesAdapter = adapter;
        getListView().setAdapter(mDeparturesAdapter);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(mClearKeepScreenOnRunnable);
        WakeLocker.release();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mStationPair != null) {
            /*
             * A station-only lookup has a null destination and is still a
             * valid state that must survive activity recreation.
             */
            if (mSelectedDepartureIdentity != null) {
                outState.putString(STATE_SELECTED_DEPARTURE_ID,
                        mSelectedDepartureIdentity);
            }
            outState.putBoolean(STATE_HAS_DEPARTURE_ACTION_MODE,
                    isDepartureActionModeActive());
            RouteArguments.putRoute(outState, mStationPair);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow()
                    .addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mHandler.removeCallbacks(mClearKeepScreenOnRunnable);
            mHandler.postDelayed(mClearKeepScreenOnRunnable, 10 * 60 * 1000);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.route_menu, menu);
        MenuItem bartSiteItem = menu.findItem(R.id.view_on_bart_site_button);
        if (bartSiteItem != null) {
            bartSiteItem.setVisible(mStationPair != null
                    && mStationPair.getDestination() != null);
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem bartSiteItem = menu.findItem(R.id.view_on_bart_site_button);
        if (bartSiteItem != null) {
            bartSiteItem.setVisible(mStationPair != null
                    && mStationPair.getDestination() != null);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            Intent routesIntent = new Intent(this, RoutesListActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(routesIntent);
            return true;
        } else if (itemId == R.id.view_on_bart_site_button) {
            if (mStationPair.getDestination() == null) {
                return true;
            }
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://m.bart.gov/schedules/qp_results.aspx?type=departure&date=today&time="
                            + DateFormat.format("h:mmaa",
                            mTimeSource.nowMillis())
                            + "&orig="
                            + mStationPair.getOrigin().abbreviation
                            + "&dest="
                            + mStationPair.getDestination().abbreviation)));
            return true;
        } else if (itemId == R.id.view_system_map_button) {
            startActivity(new Intent(this, ViewMapActivity.class));
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void followDeparture(Departure selectedDeparture,
                                 boolean openTripScreen) {
        selectedDeparture = prepareDepartureForTrip(selectedDeparture);
        TripServiceCommand command = mTripActionsViewModel.followTrip(selectedDeparture);
        requestNotificationPermissionIfNeeded();

        startBoardedDepartureService(command);

        if (openTripScreen) {
            Intent tripIntent = new Intent(this, TripInProgressActivity.class);
            RouteArguments.putTrip(tripIntent, selectedDeparture.getStationPair(),
                    selectedDeparture.getIdentity(), RouteArguments.MODE_FOLLOWED);
            startActivity(tripIntent);
        }
    }

    private void openTripSchedule(Departure departure) {
        departure = prepareDepartureForTrip(departure);
        Intent intent = new Intent(this, TripInProgressActivity.class);
        RouteArguments.putTrip(intent, mStationPair, departure.getIdentity(),
                RouteArguments.MODE_SCHEDULE);
        startActivity(intent);
    }

    private Departure prepareDepartureForTrip(Departure departure) {
        return departure.withPassengerDestination(mStationPair.getDestination() != null
                ? mStationPair.getDestination()
                : departure.getTrainDestination());
    }

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    POST_NOTIFICATIONS_REQUEST_CODE);
        }
    }

    private void startBoardedDepartureService(TripServiceCommand command) {
        Intent intent = new Intent(this, BoardedDepartureService.class)
                .setAction(command.getAction());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void startDepartureActionMode() {
        if (mActionMode == null)
            mActionMode = startSupportActionMode(new DepartureActionMode());
        mActionMode.setTitle(mSelectedDeparture.getTrainDestinationName());
        mActionMode.setSubtitle(mSelectedDeparture.getTrainLengthAndPlatform());
    }

    private class DepartureActionMode implements ActionMode.Callback {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.departure_context_menu, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == R.id.boardTrain) {
                followDeparture(mSelectedDeparture, true);

                mode.finish();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            setSelectedDeparture(null);
            mActionMode = null;
        }

    }

    private void renderState(final DeparturesViewModel.State state) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                switch (state.getStatus()) {
                    case LOADING:
                        mEmptyView.setText(R.string.departure_wait_message);
                        updateEmptyState(true);
                        mProgress.setVisibility(View.VISIBLE);
                        break;
                    case CONTENT:
                        updateEmptyState(false);
                        mProgress.setVisibility(View.GONE);
                        mDeparturesAdapter.submitList(state.getDepartures(),
                                ViewDeparturesActivity.this::restoreSelectedDeparture);
                        break;
                    case EMPTY:
                        mDeparturesAdapter.submitList(state.getDepartures(),
                                ViewDeparturesActivity.this::restoreSelectedDeparture);
                        mEmptyView.setText(R.string.no_data_message);
                        updateEmptyState(true);
                        mProgress.setVisibility(View.GONE);
                        Linkify.addLinks(mEmptyView, Linkify.WEB_URLS);
                        break;
                    case ERROR:
                        mEmptyView.setText(R.string.could_not_connect);
                        updateEmptyState(true);
                        mProgress.setVisibility(View.GONE);
                        break;
                    default:
                        throw new IllegalStateException("Unknown departures state");
                }
            }
        });
    }

    private boolean isDepartureActionModeActive() {
        return mActionMode != null;
    }

    private void restoreSelectedDeparture() {
        if (mSelectedDepartureIdentity == null) {
            return;
        }

        for (int index = 0; index < mDeparturesAdapter.getItemCount(); index++) {
            Departure departure = mDeparturesAdapter.itemAt(index);
            if (mSelectedDepartureIdentity.equals(departure.getIdentity())) {
                if (mSelectedDeparture != departure) {
                    setSelectedDeparture(departure);
                }
                if (mRestoreDepartureActionMode && mActionMode == null) {
                    mRestoreDepartureActionMode = false;
                    startDepartureActionMode();
                }
                return;
            }
        }

        if (mSelectedDeparture != null) {
            if (mActionMode != null) {
                mActionMode.finish();
            } else {
                setSelectedDeparture(null);
            }
        }
        mRestoreDepartureActionMode = false;
    }

}
