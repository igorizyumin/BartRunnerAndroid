package com.dougkeen.bart.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Stable station identities and app-specific display/timing policy. */
public enum Station {
    _12TH(new Builder("12th", "12th St. Oakland City Center", "12th St Oak")),
    _16TH(new Builder("16th", "16th St. Mission", "16th St")),
    _19TH(new Builder("19th", "19th St. Oakland", "19th St Oak")),
    _24TH(new Builder("24th", "24th St. Mission", "24th St")),
    ANTC(new Builder("antc", "Antioch", "Antioch")
            .setIgnoreRoutingDirection(true)
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(719999)),
    ASHB(new Builder("ashb", "Ashby", "Ashby")),
    BALB(new Builder("balb", "Balboa Park", "Balboa")),
    BAYF(new Builder("bayf", "Bay Fair", "Bay Fair")),
    BERY(new Builder("bery", "Berryessa/North San José", "Berryessa")
            .setIgnoreRoutingDirection(true)
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(299999)),
    CAST(new Builder("cast", "Castro Valley", "Castro Vly")),
    CIVC(new Builder("civc", "Civic Center", "Civic Ctr")
            .setApiName("Civic Center/UN Plaza")),
    COLS(new Builder("cols", "Coliseum/Oakland Airport", "Coliseum/OAK")
            .setApiName("Coliseum")),
    COLM(new Builder("colm", "Colma", "Colma")),
    CONC(new Builder("conc", "Concord", "Concord")),
    DALY(new Builder("daly", "Daly City", "Daly City")),
    DBRK(new Builder("dbrk", "Downtown Berkeley", "Dtwn Berk")),
    DUBL(new Builder("dubl", "Dublin/Pleasanton", "Dbln/Plsntn")
            .setIgnoreRoutingDirection(true)
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(719999)),
    DELN(new Builder("deln", "El Cerrito del Norte", "El Cer/Norte")),
    PLZA(new Builder("plza", "El Cerrito Plaza", "El Cer/Plz")),
    EMBR(new Builder("embr", "Embarcadero", "Embarcdro")),
    FRMT(new Builder("frmt", "Fremont", "Fremont")),
    FTVL(new Builder("ftvl", "Fruitvale", "Fruitvale")),
    GLEN(new Builder("glen", "Glen Park", "Glen Park")),
    HAYW(new Builder("hayw", "Hayward", "Hayward")),
    LAFY(new Builder("lafy", "Lafayette", "Lafayette")
            .excludeFromLimitedService()),
    LAKE(new Builder("lake", "Lake Merritt", "Lk Merritt")),
    MCAR(new Builder("mcar", "MacArthur", "MacArthur")),
    MLPT(new Builder("mlpt", "Milpitas", "Milpitas")),
    MLBR(new Builder("mlbr", "Millbrae", "Millbrae")
            .setIgnoreRoutingDirection(true)
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(719999)),
    MONT(new Builder("mont", "Montgomery St.", "Montgomery")),
    NBRK(new Builder("nbrk", "North Berkeley", "N Berkeley")),
    NCON(new Builder("ncon", "North Concord/Martinez", "N Conc/Mrtnz")),
    ORIN(new Builder("orin", "Orinda", "Orinda")
            .excludeFromLimitedService()),
    PCTR(new Builder("pctr", "Pittsburg Center", "Pitt Ctr")
            .setIgnoreRoutingDirection(true)),
    PITT(new Builder("pitt", "Pittsburg/Bay Point", "Pitt/Bay Pt")
            .setIgnoreRoutingDirection(true)),
    PHIL(new Builder("phil", "Pleasant Hill", "Plsnt Hill")
            .setApiName("Pleasant Hill/Contra Costa Centre")),
    POWL(new Builder("powl", "Powell St.", "Powell")),
    RICH(new Builder("rich", "Richmond", "Richmond")
            .setIgnoreRoutingDirection(true)
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(299999)),
    ROCK(new Builder("rock", "Rockridge", "Rockridge")
            .excludeFromLimitedService()),
    SBRN(new Builder("sbrn", "San Bruno", "San Bruno")),
    SANL(new Builder("sanl", "San Leandro", "San Leandro")),
    SFIA(new Builder("sfia", "SFO Airport", "SFO")
            .setApiName("San Francisco International Airport")
            .setLongStationLinger(true)
            .setDepartureEqualityTolerance(719999)),
    SHAY(new Builder("shay", "South Hayward", "S Hayward")),
    SSAN(new Builder("ssan", "South San Francisco", "S San Fran")),
    UCTY(new Builder("ucty", "Union City", "Union City")),
    WARM(new Builder("warm", "Warm Springs/South Fremont", "Warm Springs")
            .setIgnoreRoutingDirection(true)),
    WCRK(new Builder("wcrk", "Walnut Creek", "Walnut Crk")
            .excludeFromLimitedService()),
    WDUB(new Builder("wdub", "West Dublin/Pleasanton", "W Dbln/Plsntn")),
    WOAK(new Builder("woak", "West Oakland", "W Oakland")),
    SPCL(new Builder("spcl", "Special", "Special"));

    public final String abbreviation;
    public final String name;
    public final String apiName;
    public final String shortName;
    public final boolean ignoreRoutingDirection;
    public final boolean longStationLinger;
    public final int departureEqualityTolerance;
    public final boolean includedInLimitedService;

    public static final int DEFAULT_DEPARTURE_EQUALITY_TOLERANCE = 119999;

    private static class Builder {
        private final String abbreviation;
        private final String name;
        private final String shortName;
        private boolean ignoreRoutingDirection;
        private boolean longStationLinger;
        private int departureEqualityTolerance = DEFAULT_DEPARTURE_EQUALITY_TOLERANCE;
        private boolean includedInLimitedService = true;
        private String apiName;

        Builder(String abbreviation, String name, String shortName) {
            this.abbreviation = abbreviation;
            this.name = name;
            this.shortName = shortName;
        }

        Builder setIgnoreRoutingDirection(boolean value) {
            ignoreRoutingDirection = value;
            return this;
        }

        Builder setLongStationLinger(boolean value) {
            longStationLinger = value;
            return this;
        }

        Builder setDepartureEqualityTolerance(int value) {
            departureEqualityTolerance = value;
            return this;
        }

        Builder excludeFromLimitedService() {
            includedInLimitedService = false;
            return this;
        }

        Builder setApiName(String value) {
            apiName = value;
            return this;
        }
    }

    Station(Builder builder) {
        abbreviation = builder.abbreviation;
        name = builder.name;
        apiName = builder.apiName == null
                ? builder.name.toLowerCase(Locale.ROOT)
                : builder.apiName.toLowerCase(Locale.ROOT);
        shortName = builder.shortName;
        ignoreRoutingDirection = builder.ignoreRoutingDirection;
        longStationLinger = builder.longStationLinger;
        departureEqualityTolerance = builder.departureEqualityTolerance;
        includedInLimitedService = builder.includedInLimitedService;
    }

    public static Station getByAbbreviation(String abbreviation) {
        try {
            if (abbreviation == null) {
                return null;
            }
            String value = abbreviation.toUpperCase(Locale.ROOT);
            return Station.valueOf(value.matches("\\d+|\\d+TH")
                    ? "_" + value : value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static List<Station> getStationList() {
        List<Station> result = new ArrayList<Station>();
        for (Station station : values()) {
            if (station != SPCL) {
                result.add(station);
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return name;
    }
}
