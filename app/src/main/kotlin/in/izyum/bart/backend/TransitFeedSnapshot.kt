package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.transit.normalization.NormalizedRealtimeFeed
import `in`.izyum.bart.transit.normalization.RealtimeFeedNormalizer
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import com.google.transit.realtime.GtfsRealtime
import java.util.IdentityHashMap

/** One coherent read of the realtime feeds. */
class TransitFeedSnapshot(
    val tripUpdates: GtfsRealtime.FeedMessage,
    val alerts: GtfsRealtime.FeedMessage,
    val receivedAtMillis: Long
) {
    @Volatile
    private var normalizedTripUpdates: NormalizedRealtimeFeed? = null

    @Volatile
    private var normalizedAlerts: NormalizedRealtimeFeed? = null

    private val canonicalSnapshots = IdentityHashMap<BartGtfsNetwork, CanonicalTransitSnapshot>()

    init {
        requireNotNull(tripUpdates) { "tripUpdates" }
        requireNotNull(alerts) { "alerts" }
    }

    fun getTripUpdatesTimestampMillis(): Long =
        feedTimestampMillis(tripUpdates, receivedAtMillis)

    /** Builds the lossless trip-update normalization once for all projections. */
    fun getNormalizedTripUpdates(): NormalizedRealtimeFeed {
        normalizedTripUpdates?.let { return it }
        synchronized(this) {
            normalizedTripUpdates?.let { return it }
            return RealtimeFeedNormalizer.normalize(tripUpdates, receivedAtMillis, "trip_updates")
                .also {
                    normalizedTripUpdates = it
                }
        }
    }

    /** Builds the lossless alert-feed normalization once for all projections. */
    fun getNormalizedAlerts(): NormalizedRealtimeFeed {
        normalizedAlerts?.let { return it }
        synchronized(this) {
            normalizedAlerts?.let { return it }
            return RealtimeFeedNormalizer.normalize(alerts, receivedAtMillis, "alerts").also {
                normalizedAlerts = it
            }
        }
    }

    /** Builds one canonical snapshot for each static network consumer. */
    fun getCanonicalSnapshot(network: BartGtfsNetwork): CanonicalTransitSnapshot {
        synchronized(canonicalSnapshots) {
            return canonicalSnapshots[network] ?: CanonicalTransitSnapshot.create(
                Schedule.fromStatic(
                network,
                getTripUpdatesTimestampMillis(),
                Line.values().toSet(),
                ),
                getNormalizedTripUpdates(),
            ).also {
                canonicalSnapshots[network] = it
            }
        }
    }

    @Deprecated(
        "Use getCanonicalSnapshot(network).correctedSchedule; canonical data owns schedule correction.",
        level = DeprecationLevel.WARNING,
    )
    fun getCorrectedSchedule(network: BartGtfsNetwork): Schedule =
        getCanonicalSnapshot(network).correctedSchedule

    /** Returns true when a new fetch contains no changed feed data. */
    fun hasSameFeedData(other: TransitFeedSnapshot?): Boolean =
        other != null && tripUpdates == other.tripUpdates && alerts == other.alerts

    companion object {
        /** A timestamped empty realtime feed used to project the stored schedule offline. */
        @JvmStatic
        fun empty(receivedAtMillis: Long): TransitFeedSnapshot {
            val header = GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0")
                .setTimestamp(receivedAtMillis / 1000L)
                .build()
            val emptyFeed = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(header)
                .build()
            return TransitFeedSnapshot(emptyFeed, emptyFeed, receivedAtMillis)
        }
    }

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
