package com.dougkeen.bart.backend;

import android.content.Context;

import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.networktasks.GtfsRealtimeContentHandler;
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex;
import com.dougkeen.bart.networktasks.GtfsStaticData;
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork;

import java.util.Collections;
import java.util.List;

/** Refreshes an existing itinerary from the latest complete trip feed. */
public final class TripProgressProjection
        implements TransitProjection<List<TripLeg>> {
    private final Station origin;
    private final Station destination;
    private final List<TripLeg> existingLegs;
    private final Context context;
    private final BartGtfsNetwork bartGtfsNetwork;

    public TripProgressProjection(
            Context context,
            Station origin,
            Station destination,
            List<TripLeg> existingLegs) {
        this(context, origin, destination, existingLegs, null);
    }

    public TripProgressProjection(
            Station origin,
            Station destination,
            List<TripLeg> existingLegs,
            BartGtfsNetwork bartGtfsNetwork) {
        this(null, origin, destination, existingLegs, bartGtfsNetwork);
    }

    private TripProgressProjection(
            Context context,
            Station origin,
            Station destination,
            List<TripLeg> existingLegs,
            BartGtfsNetwork bartGtfsNetwork) {
        if (context == null && bartGtfsNetwork == null) {
            throw new IllegalArgumentException(
                    "A validated GTFS network or context is required");
        }
        this.origin = origin;
        this.destination = destination;
        this.existingLegs = existingLegs == null
                ? Collections.<TripLeg>emptyList() : existingLegs;
        this.context = context;
        this.bartGtfsNetwork = bartGtfsNetwork;
    }

    @Override
    public List<TripLeg> project(TransitFeedSnapshot snapshot)
        throws Exception {
        long feedTime = snapshot.getTripUpdatesTimestampMillis();
        BartGtfsNetwork network = network();
        GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                origin, destination, Collections.emptyList(), true, network);
        GtfsRealtimeFeedIndex feedIndex = snapshot.getTripUpdateIndex();
        return handler.updateTripLegs(feedIndex, existingLegs, feedTime);
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
}
