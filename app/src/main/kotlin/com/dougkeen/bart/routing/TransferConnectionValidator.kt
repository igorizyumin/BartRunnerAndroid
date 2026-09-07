package com.dougkeen.bart.routing

import com.dougkeen.bart.model.Line
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork

/** Pure validation rules for connecting two transit legs. */
object TransferConnectionValidator {
    /** Checks the temporal part of a transfer. */
    @JvmStatic
    fun canConnect(
        arrivalTime: Long,
        departureTime: Long,
        minimumTransferSeconds: Int
    ): Boolean {
        if (arrivalTime <= 0 || departureTime <= 0 || minimumTransferSeconds < 0) {
            return false
        }
        return departureTime - arrivalTime >= minimumTransferSeconds * 1000L
    }

    /** Checks both the feed rule and its minimum time requirement. */
    @JvmStatic
    fun canConnect(
        arrivalTime: Long,
        departureTime: Long,
        transferStation: Station?,
        fromLine: Line?,
        toLine: Line?,
        network: BartGtfsNetwork?
    ): Boolean {
        if (network == null || !network.canTransfer(transferStation, fromLine, toLine)) {
            return false
        }
        return canConnect(
            arrivalTime,
            departureTime,
            network.minimumTransferSeconds(transferStation, fromLine, toLine)
        )
    }
}
