package com.dougkeen.bart.backend;

import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.networktasks.GtfsRealtimeContentHandler;
import com.dougkeen.bart.networktasks.GtfsStaticData;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Refreshes an existing itinerary from the latest complete trip feed. */
public final class TripProgressProjection
        implements TransitProjection<List<TripLeg>> {
    private final Station origin;
    private final Station destination;
    private final List<TripLeg> existingLegs;
    private final RouteDepartureProjection.TripRouteIndex tripRouteIndex;

    public TripProgressProjection(
            Station origin,
            Station destination,
            List<TripLeg> existingLegs) {
        this(origin, destination, existingLegs,
                new RouteDepartureProjection.TripRouteIndex() {
                    @Override
                    public Map<String, String> getRouteIdsByTripId(
                            TransitFeedSnapshot snapshot) throws Exception {
                        for (com.google.transit.realtime.GtfsRealtime.FeedEntity entity
                                : snapshot.getTripUpdates().getEntityList()) {
                            if (entity.hasTripUpdate()
                                    && entity.getTripUpdate().hasTrip()
                                    && (!entity.getTripUpdate().getTrip()
                                    .hasRouteId()
                                    || entity.getTripUpdate().getTrip()
                                    .getRouteId().isEmpty())) {
                                return GtfsStaticData.get()
                                        .getRouteIdsByTripId();
                            }
                        }
                        return Collections.emptyMap();
                    }
                });
    }

    public TripProgressProjection(
            Station origin,
            Station destination,
            List<TripLeg> existingLegs,
            Map<String, String> routeIdsByTripId) {
        this(origin, destination, existingLegs,
                new RouteDepartureProjection.TripRouteIndex() {
                    @Override
                    public Map<String, String> getRouteIdsByTripId(
                            TransitFeedSnapshot snapshot) {
                        return routeIdsByTripId == null
                                ? Collections.<String, String>emptyMap()
                                : routeIdsByTripId;
                    }
                });
    }

    private TripProgressProjection(
            Station origin,
            Station destination,
            List<TripLeg> existingLegs,
            RouteDepartureProjection.TripRouteIndex tripRouteIndex) {
        this.origin = origin;
        this.destination = destination;
        this.existingLegs = existingLegs == null
                ? Collections.<TripLeg>emptyList() : existingLegs;
        this.tripRouteIndex = tripRouteIndex;
    }

    @Override
    public List<TripLeg> project(TransitFeedSnapshot snapshot)
            throws Exception {
        long feedTime = snapshot.getTripUpdatesTimestampMillis();
        Map<String, String> routeIdsByTripId = tripRouteIndex
                .getRouteIdsByTripId(snapshot);
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                origin, destination, Collections.emptyList(), true,
                routeIdsByTripId);
        return handler.updateTripLegs(snapshot.getTripUpdates(), existingLegs,
                feedTime);
    }
}
