package com.dougkeen.bart.networktasks;

import android.content.Context;
import android.util.Log;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.model.Constants;
import com.dougkeen.bart.model.ScheduleInformation;
import com.dougkeen.bart.model.ScheduleItem;
import com.dougkeen.bart.model.Station;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Loads BART's static GTFS feed once per service day. */
public final class GtfsStaticData {
    private static final String FEED_URL =
            "https://www.bart.gov/dev/schedules/google_transit.zip";
    private static final long CACHE_MILLIS = 24L * 60L * 60L * 1000L;
    private static final String CACHE_FILE_NAME = "gtfs_static_schedule.zip";
    private static final String PREFS_NAME = "gtfs_static_schedule";
    private static final String LAST_ATTEMPT = "last_attempt";
    private static final String LAST_SUCCESS = "last_success";
    private static final TimeZone PACIFIC_TIME =
            TimeZone.getTimeZone("America/Los_Angeles");
    private static final Object LOCK = new Object();
    private static final OkHttpClient CLIENT = NetworkUtils.makeHttpClient();

    private static GtfsStaticData cachedData;
    private static long cachedAt;
    private static String cachedServiceDate;

    private final Map<String, String> routeIdsByTripId;
    private final Map<String, List<StopTime>> stopTimesByTripId;
    private final Map<String, String> faresByStationPair;

    private GtfsStaticData(Map<String, String> routeIdsByTripId,
                           Map<String, List<StopTime>> stopTimesByTripId,
                           Map<String, String> faresByStationPair) {
        this.routeIdsByTripId = routeIdsByTripId;
        this.stopTimesByTripId = stopTimesByTripId;
        this.faresByStationPair = faresByStationPair;
    }

    public static GtfsStaticData get() throws IOException {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            String serviceDate = dateCode(Calendar.getInstance(PACIFIC_TIME,
                    Locale.US));
            if (cachedData != null && now - cachedAt < CACHE_MILLIS
                    && serviceDate.equals(cachedServiceDate)) {
                return cachedData;
            }

            Context context = BartRunnerApplication.getAppContext();
            if (context == null) {
                throw new IOException("Application context is unavailable");
            }

            File cacheFile = new File(context.getFilesDir(), CACHE_FILE_NAME);
            long lastSuccess = context.getSharedPreferences(PREFS_NAME,
                    Context.MODE_PRIVATE).getLong(LAST_SUCCESS, 0L);
            if (cacheFile.isFile() && now - lastSuccess < CACHE_MILLIS) {
                cachedData = parse(cacheFile);
                cachedAt = lastSuccess > 0 ? lastSuccess : now;
                cachedServiceDate = serviceDate;
                return cachedData;
            }

            long lastAttempt = context.getSharedPreferences(PREFS_NAME,
                    Context.MODE_PRIVATE).getLong(LAST_ATTEMPT, 0L);
            if (now - lastAttempt < CACHE_MILLIS) {
                if (cacheFile.isFile()) {
                    cachedData = parse(cacheFile);
                    cachedAt = lastSuccess > 0 ? lastSuccess : now;
                    cachedServiceDate = serviceDate;
                    return cachedData;
                }
                throw new IOException("Static GTFS refresh already attempted");
            }

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(LAST_ATTEMPT, now).apply();
            Log.v(Constants.TAG, "Refreshing static GTFS schedule from server");
            File temporaryFile = new File(context.getFilesDir(),
                    CACHE_FILE_NAME + ".tmp");
            try {
                download(temporaryFile);
                GtfsStaticData result = parse(temporaryFile);
                if (cacheFile.exists() && !cacheFile.delete()) {
                    throw new IOException("Could not replace static GTFS cache");
                }
                if (!temporaryFile.renameTo(cacheFile)) {
                    throw new IOException("Could not save static GTFS cache");
                }
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putLong(LAST_SUCCESS, now).apply();
                cachedData = result;
                cachedAt = now;
                cachedServiceDate = serviceDate;
                return result;
            } catch (IOException e) {
                temporaryFile.delete();
                if (cacheFile.isFile()) {
                    cachedData = parse(cacheFile);
                    cachedAt = lastSuccess > 0 ? lastSuccess : now;
                    cachedServiceDate = serviceDate;
                    return cachedData;
                }
                throw e;
            }
        }
    }

    public Map<String, String> getRouteIdsByTripId() {
        return routeIdsByTripId;
    }

    public String getFare(Station origin, Station destination) {
        return faresByStationPair.get(key(origin, destination));
    }

    public ScheduleInformation getSchedule(Station origin, Station destination) {
        Calendar now = Calendar.getInstance(PACIFIC_TIME, Locale.US);
        long nowMillis = now.getTimeInMillis();
        Calendar day = (Calendar) now.clone();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);

        List<ScheduleItem> trips = new ArrayList<ScheduleItem>();
        for (List<StopTime> stopTimes : stopTimesByTripId.values()) {
            StopTime originStop = null;
            StopTime destinationStop = null;
            Station terminal = null;
            for (StopTime stopTime : stopTimes) {
                Station station = GtfsRealtimeContentHandler
                        .stationForStopId(stopTime.stopId);
                if (station != null && station != Station.SPCL) {
                    terminal = station;
                }
                if (station == origin && originStop == null) {
                    originStop = stopTime;
                }
                if (station == destination && destinationStop == null) {
                    destinationStop = stopTime;
                }
            }
            if (originStop == null || destinationStop == null
                    || destinationStop.sequence <= originStop.sequence
                    || originStop.departureSeconds < 0
                    || destinationStop.arrivalSeconds < 0) {
                continue;
            }

            long departureTime = day.getTimeInMillis()
                    + originStop.departureSeconds * 1000L;
            if (departureTime < nowMillis) {
                continue;
            }
            ScheduleItem item = new ScheduleItem(origin, destination);
            item.setDepartureTime(departureTime);
            item.setArrivalTime(day.getTimeInMillis()
                    + destinationStop.arrivalSeconds * 1000L);
            item.setTrainHeadStation(terminal == null
                    ? destination.apiName : terminal.apiName);
            item.setBikesAllowed(true);
            trips.add(item);
        }

        Collections.sort(trips, new Comparator<ScheduleItem>() {
            @Override
            public int compare(ScheduleItem left, ScheduleItem right) {
                return Long.compare(left.getDepartureTime(),
                        right.getDepartureTime());
            }
        });

        ScheduleInformation schedule = new ScheduleInformation(origin,
                destination);
        schedule.setDate(nowMillis);
        for (int i = 0; i < Math.min(4, trips.size()); i++) {
            schedule.addTrip(trips.get(i));
        }
        return schedule;
    }

    private static void download(File destination) throws IOException {
        Request request = new Request.Builder().url(FEED_URL)
                .header("Accept", "application/zip").build();
        Response response = CLIENT.newCall(request).execute();
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Static GTFS returned " + response.code());
            }
            FileOutputStream output = new FileOutputStream(destination, false);
            try {
                byte[] buffer = new byte[8192];
                int count;
                InputStream input = response.body().byteStream();
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            } finally {
                output.close();
            }
        } finally {
            response.close();
        }
    }

    private static GtfsStaticData parse(File file) throws IOException {
        Map<String, Trip> trips = new HashMap<String, Trip>();
        Map<String, ServiceCalendar> calendars = new HashMap<String, ServiceCalendar>();
        Map<String, Map<String, Integer>> exceptions =
                new HashMap<String, Map<String, Integer>>();
        Map<String, String> farePrices = new HashMap<String, String>();
        List<FareRule> fareRules = new ArrayList<FareRule>();
        Calendar today = Calendar.getInstance(PACIFIC_TIME, Locale.US);
        String dateCode = dateCode(today);

        ZipInputStream zip = new ZipInputStream(new FileInputStream(file));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("calendar.txt".equals(entry.getName())) {
                    parseCalendars(zip, calendars);
                } else if ("calendar_dates.txt".equals(entry.getName())) {
                    parseExceptions(zip, exceptions);
                } else if ("trips.txt".equals(entry.getName())) {
                    parseTrips(zip, trips);
                } else if ("fare_attributes.txt".equals(entry.getName())) {
                    parseFareAttributes(zip, farePrices);
                } else if ("fare_rules.txt".equals(entry.getName())) {
                    parseFareRules(zip, fareRules);
                }
            }
        } finally {
            zip.close();
        }

        Set<String> activeServices = activeServices(calendars, exceptions,
                dateCode, today);
        Map<String, List<StopTime>> stopTimesByTripId =
                new HashMap<String, List<StopTime>>();
        zip = new ZipInputStream(new FileInputStream(file));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("stop_times.txt".equals(entry.getName())) {
                    parseStopTimes(zip, trips, activeServices,
                            stopTimesByTripId);
                }
            }
        } finally {
            zip.close();
        }

        Map<String, String> fares = new HashMap<String, String>();
        for (FareRule rule : fareRules) {
            String price = farePrices.get(rule.fareId);
            if (price != null && !rule.origin.isEmpty()
                    && !rule.destination.isEmpty()) {
                fares.put(rule.origin + ">" + rule.destination, "$" + price);
            }
        }

        Map<String, String> routeIds = new HashMap<String, String>();
        for (Trip trip : trips.values()) {
            routeIds.put(trip.tripId, trip.routeId);
        }
        return new GtfsStaticData(routeIds, stopTimesByTripId, fares);
    }

    private static void parseCalendars(ZipInputStream zip,
                                       Map<String, ServiceCalendar> calendars)
            throws IOException {
        BufferedReader reader = reader(zip);
        String[] header = splitCsvLine(reader.readLine());
        int service = indexOf(header, "service_id");
        int start = indexOf(header, "start_date");
        int end = indexOf(header, "end_date");
        String[] days = {"sunday", "monday", "tuesday", "wednesday",
                "thursday", "friday", "saturday"};
        int[] dayColumns = new int[days.length];
        for (int i = 0; i < days.length; i++) {
            dayColumns[i] = indexOf(header, days[i]);
        }
        String line;
        while ((line = reader.readLine()) != null) {
            String[] values = splitCsvLine(line);
            if (service < 0 || service >= values.length) {
                continue;
            }
            ServiceCalendar calendar = new ServiceCalendar();
            calendar.startDate = value(values, start);
            calendar.endDate = value(values, end);
            for (int i = 0; i < days.length; i++) {
                calendar.days[i] = "1".equals(value(values, dayColumns[i]));
            }
            calendars.put(values[service], calendar);
        }
    }

    private static void parseExceptions(ZipInputStream zip,
                                        Map<String, Map<String, Integer>> exceptions)
            throws IOException {
        BufferedReader reader = reader(zip);
        String[] header = splitCsvLine(reader.readLine());
        int service = indexOf(header, "service_id");
        int date = indexOf(header, "date");
        int type = indexOf(header, "exception_type");
        String line;
        while ((line = reader.readLine()) != null) {
            String[] values = splitCsvLine(line);
            if (service >= 0 && date >= 0 && type >= 0
                    && service < values.length && date < values.length
                    && type < values.length) {
                Map<String, Integer> serviceExceptions = exceptions.get(values[service]);
                if (serviceExceptions == null) {
                    serviceExceptions = new HashMap<String, Integer>();
                    exceptions.put(values[service], serviceExceptions);
                }
                serviceExceptions.put(values[date], Integer.valueOf(values[type]));
            }
        }
    }

    private static void parseTrips(ZipInputStream zip,
                                   Map<String, Trip> trips) throws IOException {
        BufferedReader reader = reader(zip);
        String[] header = splitCsvLine(reader.readLine());
        int route = indexOf(header, "route_id");
        int service = indexOf(header, "service_id");
        int trip = indexOf(header, "trip_id");
        String line;
        while ((line = reader.readLine()) != null) {
            String[] values = splitCsvLine(line);
            if (route >= 0 && service >= 0 && trip >= 0
                    && route < values.length && service < values.length
                    && trip < values.length) {
                Trip value = new Trip();
                value.tripId = values[trip];
                value.routeId = values[route];
                value.serviceId = values[service];
                trips.put(value.tripId, value);
            }
        }
    }

    private static void parseStopTimes(ZipInputStream zip,
                                       Map<String, Trip> trips,
                                       Set<String> activeServices,
                                       Map<String, List<StopTime>> result)
            throws IOException {
        BufferedReader reader = reader(zip);
        String[] header = splitCsvLine(reader.readLine());
        int trip = indexOf(header, "trip_id");
        int arrival = indexOf(header, "arrival_time");
        int departure = indexOf(header, "departure_time");
        int stop = indexOf(header, "stop_id");
        int sequence = indexOf(header, "stop_sequence");
        String line;
        while ((line = reader.readLine()) != null) {
            String[] values = splitCsvLine(line);
            if (trip < 0 || trip >= values.length) {
                continue;
            }
            Trip tripValue = trips.get(values[trip]);
            if (tripValue == null || !activeServices.contains(tripValue.serviceId)) {
                continue;
            }
            StopTime stopTime = new StopTime();
            stopTime.stopId = value(values, stop);
            stopTime.arrivalSeconds = parseGtfsTime(value(values, arrival));
            stopTime.departureSeconds = parseGtfsTime(value(values, departure));
            stopTime.sequence = parseInt(value(values, sequence));
            List<StopTime> tripStops = result.get(tripValue.tripId);
            if (tripStops == null) {
                tripStops = new ArrayList<StopTime>();
                result.put(tripValue.tripId, tripStops);
            }
            tripStops.add(stopTime);
        }
        for (List<StopTime> stops : result.values()) {
            Collections.sort(stops, new Comparator<StopTime>() {
                @Override
                public int compare(StopTime left, StopTime right) {
                    return Integer.compare(left.sequence, right.sequence);
                }
            });
        }
    }

    private static void parseFareAttributes(ZipInputStream zip,
                                            Map<String, String> fares)
            throws IOException {
        BufferedReader reader = reader(zip);
        String[] header = splitCsvLine(reader.readLine());
        int id = indexOf(header, "fare_id");
        int price = indexOf(header, "price");
        String line;
        while ((line = reader.readLine()) != null) {
            String[] values = splitCsvLine(line);
            if (id >= 0 && price >= 0 && id < values.length
                    && price < values.length) {
                fares.put(values[id], values[price]);
            }
        }
    }

    private static void parseFareRules(ZipInputStream zip,
                                       List<FareRule> rules) throws IOException {
        BufferedReader reader = reader(zip);
        String[] header = splitCsvLine(reader.readLine());
        int fare = indexOf(header, "fare_id");
        int origin = indexOf(header, "origin_id");
        int destination = indexOf(header, "destination_id");
        String line;
        while ((line = reader.readLine()) != null) {
            String[] values = splitCsvLine(line);
            if (fare >= 0 && origin >= 0 && destination >= 0
                    && fare < values.length && origin < values.length
                    && destination < values.length) {
                FareRule rule = new FareRule();
                rule.fareId = values[fare];
                rule.origin = values[origin];
                rule.destination = values[destination];
                rules.add(rule);
            }
        }
    }

    private static Set<String> activeServices(
            Map<String, ServiceCalendar> calendars,
            Map<String, Map<String, Integer>> exceptions,
            String date, Calendar today) {
        Set<String> active = new HashSet<String>();
        int day = today.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        for (Map.Entry<String, ServiceCalendar> entry : calendars.entrySet()) {
            ServiceCalendar calendar = entry.getValue();
            boolean isActive = date.compareTo(calendar.startDate) >= 0
                    && date.compareTo(calendar.endDate) <= 0
                    && calendar.days[day];
            Map<String, Integer> serviceExceptions = exceptions.get(entry.getKey());
            if (serviceExceptions != null && serviceExceptions.containsKey(date)) {
                isActive = serviceExceptions.get(date).intValue() == 1;
            }
            if (isActive) {
                active.add(entry.getKey());
            }
        }
        return active;
    }

    private static BufferedReader reader(InputStream input) {
        return new BufferedReader(new InputStreamReader(input,
                Charset.forName("UTF-8")));
    }

    private static String key(Station origin, Station destination) {
        return origin.abbreviation.toUpperCase(Locale.ROOT) + ">"
                + destination.abbreviation.toUpperCase(Locale.ROOT);
    }

    private static String dateCode(Calendar date) {
        return String.format(Locale.US, "%04d%02d%02d",
                date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1,
                date.get(Calendar.DAY_OF_MONTH));
    }

    private static int parseGtfsTime(String time) {
        if (time == null || time.isEmpty()) {
            return -1;
        }
        String[] parts = time.split(":");
        if (parts.length != 3) {
            return -1;
        }
        return parseInt(parts[0]) * 3600 + parseInt(parts[1]) * 60
                + parseInt(parts[2]);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String value(String[] values, int index) {
        return index >= 0 && index < values.length ? values[index] : "";
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
        if (line == null) {
            return new String[0];
        }
        ArrayList<String> values = new ArrayList<String>();
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

    private static class Trip {
        private String tripId;
        private String routeId;
        private String serviceId;
    }

    private static class StopTime {
        private String stopId;
        private int arrivalSeconds;
        private int departureSeconds;
        private int sequence;
    }

    private static class ServiceCalendar {
        private String startDate = "";
        private String endDate = "";
        private boolean[] days = new boolean[7];
    }

    private static class FareRule {
        private String fareId;
        private String origin;
        private String destination;
    }
}
