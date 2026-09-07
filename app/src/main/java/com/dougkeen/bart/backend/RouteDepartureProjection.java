package com.dougkeen.bart.backend;

import android.content.Context;

import com.dougkeen.bart.model.RealTimeDepartures;
import com.dougkeen.bart.model.Route;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.networktasks.GtfsRealtimeContentHandler;
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex;
import com.dougkeen.bart.networktasks.GtfsStaticData;
import com.dougkeen.bart.routing.TripPlanner;
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork;

import java.util.List;

/** Builds departures for one route from the already-downloaded feed. */
public final class RouteDepartureProjection
        implements TransitProjection<RealTimeDepartures> {
    private final StationPair query;
    private final boolean ignoreDirection;
    private final Context context;
    private final BartGtfsNetwork bartGtfsNetwork;

    public RouteDepartureProjection(StationPair query, Context context) {
        this(query, false, context, null);
    }

    public RouteDepartureProjection(StationPair query,
                                    BartGtfsNetwork bartGtfsNetwork) {
        this(query, false, null, bartGtfsNetwork);
    }

    public RouteDepartureProjection(StationPair query,
                                    boolean ignoreDirection,
                                    BartGtfsNetwork bartGtfsNetwork) {
        this(query, ignoreDirection, null, bartGtfsNetwork);
    }

    private RouteDepartureProjection(StationPair query,
                                     boolean ignoreDirection,
                                     Context context,
                                     BartGtfsNetwork bartGtfsNetwork) {
        if (query == null || query.getOrigin() == null) {
            throw new IllegalArgumentException("A route query needs an origin");
        }
        if (context == null && bartGtfsNetwork == null) {
            throw new IllegalArgumentException(
                    "A validated GTFS network or context is required");
        }
        this.query = query;
        this.ignoreDirection = ignoreDirection;
        this.context = context;
        this.bartGtfsNetwork = bartGtfsNetwork;
    }

    @Override
    public RealTimeDepartures project(TransitFeedSnapshot snapshot)
            throws Exception {
        BartGtfsNetwork network = network();
        List<Route> routes = resolveRoutes(query, network);
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                query.getOrigin(), query.getDestination(), routes,
                ignoreDirection, network);
        GtfsRealtimeFeedIndex feedIndex = snapshot.getTripUpdateIndex();
        RealTimeDepartures result = handler.getRealTimeDepartures(feedIndex,
                snapshot.getTripUpdatesTimestampMillis());

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

    private BartGtfsNetwork network() throws Exception {
        if (bartGtfsNetwork != null) {
            return bartGtfsNetwork;
        }
        if (context == null) {
            throw new IllegalStateException("A validated GTFS network is required");
        }
        return GtfsStaticData.get(context).getBartGtfsNetwork();
    }

    private static List<Route> resolveRoutes(StationPair query,
                                             BartGtfsNetwork network) {
        return TripPlanner.routesFor(query.getOrigin(), query.getDestination(),
                network);
    }
}
