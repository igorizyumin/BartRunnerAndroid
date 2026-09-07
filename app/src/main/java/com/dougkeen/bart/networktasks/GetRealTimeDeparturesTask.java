package com.dougkeen.bart.networktasks;

import android.content.Context;
import android.util.Log;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.model.Constants;
import com.dougkeen.bart.model.RealTimeDepartures;
import com.dougkeen.bart.model.Route;
import com.dougkeen.bart.model.StationPair;
import com.google.transit.realtime.GtfsRealtime;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public abstract class GetRealTimeDeparturesTask extends
        NetworkTask<StationPair, Integer, RealTimeDepartures> {

    private static final String GTFS_RT_TRIP_UPDATES_URL =
            "https://api.bart.gov/gtfsrt/tripupdate.aspx";
    private static final String GTFS_STATIC_URL =
            "https://www.bart.gov/dev/schedules/google_transit.zip";
    private static final int MAX_ATTEMPTS = 5;
    private static final long REAL_TIME_CACHE_MILLIS = 15000L;
    private static final long STATIC_ROUTE_CACHE_MILLIS = 24L * 60L * 60L
            * 1000L;
    private static final String STATIC_ROUTE_CACHE_FILE_NAME =
            "gtfs_static_trip_routes.cache";
    private static final String STATIC_ROUTE_CACHE_PREFS_NAME =
            "gtfs_static_trip_routes";
    private static final String STATIC_ROUTE_CACHE_LAST_ATTEMPT =
            "last_attempt";

    private static final Object REAL_TIME_FEED_CACHE_LOCK = new Object();
    private static GtfsRealtime.FeedMessage cachedRealTimeFeed;
    private static long lastRealTimeFeedAttempt;
    private static IOException lastRealTimeFeedException;
    private static boolean realTimeFeedRefreshInProgress;

    private static final Object STATIC_ROUTE_CACHE_LOCK = new Object();
    private static Map<String, String> staticRouteIdsByTripId;
    private static long lastStaticRouteCacheAttempt;

    private static final OkHttpClient REAL_TIME_CLIENT =
            NetworkUtils.makeHttpClient();

    private Exception mException;

    private List<Route> mRoutes;

    private final boolean ignoreDirection;

    public GetRealTimeDeparturesTask(boolean ignoreDirection) {
        super();
        this.ignoreDirection = ignoreDirection;
    }

    @Override
    protected RealTimeDepartures doInBackground(StationPair... paramsArray) {
        // Always expect one param
        StationPair params = paramsArray[0];

        if (params.getDestination() == null) {
            mRoutes = new ArrayList<Route>();
            for (com.dougkeen.bart.model.Line line
                    : com.dougkeen.bart.model.Line.getLinesForStation(
                    params.getOrigin())) {
                Route route = new Route();
                route.setOrigin(params.getOrigin());
                route.setDestination(null);
                route.setDirectLine(line);
                route.setTransfer(false);
                mRoutes.add(route);
            }
        } else {
            mRoutes = params.getOrigin().getDirectRoutesForDestination(
                    params.getDestination());
        }

        boolean hasDirectLine = false;
        for (Route route : mRoutes) {
            if (!route.hasTransfer()) {
                hasDirectLine = true;
                break;
            }
        }

        if ((params.getDestination() != null && mRoutes.isEmpty())
                || (params.getDestination() != null
                && params.getOrigin().transferFriendly && !hasDirectLine)) {
            mRoutes.addAll(params.getOrigin().getPreferredTransferRoutes(
                    params.getDestination()));
        }

        if (!isCancelled()) {
            return getDeparturesFromNetwork(params, 0);
        } else {
            return null;
        }
    }

    private RealTimeDepartures getDeparturesFromNetwork(StationPair params,
                                                        int attemptNumber) {
        try {
            if (isCancelled()) {
                return null;
            }

            GtfsRealtime.FeedMessage feed = getCachedRealTimeFeed();
            Map<String, String> routeIdsByTripId = getRouteIdsByTripId(feed);
            GtfsRealtimeContentHandler handler = new GtfsRealtimeContentHandler(
                    params.getOrigin(), params.getDestination(), mRoutes,
                    ignoreDirection, routeIdsByTripId);
            return handler.getRealTimeDepartures(feed);
        } catch (IOException e) {
            if (attemptNumber < MAX_ATTEMPTS - 1) {
                try {
                    Log.w(Constants.TAG,
                            "Attempt to contact server failed... retrying in 3s",
                            e);
                    Thread.sleep(3000);
                } catch (InterruptedException interrupt) {
                    Thread.currentThread().interrupt();
                }
                return getDeparturesFromNetwork(params, attemptNumber + 1);
            } else {
                mException = new Exception("Could not contact BART system", e);
                return null;
            }
        }
    }

    public static GtfsRealtime.FeedMessage getCachedRealTimeFeed()
            throws IOException {
        synchronized (REAL_TIME_FEED_CACHE_LOCK) {
            long now = System.currentTimeMillis();
            if (cachedRealTimeFeed != null
                    && now - lastRealTimeFeedAttempt < REAL_TIME_CACHE_MILLIS) {
                return cachedRealTimeFeed;
            }

            if (realTimeFeedRefreshInProgress) {
                // A stale feed is preferable to making every route query wait
                // for the same request. If there is no feed yet, wait for the
                // one refresh already in progress.
                if (cachedRealTimeFeed != null) {
                    return cachedRealTimeFeed;
                }
                while (realTimeFeedRefreshInProgress) {
                    try {
                        REAL_TIME_FEED_CACHE_LOCK.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted waiting for BART data", e);
                    }
                }
                now = System.currentTimeMillis();
                if (cachedRealTimeFeed != null
                        && now - lastRealTimeFeedAttempt
                        < REAL_TIME_CACHE_MILLIS) {
                    return cachedRealTimeFeed;
                }
                if (lastRealTimeFeedException != null
                        && now - lastRealTimeFeedAttempt
                        < REAL_TIME_CACHE_MILLIS) {
                    throw lastRealTimeFeedException;
                }
            }

            realTimeFeedRefreshInProgress = true;
            lastRealTimeFeedAttempt = now;
        }

        try {
            GtfsRealtime.FeedMessage feed = fetchRealTimeFeed();
            synchronized (REAL_TIME_FEED_CACHE_LOCK) {
                cachedRealTimeFeed = feed;
                lastRealTimeFeedException = null;
                lastRealTimeFeedAttempt = System.currentTimeMillis();
            }
            return feed;
        } catch (IOException e) {
            synchronized (REAL_TIME_FEED_CACHE_LOCK) {
                lastRealTimeFeedException = e;
                lastRealTimeFeedAttempt = System.currentTimeMillis();
                if (cachedRealTimeFeed != null) {
                    return cachedRealTimeFeed;
                }
            }
            throw e;
        } finally {
            synchronized (REAL_TIME_FEED_CACHE_LOCK) {
                realTimeFeedRefreshInProgress = false;
                REAL_TIME_FEED_CACHE_LOCK.notifyAll();
            }
        }
    }

    private static GtfsRealtime.FeedMessage fetchRealTimeFeed()
            throws IOException {
        Log.v(Constants.TAG, "Refreshing GTFS-RT feed from server");
        Request request = new Request.Builder()
                .url(GTFS_RT_TRIP_UPDATES_URL)
                .header("Accept", "application/x-google-protobuf")
                .build();
        Response response = REAL_TIME_CLIENT.newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new IOException("Server returned " + response.code());
            }
            if (response.body() == null) {
                throw new IOException("Server returned an empty response");
            }
            return GtfsRealtime.FeedMessage.parseFrom(
                    response.body().byteStream());
        } finally {
            response.close();
        }
    }

    public static Map<String, String> getRouteIdsByTripId(
            GtfsRealtime.FeedMessage feed) {
        boolean needsLookup = false;
        for (GtfsRealtime.FeedEntity entity : feed.getEntityList()) {
            if (entity.hasTripUpdate() && entity.getTripUpdate().hasTrip()) {
                if (!entity.getTripUpdate().getTrip().hasRouteId()
                        || entity.getTripUpdate().getTrip().getRouteId()
                        .isEmpty()) {
                    needsLookup = true;
                }
            }
        }
        if (!needsLookup) {
            return Collections.<String, String>emptyMap();
        }
        try {
            return GtfsStaticData.get().getRouteIdsByTripId();
        } catch (IOException e) {
            Log.w(Constants.TAG, "Could not load static GTFS trip routes", e);
            return Collections.<String, String>emptyMap();
        }
    }

    private static Map<String, String> getStaticRouteIdsByTripId() {
        long now = System.currentTimeMillis();
        synchronized (STATIC_ROUTE_CACHE_LOCK) {
            if (staticRouteIdsByTripId != null
                    && !staticRouteIdsByTripId.isEmpty()) {
                return staticRouteIdsByTripId;
            }

            Map<String, String> cached = loadStaticRouteCache(now);
            if (!cached.isEmpty()) {
                staticRouteIdsByTripId = cached;
                return cached;
            }

            long lastAttempt = getLastStaticRouteCacheAttempt();
            if (now - lastAttempt < STATIC_ROUTE_CACHE_MILLIS) {
                return Collections.emptyMap();
            }
            lastStaticRouteCacheAttempt = now;
            saveLastStaticRouteCacheAttempt(now);

            Log.v(Constants.TAG, "Refreshing static GTFS schedule from server");
            Request request = new Request.Builder().url(GTFS_STATIC_URL)
                    .header("Accept", "application/zip").build();
            try {
                Response response = REAL_TIME_CLIENT.newCall(request).execute();
                try {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("Static GTFS returned "
                                + response.code());
                    }
                    Map<String, String> result = parseStaticTripRoutes(
                            response.body().byteStream());
                    if (!result.isEmpty()) {
                        staticRouteIdsByTripId = result;
                        saveStaticRouteCache(result, now);
                    }
                    return result;
                } finally {
                    response.close();
                }
            } catch (IOException e) {
                Log.w(Constants.TAG,
                        "Could not load static GTFS trip routes", e);
                return Collections.emptyMap();
            }
        }
    }

    private static Context getApplicationContext() {
        return BartRunnerApplication.getAppContext();
    }

    private static long getLastStaticRouteCacheAttempt() {
        if (lastStaticRouteCacheAttempt > 0) {
            return lastStaticRouteCacheAttempt;
        }
        Context context = getApplicationContext();
        if (context != null) {
            lastStaticRouteCacheAttempt = context.getSharedPreferences(
                    STATIC_ROUTE_CACHE_PREFS_NAME, Context.MODE_PRIVATE)
                    .getLong(STATIC_ROUTE_CACHE_LAST_ATTEMPT, 0L);
        }
        return lastStaticRouteCacheAttempt;
    }

    private static void saveLastStaticRouteCacheAttempt(long timestamp) {
        Context context = getApplicationContext();
        if (context != null) {
            context.getSharedPreferences(STATIC_ROUTE_CACHE_PREFS_NAME,
                    Context.MODE_PRIVATE).edit()
                    .putLong(STATIC_ROUTE_CACHE_LAST_ATTEMPT, timestamp)
                    .apply();
        }
    }

    private static Map<String, String> loadStaticRouteCache(long now) {
        Context context = getApplicationContext();
        if (context == null) {
            return Collections.emptyMap();
        }

        File cacheFile = new File(context.getFilesDir(),
                STATIC_ROUTE_CACHE_FILE_NAME);
        if (!cacheFile.isFile()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new HashMap<String, String>();
        try {
            BufferedReader reader = new BufferedReader(new FileReader(cacheFile));
            try {
                String timestampLine = reader.readLine();
                long fetchedAt = Long.parseLong(timestampLine);
                if (now - fetchedAt >= STATIC_ROUTE_CACHE_MILLIS) {
                    return Collections.emptyMap();
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    int separator = line.indexOf('\t');
                    if (separator > 0 && separator + 1 < line.length()) {
                        result.put(line.substring(0, separator),
                                line.substring(separator + 1));
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException | NumberFormatException e) {
            Log.w(Constants.TAG, "Could not read cached static GTFS routes", e);
            return Collections.emptyMap();
        }
        return result;
    }

    private static void saveStaticRouteCache(Map<String, String> routes,
                                             long fetchedAt) {
        Context context = getApplicationContext();
        if (context == null) {
            return;
        }

        File cacheFile = new File(context.getFilesDir(),
                STATIC_ROUTE_CACHE_FILE_NAME);
        File temporaryFile = new File(context.getFilesDir(),
                STATIC_ROUTE_CACHE_FILE_NAME + ".tmp");
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(
                    temporaryFile, false));
            try {
                writer.write(Long.toString(fetchedAt));
                writer.newLine();
                for (Map.Entry<String, String> route : routes.entrySet()) {
                    writer.write(route.getKey());
                    writer.write('\t');
                    writer.write(route.getValue());
                    writer.newLine();
                }
            } finally {
                writer.close();
            }

            if (cacheFile.exists() && !cacheFile.delete()) {
                throw new IOException("Could not replace static GTFS cache");
            }
            if (!temporaryFile.renameTo(cacheFile)) {
                throw new IOException("Could not save static GTFS cache");
            }
        } catch (IOException e) {
            Log.w(Constants.TAG, "Could not save static GTFS routes", e);
            temporaryFile.delete();
        }
    }

    private static Map<String, String> parseStaticTripRoutes(
            java.io.InputStream input) throws IOException {
        Map<String, String> routeIdsByTripId = new HashMap<String, String>();
        ZipInputStream zip = new ZipInputStream(input);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!"trips.txt".equals(entry.getName())) {
                    continue;
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        zip, Charset.forName("UTF-8")));
                String header = reader.readLine();
                if (header == null) {
                    return routeIdsByTripId;
                }
                String[] columns = splitCsvLine(header);
                int routeColumn = indexOf(columns, "route_id");
                int tripColumn = indexOf(columns, "trip_id");
                if (routeColumn < 0 || tripColumn < 0) {
                    return routeIdsByTripId;
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] values = splitCsvLine(line);
                    if (routeColumn < values.length
                            && tripColumn < values.length) {
                        routeIdsByTripId.put(values[tripColumn],
                                values[routeColumn]);
                    }
                }
                return routeIdsByTripId;
            }
            return routeIdsByTripId;
        } finally {
            zip.close();
        }
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (value.equals(values[i])) {
                return i;
            }
        }
        return -1;
    }

    private static String[] splitCsvLine(String line) {
        java.util.ArrayList<String> values = new java.util.ArrayList<String>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < line.length()
                        && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        values.add(value.toString());
        return values.toArray(new String[values.size()]);
    }

    @Override
    protected void onPostExecute(RealTimeDepartures result) {
        if (result != null) {
            onResult(result);
        } else {
            onError(mException);
        }
    }

    public abstract void onResult(RealTimeDepartures result);

    public abstract void onError(Exception exception);
}
