package com.dougkeen.bart;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.dougkeen.bart.data.FavoritesRepository;
import com.dougkeen.bart.data.FollowedTripRepository;
import com.dougkeen.bart.data.AlarmController;
import com.dougkeen.bart.backend.HttpTransitFeedClient;
import com.dougkeen.bart.backend.TransitRepository;
import com.dougkeen.bart.model.SystemTimeSource;
import com.dougkeen.bart.model.TimeSource;

public class BartRunnerApplication extends Application implements
        Application.ActivityLifecycleCallbacks {

    private static final String PREFS_NAME = "prefs_bart_runner";
    private static final String PREFS_ACTIVITY_TIMESTAMP = "prefs_activity_timestamp";

    private SharedPreferences mApplicationPreferences;

    private FavoritesRepository favoritesRepository;

    private FollowedTripRepository followedTripRepository;

    private AlarmController alarmController;

    private TransitRepository transitRepository;

    private final TimeSource timeSource = SystemTimeSource.INSTANCE;

    private final ScheduledExecutorService transitScheduler =
            Executors.newSingleThreadScheduledExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        mApplicationPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        favoritesRepository = new FavoritesRepository(this);
        followedTripRepository = new FollowedTripRepository(this);
        alarmController = new AlarmController();
        transitRepository = new TransitRepository(
                new HttpTransitFeedClient(),
                transitScheduler,
                30_000L);
        registerActivityLifecycleCallbacks(this);
    }

    public TransitRepository getTransitRepository() {
        return transitRepository;
    }

    public FavoritesRepository getFavoritesRepository() {
        return favoritesRepository;
    }

    public FollowedTripRepository getFollowedTripRepository() {
        return followedTripRepository;
    }

    public AlarmController getAlarmController() {
        return alarmController;
    }

    public TimeSource getTimeSource() {
        return timeSource;
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
