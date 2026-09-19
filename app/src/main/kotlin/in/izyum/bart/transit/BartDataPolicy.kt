package `in`.izyum.bart.transit

import `in`.izyum.bart.model.Station
import java.time.ZoneId

/**
 * BART-specific facts that are shared by feed normalization, routing, and
 * static-data loading. Display colors intentionally do not belong here.
 */
object BartDataPolicy {
    val PACIFIC_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    val DMU_TRIP_ID_RANGE: IntRange = 600..799
    const val OAKL_STOP_ID: String = "OAKL"

    object RoutingScore {
        const val TRANSFER_WEIGHT = 100
        const val EAST_BAY_TRUNK_BONUS = -200
        const val AVOIDED_STATION_PENALTY = 80
        const val BUSY_STATION_PENALTY = 3
        const val NON_PREFERRED_STATION_PENALTY = 10
        const val BAYF_FOR_LAKE_PENALTY = 4
    }

    val AVOIDED_TRANSFER_STATIONS: Set<Station> = setOf(
        Station.DALY,
        Station.DELN,
        Station.PLZA,
        Station.FTVL,
        Station.HAYW,
        Station.MCAR,
        Station.MLBR,
        Station.MLPT,
        Station.PHIL,
        Station.RICH,
        Station.SANL,
        Station.SHAY,
        Station.UCTY,
        Station.WOAK,
    )

    val BUSY_TRANSFER_STATIONS: Set<Station> = setOf(
        Station.CIVC,
        Station.EMBR,
        Station.MONT,
        Station.POWL,
    )
}
