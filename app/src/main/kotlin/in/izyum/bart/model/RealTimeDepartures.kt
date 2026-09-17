package `in`.izyum.bart.model

import java.util.ArrayList
import java.util.Collections

/** Immutable realtime departures for one station query. */
class RealTimeDepartures internal constructor(
    private val origin: Station?,
    private val destination: Station?,
    private val time: Long,
    unfilteredDepartures: List<Departure>,
    departures: List<Departure>,
    private val transfersIncluded: Boolean = false,
) {
    private val unfilteredDepartures = immutableList(unfilteredDepartures)
    private val departures = immutableList(departures)

    fun getDepartures(): List<Departure> = departures

    fun areTransfersIncluded(): Boolean = transfersIncluded

    fun sortDepartures(): RealTimeDepartures = copy(
        departures = departures.sortedBy { it.minutes }
    )

    fun finalizeDeparturesList(): RealTimeDepartures {
        if (destination == null) {
            return sortDepartures()
        }
        return sortDepartures()
    }

    private fun copy(
        departures: List<Departure> = this.departures,
        transfersIncluded: Boolean = this.transfersIncluded,
    ): RealTimeDepartures = RealTimeDepartures(
        origin,
        destination,
        time,
        unfilteredDepartures,
        departures,
        transfersIncluded,
    )

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    override fun toString(): String =
        "RealTimeDepartures [origin=$origin, destination=$destination, " +
            "time=$time, departures=$departures]"
}
