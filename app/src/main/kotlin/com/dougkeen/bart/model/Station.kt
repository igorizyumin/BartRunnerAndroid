package com.dougkeen.bart.model

import java.util.ArrayList
import java.util.Collections
import java.util.Locale

private data class StationConfig(
    val abbreviation: String,
    val displayName: String,
    val shortName: String,
    val ignoreRoutingDirection: Boolean = false,
    val longStationLinger: Boolean = false,
    val departureEqualityTolerance: Int = Station.DEFAULT_DEPARTURE_EQUALITY_TOLERANCE,
    val includedInLimitedService: Boolean = true,
    val apiName: String? = null
)

private fun station(
    abbreviation: String,
    displayName: String,
    shortName: String,
    ignoreRoutingDirection: Boolean = false,
    longStationLinger: Boolean = false,
    departureEqualityTolerance: Int = Station.DEFAULT_DEPARTURE_EQUALITY_TOLERANCE,
    includedInLimitedService: Boolean = true,
    apiName: String? = null
) = StationConfig(
    abbreviation,
    displayName,
    shortName,
    ignoreRoutingDirection,
    longStationLinger,
    departureEqualityTolerance,
    includedInLimitedService,
    apiName
)

/** Stable station identities and app-specific display/timing policy. */
enum class Station(private val config: StationConfig) {
    _12TH(station("12th", "12th St. Oakland City Center", "12th St Oak")),
    _16TH(station("16th", "16th St. Mission", "16th St")),
    _19TH(station("19th", "19th St. Oakland", "19th St Oak")),
    _24TH(station("24th", "24th St. Mission", "24th St")),
    ANTC(station("antc", "Antioch", "Antioch", true, true, 719999)),
    ASHB(station("ashb", "Ashby", "Ashby")),
    BALB(station("balb", "Balboa Park", "Balboa")),
    BAYF(station("bayf", "Bay Fair", "Bay Fair")),
    BERY(station("bery", "Berryessa/North San José", "Berryessa", true, true, 299999)),
    CAST(station("cast", "Castro Valley", "Castro Vly")),
    CIVC(station("civc", "Civic Center", "Civic Ctr", apiName = "Civic Center/UN Plaza")),
    COLS(station("cols", "Coliseum/Oakland Airport", "Coliseum/OAK", apiName = "Coliseum")),
    COLM(station("colm", "Colma", "Colma")),
    CONC(station("conc", "Concord", "Concord")),
    DALY(station("daly", "Daly City", "Daly City")),
    DBRK(station("dbrk", "Downtown Berkeley", "Dtwn Berk")),
    DUBL(station("dubl", "Dublin/Pleasanton", "Dbln/Plsntn", true, true, 719999)),
    DELN(station("deln", "El Cerrito del Norte", "El Cer/Norte")),
    PLZA(station("plza", "El Cerrito Plaza", "El Cer/Plz")),
    EMBR(station("embr", "Embarcadero", "Embarcdro")),
    FRMT(station("frmt", "Fremont", "Fremont")),
    FTVL(station("ftvl", "Fruitvale", "Fruitvale")),
    GLEN(station("glen", "Glen Park", "Glen Park")),
    HAYW(station("hayw", "Hayward", "Hayward")),
    LAFY(station("lafy", "Lafayette", "Lafayette", includedInLimitedService = false)),
    LAKE(station("lake", "Lake Merritt", "Lk Merritt")),
    MCAR(station("mcar", "MacArthur", "MacArthur")),
    MLPT(station("mlpt", "Milpitas", "Milpitas")),
    MLBR(station("mlbr", "Millbrae", "Millbrae", true, true, 719999)),
    MONT(station("mont", "Montgomery St.", "Montgomery")),
    NBRK(station("nbrk", "North Berkeley", "N Berkeley")),
    NCON(station("ncon", "North Concord/Martinez", "N Conc/Mrtnz")),
    ORIN(station("orin", "Orinda", "Orinda", includedInLimitedService = false)),
    PCTR(station("pctr", "Pittsburg Center", "Pitt Ctr", true)),
    PITT(station("pitt", "Pittsburg/Bay Point", "Pitt/Bay Pt", true)),
    PHIL(station("phil", "Pleasant Hill", "Plsnt Hill", apiName = "Pleasant Hill/Contra Costa Centre")),
    POWL(station("powl", "Powell St.", "Powell")),
    RICH(station("rich", "Richmond", "Richmond", true, true, 299999)),
    ROCK(station("rock", "Rockridge", "Rockridge", includedInLimitedService = false)),
    SBRN(station("sbrn", "San Bruno", "San Bruno")),
    SANL(station("sanl", "San Leandro", "San Leandro")),
    SFIA(station("sfia", "SFO Airport", "SFO", true, true, 719999,
        apiName = "San Francisco International Airport")),
    SHAY(station("shay", "South Hayward", "S Hayward")),
    SSAN(station("ssan", "South San Francisco", "S San Fran")),
    UCTY(station("ucty", "Union City", "Union City")),
    WARM(station("warm", "Warm Springs/South Fremont", "Warm Springs", true)),
    WCRK(station("wcrk", "Walnut Creek", "Walnut Crk", includedInLimitedService = false)),
    WDUB(station("wdub", "West Dublin/Pleasanton", "W Dbln/Plsntn")),
    WOAK(station("woak", "West Oakland", "W Oakland")),
    SPCL(station("spcl", "Special", "Special"));

    @JvmField
    val abbreviation: String = config.abbreviation

    @JvmField
    val displayName: String = config.displayName

    @JvmField
    val apiName: String = (config.apiName ?: config.displayName).lowercase(Locale.ROOT)

    @JvmField
    val shortName: String = config.shortName

    @JvmField
    val ignoreRoutingDirection: Boolean = config.ignoreRoutingDirection

    @JvmField
    val longStationLinger: Boolean = config.longStationLinger

    @JvmField
    val departureEqualityTolerance: Int = config.departureEqualityTolerance

    @JvmField
    val includedInLimitedService: Boolean = config.includedInLimitedService

    fun getName(): String = displayName

    override fun toString(): String = displayName

    companion object {
        const val DEFAULT_DEPARTURE_EQUALITY_TOLERANCE = 119999

        @JvmStatic
        fun getByAbbreviation(abbreviation: String?): Station? {
            if (abbreviation == null) {
                return null
            }
            return try {
                val value = abbreviation.uppercase(Locale.ROOT)
                valueOf(if (value.matches(Regex("\\d+|\\d+TH"))) "_$value" else value)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        @JvmStatic
        fun getStationList(): List<Station> =
            Collections.unmodifiableList(
                ArrayList(values().filter { it != SPCL })
            )
    }
}
