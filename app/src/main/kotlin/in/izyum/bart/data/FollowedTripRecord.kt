package `in`.izyum.bart.data

import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.TripStop

/** Versioned JSON schema for the durable followed-trip state. */
class FollowedTripRecord constructor() {
    @JvmField var version: Int = CURRENT_VERSION
    @JvmField var origin: String? = null
    @JvmField var trainDestination: String? = null
    @JvmField var passengerDestination: String? = null
    @JvmField var line: String? = null
    @JvmField var trainDestinationColorHex: String? = null
    @JvmField var trainDestinationColorText: String? = null
    @JvmField var platform: String? = null
    @JvmField var direction: String? = null
    @JvmField var bikeAllowed = false
    @JvmField var trainLength: String? = null
    @JvmField var requiresTransfer = false
    @JvmField var transferScheduled = false
    @JvmField var limited = false
    @JvmField var canceled = false
    @JvmField var listedInETDs = true
    @JvmField var minutes = 0
    @JvmField var minEstimate = 0L
    @JvmField var maxEstimate = 0L
    @JvmField var arrivalTimeOverride = 0L
    @JvmField var estimatedTripTime = 0
    @JvmField var tripLegs: MutableList<TripLegRecord> = mutableListOf()

    fun toDeparture(): Departure {
        require(version == CURRENT_VERSION) {
            "Unsupported followed trip format version: $version"
        }
        val legs = tripLegs.map { it.toTripLeg() }
        return Departure.builder()
            .setOrigin(station(origin))
            .setTrainDestination(station(trainDestination))
            .setPassengerDestination(station(passengerDestination) ?: station(trainDestination))
            .setLine(line?.let(Line::valueOf))
            .setTrainDestinationColorHex(trainDestinationColorHex)
            .setTrainDestinationColorText(trainDestinationColorText)
            .setPlatform(platform)
            .setDirection(direction)
            .setBikeAllowed(bikeAllowed)
            .setTrainLength(trainLength)
            .setRequiresTransfer(requiresTransfer)
            .setTransferScheduled(transferScheduled)
            .setLimited(limited)
            .setCanceled(canceled)
            .setListedInETDs(listedInETDs)
            .setMinutes(minutes)
            .setMinEstimate(minEstimate)
            .setMaxEstimate(maxEstimate)
            .setArrivalTimeOverride(arrivalTimeOverride)
            .setEstimatedTripTime(estimatedTripTime)
            .setTripLegs(legs)
            .build()
    }

    companion object {
        const val CURRENT_VERSION = 1

        @JvmStatic
        fun fromDeparture(departure: Departure): FollowedTripRecord =
            FollowedTripRecord().apply {
                origin = abbreviation(departure.origin)
                trainDestination = abbreviation(departure.trainDestination)
                passengerDestination = abbreviation(departure.passengerDestination)
                line = departure.line?.name
                trainDestinationColorHex = departure.destinationColorHex
                trainDestinationColorText = departure.destinationColorText
                platform = departure.platform
                direction = departure.direction
                bikeAllowed = departure.bikeAllowed
                trainLength = departure.trainLength
                requiresTransfer = departure.requiresTransfer
                transferScheduled = departure.transferScheduled
                limited = departure.limited
                canceled = departure.canceled
                listedInETDs = departure.listedInETDs
                minutes = departure.minutes
                minEstimate = departure.minEstimate
                maxEstimate = departure.maxEstimate
                arrivalTimeOverride = departure.arrivalTimeOverride
                estimatedTripTime = departure.estimatedTripTime
                tripLegs = departure.tripLegs.map(TripLegRecord::fromTripLeg).toMutableList()
            }

        private fun abbreviation(station: Station?): String? = station?.abbreviation

        private fun station(abbreviation: String?): Station? =
            abbreviation?.let(Station::getByAbbreviation)
    }

    class TripLegRecord constructor() {
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
                stops = leg.stops.map(TripStopRecord::fromTripStop).toMutableList()
            }
        }
    }

    class TripStopRecord constructor() {
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

private fun abbreviation(station: Station?): String? = station?.abbreviation

private fun station(abbreviation: String?): Station? =
    abbreviation?.let(Station::getByAbbreviation)
