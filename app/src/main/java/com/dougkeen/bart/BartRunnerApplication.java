package com.dougkeen.bart;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import com.dougkeen.bart.data.FavoritesRepository;
import com.dougkeen.bart.backend.HttpTransitFeedClient;
import com.dougkeen.bart.backend.TransitRepository;
import com.dougkeen.bart.model.Constants;
import com.dougkeen.bart.model.Departure;

public class BartRunnerApplication extends Application implements
        Application.ActivityLifecycleCallbacks {

    private static final int FIVE_MINUTES = 5 * 60 * 1000;

    private static final String CACHE_FILE_NAME = "lastBoardedDeparture";
    private static final String PREFS_NAME = "prefs_bart_runner";
    private static final String PREFS_ACTIVITY_TIMESTAMP = "prefs_activity_timestamp";

    private Departure mBoardedDeparture;

    private boolean mPlayAlarmRingtone;

    private boolean mAlarmSounding;

    private MediaPlayer mAlarmMediaPlayer;

    private SharedPreferences mApplicationPreferences;

    private static Context context;

    private FavoritesRepository favoritesRepository;

    private TransitRepository transitRepository;

    private final ScheduledExecutorService transitScheduler =
            Executors.newSingleThreadScheduledExecutor();

    private final ExecutorService transitProjectionExecutor =
            Executors.newFixedThreadPool(2);

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        mApplicationPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        favoritesRepository = new FavoritesRepository(this);
        transitRepository = new TransitRepository(
                new HttpTransitFeedClient(),
                transitScheduler,
                transitProjectionExecutor,
                new Handler(Looper.getMainLooper())::post,
                30_000L);
        registerActivityLifecycleCallbacks(this);
    }

    public static Context getAppContext() {
        return context;
    }

    public TransitRepository getTransitRepository() {
        return transitRepository;
    }

    public FavoritesRepository getFavoritesRepository() {
        return favoritesRepository;
    }

    public boolean shouldPlayAlarmRingtone() {
        return mPlayAlarmRingtone;
    }

    public void setPlayAlarmRingtone(boolean playAlarmRingtone) {
        this.mPlayAlarmRingtone = playAlarmRingtone;
    }

    public Departure getBoardedDeparture() {
        return getBoardedDeparture(false);
    }

    public Departure getBoardedDeparture(boolean useOldCache) {
        if (mBoardedDeparture == null) {
            // see if there's a saved one
            File cachedDepartureFile = new File(getCacheDir(), CACHE_FILE_NAME);
            if (cachedDepartureFile.exists()) {
                InputStream inputStream = null;
                try {
                    inputStream = new FileInputStream(cachedDepartureFile);
                    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        bytes.write(buffer, 0, bytesRead);
                    }
                    final byte[] byteArray = bytes.toByteArray();
                    final Parcel parcel = Parcel.obtain();
                    parcel.unmarshall(byteArray, 0, byteArray.length);
                    parcel.setDataPosition(0);
                    Departure lastBoardedDeparture = Departure.CREATOR
                            .createFromParcel(parcel);
                    parcel.recycle();

                    /*
                     * Ooptionally check if the cached one is relatively recent.
                     * If so, restore that to the application context
                     */
                    long now = System.currentTimeMillis();
                    if (useOldCache
                            || lastBoardedDeparture.getEstimatedArrivalTime() >= now
                            - FIVE_MINUTES
                            || lastBoardedDeparture.getMeanEstimate() >= now
                            - 2 * FIVE_MINUTES) {
                        mBoardedDeparture = lastBoardedDeparture;
                    }
                } catch (Exception e) {
                    Log.w(Constants.TAG,
                            "Couldn't read or unmarshal lastBoardedDeparture file",
                            e);
                    try {
                        cachedDepartureFile.delete();
                    } catch (SecurityException anotherException) {
                        Log.w(Constants.TAG,
                                "Couldn't delete lastBoardedDeparture file",
                                anotherException);
                    }
                } finally {
                    closeQuietly(inputStream);
                }
            }
        }
        if (mBoardedDeparture != null && mBoardedDeparture.hasExpired()) {
            setBoardedDeparture(null);
        }
        return mBoardedDeparture;
    }

    public void setBoardedDeparture(Departure boardedDeparture) {
        if (!Objects.equals(boardedDeparture, mBoardedDeparture)
                || compareDepartures(mBoardedDeparture, boardedDeparture) != 0) {
            if (this.mBoardedDeparture != null) {
                this.mBoardedDeparture.getAlarmLeadTimeMinutesObservable()
                        .unregisterAllObservers();
                this.mBoardedDeparture.getAlarmPendingObservable()
                        .unregisterAllObservers();

                // Cancel any pending alarms for the current departure
                if (this.mBoardedDeparture.isAlarmPending()) {
                    this.mBoardedDeparture
                            .cancelAlarm(
                                    this,
                                    (AlarmManager) getSystemService(Context.ALARM_SERVICE));
                }
            }

            this.mBoardedDeparture = boardedDeparture;

            File cachedDepartureFile = new File(getCacheDir(), CACHE_FILE_NAME);
            if (mBoardedDeparture == null) {
                try {
                    cachedDepartureFile.delete();
                } catch (SecurityException anotherException) {
                    Log.w(Constants.TAG,
                            "Couldn't delete lastBoardedDeparture file",
                            anotherException);
                }
            } else {
                FileOutputStream fileOutputStream = null;
                try {
                    fileOutputStream = new FileOutputStream(cachedDepartureFile);
                    Parcel parcel = Parcel.obtain();
                    mBoardedDeparture.writeToParcel(parcel, 0);
                    fileOutputStream.write(parcel.marshall());
                } catch (Exception e) {
                    Log.w(Constants.TAG,
                            "Couldn't write last boarded departure cache file",
                            e);
                } finally {
                    closeQuietly(fileOutputStream);
                }
            }
        }
    }

    public boolean isAlarmSounding() {
        return mAlarmSounding;
    }

    private static int compareDepartures(Departure first, Departure second) {
        if (first == second) {
            return 0;
        }
        if (first == null) {
            return -1;
        }
        if (second == null) {
            return 1;
        }
        return first.compareTo(second);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (java.io.IOException ignored) {
            // Best-effort cleanup for the application cache.
        }
    }

    public void setAlarmSounding(boolean alarmSounding) {
        this.mAlarmSounding = alarmSounding;
    }

    public MediaPlayer getAlarmMediaPlayer() {
        return mAlarmMediaPlayer;
    }

    public void setAlarmMediaPlayer(MediaPlayer alarmMediaPlayer) {
        this.mAlarmMediaPlayer = alarmMediaPlayer;
    }

    public void setActivityTimestamp(long timestamp) {
        mApplicationPreferences.edit().putLong(PREFS_ACTIVITY_TIMESTAMP, timestamp).apply();
    }

    public long getActivityTimestamp() {
        return mApplicationPreferences.getLong(PREFS_ACTIVITY_TIMESTAMP, 0L);
    }


    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {

    }

    @Override
    public void onActivityResumed(Activity activity) {
        setActivityTimestamp(System.currentTimeMillis());
    }

    @Override
    public void onActivityPaused(Activity activity) {

    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

    }

    @Override
    public void onActivityDestroyed(Activity activity) {

    }
}
