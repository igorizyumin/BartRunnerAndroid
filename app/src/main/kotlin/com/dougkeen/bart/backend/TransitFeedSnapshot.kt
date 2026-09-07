package com.dougkeen.bart.backend

import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex
import com.google.transit.realtime.GtfsRealtime

/** One coherent read of the realtime feeds. */
class TransitFeedSnapshot(
    val tripUpdates: GtfsRealtime.FeedMessage,
    val alerts: GtfsRealtime.FeedMessage,
    val receivedAtMillis: Long
) {
    @Volatile
    private var tripUpdateIndex: GtfsRealtimeFeedIndex? = null

    @Volatile
    private var alertIndex: GtfsRealtimeFeedIndex? = null

    init {
        requireNotNull(tripUpdates) { "tripUpdates" }
        requireNotNull(alerts) { "alerts" }
    }

    fun getTripUpdatesTimestampMillis(): Long =
        feedTimestampMillis(tripUpdates, receivedAtMillis)

    fun getAlertsTimestampMillis(): Long =
        feedTimestampMillis(alerts, receivedAtMillis)

    /** Builds the trip-update index once and shares it across all projections. */
    fun getTripUpdateIndex(): GtfsRealtimeFeedIndex {
        tripUpdateIndex?.let { return it }
        synchronized(this) {
            tripUpdateIndex?.let { return it }
            return GtfsRealtimeFeedIndex.from(tripUpdates).also {
                tripUpdateIndex = it
            }
        }
    }

    /** Builds the alert index once and shares it across all projections. */
    fun getAlertIndex(): GtfsRealtimeFeedIndex {
        alertIndex?.let { return it }
        synchronized(this) {
            alertIndex?.let { return it }
            return GtfsRealtimeFeedIndex.from(alerts).also {
                alertIndex = it
            }
        }
    }

    /** Returns true when a new fetch contains no changed feed data. */
    fun hasSameFeedData(other: TransitFeedSnapshot?): Boolean =
        other != null && tripUpdates == other.tripUpdates && alerts == other.alerts

    private fun feedTimestampMillis(
        feed: GtfsRealtime.FeedMessage,
        fallback: Long
    ): Long = if (feed.hasHeader()
        && feed.header.hasTimestamp()
        && feed.header.timestamp > 0
    ) {
        feed.header.timestamp * 1000L
    } else {
        fallback
    }
}
