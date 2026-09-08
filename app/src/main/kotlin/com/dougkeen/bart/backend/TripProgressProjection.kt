package com.dougkeen.bart.backend

import com.dougkeen.bart.model.TripLeg
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.Line
import com.dougkeen.bart.networktasks.GtfsRealtimeContentHandler
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import java.util.function.Supplier

/** Refreshes an existing itinerary from the latest complete trip feed. */
class TripProgressProjection(
        origin: Station,
        destination: Station,
        existingLegs: List<TripLeg>,
        private val networkSupplier: Supplier<BartGtfsNetwork>,
    ) {
    private val origin = origin
    private val destination = destination
    private val existingLegs = existingLegs.toList()

    constructor(
        origin: Station,
        destination: Station,
        existingLegs: List<TripLeg>?,
        bartGtfsNetwork: BartGtfsNetwork
    ) : this(origin, destination, existingLegs ?: emptyList(), Supplier { bartGtfsNetwork })

    fun project(snapshot: TransitFeedSnapshot): List<TripLeg> {
        val network = networkSupplier.get()
        val schedule = Schedule.fromStatic(
            network,
            snapshot.getTripUpdatesTimestampMillis(),
            Line.values().toSet(),
        ).applyRealtime(snapshot.getTripUpdateIndex())
        val routes = schedule.routesFor(origin, destination)
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
            snapshot.getTripUpdatesTimestampMillis(),
            schedule,
        )
    }

}
