package com.dougkeen.bart.routing;

import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork;

/** Pure validation rules for connecting two transit legs. */
public final class TransferConnectionValidator {
    private TransferConnectionValidator() {
    }

    /**
     * Checks the temporal part of a transfer. A missing timestamp is never
     * treated as a valid connection.
     */
    public static boolean canConnect(long arrivalTime, long departureTime,
                                     int minimumTransferSeconds) {
        if (arrivalTime <= 0 || departureTime <= 0
                || minimumTransferSeconds < 0) {
            return false;
        }
        return departureTime - arrivalTime
                >= minimumTransferSeconds * 1000L;
    }

    /** Checks both the feed rule and its minimum time requirement. */
    public static boolean canConnect(long arrivalTime, long departureTime,
                                     Station transferStation,
                                     Line fromLine, Line toLine,
                                     BartGtfsNetwork network) {
        if (network == null || !network.canTransfer(transferStation, fromLine,
                toLine)) {
            return false;
        }
        return canConnect(arrivalTime, departureTime,
                network.minimumTransferSeconds(transferStation, fromLine,
                        toLine));
    }
}
