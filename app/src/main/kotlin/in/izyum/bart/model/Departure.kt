package `in`.izyum.bart.model

import java.util.ArrayList
import java.util.Collections

/** Immutable domain value for one scheduled or realtime departure. */
data class Departure(
    val origin: Station?,
    val trainDestination: Station?,
    val passengerDestination: Station?,
    val line: Line?,
    val destinationColorHex: String?,
    val destinationColorText: String?,
    val platform: String?,
    val direction: String?,
    val bikeAllowed: Boolean,
    val trainLength: String?,
    val requiresTransfer: Boolean,
    val transferScheduled: Boolean,
    val limited: Boolean,
    val canceled: Boolean,
    val minutes: Int,
    val minEstimate: Long,
    val maxEstimate: Long,
    val estimatedTripTime: Int,
    val beganAsDeparted: Boolean,
    val arrivalTimeOverride: Long,
    val listedInETDs: Boolean,
    val tripLegs: List<TripLeg>,
) : Comparable<Departure> {

    /** Stable identity used to match a departure across feed refreshes. */
    val identity: String
        get() = buildString {
            append(line).append('|')
                .append(trainDestination?.abbreviation).append('|')
                .append(direction).append('|')
                .append(platform).append('|')
            if (tripLegs.isEmpty()) {
                append("no-legs")
            } else {
                tripLegs.forEach { append(it.tripId).append(';') }
            }
        }

    fun getTrainDestinationName(): String? = trainDestination?.getName()

    fun getTrainDestinationColorHex(): String? = destinationColorHex

    fun getTrainDestinationColorText(): String? = destinationColorText

    fun isBikeAllowed(): Boolean = bikeAllowed

    fun isLimited(): Boolean = limited

    fun isCanceled(): Boolean = canceled

    fun getTrainDestinationAbbreviation(): String? = trainDestination?.abbreviation

    fun getStationPair(): StationPair? =
        passengerDestination?.let { StationPair(origin, it) }

    fun hasTransfers(): Boolean = tripLegs.size > 1

    fun isTransferScheduled(): Boolean = transferScheduled

    fun isListedInETDs(): Boolean = listedInETDs

    fun beganAsDeparted(): Boolean = beganAsDeparted

    fun hasEstimatedTripTime(): Boolean = estimatedTripTime > 0

    fun hasAnyArrivalEstimate(): Boolean =
        (tripLegs.isNotEmpty() && hasCompleteTripLegs() && tripLegs.last().hasArrivalTime())
            || estimatedTripTime > 0
            || arrivalTimeOverride > 0

    fun getUncertaintySeconds(): Int =
        ((maxEstimate - minEstimate + 1000L) / 2000L).toInt()

    fun getMinSecondsLeft(nowMillis: Long): Int =
        ((minEstimate - nowMillis) / 1000L).toInt()

    fun getMaxSecondsLeft(nowMillis: Long): Int =
        ((maxEstimate - nowMillis) / 1000L).toInt()

    fun getMinSecondsLeft(timeSource: TimeSource): Int =
        getMinSecondsLeft(timeSource.nowMillis())

    fun getMaxSecondsLeft(timeSource: TimeSource): Int =
        getMaxSecondsLeft(timeSource.nowMillis())

    fun getMeanSecondsLeft(timeSource: TimeSource): Int =
        getMeanSecondsLeft(timeSource.nowMillis())

    fun getMeanSecondsLeft(nowMillis: Long): Int =
        getMeanSecondsLeft(minEstimate, maxEstimate, nowMillis)

    fun getMeanSecondsLeft(min: Long, max: Long, nowMillis: Long): Int =
        ((getMeanEstimate(min, max) - nowMillis) / 1000L).toInt()

    fun getMeanEstimate(): Long = getMeanEstimate(minEstimate, maxEstimate)

    fun getMeanEstimate(min: Long, max: Long): Long = (min + max) / 2L

    fun getEstimatedArrivalTime(): Long {
        if (tripLegs.isNotEmpty() && hasCompleteTripLegs()) {
            val finalLeg = tripLegs.last()
            if (finalLeg.hasArrivalTime()) {
                return finalLeg.arrivalTime
            }
        }
        if (arrivalTimeOverride > 0) {
            return arrivalTimeOverride
        }
        return getMeanEstimate() + estimatedTripTime
    }

    fun getEstimatedArrivalMinutesLeft(timeSource: TimeSource): Long =
        getEstimatedArrivalMinutesLeft(timeSource.nowMillis())

    fun getEstimatedArrivalMinutesLeft(nowMillis: Long): Long {
        val millisLeft = getEstimatedArrivalTime() - nowMillis
        return if (millisLeft < 0) -1 else (millisLeft + 29999L) / 60_000L
    }

    fun hasDeparted(timeSource: TimeSource): Boolean =
        hasDeparted(timeSource.nowMillis())

    fun hasDeparted(nowMillis: Long): Boolean =
        getMeanSecondsLeft(minEstimate, maxEstimate, nowMillis) <= 0

    fun hasExpired(timeSource: TimeSource): Boolean =
        hasExpired(timeSource.nowMillis())

    fun hasExpired(nowMillis: Long): Boolean =
        hasAnyArrivalEstimate()
            && maxEstimate < nowMillis
            && getEstimatedArrivalTime() + EXPIRE_MINUTES_AFTER_ARRIVAL * 60_000L < nowMillis

    fun withPassengerDestination(destination: Station?): Departure {
        val nextTripTime = if (!hasCompleteTripLegs(destination) && tripLegs.isNotEmpty()) {
            0
        } else {
            estimatedTripTime
        }
        return copy(
            passengerDestination = destination,
            estimatedTripTime = nextTripTime,
        )
    }

    fun withTripLegs(legs: List<TripLeg>): Departure {
        return withImmutableTripLegs(immutableList(legs))
    }

    private fun withImmutableTripLegs(legs: List<TripLeg>): Departure {
        val withLegs = copy(tripLegs = legs)
        return if (legs.isNotEmpty() && withLegs.hasCompleteTripLegs()) {
            val finalLeg = legs.last()
            if (finalLeg.hasArrivalTime() && withLegs.getMeanEstimate() > 0) {
                withLegs.copy(
                    estimatedTripTime =
                    (finalLeg.arrivalTime - withLegs.getMeanEstimate()).toInt(),
                )
            } else {
                withLegs
            }
        } else if (legs.isNotEmpty()) {
            withLegs.copy(estimatedTripTime = 0)
        } else {
            withLegs
        }
    }

    fun replaceTripLegs(legs: List<TripLeg>): Departure = withTripLegs(legs)

    fun calculateEstimates(originalEstimateTime: Long): Departure = copy(
        minEstimate = originalEstimateTime + minutes * 60_000L - 30_000L,
        maxEstimate = originalEstimateTime + minutes * 60_000L + 30_000L,
    )

    fun mergeEstimate(departure: Departure, timeSource: TimeSource): Departure =
        merge(this, departure, updateTripLegs = true, timeSource)

    fun mergeEstimate(
        departure: Departure,
        updateTripLegs: Boolean,
        timeSource: TimeSource,
    ): Departure = merge(this, departure, updateTripLegs, timeSource)

    override fun compareTo(other: Departure): Int =
        getMeanEstimate().compareTo(other.getMeanEstimate())

    override fun toString(): String {
        return buildString {
            append(trainDestination)
            if (requiresTransfer) append(" (w/ xfer)")
            append(", ")
            append("estimate=").append(getMeanEstimate())
        }
    }

    private fun hasCompleteTripLegs(destination: Station? = passengerDestination): Boolean =
        destination == null
            || tripLegs.isEmpty()
            || tripLegs.last().destination == destination

    companion object {
        private const val MINIMUM_MERGE_OVERLAP_MILLIS = 5000L
        private const val EXPIRE_MINUTES_AFTER_ARRIVAL = 1L

        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        fun merge(
            previous: Departure,
            incoming: Departure,
            updateTripLegs: Boolean,
            timeSource: TimeSource,
        ): Departure {
            val now = timeSource.nowMillis()
            val tripLegs = if (updateTripLegs && incoming.tripLegs.isNotEmpty()) {
                incoming.tripLegs
            } else {
                previous.tripLegs
            }

            var merged = previous.copy(tripLegs = immutableList(tripLegs))
            if (incoming.hasDeparted(now)
                && previous.origin?.longStationLinger == true
                && previous.minEstimate > 0
                && !previous.beganAsDeparted
            ) {
                return merged
            }

            val wasDeparted = previous.hasDeparted(now)
            if (!previous.hasAnyArrivalEstimate() && incoming.hasAnyArrivalEstimate()) {
                merged = merged.copy(
                    arrivalTimeOverride = incoming.arrivalTimeOverride,
                    estimatedTripTime = incoming.estimatedTripTime,
                )
            }

            var newMin = maxOf(previous.minEstimate, incoming.minEstimate)
            var newMax = minOf(previous.maxEstimate, incoming.maxEstimate)
            if (previous.maxEstimate - incoming.minEstimate < MINIMUM_MERGE_OVERLAP_MILLIS
                || incoming.maxEstimate - previous.minEstimate < MINIMUM_MERGE_OVERLAP_MILLIS
            ) {
                newMin = incoming.minEstimate
                newMax = incoming.maxEstimate
            }

            if (!wasDeparted
                && ((newMin + newMax) / 2L - now) / 1000L <= 0
                && previous.getMeanSecondsLeft(now) < 60
                && previous.getUncertaintySeconds() < 30
            ) {
                return merged
            }

            return if (newMax > newMin) {
                merged.copy(minEstimate = newMin, maxEstimate = newMax)
            } else {
                merged
            }
        }

        @JvmStatic
        fun replaceFeed(
            previous: List<Departure>,
            incoming: List<Departure>,
            timeSource: TimeSource,
        ): List<Departure> {
            val previousByIdentity = previous.associateBy { it.identity }
            return incoming.map { departure ->
                previousByIdentity[departure.identity]?.let {
                    merge(it, departure, updateTripLegs = true, timeSource)
                } ?: departure
            }
        }

        private fun immutableList(values: Collection<TripLeg>): List<TripLeg> =
            Collections.unmodifiableList(ArrayList(values))
    }

    /** Java/framework construction boundary; all built values are immutable. */
    class Builder {
        private var origin: Station? = null
        private var trainDestination: Station? = null
        private var passengerDestination: Station? = null
        private var line: Line? = null
        private var destinationColorHex: String? = null
        private var destinationColorText: String? = null
        private var platform: String? = null
        private var direction: String? = null
        private var bikeAllowed = false
        private var trainLength: String? = null
        private var requiresTransfer = false
        private var transferScheduled = false
        private var limited = false
        private var canceled = false
        private var minutes = 0
        private var minEstimate = 0L
        private var maxEstimate = 0L
        private var estimatedTripTime = 0
        private var beganAsDeparted = false
        private var arrivalTimeOverride = 0L
        private var listedInETDs = true
        private var tripLegs: List<TripLeg> = emptyList()

        fun setOrigin(value: Station?) = apply { origin = value }
        fun setTrainDestination(value: Station?) = apply { trainDestination = value }
        fun setPassengerDestination(value: Station?) = apply { passengerDestination = value }
        fun setLine(value: Line?) = apply { line = value }
        fun setTrainDestinationColorHex(value: String?) = apply { destinationColorHex = value }
        fun setTrainDestinationColorText(value: String?) = apply { destinationColorText = value }
        fun setPlatform(value: String?) = apply { platform = value }
        fun setDirection(value: String?) = apply { direction = value }
        fun setBikeAllowed(value: Boolean) = apply { bikeAllowed = value }
        fun setTrainLength(value: String?) = apply { trainLength = value }
        fun setRequiresTransfer(value: Boolean) = apply { requiresTransfer = value }
        fun setTransferScheduled(value: Boolean) = apply { transferScheduled = value }
        fun setLimited(value: Boolean) = apply { limited = value }
        fun setCanceled(value: Boolean) = apply { canceled = value }
        fun setMinutes(value: Int) = apply {
            minutes = value
            beganAsDeparted = value == 0
        }
        fun setMinEstimate(value: Long) = apply { minEstimate = value }
        fun setMaxEstimate(value: Long) = apply { maxEstimate = value }
        fun setEstimatedTripTime(value: Int) = apply { estimatedTripTime = value }
        fun setArrivalTimeOverride(value: Long) = apply { arrivalTimeOverride = value }
        fun setListedInETDs(value: Boolean) = apply { listedInETDs = value }
        fun setTripLegs(value: List<TripLeg>?) = apply { tripLegs = value ?: emptyList() }

        fun build(): Departure {
            val immutableLegs = immutableList(tripLegs)
            val departure = Departure(
                origin,
                trainDestination,
                passengerDestination,
                line,
                destinationColorHex,
                destinationColorText,
                platform,
                direction,
                bikeAllowed,
                trainLength,
                requiresTransfer,
                transferScheduled,
                limited,
                canceled,
                minutes,
                minEstimate,
                maxEstimate,
                estimatedTripTime,
                beganAsDeparted,
                arrivalTimeOverride,
                listedInETDs,
                immutableLegs,
            )
            return departure.withImmutableTripLegs(immutableLegs)
        }
    }
}
