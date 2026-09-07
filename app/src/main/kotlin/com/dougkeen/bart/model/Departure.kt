package com.dougkeen.bart.model

import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Date

/** Mutable realtime departure state updated by feed projections. */
class Departure() : Comparable<Departure> {
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
    private var selected = false
    private var tripLegs = mutableListOf<TripLeg>()

    constructor(
        destinationAbbr: String?,
        destinationColorHex: String?,
        platform: String?,
        direction: String?,
        bikeAllowed: Boolean,
        trainLength: String?,
        minutes: Int
    ) : this() {
        trainDestination = Station.getByAbbreviation(destinationAbbr)
        this.destinationColorHex = destinationColorHex
        this.platform = platform
        this.direction = direction
        this.bikeAllowed = bikeAllowed
        this.trainLength = trainLength
        this.minutes = minutes
    }

    fun getOrigin(): Station? = origin

    fun setOrigin(origin: Station?) {
        this.origin = origin
    }

    fun getTrainDestination(): Station? = trainDestination

    fun setTrainDestination(destination: Station?) {
        trainDestination = destination
    }

    fun getTrainDestinationName(): String? = trainDestination?.getName()

    fun getTrainDestinationAbbreviation(): String? = trainDestination?.abbreviation

    fun getPassengerDestination(): Station? = passengerDestination

    fun setPassengerDestination(passengerDestination: Station?) {
        this.passengerDestination = passengerDestination
    }

    fun getTripLegs(): List<TripLeg> =
        Collections.unmodifiableList(ArrayList(tripLegs))

    fun setTripLegs(tripLegs: List<TripLeg>?) {
        this.tripLegs = tripLegs?.toMutableList() ?: mutableListOf()
        if (this.tripLegs.isNotEmpty()) {
            val finalLeg = this.tripLegs.last()
            if (finalLeg.hasArrivalTime() && getMeanEstimate() > 0) {
                setEstimatedTripTime((finalLeg.arrivalTime - getMeanEstimate()).toInt())
            }
        }
    }

    fun hasTransfers(): Boolean = tripLegs.size > 1

    fun getStationPair(): StationPair? =
        passengerDestination?.let { StationPair(origin, it) }

    fun getLine(): Line? = line

    fun setLine(line: Line?) {
        this.line = line
    }

    fun getTrainDestinationColorHex(): String? = destinationColorHex

    fun setTrainDestinationColorHex(destinationColor: String?) {
        destinationColorHex = destinationColor
    }

    fun getTrainDestinationColorText(): String? = destinationColorText

    fun setTrainDestinationColorText(destinationColorText: String?) {
        this.destinationColorText = destinationColorText
    }

    fun getPlatform(): String? = platform

    fun setPlatform(platform: String?) {
        this.platform = platform
    }

    fun getDirection(): String? = direction

    fun setDirection(direction: String?) {
        this.direction = direction
    }

    fun isBikeAllowed(): Boolean = bikeAllowed

    fun setBikeAllowed(bikeAllowed: Boolean) {
        this.bikeAllowed = bikeAllowed
    }

    fun isCanceled(): Boolean = canceled

    fun setCanceled(canceled: Boolean) {
        this.canceled = canceled
    }

    fun getTrainLength(): String? = trainLength

    fun setTrainLength(trainLength: String?) {
        this.trainLength = trainLength
    }

    fun getTrainLengthText(): String =
        if (isBlank(trainLength)) "" else "$trainLength cars"

    fun getTrainLengthAndPlatform(): String {
        val result = StringBuilder()
        if (!isBlank(trainLength)) {
            result.append(trainLength).append(" cars")
        }
        if (!isBlank(platform)) {
            if (result.isNotEmpty()) {
                result.append(", ")
            }
            result.append("platform ").append(platform)
        }
        return result.toString()
    }

    fun getRequiresTransfer(): Boolean = requiresTransfer

    fun setRequiresTransfer(requiresTransfer: Boolean) {
        this.requiresTransfer = requiresTransfer
    }

    fun isTransferScheduled(): Boolean = transferScheduled

    fun setTransferScheduled(transferScheduled: Boolean) {
        this.transferScheduled = transferScheduled
    }

    fun isLimited(): Boolean = limited

    fun setLimited(limited: Boolean) {
        this.limited = limited
    }

    fun getMinutes(): Int = minutes

    fun setMinutes(minutes: Int) {
        this.minutes = minutes
        if (minutes == 0) {
            beganAsDeparted = true
        }
    }

    fun getMinEstimate(): Long = minEstimate

    fun setMinEstimate(minEstimate: Long) {
        this.minEstimate = minEstimate
    }

    fun getMaxEstimate(): Long = maxEstimate

    fun setMaxEstimate(maxEstimate: Long) {
        this.maxEstimate = maxEstimate
    }

    fun getEstimatedTripTime(): Int = estimatedTripTime

    fun setEstimatedTripTime(estimatedTripTime: Int) {
        this.estimatedTripTime = estimatedTripTime
    }

    fun hasEstimatedTripTime(): Boolean = estimatedTripTime > 0

    fun hasAnyArrivalEstimate(): Boolean = estimatedTripTime > 0 || arrivalTimeOverride > 0

    fun getUncertaintySeconds(): Int =
        ((maxEstimate - minEstimate + 1000L) / 2000L).toInt()

    fun getMinSecondsLeft(): Int =
        ((getMinEstimate() - System.currentTimeMillis()) / 1000L).toInt()

    fun getMaxSecondsLeft(): Int =
        ((getMaxEstimate() - System.currentTimeMillis()) / 1000L).toInt()

    fun getMeanSecondsLeft(): Int =
        getMeanSecondsLeft(getMinEstimate(), getMaxEstimate())

    fun getMeanSecondsLeft(min: Long, max: Long): Int =
        ((getMeanEstimate(min, max) - System.currentTimeMillis()) / 1000L).toInt()

    fun getMeanEstimate(): Long = getMeanEstimate(getMinEstimate(), getMaxEstimate())

    fun getMeanEstimate(min: Long, max: Long): Long = (min + max) / 2L

    fun getArrivalTimeOverride(): Long = arrivalTimeOverride

    fun setArrivalTimeOverride(arrivalTimeOverride: Long) {
        this.arrivalTimeOverride = arrivalTimeOverride
    }

    fun getEstimatedArrivalTime(): Long {
        if (tripLegs.isNotEmpty()) {
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

    fun getEstimatedArrivalMinutesLeft(): Long {
        val millisLeft = getEstimatedArrivalTime() - System.currentTimeMillis()
        return if (millisLeft < 0) -1 else (millisLeft + 29999L) / (60L * 1000L)
    }

    fun hasDeparted(): Boolean = getMeanSecondsLeft() <= 0

    fun beganAsDeparted(): Boolean = beganAsDeparted

    fun calculateEstimates(originalEstimateTime: Long) {
        setMinEstimate(originalEstimateTime + minutes * 60L * 1000L - 30000L)
        setMaxEstimate(getMinEstimate() + 60000L)
    }

    fun mergeEstimate(departure: Departure) {
        mergeEstimate(departure, true)
    }

    /** Merges an origin estimate, optionally retaining exact leg data. */
    fun mergeEstimate(departure: Departure, updateTripLegs: Boolean) {
        if (updateTripLegs && departure.tripLegs.isNotEmpty()) {
            setTripLegs(departure.tripLegs)
        }
        if (departure.hasDeparted() && origin?.longStationLinger == true
            && minEstimate > 0 && !beganAsDeparted
        ) {
            return
        }

        val wasDeparted = hasDeparted()
        if (!hasAnyArrivalEstimate() && departure.hasAnyArrivalEstimate()) {
            setArrivalTimeOverride(departure.arrivalTimeOverride)
            setEstimatedTripTime(departure.estimatedTripTime)
        }

        var newMin = maxOf(minEstimate, departure.minEstimate)
        var newMax = minOf(maxEstimate, departure.maxEstimate)
        if (maxEstimate - departure.minEstimate < MINIMUM_MERGE_OVERLAP_MILLIS
            || departure.maxEstimate - minEstimate < MINIMUM_MERGE_OVERLAP_MILLIS
        ) {
            newMin = departure.minEstimate
            newMax = departure.maxEstimate
        }

        if (!wasDeparted && getMeanSecondsLeft(newMin, newMax) <= 0
            && getMeanSecondsLeft() < 60 && getUncertaintySeconds() < 30
        ) {
            return
        }

        if (newMax > newMin) {
            setMinEstimate(newMin)
            setMaxEstimate(newMax)
        }
    }

    fun hasExpired(): Boolean {
        val now = System.currentTimeMillis()
        return maxEstimate < now
            && getEstimatedArrivalTime() + EXPIRE_MINUTES_AFTER_ARRIVAL * 60000L < now
    }

    override fun compareTo(other: Departure): Int =
        getMeanSecondsLeft().compareTo(other.getMeanSecondsLeft())

    override fun hashCode(): Int {
        var result = 1
        result = 31 * result + if (bikeAllowed) 1231 else 1237
        result = 31 * result + (trainDestination?.hashCode() ?: 0)
        result = 31 * result + (destinationColorHex?.hashCode() ?: 0)
        result = 31 * result + (direction?.hashCode() ?: 0)
        result = 31 * result + (line?.hashCode() ?: 0)
        result = 31 * result + (maxEstimate xor (maxEstimate ushr 32)).toInt()
        result = 31 * result + (minEstimate xor (minEstimate ushr 32)).toInt()
        result = 31 * result + minutes
        result = 31 * result + (platform?.hashCode() ?: 0)
        result = 31 * result + if (requiresTransfer) 1231 else 1237
        result = 31 * result + (trainLength?.hashCode() ?: 0)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Departure) return false
        return bikeAllowed == other.bikeAllowed
            && trainDestination == other.trainDestination
            && destinationColorHex == other.destinationColorHex
            && direction == other.direction
            && line == other.line
            && kotlin.math.abs(maxEstimate - other.maxEstimate) <= getEqualsTolerance()
            && platform == other.platform
            && requiresTransfer == other.requiresTransfer
            && trainLength == other.trainLength
    }

    private fun getEqualsTolerance(): Int =
        origin?.departureEqualityTolerance ?: Station.DEFAULT_DEPARTURE_EQUALITY_TOLERANCE

    fun getUncertaintyText(): String =
        if (hasDeparted() || isCanceled()) "" else "(±${getUncertaintySeconds()}s)"

    fun isListedInETDs(): Boolean = listedInETDs

    fun setListedInETDs(listedInETDs: Boolean) {
        this.listedInETDs = listedInETDs
    }

    fun isSelected(): Boolean = selected

    fun setSelected(selected: Boolean) {
        this.selected = selected
    }

    override fun toString(): String {
        val format = SimpleDateFormat.getTimeInstance()
        return buildString {
            append(trainDestination)
            if (requiresTransfer) append(" (w/ xfer)")
            append(", ")
            append(getDebugCountdownText())
            append(", ")
            append(format.format(Date(getMeanEstimate())))
        }
    }

    private fun getDebugCountdownText(): String {
        val secondsLeft = getMeanSecondsLeft()
        return when {
            isCanceled() -> "Canceled"
            hasDeparted() && origin?.longStationLinger == true && beganAsDeparted -> "At station"
            hasDeparted() -> if (isListedInETDs()) "Leaving" else "Departed"
            else -> "${secondsLeft / 60}m, ${secondsLeft % 60}s"
        }
    }

    companion object {
        private const val MINIMUM_MERGE_OVERLAP_MILLIS = 5000L
        private const val EXPIRE_MINUTES_AFTER_ARRIVAL = 1L

        private fun isBlank(value: String?): Boolean = value.isNullOrBlank()
    }
}
