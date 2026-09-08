package com.dougkeen.bart.backend

import com.dougkeen.bart.model.RealTimeDepartures
import com.dougkeen.bart.model.Route
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.networktasks.GtfsRealtimeContentHandler
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex
import com.dougkeen.bart.routing.TripPlanner
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import java.util.function.Supplier

/** Builds departures for one route from the already-downloaded feed. */
class RouteDepartureProjection private constructor(
    private val query: StationPair,
    private val ignoreDirection: Boolean,
    private val networkSupplier: Supplier<BartGtfsNetwork>,
) {
    constructor(query: StationPair, networkSupplier: Supplier<BartGtfsNetwork>) :
        this(query, false, networkSupplier)

    constructor(query: StationPair, bartGtfsNetwork: BartGtfsNetwork) :
        this(query, false, Supplier { bartGtfsNetwork })

    constructor(
        query: StationPair,
        ignoreDirection: Boolean,
        bartGtfsNetwork: BartGtfsNetwork
    ) : this(query, ignoreDirection, Supplier { bartGtfsNetwork })

    init {
        require(query.origin != null) { "A route query needs an origin" }
    }

    fun project(snapshot: TransitFeedSnapshot): RealTimeDepartures {
        val network = networkSupplier.get()
        val routes = resolveRoutes(query, network)
        val handler = GtfsRealtimeContentHandler(
            query.origin!!,
            query.destination,
            routes,
            ignoreDirection,
            network
        )
        var result = handler.getRealTimeDepartures(
            snapshot.getTripUpdateIndex(),
            snapshot.getTripUpdatesTimestampMillis()
        )

        if (result.getDepartures().isEmpty() && query.destination != null) {
            result = result.includeTransferRoutes()
        }
        result = result.sortDepartures()
        if (result.getDepartures().isEmpty() && query.destination != null) {
            result = result.includeDoubleTransferRoutes()
        }
        return result.finalizeDeparturesList()
    }

    fun areEquivalent(
        previous: RealTimeDepartures?,
        current: RealTimeDepartures?
    ): Boolean = previous != null && current != null
        && previous.areTransfersIncluded() == current.areTransfersIncluded()
        && previous.getDepartures() == current.getDepartures()

    private companion object {
        fun resolveRoutes(query: StationPair, network: BartGtfsNetwork): List<Route> =
            TripPlanner.routesFor(query.origin, query.destination, network)
    }
}
