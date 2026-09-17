package `in`.izyum.bart.transit.normalization

import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog
import java.time.LocalDate
import java.util.Collections

/** A static GTFS stop-time without collapsing its platform identity. */
data class StaticStopObservation(
    val rawStopId: String,
    val parentStationId: String?,
    val platformCode: String?,
    val stopSequence: Int,
    val arrivalSecondsAfterServiceMidnight: Int?,
    val departureSecondsAfterServiceMidnight: Int?,
)

/** Published static service for one date, before any realtime interpretation. */
data class StaticTripObservation(
    val identity: StaticTripIdentity,
    val routeId: String,
    val serviceId: String?,
    val directionId: String?,
    val headsign: String?,
    val stops: List<StaticStopObservation>,
)

/** Maps static GTFS into records suitable for association and diagnostics. */
object StaticTransitNormalizer {
    @JvmStatic
    fun normalize(
        catalog: GtfsNetworkCatalog,
        serviceDate: LocalDate,
    ): List<StaticTripObservation> = immutableList(
        catalog.scheduledTripsFor(serviceDate).map { scheduled ->
            StaticTripObservation(
                identity = StaticTripIdentity(serviceDate, scheduled.trip.tripId),
                routeId = scheduled.trip.routeId,
                serviceId = scheduled.trip.serviceId,
                directionId = scheduled.trip.directionId,
                headsign = scheduled.trip.headsign,
                stops = immutableList(scheduled.stopTimes.map { stopTime ->
                    val stop = catalog.stopsById[stopTime.stopId]
                    StaticStopObservation(
                        rawStopId = stopTime.stopId,
                        parentStationId = stop?.parentStationId ?: stopTime.stopId,
                        platformCode = platformCode(stopTime.stopId),
                        stopSequence = stopTime.sequence,
                        arrivalSecondsAfterServiceMidnight = stopTime.arrivalSeconds,
                        departureSecondsAfterServiceMidnight = stopTime.departureSeconds,
                    )
                }),
            )
        }
    )

    private fun platformCode(stopId: String): String? =
        stopId.substringAfterLast('-', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() && it.all(Char::isDigit) }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
