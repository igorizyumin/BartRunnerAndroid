package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Itinerary
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import java.util.function.Supplier

/**
 * Refreshes only the followed itinerary leg that controls the departure alarm.
 * It deliberately does not validate connections or replan future legs.
 */
class FollowedItineraryAlarmProjection(
    private val networkSupplier: Supplier<BartGtfsNetwork>,
) {
    constructor(network: BartGtfsNetwork) : this(Supplier { network })

    fun project(snapshot: TransitFeedSnapshot, itinerary: Itinerary): Itinerary {
        val firstLeg = itinerary.legs.firstOrNull() ?: return itinerary
        val network = networkSupplier.get()
        val canonical = snapshot.getCanonicalSnapshot(network)
        val refreshed = ItineraryRefreshProjector(
            itinerary.origin,
            itinerary.destination,
            network,
        ).refreshLeg(canonical, firstLeg)
        if (refreshed == firstLeg) return itinerary
        return itinerary.replaceLegs(listOf(refreshed) + itinerary.legs.drop(1))
    }
}
