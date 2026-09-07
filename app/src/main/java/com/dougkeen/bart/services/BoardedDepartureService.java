package com.dougkeen.bart.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.Manifest;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.R;
import com.dougkeen.bart.backend.RouteDepartureProjection;
import com.dougkeen.bart.backend.TransitProjectionListener;
import com.dougkeen.bart.backend.TransitRepository;
import com.dougkeen.bart.model.RealTimeDepartures;
import com.dougkeen.bart.model.Constants;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.platform.DepartureAlarmScheduler;
import com.dougkeen.bart.presentation.DepartureNotificationFactory;
import com.dougkeen.bart.platform.DepartureParcel;

import java.lang.ref.WeakReference;
import java.util.List;

public class BoardedDepartureService extends Service implements
        TransitProjectionListener<RealTimeDepartures> {

    private static final int DEPARTURE_NOTIFICATION_ID = 123;

    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mServiceHandler;

    private TransitRepository.Subscription mTransitSubscription;
    private StationPair mStationPair;
    private NotificationManagerCompat mNotificationManager;
    private Handler mHandler;
    private boolean mHasShutDown = false;

    public BoardedDepartureService() {
        super();
    }

    private static final class ServiceHandler extends Handler {
        private final WeakReference<BoardedDepartureService> mServiceRef;

        public ServiceHandler(Looper looper,
                              BoardedDepartureService boardedDepartureService) {
            super(looper);
            mServiceRef = new WeakReference<BoardedDepartureService>(
                    boardedDepartureService);
        }

        @Override
        public void handleMessage(Message msg) {
            BoardedDepartureService service = mServiceRef.get();
            if (service != null) {
                service.onHandleIntent((Intent) msg.obj);
            }
        }
    }

    @Override
    public void onCreate() {
        HandlerThread thread = new HandlerThread(
                "BartRunnerNotificationService");
        thread.start();

        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper, this);

        mNotificationManager = NotificationManagerCompat.from(this);
        mHandler = new Handler(Looper.getMainLooper());

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    this.getString(R.string.notification_channel_id),
                    this.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            NotificationManager notificationManager = this.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        super.onCreate();
    }

    private void enqueueIntent(Intent intent, int startId) {
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = startId;
        msg.obj = intent;
        mServiceHandler.sendMessage(msg);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mHasShutDown = false;
        enqueueIntent(intent, startId);
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        shutDown(true);
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
        mServiceLooper.quitSafely();
        super.onDestroy();
    }

    protected void onHandleIntent(Intent intent) {
        if (intent == null)
            return;

        final BartRunnerApplication application = (BartRunnerApplication) getApplication();
        final Departure boardedDeparture;
        if (intent.hasExtra("departure")) {
            DepartureParcel departureParcel = IntentCompat.getParcelableExtra(
                    intent, "departure", DepartureParcel.class);
            boardedDeparture = departureParcel == null
                    ? null : departureParcel.getDeparture();
        } else {
            boardedDeparture = application.getFollowedTripRepository().getFollowedDeparture();
        }
        if (boardedDeparture == null) {
            // Nothing to notify about
            if (mNotificationManager != null) {
                mNotificationManager.cancel(DEPARTURE_NOTIFICATION_ID);
            }
            return;
        }
        if (intent.getBooleanExtra("cancelNotifications", false)
                || intent.getBooleanExtra(Constants.CLEAR_DEPARTURE, false)) {
            // We want to cancel the alarm
            DepartureAlarmScheduler alarmScheduler = application
                    .getFollowedTripRepository().getAlarmScheduler();
            if (alarmScheduler != null) {
                alarmScheduler.cancel();
            }
            if (intent.getBooleanExtra(Constants.CLEAR_DEPARTURE, false)) {
                application.getFollowedTripRepository().clearFollowedDeparture();
                shutDown(false);
            } else {
                updateNotification();
            }
            return;
        }

        StationPair oldStationPair = mStationPair;
        mStationPair = boardedDeparture.getStationPair();

        if (mTransitSubscription != null && mStationPair != null
                && !mStationPair.equals(oldStationPair)) {
            mTransitSubscription.close();
            mTransitSubscription = null;
        }

        if (mStationPair != null && mTransitSubscription == null) {
            mTransitSubscription = ((BartRunnerApplication) getApplication())
                    .getTransitRepository().subscribe(
                            new RouteDepartureProjection(mStationPair,
                                    getApplicationContext()), this);
        }

        DepartureAlarmScheduler alarmScheduler = application.getFollowedTripRepository()
                .getAlarmScheduler();
        if (alarmScheduler == null) {
            return;
        }
        updateNotification();

        pollDepartureStatus();
    }

    private void updateAlarm() {
        DepartureAlarmScheduler alarmScheduler = ((BartRunnerApplication) getApplication())
                .getFollowedTripRepository().getAlarmScheduler();
        if (alarmScheduler != null) {
            alarmScheduler.update();
        }
    }

    @Override
    public void onData(RealTimeDepartures result,
                       com.dougkeen.bart.backend.TransitFeedSnapshot snapshot) {
        onETDChanged(result.getDepartures());
    }

    private void onETDChanged(List<Departure> departures) {
        final Departure boardedDeparture = ((BartRunnerApplication) getApplication())
                .getFollowedTripRepository().getFollowedDeparture();
        for (Departure departure : departures) {
            if (departure.equals(boardedDeparture)
                    && (boardedDeparture.getMeanSecondsLeft() != departure
                    .getMeanSecondsLeft() || boardedDeparture
                    .getUncertaintySeconds() != departure
                    .getUncertaintySeconds())) {
                boardedDeparture.mergeEstimate(departure, false);
                // Also merge back, in case boardedDeparture estimate is better
                departure.mergeEstimate(boardedDeparture, false);

                updateAlarm();
                break;
            }
        }
    }

    @Override
    public void onError(Exception exception,
                        com.dougkeen.bart.backend.TransitFeedSnapshot snapshot) {
        // Do nothing
    }

    private long mNextScheduledCheckClockTime = 0;

    private void pollDepartureStatus() {
        if (mHasShutDown) {
            return;
        }

        final Departure boardedDeparture = ((BartRunnerApplication) getApplication())
                .getFollowedTripRepository().getFollowedDeparture();

        if (BoardedDepartureServicePolicy.shouldStop(boardedDeparture != null,
                boardedDeparture != null && boardedDeparture.hasDeparted())) {
            shutDown(false);
            return;
        }

        if (mTransitSubscription == null && mStationPair != null) {
            mTransitSubscription = ((BartRunnerApplication) getApplication())
                    .getTransitRepository().subscribe(
                            new RouteDepartureProjection(mStationPair,
                                    getApplicationContext()), this);
        }

        DepartureAlarmScheduler alarmScheduler = ((BartRunnerApplication) getApplication())
                .getFollowedTripRepository().getAlarmScheduler();
        if (alarmScheduler != null) {
            alarmScheduler.update();
        }

        updateNotification();

        final int pollIntervalMillis = getPollIntervalMillis();
        final long scheduledCheckClockTime = System.currentTimeMillis()
                + pollIntervalMillis;
        if (mNextScheduledCheckClockTime < scheduledCheckClockTime) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    pollDepartureStatus();
                }
            }, pollIntervalMillis);
            mNextScheduledCheckClockTime = scheduledCheckClockTime;
        }
    }

    private void shutDown(boolean isBeingDestroyed) {
        if (!mHasShutDown) {
            if (mHandler != null) {
                mHandler.removeCallbacksAndMessages(null);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            mHasShutDown = true;
            if (mTransitSubscription != null) {
                mTransitSubscription.close();
                mTransitSubscription = null;
            }
            if (mNotificationManager != null) {
                mNotificationManager.cancel(DEPARTURE_NOTIFICATION_ID);
            }
            if (!isBeingDestroyed)
                stopSelf();
        }
    }

    private void updateNotification() {
        if (mHasShutDown) {
            return;
        }

        final Departure boardedDeparture = ((BartRunnerApplication) getApplication())
                .getFollowedTripRepository().getFollowedDeparture();
        if (boardedDeparture != null) {
            Notification notification = DepartureNotificationFactory.create(
                    getApplicationContext(), boardedDeparture,
                    ((BartRunnerApplication) getApplication()).getFollowedTripRepository()
                            .getAlarmScheduler());
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    || ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                mNotificationManager.notify(DEPARTURE_NOTIFICATION_ID, notification);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(DEPARTURE_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(DEPARTURE_NOTIFICATION_ID, notification);
            }
        }
    }

    private int getPollIntervalMillis() {
        DepartureAlarmScheduler alarmScheduler = ((BartRunnerApplication) getApplication())
                .getFollowedTripRepository().getAlarmScheduler();
        return BoardedDepartureServicePolicy.pollIntervalMillis(
                alarmScheduler == null ? 0 : alarmScheduler.getSecondsUntilAlarm());
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Doesn't support binding
        return null;
    }

}
