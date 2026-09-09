package `in`.izyum.bart.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/** A favorite origin/destination pair. */
class StationPair @JsonCreator constructor(
    @param:JsonProperty("origin") val origin: Station?,
    @param:JsonProperty("destination") val destination: Station?,
) {
    @JsonIgnore
    fun isStationOnly(): Boolean = origin != null && destination == null

    fun isBetweenStations(station1: Station?, station2: Station?): Boolean =
        origin != null
            && destination != null
            && ((origin == station1 && destination == station2)
            || (origin == station2 && destination == station1))

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
