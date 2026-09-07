package com.dougkeen.bart.backend

import com.dougkeen.bart.networktasks.NetworkUtils
import com.google.transit.realtime.GtfsRealtime
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Downloads BART's complete trip-update and alert feeds. */
class HttpTransitFeedClient : TransitFeedClient {
    @JvmOverloads
    constructor(client: OkHttpClient = NetworkUtils.makeHttpClient()) {
        this.client = client
    }

    private val client: OkHttpClient

    override fun fetch(): TransitFeedSnapshot {
        val tripUpdates = fetchFeed(TRIP_UPDATES_URL)
        val alerts = fetchFeed(ALERTS_URL)
        return TransitFeedSnapshot(tripUpdates, alerts, System.currentTimeMillis())
    }

    override fun fetchFeeds(): TransitFeedFetchResult {
        var tripUpdates: GtfsRealtime.FeedMessage? = null
        var alerts: GtfsRealtime.FeedMessage? = null
        var tripUpdatesError: Exception? = null
        var alertsError: Exception? = null
        try {
            tripUpdates = fetchFeed(TRIP_UPDATES_URL)
        } catch (exception: Exception) {
            tripUpdatesError = exception
        }
        try {
            alerts = fetchFeed(ALERTS_URL)
        } catch (exception: Exception) {
            alertsError = exception
        }
        return TransitFeedFetchResult(
            tripUpdates,
            tripUpdatesError,
            alerts,
            alertsError
        )
    }

    private fun fetchFeed(url: String): GtfsRealtime.FeedMessage {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/x-google-protobuf")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Realtime feed returned ${response.code} for $url")
            }
            val body = response.body
                ?: throw IOException("Realtime feed returned an empty body for $url")
            GtfsRealtime.FeedMessage.parseFrom(body.byteStream())
        }
    }

    companion object {
        @JvmField
        val TRIP_UPDATES_URL = "https://api.bart.gov/gtfsrt/tripupdate.aspx"

        @JvmField
        val ALERTS_URL = "https://api.bart.gov/gtfsrt/alerts.aspx"
    }
}
