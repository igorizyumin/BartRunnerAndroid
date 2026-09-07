package com.dougkeen.bart.backend

import android.content.Context
import com.dougkeen.bart.model.TripLeg
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.networktasks.GtfsRealtimeContentHandler
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex
import com.dougkeen.bart.networktasks.GtfsStaticData
import com.dougkeen.bart.routing.TripPlanner
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import kotlin.jvm.JvmSuppressWildcards

/** Refreshes an existing itinerary from the latest complete trip feed. */
class TripProgressProjection private constructor(
    private val context: Context?,
    private val origin: Station,
    private val destination: Station,
    private val existingLegs: List<TripLeg>,
    private val bartGtfsNetwork: BartGtfsNetwork?
) : TransitProjection<@JvmSuppressWildcards List<TripLeg>> {
    constructor(
        context: Context?,
        origin: Station,
        destination: Station,
        existingLegs: List<TripLeg>?
    ) : this(context, origin, destination, existingLegs ?: emptyList(), null)

    constructor(
        origin: Station,
        destination: Station,
        existingLegs: List<TripLeg>?,
        bartGtfsNetwork: BartGtfsNetwork
    ) : this(null, origin, destination, existingLegs ?: emptyList(), bartGtfsNetwork)

    init {
        require(context != null || bartGtfsNetwork != null) {
            "A validated GTFS network or context is required"
        }
    }

    override fun project(snapshot: TransitFeedSnapshot): List<TripLeg> {
        val network = network()
        val routes = TripPlanner.routesFor(origin, destination, network)
        val handler = GtfsRealtimeContentHandler(
            origin,
            destination,
            routes,
            true,
            network
        )
        return handler.updateTripLegs(
            snapshot.getTripUpdateIndex(),
            existingLegs,
            snapshot.getTripUpdatesTimestampMillis()
        )
    }

    private fun network(): BartGtfsNetwork =
        bartGtfsNetwork ?: GtfsStaticData.get(context).getBartGtfsNetwork()
}
