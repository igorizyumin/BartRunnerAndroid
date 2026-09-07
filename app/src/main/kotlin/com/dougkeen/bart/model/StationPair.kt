package com.dougkeen.bart.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/** An origin/destination pair, optionally enriched with fare statistics. */
class StationPair @JsonCreator constructor(
    @param:JsonProperty("origin") val origin: Station?,
    @param:JsonProperty("destination") val destination: Station?,
    @param:JsonProperty("fare") val fare: String?,
    @param:JsonProperty("fareLastUpdated") val fareLastUpdated: Long,
    @param:JsonProperty("averageTripLength") val averageTripLength: Int,
    @param:JsonProperty("averageTripSampleCount") val averageTripSampleCount: Int
) {
    constructor(origin: Station?, destination: Station?) : this(
        origin,
        destination,
        null,
        0L,
        0,
        0
    )

    @JsonIgnore
    fun isStationOnly(): Boolean = origin != null && destination == null

    fun withFare(fare: String?, fareLastUpdated: Long): StationPair = StationPair(
        origin,
        destination,
        fare,
        fareLastUpdated,
        averageTripLength,
        averageTripSampleCount
    )

    fun isBetweenStations(station1: Station?, station2: Station?): Boolean =
        origin != null
            && destination != null
            && ((origin == station1 && destination == station2)
            || (origin == station2 && destination == station1))

    fun fareEquals(other: StationPair?): Boolean =
        other != null
            && fare == other.fare
            && fareLastUpdated == other.fareLastUpdated

    override fun hashCode(): Int =
        31 * (31 + (destination?.hashCode() ?: 0)) + (origin?.hashCode() ?: 0)

    override fun equals(other: Any?): Boolean =
        this === other
            || (other is StationPair
            && destination == other.destination
            && origin == other.origin)

    override fun toString(): String =
        "StationPair [origin=$origin, destination=$destination]"
}
