package `in`.izyum.bart.backend

import com.google.transit.realtime.GtfsRealtime

/** Results from independently fetched trip-update and alert feeds. */
class TransitFeedFetchResult private constructor(
    val tripUpdates: GtfsRealtime.FeedMessage?,
    val tripUpdatesError: Exception?,
    val alerts: GtfsRealtime.FeedMessage?,
    val alertsError: Exception?,
    private val completeSnapshot: TransitFeedSnapshot?
) {
    constructor(
        tripUpdates: GtfsRealtime.FeedMessage?,
        tripUpdatesError: Exception?,
        alerts: GtfsRealtime.FeedMessage?,
        alertsError: Exception?
    ) : this(
        tripUpdates,
        tripUpdatesError,
        alerts,
        alertsError,
        null
    ) {
        require(tripUpdates != null || tripUpdatesError != null) {
            "Trip updates need data or an error"
        }
        require(alerts != null || alertsError != null) {
            "Alerts need data or an error"
        }
    }

    fun getCompleteSnapshot(): TransitFeedSnapshot? = completeSnapshot

    companion object {
        @JvmStatic
        fun complete(snapshot: TransitFeedSnapshot?): TransitFeedFetchResult {
            val value = requireNotNull(snapshot) { "snapshot" }
            return TransitFeedFetchResult(
                value.tripUpdates,
                null,
                value.alerts,
                null,
                value
            )
        }

        @JvmStatic
        fun failed(exception: Exception?): TransitFeedFetchResult {
            val value = requireNotNull(exception) { "exception" }
            return TransitFeedFetchResult(null, value, null, value)
        }
    }
}
