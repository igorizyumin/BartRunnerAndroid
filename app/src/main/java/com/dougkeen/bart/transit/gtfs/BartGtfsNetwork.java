package com.dougkeen.bart.transit.gtfs;

import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Station;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** BART-specific adapter from generic GTFS facts to stable app identities. */
public final class BartGtfsNetwork {
    public static final class StationPattern {
        private final String routeId;
        private final String direction;
        private final List<Station> stations;
        private final List<String> tripIds;

        private StationPattern(String routeId, String direction,
                               List<Station> stations, List<String> tripIds) {
            this.routeId = routeId;
            this.direction = direction;
            this.stations = Collections.unmodifiableList(
                    new ArrayList<Station>(stations));
            this.tripIds = Collections.unmodifiableList(
                    new ArrayList<String>(tripIds));
        }

        public String getRouteId() {
            return routeId;
        }

        public String getDirection() {
            return direction;
        }

        public List<Station> getStations() {
            return stations;
        }

        public List<String> getTripIds() {
            return tripIds;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof StationPattern)) {
                return false;
            }
            StationPattern pattern = (StationPattern) other;
            return java.util.Objects.equals(routeId, pattern.routeId)
                    && java.util.Objects.equals(direction, pattern.direction)
                    && stations.equals(pattern.stations);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(routeId, direction, stations);
        }
    }

    /** Immutable, BART-mapped transfer rule from transfers.txt. */
    public static final class TransferRule {
        private final Station fromStation;
        private final Station toStation;
        private final String fromRouteId;
        private final String toRouteId;
        private final Line fromLine;
        private final Line toLine;
        private final int transferType;
        private final Integer minimumTransferSeconds;

        private TransferRule(Station fromStation, Station toStation,
                             String fromRouteId, String toRouteId,
                             Line fromLine, Line toLine, int transferType,
                             Integer minimumTransferSeconds) {
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.fromRouteId = fromRouteId;
            this.toRouteId = toRouteId;
            this.fromLine = fromLine;
            this.toLine = toLine;
            this.transferType = transferType;
            this.minimumTransferSeconds = minimumTransferSeconds;
        }

        public Station getFromStation() {
            return fromStation;
        }

        public Station getToStation() {
            return toStation;
        }

        public String getFromRouteId() {
            return fromRouteId;
        }

        public String getToRouteId() {
            return toRouteId;
        }

        public Line getFromLine() {
            return fromLine;
        }

        public Line getToLine() {
            return toLine;
        }

        public int getTransferType() {
            return transferType;
        }

        public Integer getMinimumTransferSeconds() {
            return minimumTransferSeconds;
        }

        public boolean isForbidden() {
            return transferType == 3;
        }
    }

    private final GtfsNetworkCatalog catalog;
    private final Map<String, Station> stationsByStopId;
    private final Map<String, Line> linesByRouteId;
    private final List<TransferRule> transferRules;

    private BartGtfsNetwork(GtfsNetworkCatalog catalog,
                            Map<String, Station> stationsByStopId,
                            Map<String, Line> linesByRouteId,
                            List<TransferRule> transferRules) {
        this.catalog = catalog;
        this.stationsByStopId = immutableMap(stationsByStopId);
        this.linesByRouteId = immutableMap(linesByRouteId);
        this.transferRules = Collections.unmodifiableList(
                new ArrayList<TransferRule>(transferRules));
    }

    public static BartGtfsNetwork fromCatalog(GtfsNetworkCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("A GTFS catalog is required");
        }

        Map<String, Station> byStopId = new HashMap<String, Station>();
        for (GtfsStop stop : catalog.getStopsById().values()) {
            Station station = stationForStop(catalog, stop);
            if (station == null) {
                continue;
            }
            byStopId.put(stop.getStopId(), station);
        }

        Map<String, Line> lines = routeLineMappings(catalog);
        return new BartGtfsNetwork(catalog, byStopId, lines,
                transferRules(catalog, byStopId, lines));
    }

    public Station stationForStopId(String stopId) {
        if (stopId == null) {
            return null;
        }
        Station exact = stationsByStopId.get(stopId);
        return exact;
    }

    public Line lineForRouteId(String routeId) {
        return routeId == null ? null : linesByRouteId.get(routeId);
    }

    /** Returns the direction encoded by the GTFS route name, if present. */
    public String directionForRouteId(String routeId) {
        if (routeId == null) {
            return null;
        }
        GtfsRoute route = catalog.getRoutesById().get(routeId);
        if (route == null || route.getShortName() == null) {
            return null;
        }
        String shortName = route.getShortName().trim().toUpperCase(Locale.ROOT);
        int separator = shortName.lastIndexOf('-');
        if (separator < 0 || separator + 1 >= shortName.length()) {
            return null;
        }
        String direction = shortName.substring(separator + 1);
        return "N".equals(direction) ? "n" : "S".equals(direction) ? "s" : null;
    }

    /** Returns GTFS station patterns while retaining each route direction. */
    public List<StationPattern> routePatternsForLine(Line line) {
        if (line == null) {
            return Collections.emptyList();
        }
        LinkedHashSet<StationPattern> patterns = new LinkedHashSet<StationPattern>();
        for (GtfsRoutePattern pattern : catalog.getPatterns()) {
            if (line != linesByRouteId.get(pattern.getRouteId())) {
                continue;
            }
            List<Station> stations = stationsForPattern(pattern);
            if (stations.size() >= 2) {
                patterns.add(new StationPattern(
                        pattern.getRouteId(),
                        directionForRouteId(pattern.getRouteId()),
                        stations,
                        new ArrayList<String>(pattern.getTripIds())));
            }
        }
        return Collections.unmodifiableList(
                new ArrayList<StationPattern>(patterns));
    }

    /** Returns the distinct station sequences supplied by GTFS for a line. */
    public List<List<Station>> stationPatternsForLine(Line line) {
        List<List<Station>> result = new ArrayList<List<Station>>();
        for (StationPattern pattern : routePatternsForLine(line)) {
            if (!result.contains(pattern.getStations())) {
                result.add(pattern.getStations());
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<Line> linesForStation(Station station) {
        if (station == null) {
            return Collections.emptyList();
        }
        List<Line> result = new ArrayList<Line>();
        for (Line line : Line.values()) {
            for (StationPattern pattern : routePatternsForLine(line)) {
                if (pattern.getStations().contains(station)) {
                    result.add(line);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public boolean isBetween(Station station, Station origin,
                             Station destination, Line line) {
        if (station == null || origin == null || destination == null
                || line == null) {
            return false;
        }
        for (StationPattern pattern : routePatternsForLine(line)) {
            List<Station> stations = pattern.getStations();
            int originIndex = stations.indexOf(origin);
            int destinationIndex = stations.indexOf(destination);
            int stationIndex = stations.indexOf(station);
            if (originIndex >= 0 && destinationIndex >= 0 && stationIndex >= 0
                    && originIndex < destinationIndex
                    && stationIndex > originIndex
                    && stationIndex < destinationIndex) {
                return true;
            }
        }
        return false;
    }

    public String routeIdForTrip(String tripId) {
        return catalog.routeIdForTrip(tripId);
    }

    public List<TransferRule> getTransferRules() {
        return transferRules;
    }

    /**
     * Returns whether the feed explicitly permits changing between two lines
     * at a station. Missing transfer data is not treated as permission.
     */
    public boolean canTransfer(Station station, Line fromLine, Line toLine) {
        if (station == null || fromLine == null || toLine == null
                || fromLine == toLine) {
            return false;
        }
        boolean matchedRule = false;
        for (TransferRule rule : transferRules) {
            if (rule.getFromStation() != station
                    || rule.getToStation() != station
                    || !lineMatches(rule.getFromRouteId(), rule.getFromLine(),
                    fromLine)
                    || !lineMatches(rule.getToRouteId(), rule.getToLine(),
                    toLine)) {
                continue;
            }
            matchedRule = true;
            if (rule.isForbidden()) {
                return false;
            }
        }
        return matchedRule;
    }

    /**
     * Returns the smallest matching feed minimum because platform IDs are
     * intentionally abstracted to one app station identity.
     */
    public int minimumTransferSeconds(Station station, Line fromLine,
                                      Line toLine) {
        if (!canTransfer(station, fromLine, toLine)) {
            return -1;
        }
        int minimum = Integer.MAX_VALUE;
        for (TransferRule rule : transferRules) {
            if (rule.getFromStation() != station
                    || rule.getToStation() != station
                    || !lineMatches(rule.getFromRouteId(), rule.getFromLine(),
                    fromLine)
                    || !lineMatches(rule.getToRouteId(), rule.getToLine(),
                    toLine)
                    || rule.isForbidden()) {
                continue;
            }
            Integer seconds = rule.getMinimumTransferSeconds();
            if (seconds != null && seconds >= 0) {
                minimum = Math.min(minimum, seconds);
            }
        }
        return minimum == Integer.MAX_VALUE ? 0 : minimum;
    }

    public List<String> validationErrors() {
        List<String> errors = new ArrayList<String>(catalog.validationErrors());
        for (String routeId : catalog.getRoutesById().keySet()) {
            GtfsRoute route = catalog.getRoutesById().get(routeId);
            if (!linesByRouteId.containsKey(routeId)
                    && !isExplicitlyUnsupportedRoute(route)) {
                errors.add("No BART line mapping for route " + routeId);
            }
            if (linesByRouteId.containsKey(routeId)
                    && directionForRouteId(routeId) == null) {
                errors.add("No BART direction mapping for route " + routeId);
            }
        }
        for (GtfsStop stop : catalog.getStopsById().values()) {
            if (!stationsByStopId.containsKey(stop.getStopId())
                    && !isNonRevenueOaklandAirportStop(stop)) {
                errors.add("No BART station mapping for stop " + stop.getStopId());
            }
        }
        return Collections.unmodifiableList(errors);
    }

    private static boolean isNonRevenueOaklandAirportStop(GtfsStop stop) {
        return "OAKL".equalsIgnoreCase(stop.getStopId())
                || "OAKL".equalsIgnoreCase(stop.getParentStationId())
                || "OAKL".equalsIgnoreCase(stop.getZoneId());
    }

    private static boolean lineMatches(String ruleRouteId, Line ruleLine,
                                       Line requestedLine) {
        return ruleRouteId == null
                ? ruleLine == null || ruleLine == requestedLine
                : ruleLine == requestedLine;
    }

    private static List<TransferRule> transferRules(
            GtfsNetworkCatalog catalog, Map<String, Station> stationsByStopId,
            Map<String, Line> linesByRouteId) {
        List<TransferRule> result = new ArrayList<TransferRule>();
        for (GtfsTransfer transfer : catalog.getTransfers()) {
            Station fromStation = stationsByStopId.get(transfer.getFromStopId());
            Station toStation = stationsByStopId.get(transfer.getToStopId());
            if (fromStation == null || toStation == null) {
                continue;
            }
            result.add(new TransferRule(fromStation, toStation,
                    transfer.getFromRouteId(), transfer.getToRouteId(),
                    linesByRouteId.get(transfer.getFromRouteId()),
                    linesByRouteId.get(transfer.getToRouteId()),
                    transfer.getTransferType(),
                    transfer.getMinimumTransferSeconds()));
        }
        return result;
    }

    private List<Station> stationsForPattern(GtfsRoutePattern pattern) {
        List<Station> stations = new ArrayList<Station>();
        for (String stopId : pattern.getStopIds()) {
            Station station = stationForStopId(stopId);
            if (station != null && station != Station.SPCL
                    && (stations.isEmpty()
                    || stations.get(stations.size() - 1) != station)) {
                stations.add(station);
            }
        }
        return stations;
    }

    private static Station stationForStop(GtfsNetworkCatalog catalog,
                                          GtfsStop stop) {
        Station station = Station.getByAbbreviation(stop.getZoneId());
        if (station != null && station != Station.SPCL) {
            return station;
        }
        station = stationForName(stop.getName());
        if (station != null) {
            return station;
        }
        if (stop.getParentStationId() != null) {
            GtfsStop parent = catalog.getStopsById().get(stop.getParentStationId());
            if (parent != null) {
                station = Station.getByAbbreviation(parent.getZoneId());
                if (station != null && station != Station.SPCL) {
                    return station;
                }
                return stationForName(parent.getName());
            }
        }
        return null;
    }

    private static Station stationForName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String normalized = normalize(name);
        for (Station station : Station.getStationList()) {
            if (normalized.equals(normalize(station.toString()))
                    || normalized.equals(normalize(station.apiName))
                    || normalized.startsWith(normalize(station.toString()) + "platform")) {
                return station;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        StringBuilder result = new StringBuilder();
        String lowercase = value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lowercase.length(); i++) {
            char character = lowercase.charAt(i);
            if (character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9') {
                result.append(character);
            }
        }
        return result.toString();
    }

    /** Route IDs are source-system aliases; topology comes from GTFS patterns. */
    private static Map<String, Line> routeLineMappings(
            GtfsNetworkCatalog catalog) {
        Map<String, Line> mappings = new LinkedHashMap<String, Line>();
        for (GtfsRoute route : catalog.getRoutesById().values()) {
            Line line = lineForRouteName(route.getShortName());
            if (line != null) {
                mappings.put(route.getRouteId(), line);
            }
        }
        return mappings;
    }

    private static Line lineForRouteName(String routeName) {
        if (routeName == null) {
            return null;
        }
        String normalized = routeName.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("yellow")) {
            return Line.YELLOW;
        }
        if (normalized.startsWith("orange")) {
            return Line.ORANGE;
        }
        if (normalized.startsWith("green")) {
            return Line.GREEN;
        }
        if (normalized.startsWith("red")) {
            return Line.RED;
        }
        if (normalized.startsWith("blue")) {
            return Line.BLUE;
        }
        return null;
    }

    private static boolean isExplicitlyUnsupportedRoute(GtfsRoute route) {
        if (route == null || route.getShortName() == null) {
            return false;
        }
        String normalized = route.getShortName().toLowerCase(Locale.ROOT);
        return normalized.startsWith("grey")
                || normalized.startsWith("bridge");
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<K, V>(values));
    }
}
