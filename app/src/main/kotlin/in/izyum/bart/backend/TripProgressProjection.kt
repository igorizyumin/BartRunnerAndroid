package `in`.izyum.bart.backend

import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.Itinerary
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
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
    private var currentItinerary = Itinerary(origin, destination, this.existingLegs)

    constructor(
        origin: Station,
        destination: Station,
        existingLegs: List<TripLeg>?,
        bartGtfsNetwork: BartGtfsNetwork
    ) : this(origin, destination, existingLegs ?: emptyList(), Supplier { bartGtfsNetwork })

    fun project(snapshot: TransitFeedSnapshot): List<TripLeg> {
        return projectItinerary(snapshot).legs
    }

    fun projectItinerary(snapshot: TransitFeedSnapshot): Itinerary {
        val network = networkSupplier.get()
        val canonical = snapshot.getCanonicalSnapshot(network)
        val refreshed = ItineraryRefreshProjector(
            origin,
            destination,
            network
        ).project(canonical, currentItinerary)
        currentItinerary = refreshed
        return refreshed
    }

}
