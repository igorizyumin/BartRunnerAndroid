package com.dougkeen.bart

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.dougkeen.bart.backend.HttpTransitFeedClient
import com.dougkeen.bart.backend.TransitRepository
import com.dougkeen.bart.data.AlarmController
import com.dougkeen.bart.data.FavoritesRepository
import com.dougkeen.bart.data.FollowedTripRepository
import com.dougkeen.bart.model.SystemTimeSource
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.networktasks.GtfsStaticData
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import java.io.IOException
import java.util.function.Supplier

class BartRunnerApplication : Application(), Application.ActivityLifecycleCallbacks {
    companion object {
        private const val PREFS_NAME = "prefs_bart_runner"
        private const val PREFS_ACTIVITY_TIMESTAMP = "prefs_activity_timestamp"
    }

    private lateinit var applicationPreferences: android.content.SharedPreferences
    lateinit var favoritesRepository: FavoritesRepository
    lateinit var followedTripRepository: FollowedTripRepository
    lateinit var alarmController: AlarmController
    lateinit var transitRepository: TransitRepository
    lateinit var gtfsStaticData: GtfsStaticData

    val timeSource: TimeSource = SystemTimeSource

    val bartGtfsNetworkSupplier: Supplier<BartGtfsNetwork> = Supplier {
        try {
            gtfsStaticData.getBartGtfsNetwork()
        } catch (exception: IOException) {
            throw IllegalStateException("Could not load static GTFS network", exception)
        }
    }

    override fun onCreate() {
        super.onCreate()
        applicationPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        favoritesRepository = FavoritesRepository(this)
        followedTripRepository = FollowedTripRepository(this)
        alarmController = AlarmController()
        gtfsStaticData = GtfsStaticData(this, timeSource)
        transitRepository = TransitRepository(HttpTransitFeedClient(), 30_000L)
        registerActivityLifecycleCallbacks(this)
    }

    var activityTimestamp: Long
        get() = applicationPreferences.getLong(PREFS_ACTIVITY_TIMESTAMP, 0L)
        set(value) {
            applicationPreferences.edit()
                .putLong(PREFS_ACTIVITY_TIMESTAMP, value)
                .apply()
        }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) {
        activityTimestamp = System.currentTimeMillis()
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
