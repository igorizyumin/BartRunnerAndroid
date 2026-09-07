package com.dougkeen.bart.routing;

import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Route;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure BART route-planning rules. This class has no Android, network, or UI
 * dependencies and is the single source of truth for direct and transfer
 * route selection.
 */
public final class TripPlanner {
    private static final Comparator<Route> ROUTE_PREFERENCE =
            new Comparator<Route>() {
                @Override
                public int compare(Route left, Route right) {
                    return Integer.compare(routeScore(left), routeScore(right));
                }
            };

    private TripPlanner() {
    }

    /**
     * Plans direct routes from validated static GTFS patterns. Transfer
     * selection remains on the existing policy path until it is pattern-aware.
     */
    public static List<Route> routesFor(Station origin, Station destination,
                                        BartGtfsNetwork network) {
        requireNetwork(network);
        if (origin == null || origin == destination) {
            return new ArrayList<Route>();
        }
        if (destination == null) {
            return catalogStationOnlyRoutes(origin, network);
        }

        List<Route> routes = catalogDirectRoutes(origin, destination, network);
        if (routes.isEmpty()) {
            routes.addAll(catalogPreferredTransferRoutes(origin, destination,
                    network));
        }
        return routes;
    }

    public static List<Route> preferredTransferRoutes(
            Station origin, Station destination, BartGtfsNetwork network) {
        requireNetwork(network);
        List<Route> transferRoutes = catalogTransferRoutes(origin, destination,
                network, false);
        List<Route> doubleTransferRoutes = catalogTransferRoutes(origin,
                destination, network, true);
        Collections.sort(transferRoutes, ROUTE_PREFERENCE);
        Collections.sort(doubleTransferRoutes, ROUTE_PREFERENCE);
        if (doubleTransferRoutes.isEmpty()) {
            return transferRoutes;
        }
        if (transferRoutes.isEmpty()
                || routeScore(doubleTransferRoutes.get(0))
                < routeScore(transferRoutes.get(0))) {
            return doubleTransferRoutes;
        }
        return transferRoutes;
    }

    public static List<Route> transferRoutes(Station origin,
                                             Station destination,
                                             BartGtfsNetwork network) {
        requireNetwork(network);
        List<Route> routes = catalogTransferRoutes(origin, destination, network,
                false);
        Collections.sort(routes, ROUTE_PREFERENCE);
        return routes;
    }

    public static List<Route> doubleTransferRoutes(Station origin,
                                                   Station destination,
                                                   BartGtfsNetwork network) {
        requireNetwork(network);
        List<Route> routes = catalogTransferRoutes(origin, destination, network,
                true);
        Collections.sort(routes, ROUTE_PREFERENCE);
        return routes;
    }

    private static List<Route> catalogStationOnlyRoutes(Station origin,
                                                        BartGtfsNetwork network) {
        List<Route> routes = new ArrayList<Route>();
        for (Line line : network.linesForStation(origin)) {
            List<Station> bestPattern = null;
            for (BartGtfsNetwork.StationPattern pattern
                    : network.routePatternsForLine(line)) {
                List<Station> stations = pattern.getStations();
                if (stations.contains(origin)
                        && (bestPattern == null || stations.size() > bestPattern.size())) {
                    bestPattern = stations;
                }
            }
            if (bestPattern == null) {
                continue;
            }
            Route route = new Route();
            route.setOrigin(origin);
            route.setDestination(null);
            route.setDirectLine(line);
            route.setTransfer(false);
            route.setStationSequence(line, bestPattern);
            routes.add(route);
        }
        return routes;
    }

    private static List<Route> catalogDirectRoutes(Station origin,
                                                   Station destination,
                                                   BartGtfsNetwork network) {
        List<Route> routes = new ArrayList<Route>();
        for (Line line : network.linesForStation(origin)) {
            Map<String, BartGtfsNetwork.StationPattern> bestPatterns =
                    new LinkedHashMap<String, BartGtfsNetwork.StationPattern>();
            for (BartGtfsNetwork.StationPattern pattern
                    : network.routePatternsForLine(line)) {
                List<Station> stations = pattern.getStations();
                int candidateOriginIndex = stations.indexOf(origin);
                int candidateDestinationIndex = stations.indexOf(destination);
                if (candidateOriginIndex < 0 || candidateDestinationIndex < 0
                        || candidateOriginIndex >= candidateDestinationIndex) {
                    continue;
                }
                String direction = pattern.getDirection();
                BartGtfsNetwork.StationPattern previous = bestPatterns.get(direction);
                if (previous == null || stations.size() > previous.getStations().size()) {
                    bestPatterns.put(direction, pattern);
                }
            }
            for (BartGtfsNetwork.StationPattern pattern : bestPatterns.values()) {
                Route route = new Route();
                route.setOrigin(origin);
                route.setDestination(destination);
                route.setDirectLine(line);
                route.setDirection(pattern.getDirection());
                route.setTransfer(false);
                route.setStationSequence(line, pattern.getStations());
                routes.add(route);
            }
        }
        return routes;
    }

    private static List<Route> catalogPreferredTransferRoutes(
            Station origin, Station destination, BartGtfsNetwork network) {
        List<Route> transferRoutes = catalogTransferRoutes(origin, destination,
                network, false);
        List<Route> doubleTransferRoutes = catalogTransferRoutes(origin,
                destination, network, true);
        Collections.sort(transferRoutes, ROUTE_PREFERENCE);
        Collections.sort(doubleTransferRoutes, ROUTE_PREFERENCE);
        if (doubleTransferRoutes.isEmpty()) {
            return transferRoutes;
        }
        if (transferRoutes.isEmpty()
                || routeScore(doubleTransferRoutes.get(0))
                < routeScore(transferRoutes.get(0))) {
            return doubleTransferRoutes;
        }
        return transferRoutes;
    }

    private static List<Route> catalogTransferRoutes(Station origin,
                                                     Station destination,
                                                     BartGtfsNetwork network,
                                                     boolean onlyDoubleTransfers) {
        List<Route> routes = new ArrayList<Route>();
        List<Line> usableLines = catalogUsableLines(network);
        for (Line first : usableLines) {
            if (!catalogContains(first, origin, network)) {
                continue;
            }
            for (Line last : usableLines) {
                if (first == last || !catalogContains(last, destination, network)) {
                    continue;
                }
                for (Station transfer : catalogCommonStations(first, last, network)) {
                    if (!network.canTransfer(transfer, first, last)) {
                        continue;
                    }
                    if (!onlyDoubleTransfers
                            && catalogSegment(first, origin, transfer, network) != null
                            && catalogSegment(last, transfer, destination, network) != null) {
                        addCatalogTransferRoute(routes, origin, destination,
                                asList(first, last), asList(transfer), network);
                    }
                }
                for (Line middle : usableLines) {
                    if (middle == first || middle == last) {
                        continue;
                    }
                    for (Station firstTransfer : catalogCommonStations(first,
                            middle, network)) {
                        if (!network.canTransfer(firstTransfer, first, middle)
                                || catalogSegment(first, origin, firstTransfer, network)
                                == null) {
                            continue;
                        }
                        for (Station secondTransfer : catalogCommonStations(
                                middle, last, network)) {
                            if (!network.canTransfer(secondTransfer, middle, last)
                                    || firstTransfer == secondTransfer
                                    || catalogSegment(middle, firstTransfer,
                                    secondTransfer, network) == null
                                    || catalogSegment(last, secondTransfer,
                                    destination, network) == null) {
                                continue;
                            }
                            addCatalogTransferRoute(routes, origin, destination,
                                    asList(first, middle, last),
                                    asList(firstTransfer, secondTransfer), network);
                        }
                    }
                }
            }
        }
        return uniqueRoutes(routes);
    }

    private static void addCatalogTransferRoute(List<Route> routes,
                                                Station origin,
                                                Station destination,
                                                List<Line> lines,
                                                List<Station> transfers,
                                                BartGtfsNetwork network) {
        Route route = makeCatalogTransferRoute(origin, destination, lines,
                transfers, network);
        if (route != null && isValidTransferPath(route)) {
            routes.add(route);
        }
    }

    private static Route makeCatalogTransferRoute(Station origin,
                                                  Station destination,
                                                  List<Line> lines,
                                                  List<Station> transfers,
                                                  BartGtfsNetwork network) {
        Route route = new Route();
        route.setOrigin(origin);
        route.setDestination(destination);
        route.setLines(lines);
        route.setDirectLine(lines.get(0));
        route.setTransferStations(transfers);
        route.setTransfer(true);
        route.setTransferLines(lines.subList(1, lines.size()));
        for (int i = 0; i < lines.size(); i++) {
            Station segmentOrigin = i == 0 ? origin : transfers.get(i - 1);
            Station segmentDestination = i == lines.size() - 1
                    ? destination : transfers.get(i);
            BartGtfsNetwork.StationPattern segment = catalogSegment(lines.get(i),
                    segmentOrigin,
                    segmentDestination, network);
            if (segment == null) {
                return null;
            }
            route.setStationSequence(lines.get(i), segment.getStations());
            if (i == 0) {
                route.setDirection(segment.getDirection());
            }
        }
        return route;
    }

    private static List<Line> catalogUsableLines(BartGtfsNetwork network) {
        List<Line> lines = new ArrayList<Line>();
        for (Line line : Line.values()) {
            if (!network.routePatternsForLine(line).isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static boolean catalogContains(Line line, Station station,
                                           BartGtfsNetwork network) {
        for (BartGtfsNetwork.StationPattern pattern
                : network.routePatternsForLine(line)) {
            if (pattern.getStations().contains(station)) {
                return true;
            }
        }
        return false;
    }

    private static List<Station> catalogCommonStations(Line first, Line second,
                                                       BartGtfsNetwork network) {
        List<Station> result = new ArrayList<Station>();
        for (BartGtfsNetwork.StationPattern firstPattern
                : network.routePatternsForLine(first)) {
            for (Station station : firstPattern.getStations()) {
                if (!result.contains(station) && catalogContains(second, station,
                        network)) {
                    result.add(station);
                }
            }
        }
        return result;
    }

    private static BartGtfsNetwork.StationPattern catalogSegment(
            Line line, Station origin, Station destination,
            BartGtfsNetwork network) {
        BartGtfsNetwork.StationPattern best = null;
        for (BartGtfsNetwork.StationPattern pattern
                : network.routePatternsForLine(line)) {
            List<Station> stations = pattern.getStations();
            int originIndex = stations.indexOf(origin);
            int destinationIndex = stations.indexOf(destination);
            if (originIndex >= 0 && destinationIndex >= 0
                    && originIndex < destinationIndex
                    && (best == null || stations.size() > best.getStations().size())) {
                best = pattern;
            }
        }
        return best;
    }

    /**
     * Rejects paths that pass the final destination on an earlier leg, or
     * return through the origin after leaving it. Those paths are technically
     * connected in the station graph but are not usable passenger itineraries.
     */
    private static boolean isValidTransferPath(Route route) {
        Station origin = route.getOrigin();
        Station destination = route.getDestination();
        List<Line> lines = route.getLines();
        List<Station> transfers = route.getTransferStations();
        for (int i = 0; i < lines.size(); i++) {
            Station segmentOrigin = i == 0 ? origin : transfers.get(i - 1);
            Station segmentDestination = i == lines.size() - 1
                    ? destination : transfers.get(i);
            List<Station> stationSequence = route.getStationSequence(lines.get(i));
            if (stationSequence.indexOf(segmentOrigin) < 0
                    || stationSequence.indexOf(segmentDestination) < 0
                    || stationSequence.indexOf(segmentOrigin)
                    == stationSequence.indexOf(segmentDestination)) {
                return false;
            }
            if (i < lines.size() - 1 && stationSequence.contains(destination)) {
                return false;
            }
            if (i > 0 && stationSequence.contains(origin)) {
                return false;
            }
        }
        return true;
    }

    private static boolean usesPreferredTransferStations(Route route) {
        List<Line> lines = route.getLines();
        List<Station> transfers = route.getTransferStations();
        for (int i = 0; i < transfers.size(); i++) {
            Station preferred = preferredTransferStation(route, lines.get(i),
                    lines.get(i + 1), lines, transfers, i);
            if (preferred != null && transfers.get(i) != preferred) {
                return false;
            }
        }
        return true;
    }

    private static int routeScore(Route route) {
        int score = route.getTransferStations().size() * 100;
        List<Line> lines = route.getLines();
        if ((route.getOrigin() == Station.DUBL
                || route.getOrigin() == Station.CAST)
                && (route.getDestination() == Station.PITT
                || route.getDestination() == Station.PCTR
                || route.getDestination() == Station.ANTC)
                && lines.size() == 3
                && lines.get(0) == Line.BLUE
                && lines.get(1) == Line.ORANGE
                && lines.get(2) == Line.YELLOW
                && route.getTransferStations().size() == 2) {
            score -= 200;
        }
        List<Station> transfers = route.getTransferStations();
        for (int i = 0; i < transfers.size(); i++) {
            score += transferStationPenalty(route, lines, transfers, i);
        }
        return score;
    }

    private static int transferStationPenalty(Route route, List<Line> lines,
                                               List<Station> transfers,
                                               int index) {
        Station preferred = preferredTransferStation(route, lines.get(index),
                lines.get(index + 1), lines, transfers, index);
        return transfers.get(index) == preferred ? 0 : 10;
    }

    private static Station preferredTransferStation(Route route, Line first,
                                                    Line second,
                                                    List<Line> lines,
                                                    List<Station> transfers,
                                                    int transferIndex) {
        if (isEastBayToSanFranciscoTrunk(first, second)) {
            return Station.BALB;
        }
        if (samePair(first, second, Line.BLUE, Line.ORANGE)) {
            return Station.BAYF;
        }
        if (samePair(first, second, Line.ORANGE, Line.YELLOW)) {
            Line orange = first == Line.ORANGE ? first : second;
            Station orangeStart;
            Station orangeEnd;
            if (first == Line.ORANGE) {
                orangeStart = transferIndex == 0 ? route.getOrigin()
                        : transfers.get(transferIndex - 1);
                orangeEnd = transfers.get(transferIndex);
            } else {
                orangeStart = transfers.get(transferIndex);
                orangeEnd = transferIndex + 1 < transfers.size()
                        ? transfers.get(transferIndex + 1)
                        : route.getDestination();
            }
            if (orangeStart != null && orangeEnd != null) {
                List<Station> orangeStations = route.getStationSequence(orange);
                int startIndex = orangeStations.indexOf(orangeStart);
                int endIndex = orangeStations.indexOf(orangeEnd);
                if (startIndex > endIndex) {
                    return Station.MCAR;
                }
                return Station._19TH;
            }
            return Station.MCAR;
        }
        return null;
    }

    private static boolean isEastBayToSanFranciscoTrunk(Line first,
                                                         Line second) {
        boolean eastBayLine = first == Line.BLUE || first == Line.GREEN;
        boolean sanFranciscoTrunk = second == Line.RED
                || second == Line.YELLOW
                || second == Line.YELLOW_LATE_NIGHT;
        boolean reversedEastBayLine = second == Line.BLUE
                || second == Line.GREEN;
        boolean reversedSanFranciscoTrunk = first == Line.RED
                || first == Line.YELLOW
                || first == Line.YELLOW_LATE_NIGHT;
        return (eastBayLine && sanFranciscoTrunk)
                || (reversedEastBayLine && reversedSanFranciscoTrunk);
    }

    private static boolean samePair(Line left, Line right, Line expectedLeft,
                                    Line expectedRight) {
        return (left == expectedLeft && right == expectedRight)
                || (left == expectedRight && right == expectedLeft);
    }

    private static List<Route> uniqueRoutes(List<Route> routes) {
        List<Route> unique = new ArrayList<Route>();
        for (Route route : routes) {
            boolean duplicate = false;
            for (Route existing : unique) {
                if (existing.getLines().equals(route.getLines())
                        && existing.getTransferStations().equals(
                        route.getTransferStations())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(route);
            }
        }
        return unique;
    }

    private static void requireNetwork(BartGtfsNetwork network) {
        if (network == null) {
            throw new IllegalArgumentException(
                    "A validated GTFS network is required for route planning");
        }
    }

    @SafeVarargs
    private static <T> List<T> asList(T... values) {
        List<T> result = new ArrayList<T>();
        Collections.addAll(result, values);
        return result;
    }
}
