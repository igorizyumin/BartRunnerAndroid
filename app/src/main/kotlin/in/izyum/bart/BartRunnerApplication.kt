package `in`.izyum.bart

import android.app.Application
import android.app.Activity
import android.os.Bundle
import `in`.izyum.bart.backend.TransitFeedSnapshot
import `in`.izyum.bart.backend.HttpTransitFeedClient
import `in`.izyum.bart.backend.TransitRepository
import `in`.izyum.bart.data.FavoritesRepository
import `in`.izyum.bart.data.FollowedTripRepository
import `in`.izyum.bart.model.SystemTimeSource
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.networktasks.GtfsStaticData
import `in`.izyum.bart.networktasks.EtdStationCache
import `in`.izyum.bart.networktasks.HttpEtdClient
import `in`.izyum.bart.platform.OfflineStatusController
import `in`.izyum.bart.receivers.DownloadRetryReceiver
import `in`.izyum.bart.platform.DeparturePollingWork
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import java.io.IOException
import java.util.function.Supplier

class BartRunnerApplication : Application() {
    lateinit var favoritesRepository: FavoritesRepository
    lateinit var followedTripRepository: FollowedTripRepository
    lateinit var transitRepository: TransitRepository
    lateinit var gtfsStaticData: GtfsStaticData
    lateinit var offlineStatusController: OfflineStatusController
    lateinit var etdStationCache: EtdStationCache

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
        gtfsStaticData = GtfsStaticData(this, timeSource)
        etdStationCache = EtdStationCache(HttpEtdClient(), timeSource)
        transitRepository = TransitRepository(
            HttpTransitFeedClient(),
            15_000L,
            offlineSnapshotProvider = { TransitFeedSnapshot.empty(timeSource.nowMillis()) },
        )
        offlineStatusController = OfflineStatusController(this, transitRepository, followedTripRepository) {
            runCatching { gtfsStaticData.warmUp() }
            transitRepository.refreshNow()
        }
        DownloadRetryReceiver.schedule(this)
        DeparturePollingWork.refresh(this, followedTripRepository)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivityCount = 0

            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                transitRepository.setAppInForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) {
                    transitRepository.setAppInForeground(false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
