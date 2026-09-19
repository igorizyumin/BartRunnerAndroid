package `in`.izyum.bart.backend

import `in`.izyum.bart.model.RealTimeDepartures
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.performance.PerformanceTrace
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import java.util.function.Supplier

/** Builds departures for one route from the already-downloaded feed. */
class RouteDepartureProjection(
    private val query: StationPair,
    private val networkSupplier: Supplier<BartGtfsNetwork>,
) {
    constructor(query: StationPair, bartGtfsNetwork: BartGtfsNetwork) :
        this(query, Supplier { bartGtfsNetwork })

    fun project(snapshot: TransitFeedSnapshot): RealTimeDepartures =
        projectInternal(snapshot)

    private fun projectInternal(
        snapshot: TransitFeedSnapshot,
    ): RealTimeDepartures {
        val name = "BART route ${query.origin.abbreviation}-${query.destination?.abbreviation.orEmpty()}"
        return PerformanceTrace.section(name) {
            val network = networkSupplier.get()
            val canonical = snapshot.getCanonicalSnapshot(network)
            DepartureProjector(
                query.origin,
                query.destination,
                network,
            ).project(canonical).sortDepartures()
        }
    }

    fun areEquivalent(
        previous: RealTimeDepartures?,
        current: RealTimeDepartures?
    ): Boolean = previous != null && current != null
        && previous.areTransfersIncluded() == current.areTransfersIncluded()
        && previous.getDepartures() == current.getDepartures()

}
