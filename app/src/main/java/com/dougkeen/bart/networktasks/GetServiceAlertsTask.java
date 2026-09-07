package com.dougkeen.bart.networktasks;

import com.dougkeen.bart.model.Alert;
import com.dougkeen.bart.model.Constants;
import com.google.transit.realtime.GtfsRealtime;

import android.util.Log;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public abstract class GetServiceAlertsTask extends
        NetworkTask<Void, Integer, Alert.AlertList> {

    private static final String ALERTS_URL =
            "https://api.bart.gov/gtfsrt/alerts.aspx";
    private static final long CACHE_MILLIS = 90000L;
    private static final Object CACHE_LOCK = new Object();
    private static final OkHttpClient CLIENT = NetworkUtils.makeHttpClient();
    private static Alert.AlertList cachedAlerts;
    private static long cachedAt;
    private static boolean refreshInProgress;

    private Exception exception;

    @Override
    protected Alert.AlertList doInBackground(Void... ignored) {
        try {
            return getCachedAlerts();
        } catch (IOException e) {
            exception = e;
            return null;
        }
    }

    private Alert.AlertList getCachedAlerts() throws IOException {
        synchronized (CACHE_LOCK) {
            long now = System.currentTimeMillis();
            if (cachedAlerts != null && now - cachedAt < CACHE_MILLIS) {
                return cachedAlerts;
            }
            while (refreshInProgress) {
                try {
                    CACHE_LOCK.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for alerts", e);
                }
                now = System.currentTimeMillis();
                if (cachedAlerts != null && now - cachedAt < CACHE_MILLIS) {
                    return cachedAlerts;
                }
            }
            refreshInProgress = true;
        }

        try {
            Log.v(Constants.TAG, "Refreshing GTFS-RT service alerts");
            Request request = new Request.Builder().url(ALERTS_URL)
                    .header("Accept", "application/x-google-protobuf")
                    .build();
            Response response = CLIENT.newCall(request).execute();
            try {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("Service alerts returned "
                            + response.code());
                }
                Alert.AlertList result = parse(GtfsRealtime.FeedMessage
                        .parseFrom(response.body().byteStream()));
                synchronized (CACHE_LOCK) {
                    cachedAlerts = result;
                    cachedAt = System.currentTimeMillis();
                }
                return result;
            } finally {
                response.close();
            }
        } finally {
            synchronized (CACHE_LOCK) {
                refreshInProgress = false;
                CACHE_LOCK.notifyAll();
            }
        }
    }

    private static Alert.AlertList parse(GtfsRealtime.FeedMessage feed) {
        Alert.AlertList result = new Alert.AlertList();
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT,
                DateFormat.SHORT, Locale.getDefault());
        for (GtfsRealtime.FeedEntity entity : feed.getEntityList()) {
            if (!entity.hasAlert()) {
                continue;
            }
            GtfsRealtime.Alert source = entity.getAlert();
            Alert alert = new Alert(entity.getId());
            alert.setType(source.hasEffect() ? source.getEffect().name() : "");
            alert.setDescription(text(source.hasHeaderText()
                    ? source.getHeaderText() : null, source.hasDescriptionText()
                    ? source.getDescriptionText() : null));
            if (!source.getActivePeriodList().isEmpty()) {
                GtfsRealtime.TimeRange period = source.getActivePeriod(0);
                if (period.hasStart()) {
                    alert.setPostedTime(format.format(new Date(
                            period.getStart() * 1000L)));
                }
                if (period.hasEnd()) {
                    alert.setExpiresTime(format.format(new Date(
                            period.getEnd() * 1000L)));
                }
            }
            result.addAlert(alert);
        }
        if (!result.hasAlerts()) {
            result.setNoDelaysReported(true);
        }
        return result;
    }

    private static String text(GtfsRealtime.TranslatedString header,
                               GtfsRealtime.TranslatedString description) {
        String headerText = translation(header);
        String descriptionText = translation(description);
        if (headerText.isEmpty()) {
            return descriptionText;
        }
        if (descriptionText.isEmpty()) {
            return headerText;
        }
        return headerText + "\n" + descriptionText;
    }

    private static String translation(GtfsRealtime.TranslatedString value) {
        if (value == null || value.getTranslationList().isEmpty()) {
            return "";
        }
        return value.getTranslation(0).getText();
    }

    @Override
    protected void onPostExecute(Alert.AlertList result) {
        if (result != null) {
            onResult(result);
        } else {
            onError(exception);
        }
    }

    public abstract void onResult(Alert.AlertList alerts);

    public abstract void onError(Exception exception);
}
