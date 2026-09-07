package com.dougkeen.bart.networktasks;

import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.model.Station;
import com.google.transit.realtime.GtfsRealtime;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Refreshes the exact trains belonging to a selected trip. */
public abstract class GetTripProgressTask extends
        NetworkTask<GetTripProgressTask.Params, Integer, List<TripLeg>> {

    private Exception exception;

    public static class Params {
        private final Station origin;
        private final Station destination;
        private final List<TripLeg> legs;

        public Params(Station origin, Station destination, List<TripLeg> legs) {
            this.origin = origin;
            this.destination = destination;
            this.legs = legs;
        }
    }

    @Override
    protected List<TripLeg> doInBackground(Params... paramsArray) {
        if (isCancelled()) {
            return null;
        }
        try {
            Params params = paramsArray[0];
            GtfsRealtime.FeedMessage feed =
                    GetRealTimeDeparturesTask.getCachedRealTimeFeed();
            long feedTime = feed.hasHeader() && feed.getHeader().hasTimestamp()
                    && feed.getHeader().getTimestamp() > 0
                    ? feed.getHeader().getTimestamp() * 1000L
                    : System.currentTimeMillis();
            Map<String, String> routeIds =
                    GetRealTimeDeparturesTask.getRouteIdsByTripId(feed);
            GtfsRealtimeContentHandler handler =
                    new GtfsRealtimeContentHandler(params.origin,
                            params.destination, Collections.emptyList(), true,
                            routeIds);
            return handler.updateTripLegs(feed, params.legs, feedTime);
        } catch (IOException e) {
            exception = new Exception("Could not refresh trip progress", e);
            return null;
        }
    }

    @Override
    protected void onPostExecute(List<TripLeg> result) {
        if (result != null) {
            onResult(result);
        } else {
            onError(exception);
        }
    }

    public abstract void onResult(List<TripLeg> legs);

    public abstract void onError(Exception exception);
}
