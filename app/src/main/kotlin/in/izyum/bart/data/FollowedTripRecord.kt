package `in`.izyum.bart.data

import `in`.izyum.bart.model.Itinerary
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.TripStop
import java.time.LocalDate

/** Versioned JSON schema for the durable followed-trip state. */
class FollowedTripRecord {
    @JvmField var version: Int = CURRENT_VERSION
    @JvmField var origin: String? = null
    @JvmField var trainDestination: String? = null
    @JvmField var passengerDestination: String? = null
    @JvmField var line: String? = null
    @JvmField var platform: String? = null
    @JvmField var trainLength: String? = null
    @JvmField var canceled = false
    @JvmField var minutes = 0
    @JvmField var minEstimate = 0L
    @JvmField var maxEstimate = 0L
    @JvmField var arrivalTimeOverride = 0L
    @JvmField var estimatedTripTime = 0
    @JvmField var tripLegs: MutableList<TripLegRecord> = mutableListOf()

    fun toItinerary(): Itinerary {
        require(version == CURRENT_VERSION) {
            "Unsupported followed trip format version: $version"
        }
        val itineraryOrigin = station(origin)
            ?: throw IllegalArgumentException("Followed trip has no origin")
        val itineraryDestination = station(passengerDestination ?: trainDestination)
            ?: throw IllegalArgumentException("Followed trip has no destination")
        return Itinerary(itineraryOrigin, itineraryDestination, tripLegs.map { it.toTripLeg() })
    }

    companion object {
        const val CURRENT_VERSION = 2

        @JvmStatic
        fun fromItinerary(itinerary: Itinerary): FollowedTripRecord =
            FollowedTripRecord().apply {
                origin = itinerary.origin.abbreviation
                trainDestination = itinerary.trainDestination?.abbreviation
                passengerDestination = itinerary.destination.abbreviation
                line = itinerary.line?.name
                platform = itinerary.platform
                canceled = itinerary.canceled
                minEstimate = itinerary.getInitialDepartureTime()
                maxEstimate = itinerary.getInitialDepartureTime()
                estimatedTripTime = (
                    itinerary.getEstimatedArrivalTime() - itinerary.getInitialDepartureTime()
                ).coerceAtLeast(0L).toInt()
                tripLegs = itinerary.legs.map(TripLegRecord::fromTripLeg).toMutableList()
            }

        private fun abbreviation(station: Station?): String? = station?.abbreviation

        private fun station(abbreviation: String?): Station? =
            abbreviation?.let(Station::getByAbbreviation)
    }

    class TripLegRecord {
        @JvmField var line: String? = null
        @JvmField var origin: String? = null
        @JvmField var destination: String? = null
        @JvmField var trainDestination: String? = null
        @JvmField var tripId: String? = null
        @JvmField var departureTime = 0L
        @JvmField var arrivalTime = 0L
        @JvmField var minimumTransferSecondsAfter = 0
        @JvmField var scheduledDepartureTime = 0L
        @JvmField var scheduledArrivalTime = 0L
        @JvmField var departureSource: String? = null
        @JvmField var arrivalSource: String? = null
        @JvmField var platform: String? = null
        @JvmField var serviceDate: String? = null
        @JvmField var canceled = false
        @JvmField var direction: String? = null
        @JvmField var stops: MutableList<TripStopRecord> = mutableListOf()

        fun toTripLeg(): TripLeg = TripLeg(
            line?.let(Line::valueOf),
            station(origin),
            station(destination),
            station(trainDestination),
            tripId,
            departureTime,
            arrivalTime,
            stops.map { it.toTripStop() },
            minimumTransferSecondsAfter,
            scheduledDepartureTime,
            scheduledArrivalTime,
            predictionSource(departureSource),
            predictionSource(arrivalSource),
            platform,
            serviceDate?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() },
            canceled,
            direction,
        )

        companion object {
            @JvmStatic
            fun fromTripLeg(leg: TripLeg): TripLegRecord = TripLegRecord().apply {
                line = leg.line?.name
                origin = abbreviation(leg.origin)
                destination = abbreviation(leg.destination)
                trainDestination = abbreviation(leg.trainDestination)
                tripId = leg.tripId
                departureTime = leg.departureTime
                arrivalTime = leg.arrivalTime
                minimumTransferSecondsAfter = leg.minimumTransferSecondsAfter
                scheduledDepartureTime = leg.scheduledDepartureTime
                scheduledArrivalTime = leg.scheduledArrivalTime
                departureSource = leg.departureSource.name
                arrivalSource = leg.arrivalSource.name
                platform = leg.platform
                serviceDate = leg.serviceDate?.toString()
                canceled = leg.canceled
                direction = leg.direction
                stops = leg.stops.map(TripStopRecord::fromTripStop).toMutableList()
            }
        }
    }

    class TripStopRecord {
        @JvmField var station: String? = null
        @JvmField var arrivalTime = 0L
        @JvmField var departureTime = 0L
        @JvmField var scheduledArrivalTime = 0L
        @JvmField var scheduledDepartureTime = 0L
        @JvmField var arrivalSource: String? = null
        @JvmField var departureSource: String? = null

        fun toTripStop(): TripStop = TripStop(
            station(station),
            arrivalTime,
            departureTime,
            scheduledArrivalTime,
            scheduledDepartureTime,
            predictionSource(arrivalSource),
            predictionSource(departureSource),
        )

        companion object {
            @JvmStatic
            fun fromTripStop(stop: TripStop): TripStopRecord = TripStopRecord().apply {
                station = stop.station?.abbreviation
                arrivalTime = stop.arrivalTime
                departureTime = stop.departureTime
                scheduledArrivalTime = stop.scheduledArrivalTime
                scheduledDepartureTime = stop.scheduledDepartureTime
                arrivalSource = stop.arrivalSource.name
                departureSource = stop.departureSource.name
            }
        }
    }
}

private fun predictionSource(value: String?): PredictionSource =
    value?.let { runCatching { PredictionSource.valueOf(it) }.getOrNull() }
        ?: PredictionSource.UNKNOWN

private fun station(abbreviation: String?): Station? =
    abbreviation?.let(Station::getByAbbreviation)
