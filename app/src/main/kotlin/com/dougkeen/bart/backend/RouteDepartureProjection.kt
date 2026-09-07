package com.dougkeen.bart.backend

import android.content.Context
import com.dougkeen.bart.model.RealTimeDepartures
import com.dougkeen.bart.model.Route
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.networktasks.GtfsRealtimeContentHandler
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex
import com.dougkeen.bart.networktasks.GtfsStaticData
import com.dougkeen.bart.routing.TripPlanner
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork

/** Builds departures for one route from the already-downloaded feed. */
class RouteDepartureProjection private constructor(
    private val query: StationPair,
    private val ignoreDirection: Boolean,
    private val context: Context?,
    private val bartGtfsNetwork: BartGtfsNetwork?
) : TransitProjection<RealTimeDepartures> {
    constructor(query: StationPair, context: Context?) : this(query, false, context, null)

    constructor(query: StationPair, bartGtfsNetwork: BartGtfsNetwork) :
        this(query, false, null, bartGtfsNetwork)

    constructor(
        query: StationPair,
        ignoreDirection: Boolean,
        bartGtfsNetwork: BartGtfsNetwork
    ) : this(query, ignoreDirection, null, bartGtfsNetwork)

    init {
        require(query.origin != null) { "A route query needs an origin" }
        require(context != null || bartGtfsNetwork != null) {
            "A validated GTFS network or context is required"
        }
    }

    override fun project(snapshot: TransitFeedSnapshot): RealTimeDepartures {
        val network = network()
        val routes = resolveRoutes(query, network)
        val handler = GtfsRealtimeContentHandler(
            query.origin!!,
            query.destination,
            routes,
            ignoreDirection,
            network
        )
        val result = handler.getRealTimeDepartures(
            snapshot.getTripUpdateIndex(),
            snapshot.getTripUpdatesTimestampMillis()
        )

        if (result.getDepartures().isEmpty() && query.destination != null) {
            result.includeTransferRoutes()
        }
        result.sortDepartures()
        if (result.getDepartures().isEmpty() && query.destination != null) {
            result.includeDoubleTransferRoutes()
        }
        result.finalizeDeparturesList()
        return result
    }

    override fun areEquivalent(
        previous: RealTimeDepartures?,
        current: RealTimeDepartures?
    ): Boolean = previous != null && current != null
        && previous.areTransfersIncluded() == current.areTransfersIncluded()
        && previous.getDepartures() == current.getDepartures()

    private fun network(): BartGtfsNetwork {
        bartGtfsNetwork?.let { return it }
        return GtfsStaticData.get(context).getBartGtfsNetwork()
    }

    private companion object {
        fun resolveRoutes(query: StationPair, network: BartGtfsNetwork): List<Route> =
            TripPlanner.routesFor(query.origin, query.destination, network)
    }
}
