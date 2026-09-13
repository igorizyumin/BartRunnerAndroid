package `in`.izyum.bart.backend

import com.google.transit.realtime.GtfsRealtime
import java.io.IOException

/** Fetches the independent realtime feeds used by the transit backend. */
interface TransitFeedClient {
    @Throws(IOException::class)
    fun fetchFeeds(): TransitFeedFetchResult

    /** Fetches only trip updates for latency-sensitive departure refreshes. */
    @Throws(IOException::class)
    fun fetchTripUpdates(): GtfsRealtime.FeedMessage =
        fetchFeeds().tripUpdates
            ?: throw IOException("Trip updates were unavailable")
}
