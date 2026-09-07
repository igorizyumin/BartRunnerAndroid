package com.dougkeen.bart.backend;

import com.google.transit.realtime.GtfsRealtime;

/** Results from independently fetched trip-update and alert feeds. */
public final class TransitFeedFetchResult {
    private final GtfsRealtime.FeedMessage tripUpdates;
    private final GtfsRealtime.FeedMessage alerts;
    private final Exception tripUpdatesError;
    private final Exception alertsError;
    private final TransitFeedSnapshot completeSnapshot;

    public TransitFeedFetchResult(GtfsRealtime.FeedMessage tripUpdates,
                                  Exception tripUpdatesError,
                                  GtfsRealtime.FeedMessage alerts,
                                  Exception alertsError) {
        if (tripUpdates == null && tripUpdatesError == null) {
            throw new IllegalArgumentException(
                    "Trip updates need data or an error");
        }
        if (alerts == null && alertsError == null) {
            throw new IllegalArgumentException("Alerts need data or an error");
        }
        this.tripUpdates = tripUpdates;
        this.tripUpdatesError = tripUpdatesError;
        this.alerts = alerts;
        this.alertsError = alertsError;
        this.completeSnapshot = null;
    }

    public static TransitFeedFetchResult complete(TransitFeedSnapshot snapshot) {
        if (snapshot == null) {
            throw new NullPointerException("snapshot");
        }
        return new TransitFeedFetchResult(snapshot.getTripUpdates(), null,
                snapshot.getAlerts(), null, snapshot);
    }

    public static TransitFeedFetchResult failed(Exception exception) {
        if (exception == null) {
            throw new NullPointerException("exception");
        }
        return new TransitFeedFetchResult(null, exception, null, exception);
    }

    public GtfsRealtime.FeedMessage getTripUpdates() {
        return tripUpdates;
    }

    public GtfsRealtime.FeedMessage getAlerts() {
        return alerts;
    }

    public Exception getTripUpdatesError() {
        return tripUpdatesError;
    }

    public Exception getAlertsError() {
        return alertsError;
    }

    TransitFeedSnapshot getCompleteSnapshot() {
        return completeSnapshot;
    }

    private TransitFeedFetchResult(GtfsRealtime.FeedMessage tripUpdates,
                                   Exception tripUpdatesError,
                                   GtfsRealtime.FeedMessage alerts,
                                   Exception alertsError,
                                   TransitFeedSnapshot completeSnapshot) {
        this.tripUpdates = tripUpdates;
        this.tripUpdatesError = tripUpdatesError;
        this.alerts = alerts;
        this.alertsError = alertsError;
        this.completeSnapshot = completeSnapshot;
    }
}
