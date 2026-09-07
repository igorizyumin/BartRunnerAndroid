package com.dougkeen.bart.backend;

import com.google.transit.realtime.GtfsRealtime;

import java.util.Objects;

/**
 * One coherent read of the realtime feeds. The feeds are kept raw at this
 * boundary; route, station, and trip projections are built from this value.
 */
public final class TransitFeedSnapshot {
    private final GtfsRealtime.FeedMessage tripUpdates;
    private final GtfsRealtime.FeedMessage alerts;
    private final long receivedAtMillis;

    public TransitFeedSnapshot(GtfsRealtime.FeedMessage tripUpdates,
                               GtfsRealtime.FeedMessage alerts,
                               long receivedAtMillis) {
        this.tripUpdates = Objects.requireNonNull(tripUpdates, "tripUpdates");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.receivedAtMillis = receivedAtMillis;
    }

    public GtfsRealtime.FeedMessage getTripUpdates() {
        return tripUpdates;
    }

    public GtfsRealtime.FeedMessage getAlerts() {
        return alerts;
    }

    public long getReceivedAtMillis() {
        return receivedAtMillis;
    }

    public long getTripUpdatesTimestampMillis() {
        return feedTimestampMillis(tripUpdates, receivedAtMillis);
    }

    public long getAlertsTimestampMillis() {
        return feedTimestampMillis(alerts, receivedAtMillis);
    }

    /** Returns true when a new fetch contains no changed feed data. */
    public boolean hasSameFeedData(TransitFeedSnapshot other) {
        return other != null
                && tripUpdates.equals(other.tripUpdates)
                && alerts.equals(other.alerts);
    }

    private static long feedTimestampMillis(GtfsRealtime.FeedMessage feed,
                                             long fallback) {
        if (feed.hasHeader() && feed.getHeader().hasTimestamp()
                && feed.getHeader().getTimestamp() > 0) {
            return feed.getHeader().getTimestamp() * 1000L;
        }
        return fallback;
    }
}
