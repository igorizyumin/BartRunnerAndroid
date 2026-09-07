package com.dougkeen.bart.backend;

import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.RealTimeDepartures;
import com.dougkeen.bart.model.Route;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.networktasks.GtfsRealtimeContentHandler;
import com.dougkeen.bart.networktasks.GtfsStaticData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Builds departures for one route from the already-downloaded feed. */
public final class RouteDepartureProjection
        implements TransitProjection<RealTimeDepartures> {
    public interface TripRouteIndex {
        Map<String, String> getRouteIdsByTripId(
                TransitFeedSnapshot snapshot) throws Exception;
    }

    private final StationPair query;
    private final boolean ignoreDirection;
    private final TripRouteIndex tripRouteIndex;

    public RouteDepartureProjection(StationPair query) {
        this(query, false, new TripRouteIndex() {
            @Override
            public Map<String, String> getRouteIdsByTripId(
                    TransitFeedSnapshot snapshot) throws Exception {
                for (com.google.transit.realtime.GtfsRealtime.FeedEntity entity
                        : snapshot.getTripUpdates().getEntityList()) {
                    if (entity.hasTripUpdate()
                            && entity.getTripUpdate().hasTrip()
                            && (!entity.getTripUpdate().getTrip().hasRouteId()
                            || entity.getTripUpdate().getTrip().getRouteId()
                            .isEmpty())) {
                        return GtfsStaticData.get().getRouteIdsByTripId();
                    }
                }
                return Collections.emptyMap();
            }
        });
    }

    public RouteDepartureProjection(StationPair query,
                                    boolean ignoreDirection,
                                    TripRouteIndex tripRouteIndex) {
        if (query == null || query.getOrigin() == null) {
            throw new IllegalArgumentException("A route query needs an origin");
        }
        this.query = query;
        this.ignoreDirection = ignoreDirection;
        this.tripRouteIndex = tripRouteIndex;
    }

    @Override
    public RealTimeDepartures project(TransitFeedSnapshot snapshot)
            throws Exception {
        List<Route> routes = resolveRoutes(query);
        Map<String, String> routeIds = tripRouteIndex
                .getRouteIdsByTripId(snapshot);
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                query.getOrigin(), query.getDestination(), routes,
                ignoreDirection, routeIds == null
                ? Collections.<String, String>emptyMap() : routeIds);
        RealTimeDepartures result = handler.getRealTimeDepartures(
                snapshot.getTripUpdates());

        if (result.getDepartures().isEmpty() && query.getDestination() != null) {
            result.includeTransferRoutes();
        }
        result.sortDepartures();
        if (result.getDepartures().isEmpty() && query.getDestination() != null) {
            result.includeDoubleTransferRoutes();
        }
        result.finalizeDeparturesList();
        return result;
    }

    @Override
    public boolean areEquivalent(RealTimeDepartures previous,
                                 RealTimeDepartures current) {
        return previous != null && current != null
                && previous.areTransfersIncluded()
                == current.areTransfersIncluded()
                && previous.getDepartures().equals(current.getDepartures());
    }

    private static List<Route> resolveRoutes(StationPair query) {
        Station origin = query.getOrigin();
        Station destination = query.getDestination();
        List<Route> routes = new ArrayList<Route>();
        if (destination == null) {
            for (Line line : Line.getLinesForStation(origin)) {
                Route route = new Route();
                route.setOrigin(origin);
                route.setDestination(null);
                route.setDirectLine(line);
                route.setTransfer(false);
                routes.add(route);
            }
            return routes;
        }

        routes.addAll(origin.getDirectRoutesForDestination(destination));
        boolean hasDirectLine = false;
        for (Route route : routes) {
            if (!route.hasTransfer()) {
                hasDirectLine = true;
                break;
            }
        }
        if (routes.isEmpty() || (origin.transferFriendly && !hasDirectLine)) {
            routes.addAll(origin.getPreferredTransferRoutes(destination));
        }
        return routes;
    }
}
