package com.dougkeen.bart.model;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public enum Station {
    _12TH(new Builder("12th", "12th St. Oakland City Center", "12th St Oak")
            .setTransferStation("bayf")),

    _16TH(new Builder("16th", "16th St. Mission", "16th St")),

    _19TH(new Builder("19th", "19th St. Oakland", "19th St Oak")
            .setTransferStation("bayf")),

    _24TH(new Builder("24th", "24th St. Mission", "24th St")),

    ANTC(new Builder("antc", "Antioch", "Antioch")
            .setIgnoreRoutingDirection(true)
            .setTransferStation("mcar")
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(719999)),

    ASHB(new Builder("ashb", "Ashby", "Ashby")
            .setTransferStation("mcar")),

    BALB(new Builder("balb", "Balboa Park", "Balboa")),

    BAYF(new Builder("bayf", "Bay Fair", "Bay Fair")
            .setInvertDirection(true)
            .setTransferStation("mcar")),

    BERY(new Builder("bery", "Berryessa/North San José", "Berryessa")
            .setInvertDirection(true)
            .setIgnoreRoutingDirection(true)
            .setTransferStation("bayf")
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(299999)),

    CAST(new Builder("cast", "Castro Valley", "Castro Vly")
            .setTransferStation("bayf")),

    CIVC(new Builder("civc", "Civic Center", "Civic Ctr")
            .setTransferStation("embr")
            .setApiName("Civic Center/UN Plaza")),

    COLS(new Builder("cols", "Coliseum/Oakland Airport", "Coliseum/OAK")
            .setInvertDirection(true)
            .setApiName("Coliseum")
            .setTransferStation("mcar")),

    COLM(new Builder("colm", "Colma", "Colma")
            .setTransferStation("balb")),

    CONC(new Builder("conc", "Concord", "Concord")
            .setTransferStation("mcar")),

    DALY(new Builder("daly", "Daly City", "Daly City")),

    DBRK(new Builder("dbrk", "Downtown Berkeley", "Dtwn Berk")
            .setTransferStation("mcar")),

    DUBL(new Builder("dubl", "Dublin/Pleasanton", "Dbln/Plsntn")
            .setIgnoreRoutingDirection(true)
            .setTransferStation("bayf")
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(719999)),

    DELN(new Builder("deln", "El Cerrito del Norte", "El Cer/Norte")
            .setTransferStation("mcar")),

    PLZA(new Builder("plza", "El Cerrito Plaza", "El Cer/Plz")
            .setTransferStation("mcar")),

    EMBR(new Builder("embr", "Embarcadero", "Embarcdro")),

    FRMT(new Builder("frmt", "Fremont", "Fremont")
            .setInvertDirection(true)
            .setTransferStation("bayf")),

    FTVL(new Builder("ftvl", "Fruitvale", "Fruitvale")
            .setInvertDirection(true)
            .setTransferStation("mcar")),

    GLEN(new Builder("glen", "Glen Park", "Glen Park")),

    HAYW(new Builder("hayw", "Hayward", "Hayward")
            .setInvertDirection(true)
            .setTransferStation("bayf")),

    LAFY(new Builder("lafy", "Lafayette", "Lafayette")
            .setTransferStation("mcar")
            .excludeFromLimitedService()),

    LAKE(new Builder("lake", "Lake Merritt", "Lk Merritt")
            .setInvertDirection(true)
            .setTransferStation("mcar")),

    MCAR(new Builder("mcar", "MacArthur", "MacArthur")
            .setTransferStation("bayf")),

    MLPT(new Builder("mlpt", "Milpitas", "Milpitas")
            .setInvertDirection(true)
            .setTransferStation("bayf")),

    MLBR(new Builder("mlbr", "Millbrae", "Millbrae")
            .setIgnoreRoutingDirection(true)
            .setTransferStation("balb")
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(719999)),

    MONT(new Builder("mont", "Montgomery St.", "Montgomery")
            .setTransferStation("embr")),

    NBRK(new Builder("nbrk", "North Berkeley", "N Berkeley")
            .setTransferStation("mcar")),

    NCON(new Builder("ncon", "North Concord/Martinez", "N Conc/Mrtnz")
            .setTransferStation("mcar")),

    ORIN(new Builder("orin", "Orinda", "Orinda")
            .setTransferStation("mcar")
            .excludeFromLimitedService()),

    PCTR(new Builder("pctr", "Pittsburg Center", "Pitt Ctr")
            .setIgnoreRoutingDirection(true)
            .setTransferStation("mcar")),

    PITT(new Builder("pitt", "Pittsburg/Bay Point", "Pitt/Bay Pt")
            .setIgnoreRoutingDirection(true)
            .setTransferStation("mcar")),

    PHIL(new Builder("phil", "Pleasant Hill", "Plsnt Hill")
            .setApiName("Pleasant Hill/Contra Costa Centre")
            .setTransferStation("mcar")),

    POWL(new Builder("powl", "Powell St.", "Powell")
            .setTransferStation("embr")),

    RICH(new Builder("rich", "Richmond", "Richmond")
            .setIgnoreRoutingDirection(true)
            .setTransferStation("mcar")
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(299999)),

    ROCK(new Builder("rock", "Rockridge", "Rockridge")
            .setTransferStation("mcar")
            .excludeFromLimitedService()),

    SBRN(new Builder("sbrn", "San Bruno", "San Bruno")
            .setTransferStation("balb")),

    SANL(new Builder("sanl", "San Leandro", "San Leandro")
            .setInvertDirection(true)
            .setTransferStation("mcar")),

    SFIA(new Builder("sfia", "SFO Airport", "SFO")
            .setTransferStation("balb")
            .setApiName("San Francisco International Airport")
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(719999)),

    SHAY(new Builder("shay", "South Hayward", "S Hayward")
            .setInvertDirection(true)
            .setTransferStation("bayf")),

    SSAN(new Builder("ssan", "South San Francisco", "S San Fran")
            .setTransferStation("balb")),

    UCTY(new Builder("ucty", "Union City", "Union City")
            .setInvertDirection(true)
            .setTransferStation("bayf")),

    WARM(new Builder("warm", "Warm Springs/South Fremont", "Warm Springs")
            .setInvertDirection(true)
            .setIgnoreRoutingDirection(true)
            .setTransferStation("bayf")),

    WCRK(new Builder("wcrk", "Walnut Creek", "Walnut Crk")
            .setTransferStation("mcar")
            .excludeFromLimitedService()),

    WDUB(new Builder("wdub", "West Dublin/Pleasanton", "W Dbln/Plsntn")
            .setTransferStation("bayf")),

    WOAK(new Builder("woak", "West Oakland", "W Oakland")),

    SPCL(new Builder("spcl", "Special", "Special"));

    public final String abbreviation;
    public final String name;
    public final String apiName;
    public final String shortName;
    public final boolean transferFriendly;
    public final boolean invertDirection;
    protected final String inboundTransferStation;
    protected final String outboundTransferStation;
    public final boolean ignoreRoutingDirection;
    public final boolean longStationLinger;
    public final int departureEqualityTolerance;
    public final boolean includedInLimitedService;

    public final static int DEFAULT_DEPARTURE_EQUALITY_TOLERANCE = 119999;

    private static class Builder {

        private final String abbreviation;
        private final String name;
        private final String shortName;
        private boolean invertDirection = false;
        private String inboundTransferStation = null;
        private String outboundTransferStation = null;
        private boolean ignoreRoutingDirection = false;
        private boolean longStationLinger = false;
        private int departureEqualityTolerance = DEFAULT_DEPARTURE_EQUALITY_TOLERANCE;
        private boolean includedInLimitedService = true;
        private String apiName = null;

        public Builder(String abbreviation, String name, String shortName) {
            this.abbreviation = abbreviation;
            this.name = name;
            this.shortName = shortName;
        }

        public Builder setInvertDirection(boolean invertDirection) {
            this.invertDirection = invertDirection;
            return this;
        }

        public Builder setIgnoreRoutingDirection(boolean ignoreRoutingDirection) {
            this.ignoreRoutingDirection = ignoreRoutingDirection;
            return this;
        }

        public Builder setTransferStation(String transferStation) {
            this.inboundTransferStation = transferStation;
            this.outboundTransferStation = transferStation;
            return this;
        }

        public Builder setInboundTransferStation(String inboundTransferStation) {
            this.inboundTransferStation = inboundTransferStation;
            return this;
        }

        public Builder setOutboundTransferStation(String outboundTransferStation) {
            this.outboundTransferStation = outboundTransferStation;
            return this;
        }

        public Builder setLongStationLinger(boolean longStationLinger) {
            this.longStationLinger = longStationLinger;
            return this;
        }

        public Builder setDepartureEqualityTolerance(int departureEqualityTolerance) {
            this.departureEqualityTolerance = departureEqualityTolerance;
            return this;
        }

        public Builder excludeFromLimitedService() {
            this.includedInLimitedService = false;
            return this;
        }

        public Builder setApiName(String apiName) {
            this.apiName = apiName;
            return this;
        }
    }

    Station(Builder builder) {
        this.abbreviation = builder.abbreviation;
        this.name = builder.name;
        this.apiName = builder.apiName != null ? builder.apiName.toLowerCase(Locale.ROOT) : builder.name.toLowerCase(Locale.ROOT);
        this.shortName = builder.shortName;
        this.invertDirection = builder.invertDirection;
        this.inboundTransferStation = builder.inboundTransferStation;
        this.outboundTransferStation = builder.outboundTransferStation;
        this.ignoreRoutingDirection = builder.ignoreRoutingDirection;
        this.longStationLinger = builder.longStationLinger;
        this.departureEqualityTolerance = builder.departureEqualityTolerance;
        this.includedInLimitedService = builder.includedInLimitedService;
        this.transferFriendly = this.outboundTransferStation != null;
    }

    public static Station getByAbbreviation(String abbr) {
        try {
            if (abbr == null) {
                return null;
            } else if (Character.isDigit(abbr.charAt(0))) {
                return Station.valueOf("_" + abbr.toUpperCase(Locale.ROOT));
            } else {
                return Station.valueOf(abbr.toUpperCase(Locale.ROOT));
            }
        } catch (IllegalArgumentException e) {
            Log.e(Constants.TAG, "Could not find station for '" + abbr + "'", e);
            return null;
        }
    }

    public static Station getByApproximateName(String name) {
        if (name == null) return null;

        final String lowercaseName = name.toLowerCase(Locale.ROOT);
        for (Station station : Station.values()) {
            if (lowercaseName.startsWith(station.name.toLowerCase(Locale.ROOT))) {
                return station;
            }
        }
        for (Station station : Station.values()) {
            if (lowercaseName.endsWith(station.name.toLowerCase(Locale.ROOT))) {
                return station;
            }
        }
        return Station.SPCL;
    }

    public Station getInboundTransferStation() {
        return getByAbbreviation(inboundTransferStation);
    }

    public Station getOutboundTransferStation() {
        return getByAbbreviation(outboundTransferStation);
    }

    public boolean isValidEndpointForDestination(Station dest, Station endpoint) {
        for (Line line : Line.values()) {
            int origIndex = line.stations.indexOf(this);
            if (origIndex < 0)
                continue;
            int destIndex = line.stations.indexOf(dest);
            if (destIndex < 0)
                continue;
            int endpointIndex = line.stations.indexOf(endpoint);
            if (endpointIndex >= 0)
                return true;
        }
        return false;
    }

    public List<Route> getDirectRoutesForDestination(Station dest) {
        return getDirectRoutesForDestination(this, dest, null, null);
    }

    public List<Route> getDirectRoutesForDestination(Station origin,
                                                     Station dest, Station transferStation,
                                                     Collection<Line> transferLines) {
        if (dest == null)
            return null;
        Boolean isNorth = null;
        List<Route> returnList = new ArrayList<Route>();
        final Collection<Line> applicableLines = Line.getLinesWithStations(
                this, dest);
        if (transferLines != null && !transferLines.isEmpty()) {
            for (Line transferLine : transferLines) {
                int origIndex = transferLine.stations.indexOf(origin);
                int destIndex = transferLine.stations.indexOf(origin
                        .getOutboundTransferStation());

                isNorth = (origIndex < destIndex);
                if (origin.invertDirection && transferLine.directionMayInvert) {
                    isNorth = !isNorth;
                    break;
                }
            }
        }
        for (Line line : applicableLines) {
            if (transferLines == null || transferLines.isEmpty()) {
                int origIndex = line.stations.indexOf(this);
                int destIndex = line.stations.indexOf(dest);

                isNorth = (origIndex < destIndex);
                if (line.directionMayInvert && this.invertDirection) {
                    isNorth = !isNorth;
                }
            }
            Route route = new Route();
            route.setOrigin(origin);
            route.setDirectLine(line);
            if (this.equals(origin)) {
                route.setDestination(dest);
            } else {
                // This must be the outbound transfer station
                route.setDestination(origin.getOutboundTransferStation());
                route.setTransferLines(transferLines);
            }
            route.setDirection(isNorth ? "n" : "s");
            if (transferStation != null || line.requiresTransfer) {
                route.setTransfer(true);
            } else {
                route.setTransfer(false);
            }

            returnList.add(route);
        }
        return returnList;
    }

    public List<Route> getTransferRoutes(Station dest) {
        if (dest == null || (this == Station.SFIA && dest == Station.MLBR)) {
            return new ArrayList<Route>();
        }

        List<Route> routes = findTransferRoutes(dest, false);
        Collections.sort(routes, ROUTE_PREFERENCE);
        return routes;
    }

    /**
     * Returns the transfer pattern that best matches the network's preferred
     * transfer stations. Some destinations have a geographically sensible
     * two-transfer path that is better than the shorter-looking path through
     * San Francisco.
     */
    public List<Route> getPreferredTransferRoutes(Station dest) {
        List<Route> transferRoutes = getTransferRoutes(dest);
        List<Route> doubleTransferRoutes = getDoubleTransferRoutes(dest);
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

    public List<Route> getDoubleTransferRoutes(Station dest) {
        if (dest == null) {
            return new ArrayList<Route>();
        }
        List<Route> routes = findTransferRoutes(dest, true);
        Collections.sort(routes, ROUTE_PREFERENCE);
        return routes;
    }

    private static final Comparator<Route> ROUTE_PREFERENCE =
            new Comparator<Route>() {
                @Override
                public int compare(Route left, Route right) {
                    return Integer.compare(routeScore(left), routeScore(right));
                }
            };

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
            // The one-transfer Balboa option backtracks through Daly City
            // and is not the useful East-Bay-to-East-Bay itinerary. Prefer
            // the Blue -> Orange -> Yellow path via Bay Fair and 19th St.
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
        Line first = lines.get(index);
        Line second = lines.get(index + 1);
        Station preferred = preferredTransferStation(route, first, second,
                lines, transfers, index);
        return transfers.get(index) == preferred ? 0 : 10;
    }

    private static Station preferredTransferStation(Route route, Line first,
                                                    Line second,
                                                    List<Line> lines,
                                                    List<Station> transfers,
                                                    int transferIndex) {
        if (samePair(first, second, Line.BLUE, Line.YELLOW)) {
            return Station.BALB;
        }
        if (samePair(first, second, Line.BLUE, Line.ORANGE)) {
            return Station.BAYF;
        }
        if (samePair(first, second, Line.ORANGE, Line.YELLOW)) {
            Line orange = first == Line.ORANGE ? first : second;
            // Determine the direction on Orange using the endpoints of the
            // Orange segment. Increasing GTFS station order is northbound.
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
                int startIndex = orange.stations.indexOf(orangeStart);
                int endIndex = orange.stations.indexOf(orangeEnd);
                if (startIndex > endIndex) {
                    return Station.MCAR;
                }
                return Station._19TH;
            }
            return Station.MCAR;
        }
        return null;
    }

    private static boolean samePair(Line left, Line right, Line expectedLeft,
                                    Line expectedRight) {
        return (left == expectedLeft && right == expectedRight)
                || (left == expectedRight && right == expectedLeft);
    }

    private List<Route> findTransferRoutes(Station dest,
                                           boolean onlyDoubleTransfers) {
        List<Route> routes = new ArrayList<Route>();
        List<Line> usableLines = usableLines();
        for (Line first : usableLines) {
            if (!first.containsStation(this)) {
                continue;
            }
            for (Line last : usableLines) {
                if (first == last || !last.containsStation(dest)) {
                    continue;
                }
                for (Station transfer : commonStations(first, last)) {
                    if (isSegmentUsable(first, this, transfer)
                            && isSegmentUsable(last, transfer, dest)) {
                        if (!onlyDoubleTransfers) {
                            Route route = makeTransferRoute(dest,
                                    asList(first, last),
                                    asList(transfer));
                            if (usesPreferredTransferStations(route)) {
                                routes.add(route);
                            }
                        }
                    }
                }
                for (Line middle : usableLines) {
                        if (middle == first || middle == last) {
                            continue;
                        }
                        for (Station firstTransfer : commonStations(first,
                                middle)) {
                            if (!isSegmentUsable(first, this, firstTransfer)) {
                                continue;
                            }
                            for (Station secondTransfer : commonStations(middle,
                                    last)) {
                                if (firstTransfer == secondTransfer
                                        || !isSegmentUsable(middle,
                                        firstTransfer, secondTransfer)
                                        || !isSegmentUsable(last,
                                        secondTransfer, dest)) {
                                    continue;
                                }
                                Route route = makeTransferRoute(dest,
                                        asList(first, middle, last),
                                        asList(firstTransfer,
                                                secondTransfer));
                                if (usesPreferredTransferStations(route)) {
                                    routes.add(route);
                                }
                            }
                        }
                }
            }
        }
        return uniqueRoutes(routes);
    }

    private static List<Line> usableLines() {
        List<Line> lines = new ArrayList<Line>();
        for (Line line : Line.values()) {
            if (!line.requiresTransfer && line != Line.PURPLE) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static List<Station> commonStations(Line first, Line second) {
        List<Station> stations = new ArrayList<Station>();
        for (Station station : first.stations) {
            if (second.stations.contains(station)) {
                stations.add(station);
            }
        }
        return stations;
    }

    private static boolean isSegmentUsable(Line line, Station origin,
                                           Station destination) {
        int originIndex = line.stations.indexOf(origin);
        int destinationIndex = line.stations.indexOf(destination);
        return originIndex >= 0 && destinationIndex >= 0
                && originIndex != destinationIndex;
    }

    private Route makeTransferRoute(Station destination, List<Line> lines,
                                    List<Station> transfers) {
        Route route = new Route();
        route.setOrigin(this);
        route.setDestination(destination);
        route.setLines(lines);
        route.setDirectLine(lines.get(0));
        route.setTransferStations(transfers);
        route.setTransfer(true);
        route.setTransferLines(lines.subList(1, lines.size()));
        Station firstTransfer = transfers.get(0);
        int originIndex = lines.get(0).stations.indexOf(this);
        int transferIndex = lines.get(0).stations.indexOf(firstTransfer);
        route.setDirection(originIndex < transferIndex ? "n" : "s");
        return route;
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

    private static <T> List<T> asList(T... values) {
        List<T> result = new ArrayList<T>();
        Collections.addAll(result, values);
        return result;
    }

    static public List<Station> getStationList() {
        List<Station> list = new ArrayList<Station>();
        for (Station station : values()) {
            if (!station.equals(Station.SPCL)) {
                list.add(station);
            }
        }
        return list;
    }

    public String toString() {
        return name;
    }

    public boolean isBetween(Station origin, Station destination, Line line) {
        int originIndex = line.stations.indexOf(origin);
        int destinationIndex = line.stations.indexOf(destination);
        int stationIndex = line.stations.indexOf(this);
        if (originIndex < 0 || destinationIndex < 0 || stationIndex < 0) {
            return false;
        }

        return Math.abs(stationIndex - originIndex) < Math.abs(destinationIndex
                - originIndex);
    }
}
