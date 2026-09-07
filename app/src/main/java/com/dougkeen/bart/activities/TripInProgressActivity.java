package com.dougkeen.bart.activities;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.format.DateFormat;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.IntentCompat;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.R;
import com.dougkeen.bart.data.LifecycleFlowCollector;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.TimeSource;
import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.model.TripStop;
import com.dougkeen.bart.platform.DepartureParcel;
import com.dougkeen.bart.presentation.DepartureTextFormatter;
import com.dougkeen.bart.services.BoardedDepartureService;

import java.util.Date;
import java.util.List;

/** Shows the live state of a selected, possibly multi-train trip. */
public class TripInProgressActivity extends AbstractViewActivity {

    private static final int POST_NOTIFICATIONS_REQUEST_CODE = 1002;

    private final Handler mHandler = new Handler(android.os.Looper.getMainLooper());
    private TimeSource mTimeSource;
    private Departure mDeparture;
    private TripProgressViewModel mTripProgressViewModel;
    private TripActionsViewModel mTripActionsViewModel;
    private boolean mIsFollowing;

    private TextView mStatus;
    private TextView mRoute;
    private TextView mArrival;
    private View mFollowTripButton;
    private LinearLayout mTimeline;

    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            renderTrip();
            if (!isFinishing()) {
                mHandler.postDelayed(this, 1000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.trip_in_progress);

        mStatus = (TextView) findViewById(R.id.tripStatus);
        mRoute = (TextView) findViewById(R.id.tripRoute);
        mArrival = (TextView) findViewById(R.id.tripArrival);
        mFollowTripButton = findViewById(R.id.followTripButton);
        mTimeline = (LinearLayout) findViewById(R.id.tripTimeline);

        mFollowTripButton.setOnClickListener(view -> followTrip());

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        BartRunnerApplication application = (BartRunnerApplication) getApplication();
        mTimeSource = application.getTimeSource();
        mTripActionsViewModel = new androidx.lifecycle.ViewModelProvider(this)
                .get(TripActionsViewModel.class);
        DepartureParcel departureParcel = IntentCompat.getParcelableExtra(
                getIntent(), BoardedDepartureService.DEPARTURE_EXTRA,
                DepartureParcel.class);
        Departure requestedDeparture = departureParcel == null
                ? null : departureParcel.getDeparture();
        if (requestedDeparture != null) {
            mDeparture = requestedDeparture;
            Departure followedDeparture = mTripActionsViewModel.getFollowedDeparture();
            mIsFollowing = followedDeparture != null
                    && followedDeparture.equals(requestedDeparture);
        } else {
            mDeparture = mTripActionsViewModel.getFollowedDeparture();
            mIsFollowing = mDeparture != null;
        }
        if (mDeparture == null) {
            finish();
            return;
        }
        mTripProgressViewModel = new androidx.lifecycle.ViewModelProvider(this)
                .get(TripProgressViewModel.class);
        mTripProgressViewModel.setDeparture(mDeparture, mTimeSource);
        LifecycleFlowCollector.collect(this,
                mTripProgressViewModel.getDepartureState(), departure -> {
                    if (departure == null) {
                        return;
                    }
                    mDeparture = departure;
                    if (mIsFollowing) {
                        mTripActionsViewModel.updateFollowedTrip(mDeparture);
                    }
                    renderTrip();
                    invalidateOptionsMenu();
                });
        updateFollowState();
        renderTrip();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mHandler.post(mRefreshRunnable);
    }

    @Override
    protected void onStop() {
        mHandler.removeCallbacks(mRefreshRunnable);
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.trip_in_progress_menu, menu);
        updateMenu(menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateMenu(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    private void updateMenu(Menu menu) {
        if (mDeparture == null) {
            return;
        }
        MenuItem cancel = menu.findItem(R.id.cancel_alarm_button);
        MenuItem set = menu.findItem(R.id.set_alarm_button);
        MenuItem delete = menu.findItem(R.id.delete);
        boolean alarmPending = mTripActionsViewModel.isAlarmPending();
        cancel.setVisible(mIsFollowing && alarmPending);
        set.setVisible(mIsFollowing && !alarmPending
                && mDeparture.getMeanSecondsLeft(
                mDeparture.getMinEstimate(), mDeparture.getMaxEstimate(),
                mTimeSource.nowMillis()) > 60);
        delete.setVisible(mIsFollowing);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (mDeparture == null) {
            return super.onOptionsItemSelected(item);
        }
        if (item.getItemId() == R.id.set_alarm_button) {
            new com.dougkeen.bart.activities.TrainAlarmDialogFragment()
                    .show(getSupportFragmentManager(),
                            TrainAlarmDialogFragment.TAG);
            return true;
        } else if (item.getItemId() == R.id.cancel_alarm_button) {
            sendServiceAction(mTripActionsViewModel.cancelAlarm());
            return true;
        } else if (item.getItemId() == R.id.share_arrival) {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "My BART trip");
            share.putExtra(Intent.EXTRA_TEXT, getString(
                    R.string.arrival_message,
                    mDeparture.getStationPair().getDestination().getName(),
                    DepartureTextFormatter.estimatedArrivalTime(
                            this, mDeparture, false)));
            startActivity(Intent.createChooser(share,
                    getString(R.string.share_arrival_time)));
            return true;
        } else if (item.getItemId() == R.id.delete) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.clear_trip_confirmation)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog,
                                                     int which) {
                                    sendServiceAction(mTripActionsViewModel.clearTrip());
                                    finish();
                                }
                            }).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void sendServiceAction(TripServiceCommand command) {
        Intent intent = new Intent(this, BoardedDepartureService.class)
                .setAction(command.getAction());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void followTrip() {
        if (mDeparture == null || mIsFollowing) {
            return;
        }

        TripServiceCommand command = mTripActionsViewModel.followTrip(mDeparture);
        requestNotificationPermissionIfNeeded();

        startBoardedDepartureService(command);

        mIsFollowing = true;
        updateFollowState();
        invalidateOptionsMenu();
    }

    private void startBoardedDepartureService(TripServiceCommand command) {
        Intent intent = new Intent(this, BoardedDepartureService.class)
                .setAction(command.getAction());
        if (command.getDeparture() != null) {
            intent.putExtra(BoardedDepartureService.DEPARTURE_EXTRA,
                    new DepartureParcel(command.getDeparture()));
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    POST_NOTIFICATIONS_REQUEST_CODE);
        }
    }

    private void updateFollowState() {
        mFollowTripButton.setVisibility(mIsFollowing ? View.GONE : View.VISIBLE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(mIsFollowing
                    ? R.string.trip_in_progress : R.string.train_schedule);
        }
    }

    private void renderTrip() {
        if (mDeparture == null || mDeparture.getStationPair() == null) {
            return;
        }
        mRoute.setText(mDeparture.getStationPair().getOrigin().getName() + " → "
                + mDeparture.getStationPair().getDestination().getName());
        mStatus.setText(getTripStatus());
        String estimatedArrival = DepartureTextFormatter.estimatedArrivalTime(
                this, mDeparture, false);
        mArrival.setText(estimatedArrival.isEmpty()
                ? getString(R.string.trip_final_arrival_unknown,
                mDeparture.getStationPair().getDestination().getName())
                : getString(R.string.trip_final_arrival,
                mDeparture.getStationPair().getDestination().getName(),
                estimatedArrival));

        mTimeline.removeAllViews();
        List<TripLeg> legs = mDeparture.getTripLegs();
        if (legs.isEmpty()) {
            addText(R.string.trip_details_unavailable, false);
            return;
        }
        int firstActiveLeg = getFirstActiveLegIndex(legs);
        for (int i = firstActiveLeg; i < legs.size(); i++) {
            TripLeg leg = legs.get(i);
            addLeg(leg, i == firstActiveLeg);
            if (i + 1 < legs.size() && i + 1 >= firstActiveLeg) {
                addConnection(leg, legs.get(i + 1));
            }
        }
    }

    private String getTripStatus() {
        if (mDeparture.isCanceled()) {
            return getString(R.string.trip_canceled);
        }
        long nowMillis = mTimeSource.nowMillis();
        if (!mDeparture.hasDeparted(nowMillis)) {
            return getString(R.string.trip_leaves_in,
                    DepartureTextFormatter.countdown(this, mDeparture, nowMillis));
        }

        String connectionStatus = getWaitingConnectionStatus();
        if (connectionStatus != null) {
            return connectionStatus;
        }

        TripStop nextStop = getNextStop();
        if (nextStop != null) {
            return getString(R.string.trip_next_stop, nextStop.getStation().getName(),
                    formatEta(nextStop.getArrivalTime()));
        }

        long arrival = mDeparture.getEstimatedArrivalTime();
        if (arrival > 0 && arrival <= nowMillis) {
            return getString(R.string.trip_arrived);
        }
        return getString(R.string.trip_current_train);
    }

    private int getFirstActiveLegIndex(List<TripLeg> legs) {
        long now = mTimeSource.nowMillis();
        for (int i = 0; i < legs.size(); i++) {
            if (!hasPassedAllStops(legs.get(i), now)) {
                return i;
            }
        }
        return legs.size();
    }

    private boolean hasPassedAllStops(TripLeg leg, long now) {
        if (!leg.getStops().isEmpty()) {
            for (TripStop stop : leg.getStops()) {
                if (stop.getArrivalTime() <= 0 || stop.getArrivalTime() > now) {
                    return false;
                }
            }
            return true;
        }
        return leg.getArrivalTime() > 0 && leg.getArrivalTime() <= now;
    }

    private TripStop getNextStop() {
        long now = mTimeSource.nowMillis();
        List<TripLeg> legs = mDeparture.getTripLegs();
        int firstActiveLeg = getFirstActiveLegIndex(legs);
        for (int i = firstActiveLeg; i < legs.size(); i++) {
            TripLeg leg = legs.get(i);
            for (TripStop stop : leg.getStops()) {
                if (stop.getStation() != leg.getOrigin()
                        && stop.getArrivalTime() > now) {
                    return stop;
                }
            }
        }
        return null;
    }

    private String getWaitingConnectionStatus() {
        long now = mTimeSource.nowMillis();
        List<TripLeg> legs = mDeparture.getTripLegs();
        for (int i = 0; i + 1 < legs.size(); i++) {
            TripLeg arrivingLeg = legs.get(i);
            TripLeg nextLeg = legs.get(i + 1);
            if (arrivingLeg.getArrivalTime() > 0
                    && arrivingLeg.getArrivalTime() <= now
                    && nextLeg.getDepartureTime() > now) {
                String lineName = nextLeg.getLine() == null ? "Train"
                        : nextLeg.getLine().getDisplayName();
                return getString(R.string.trip_transfer_now, lineName);
            }
            if (arrivingLeg.getArrivalTime() > 0
                    && arrivingLeg.getArrivalTime() <= now
                    && nextLeg.getDepartureTime() <= 0) {
                return getString(R.string.trip_no_departure_scheduled);
            }
        }
        return null;
    }

    private void addLeg(TripLeg leg, boolean isCurrentTrain) {
        TextView heading = addText(null, true);
        String lineName = leg.getLine() == null ? "Train"
                : leg.getLine().getDisplayName();
        String prefix = isCurrentTrain ? getString(R.string.trip_current_train)
                : getString(R.string.trip_connection_train);
        heading.setText(prefix + " · " + lineName);

        TextView route = addText(null, false);
        route.setText(leg.getOrigin().getName() + " → "
                + leg.getDestination().getName());
        route.setTextColor(getResources().getColor(R.color.text_secondary));

        if (leg.getStops().isEmpty()) {
            TextView arrival = addText(null, false);
            arrival.setText(leg.getDepartureTime() > 0
                    ? getString(R.string.trip_train_departure,
                    formatTime(leg.getDepartureTime()))
                    : getString(R.string.trip_no_departure_scheduled));
            return;
        }
        for (TripStop stop : leg.getStops()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(8), dp(7), dp(8), dp(7));

            TextView station = new TextView(this);
            station.setText(stop.getStation().getName());
            station.setTextColor(getResources().getColor(R.color.text_primary));
            station.setTextSize(16);
            row.addView(station, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView time = new TextView(this);
            time.setGravity(android.view.Gravity.RIGHT);
            time.setTextColor(getResources().getColor(R.color.text_secondary));
            long displayedTime = stop.getStation() == leg.getOrigin()
                    ? stop.getDepartureTime() : stop.getArrivalTime();
            time.setText(formatTime(displayedTime) + "\n"
                    + formatEta(displayedTime));
            row.addView(time, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            mTimeline.addView(row);
        }
    }

    private void addConnection(TripLeg arrivingLeg, TripLeg nextLeg) {
        TextView connection = addText(null, true);
        connection.setText(getString(R.string.trip_transfer_at,
                arrivingLeg.getDestination().getName()));
        connection.setTextColor(getResources().getColor(R.color.brand_primary));

        TextView details = addText(null, false);
        long arrival = getArrivalAt(arrivingLeg, arrivingLeg.getDestination());
        long departure = getDepartureAt(nextLeg, nextLeg.getOrigin());
        if (departure <= 0) {
            details.setText(R.string.trip_no_departure_scheduled);
        } else {
            details.setText(getString(R.string.trip_connection_details,
                    formatTime(arrival), formatEta(arrival), formatTime(departure),
                    formatMargin(departure - arrival)));
        }
        long margin = departure - arrival;
        details.setTextColor(getResources().getColor(
                isConnectionWarning(arrivingLeg, margin)
                        ? R.color.connection_warning : R.color.text_secondary));
    }

    private boolean isConnectionWarning(TripLeg arrivingLeg, long margin) {
        int minimumSeconds = arrivingLeg.getMinimumTransferSecondsAfter();
        return margin < 0
                || (minimumSeconds > 0 && margin < minimumSeconds * 1000L);
    }

    private long getArrivalAt(TripLeg leg,
                              com.dougkeen.bart.model.Station station) {
        for (TripStop stop : leg.getStops()) {
            if (stop.getStation() == station) {
                return stop.getArrivalTime();
            }
        }
        return leg.getArrivalTime();
    }

    private long getDepartureAt(TripLeg leg,
                                com.dougkeen.bart.model.Station station) {
        for (TripStop stop : leg.getStops()) {
            if (stop.getStation() == station) {
                return stop.getDepartureTime();
            }
        }
        return leg.getDepartureTime();
    }

    private TextView addText(Integer resource, boolean heading) {
        TextView text = new TextView(this);
        text.setPadding(dp(8), heading ? dp(12) : dp(3), dp(8), dp(3));
        text.setTextSize(heading ? 18 : 14);
        text.setTextColor(getResources().getColor(
                heading ? R.color.text_primary : R.color.text_secondary));
        if (heading) {
            text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        if (resource != null) {
            text.setText(resource);
        }
        mTimeline.addView(text);
        return text;
    }

    private String formatTime(long time) {
        return time > 0 ? DateFormat.getTimeFormat(this)
                .format(new Date(time)) : "--";
    }

    private String formatEta(long time) {
        if (time <= 0) {
            return getString(R.string.trip_eta_unknown);
        }
        long seconds = (time - mTimeSource.nowMillis()) / 1000L;
        if (seconds <= 0) {
            return getString(R.string.trip_stop_passed);
        }
        long minutes = seconds / 60L;
        return getString(R.string.trip_eta_minutes_seconds, minutes,
                seconds % 60L);
    }

    private String formatMargin(long margin) {
        if (margin < 0) {
            return getString(R.string.trip_connection_missed);
        }
        long seconds = margin / 1000L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        if (minutes == 0) {
            return getString(R.string.trip_margin_seconds, seconds);
        }
        return seconds == 0
                ? getString(R.string.trip_margin_minutes, minutes)
                : getString(R.string.trip_margin_minutes_seconds, minutes,
                seconds);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

}
