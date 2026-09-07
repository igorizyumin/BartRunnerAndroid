package com.dougkeen.bart.backend;

import com.google.transit.realtime.GtfsRealtime;
import com.dougkeen.bart.networktasks.NetworkUtils;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Downloads BART's complete trip-update and alert feeds. */
public final class HttpTransitFeedClient implements TransitFeedClient {
    public static final String TRIP_UPDATES_URL =
            "https://api.bart.gov/gtfsrt/tripupdate.aspx";
    public static final String ALERTS_URL =
            "https://api.bart.gov/gtfsrt/alerts.aspx";

    private final OkHttpClient client;

    public HttpTransitFeedClient() {
        this(NetworkUtils.makeHttpClient());
    }

    public HttpTransitFeedClient(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public TransitFeedSnapshot fetch() throws IOException {
        GtfsRealtime.FeedMessage tripUpdates = fetchFeed(TRIP_UPDATES_URL);
        GtfsRealtime.FeedMessage alerts = fetchFeed(ALERTS_URL);
        return new TransitFeedSnapshot(tripUpdates, alerts,
                System.currentTimeMillis());
    }

    private GtfsRealtime.FeedMessage fetchFeed(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/x-google-protobuf")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Realtime feed returned "
                        + response.code() + " for " + url);
            }
            if (response.body() == null) {
                throw new IOException("Realtime feed returned an empty body for "
                        + url);
            }
            return GtfsRealtime.FeedMessage.parseFrom(
                    response.body().byteStream());
        }
    }
}
