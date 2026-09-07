package com.dougkeen.bart.networktasks;

import com.google.transit.realtime.GtfsRealtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable one-pass index of the entities in a GTFS realtime feed. */
public final class GtfsRealtimeFeedIndex {
    private final List<GtfsRealtime.FeedEntity> tripUpdateEntities;
    private final Map<String, GtfsRealtime.FeedEntity> tripUpdatesById;
    private final List<GtfsRealtime.FeedEntity> alertEntities;
    private final Map<String, GtfsRealtime.FeedEntity> alertsById;

    private GtfsRealtimeFeedIndex(GtfsRealtime.FeedMessage feed) {
        List<GtfsRealtime.FeedEntity> tripEntities =
                new ArrayList<GtfsRealtime.FeedEntity>();
        Map<String, GtfsRealtime.FeedEntity> trips =
                new LinkedHashMap<String, GtfsRealtime.FeedEntity>();
        List<GtfsRealtime.FeedEntity> entitiesWithAlerts =
                new ArrayList<GtfsRealtime.FeedEntity>();
        Map<String, GtfsRealtime.FeedEntity> alerts =
                new LinkedHashMap<String, GtfsRealtime.FeedEntity>();
        for (GtfsRealtime.FeedEntity entity : feed.getEntityList()) {
            if (entity.hasTripUpdate()) {
                tripEntities.add(entity);
                GtfsRealtime.TripDescriptor trip = entity.getTripUpdate()
                        .hasTrip() ? entity.getTripUpdate().getTrip() : null;
                if (trip != null && trip.hasTripId()
                        && !trip.getTripId().isEmpty()) {
                    trips.put(trip.getTripId(), entity);
                } else if (entity.hasId()) {
                    trips.put(entity.getId(), entity);
                }
            }
            if (entity.hasAlert()) {
                entitiesWithAlerts.add(entity);
                if (entity.hasId()) {
                    alerts.put(entity.getId(), entity);
                }
            }
        }
        tripUpdateEntities = Collections.unmodifiableList(tripEntities);
        tripUpdatesById = Collections.unmodifiableMap(trips);
        alertEntities = Collections.unmodifiableList(entitiesWithAlerts);
        alertsById = Collections.unmodifiableMap(alerts);
    }

    public static GtfsRealtimeFeedIndex from(GtfsRealtime.FeedMessage feed) {
        if (feed == null) {
            throw new NullPointerException("feed");
        }
        return new GtfsRealtimeFeedIndex(feed);
    }

    public List<GtfsRealtime.FeedEntity> getTripUpdateEntities() {
        return tripUpdateEntities;
    }

    public Map<String, GtfsRealtime.FeedEntity> getTripUpdatesById() {
        return tripUpdatesById;
    }

    public List<GtfsRealtime.FeedEntity> getAlertEntities() {
        return alertEntities;
    }

    public Map<String, GtfsRealtime.FeedEntity> getAlertsById() {
        return alertsById;
    }

}
