package `in`.izyum.bart.routing

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork

/** Pure validation rules for connecting two transit legs. */
object TransferConnectionValidator {
    /** Checks the temporal part of a transfer. */
    @JvmStatic
    fun canConnect(
        arrivalTime: Long,
        departureTime: Long,
        minimumTransferSeconds: Int
    ): Boolean = meetsMinimumTransferTime(
        arrivalTime, departureTime, minimumTransferSeconds
    )

    /** Returns whether the connection is feasible based on the feed rule. */
    @JvmStatic
    fun canTransfer(
        arrivalTime: Long,
        departureTime: Long,
        transferStation: Station?,
        fromLine: Line?,
        toLine: Line?,
        network: BartGtfsNetwork?
    ): Boolean {
        if (arrivalTime <= 0 || departureTime < arrivalTime) {
            return false
        }
        return network != null && network.canTransfer(transferStation, fromLine, toLine)
    }

    /** Returns whether the connection meets the feed's recommended minimum. */
    @JvmStatic
    fun meetsMinimumTransferTime(
        arrivalTime: Long,
        departureTime: Long,
        minimumTransferSeconds: Int
    ): Boolean {
        if (arrivalTime <= 0 || departureTime <= 0 || minimumTransferSeconds < 0) {
            return false
        }
        return departureTime - arrivalTime >= minimumTransferSeconds * 1000L
    }

    /** Checks whether the feed allows this transfer, ignoring its time warning. */
    @JvmStatic
    fun canConnect(
        arrivalTime: Long,
        departureTime: Long,
        transferStation: Station?,
        fromLine: Line?,
        toLine: Line?,
        network: BartGtfsNetwork?
    ): Boolean {
        return canTransfer(arrivalTime, departureTime, transferStation,
            fromLine, toLine, network)
    }
}
