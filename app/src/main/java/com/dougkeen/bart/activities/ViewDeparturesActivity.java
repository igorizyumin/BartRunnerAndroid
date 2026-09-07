package com.dougkeen.bart.activities;

import java.util.List;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.content.ServiceConnection;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.VibratorManager;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ActionMode;
import androidx.core.content.IntentCompat;
import androidx.core.os.BundleCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.format.DateFormat;
import android.text.util.Linkify;
import android.util.Log;
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
import com.dougkeen.bart.controls.Ticker;
import com.dougkeen.bart.data.DepartureArrayAdapter;
import com.dougkeen.bart.model.Constants;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.services.BoardedDepartureService;
import com.dougkeen.bart.services.EtdService;
import com.dougkeen.bart.services.EtdService.EtdServiceBinder;
import com.dougkeen.bart.services.EtdService.EtdServiceListener;
import com.dougkeen.util.Assert;
import com.dougkeen.util.WakeLocker;

public class ViewDeparturesActivity extends AbstractViewActivity implements
        EtdServiceListener, DepartureArrayAdapter.Listener {

    private StationPair mStationPair;

    private Departure mSelectedDeparture;

    private DepartureArrayAdapter mDeparturesAdapter;

    private TextView mEmptyView;
    private ProgressBar mProgress;
    private RecyclerView mListView;

    private ActionMode mActionMode;

    private EtdService mEtdService;

    private final Handler mHandler = new Handler(android.os.Looper.getMainLooper());

    private boolean mBound = false;

    private static final int POST_NOTIFICATIONS_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.departures);

        final Intent intent = getIntent();

        final BartRunnerApplication bartRunnerApplication = (BartRunnerApplication) getApplication();

        if (bartRunnerApplication.shouldPlayAlarmRingtone()
                || bartRunnerApplication.isAlarmSounding()) {
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
        mDeparturesAdapter = new DepartureArrayAdapter(this, this);
        setListAdapter(mDeparturesAdapter);

        if (savedInstanceState != null
                && savedInstanceState.containsKey("stationPair")) {
            mStationPair = BundleCompat.getParcelable(savedInstanceState,
                    "stationPair", StationPair.class);
            setListTitle();
        } else {
            mStationPair = IntentCompat.getParcelableExtra(intent,
                    Constants.STATION_PAIR_EXTRA, StationPair.class);
            setListTitle();
            if (mBound && mEtdService != null)
                mEtdService
                        .registerListener(ViewDeparturesActivity.this, false);
        }

        if (savedInstanceState != null) {
            Parcelable[] departuresArray = savedInstanceState.getParcelableArray("departures");
            if (departuresArray != null) {
                for (Parcelable departure : departuresArray) {
                    mDeparturesAdapter.add((Departure) departure);
                }
                mDeparturesAdapter.notifyDataSetChanged();
            }
            if (savedInstanceState.containsKey("selectedDeparture")) {
                setSelectedDeparture(BundleCompat.getParcelable(savedInstanceState,
                        "selectedDeparture", Departure.class));
            }
            if (savedInstanceState.getBoolean("hasDepartureActionMode")
                    && mSelectedDeparture != null) {
                startDepartureActionMode();
            }
        }

        updateEmptyState(mDeparturesAdapter.getCount() == 0);

        if (mStationPair == null) {
            finish();
            return;
        }

        ActionBar supportActionBar = Assert.notNull(getSupportActionBar());
        supportActionBar.setHomeButtonEnabled(true);
        supportActionBar.setDisplayHomeAsUpEnabled(true);

        if (bartRunnerApplication.shouldPlayAlarmRingtone()) {
            soundTheAlarm();
        }

        if (bartRunnerApplication.isAlarmSounding()) {
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
        if (mSelectedDeparture != null && !mSelectedDeparture.equals(departure)) {
            mSelectedDeparture.setSelected(false);
        }
        if (departure != null) {
            departure.setSelected(true);
        }
        mSelectedDeparture = departure;
        mDeparturesAdapter.notifyDataSetChanged();
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
        if (application.getAlarmMediaPlayer() == null) {
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

        application.setPlayAlarmRingtone(false);
        application.setAlarmSounding(true);
    }

    private boolean tryToPlayRingtone(Uri alertSound) {
        MediaPlayer mediaPlayer = MediaPlayer.create(this, alertSound);
        if (mediaPlayer == null)
            return false;
        mediaPlayer.setLooping(true);
        mediaPlayer.start();
        ((BartRunnerApplication) getApplication())
                .setAlarmMediaPlayer(mediaPlayer);
        return true;
    }

    private void silenceAlarm() {
        final BartRunnerApplication application = (BartRunnerApplication) getApplication();
        final MediaPlayer mediaPlayer = application.getAlarmMediaPlayer();
        application.setAlarmSounding(false);
        application.setAlarmMediaPlayer(null);
        final Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vibrator.cancel();
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
                mediaPlayer.release();
            }
        } catch (IllegalStateException e) {
            Log.e(Constants.TAG,
                    "Couldn't stop media player; It was in an invalid state", e);
        }
    }

    private void setListTitle() {
        String listTitle;
        if (mStationPair == null || mStationPair.getOrigin() == null || mStationPair.getDestination() == null) {
            listTitle = mStationPair != null && mStationPair.getOrigin() != null
                    ? getString(R.string.arrivals_at_station,
                    mStationPair.getOrigin().name) : "";
        } else {
            listTitle = mStationPair.getOrigin().name + " to " + mStationPair.getDestination().name;
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

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mEtdService = null;
            mBound = false;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mEtdService = ((EtdServiceBinder) service).getService();
            mBound = true;
            if (getStationPair() != null) {
                mEtdService
                        .registerListener(ViewDeparturesActivity.this, false);
            }
        }
    };

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
        if (mEtdService != null)
            mEtdService.unregisterListener(this);
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
        Ticker.getInstance().stopTicking(this);
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
            Departure[] departures = new Departure[mDeparturesAdapter
                    .getCount()];
            for (int i = mDeparturesAdapter.getCount() - 1; i >= 0; i--) {
                departures[i] = mDeparturesAdapter.getItem(i);
            }
            outState.putParcelableArray("departures", departures);
            outState.putParcelable("selectedDeparture", mSelectedDeparture);
            outState.putBoolean("hasDepartureActionMode",
                    isDepartureActionModeActive());
            outState.putParcelable("stationPair", mStationPair);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, EtdService.class), mConnection,
                Context.BIND_AUTO_CREATE);
        Ticker.getInstance().startTicking(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow()
                    .addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (getWindow() != null)
                        getWindow().clearFlags(
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                }
            }, 10 * 60 * 1000);
            Ticker.getInstance().startTicking(this);
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
                            System.currentTimeMillis())
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

    private void setBoardedDeparture(Departure selectedDeparture,
                                     boolean openTripScreen) {
        prepareDepartureForTrip(selectedDeparture);
        final BartRunnerApplication application = (BartRunnerApplication) getApplication();
        application.setBoardedDeparture(selectedDeparture);
        requestNotificationPermissionIfNeeded();

        // Start the notification service
        final Intent intent = new Intent(ViewDeparturesActivity.this,
                BoardedDepartureService.class);
        intent.putExtra("departure", selectedDeparture);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startBoardedDepartureService(intent);
        }

        if (openTripScreen) {
            startActivity(new Intent(this, TripInProgressActivity.class));
        }
    }

    private void openTripSchedule(Departure departure) {
        prepareDepartureForTrip(departure);
        Intent intent = new Intent(this, TripInProgressActivity.class);
        intent.putExtra("departure", departure);
        startActivity(intent);
    }

    private void prepareDepartureForTrip(Departure departure) {
        departure.setPassengerDestination(mStationPair.getDestination() != null
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

    private void startBoardedDepartureService(Intent intent) {
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
                setBoardedDeparture(mSelectedDeparture, true);

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

    @Override
    public void onETDChanged(final List<Departure> departures) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (departures.isEmpty()) {
                    final TextView textView = mEmptyView;
                    textView.setText(R.string.no_data_message);
                    updateEmptyState(true);
                    mProgress.setVisibility(View.GONE);
                    Linkify.addLinks(textView, Linkify.WEB_URLS);
                } else {
                    updateEmptyState(false);
                    // TODO: Figure out why Ticker occasionally stops
                    Ticker.getInstance().startTicking(
                            ViewDeparturesActivity.this);

                    // Merge lists
                    if (mDeparturesAdapter.getCount() > 0) {
                        int adapterIndex = -1;
                        for (Departure departure : departures) {
                            adapterIndex++;
                            Departure existingDeparture = null;
                            if (adapterIndex < mDeparturesAdapter.getCount()) {
                                existingDeparture = mDeparturesAdapter
                                        .getItem(adapterIndex);
                            }
                            while (existingDeparture != null
                                    && !departure.equals(existingDeparture)) {
                                mDeparturesAdapter.remove(existingDeparture);
                                if (adapterIndex < mDeparturesAdapter
                                        .getCount()) {
                                    existingDeparture = mDeparturesAdapter
                                            .getItem(adapterIndex);
                                } else {
                                    existingDeparture = null;
                                }
                            }
                            if (existingDeparture != null) {
                                existingDeparture.mergeEstimate(departure);
                            } else {
                                mDeparturesAdapter.add(departure);
                            }
                        }
                    } else {
                        final DepartureArrayAdapter listAdapter = getListAdapter();
                        listAdapter.clear();
                        for (Departure departure : departures) {
                            listAdapter.add(departure);
                        }
                    }

                    getListAdapter().notifyDataSetChanged();
                }
            }
        });
    }

    @Override
    public void onError(final String errorMessage) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mEmptyView.setText(errorMessage);
                updateEmptyState(true);
            }
        });
    }

    @Override
    public void onRequestStarted() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mProgress.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onRequestEnded() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mProgress.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public StationPair getStationPair() {
        return mStationPair;
    }

    private boolean isDepartureActionModeActive() {
        return mActionMode != null;
    }

}
