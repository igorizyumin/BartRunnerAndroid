package `in`.izyum.bart.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/** An origin query, optionally constrained to a destination. */
data class StationPair @JsonCreator constructor(
    @param:JsonProperty("origin") val origin: Station,
    @param:JsonProperty("destination") val destination: Station?,
)
