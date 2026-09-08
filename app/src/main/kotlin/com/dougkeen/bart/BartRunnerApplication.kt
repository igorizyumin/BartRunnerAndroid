package com.dougkeen.bart

import android.app.Application
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

class BartRunnerApplication : Application() {
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
        favoritesRepository = FavoritesRepository(this)
        followedTripRepository = FollowedTripRepository(this)
        alarmController = AlarmController()
        gtfsStaticData = GtfsStaticData(this, timeSource)
        transitRepository = TransitRepository(HttpTransitFeedClient(), 15_000L)
    }
}
