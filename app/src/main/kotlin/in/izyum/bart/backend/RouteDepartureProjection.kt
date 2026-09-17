package `in`.izyum.bart.backend

import `in`.izyum.bart.model.RealTimeDepartures
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.networktasks.GtfsRealtimeContentHandler
import `in`.izyum.bart.performance.PerformanceTrace
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
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

    fun project(snapshot: TransitFeedSnapshot): RealTimeDepartures =
        projectInternal(snapshot)

    private fun projectInternal(
        snapshot: TransitFeedSnapshot,
    ): RealTimeDepartures {
        val name = "BART route ${query.origin.abbreviation}-${query.destination?.abbreviation.orEmpty()}"
        return PerformanceTrace.section(name) {
            val network = networkSupplier.get()
            val canonical = snapshot.getCanonicalSnapshot(network)
            GtfsRealtimeContentHandler(
                query.origin,
                query.destination,
                ignoreDirection,
                network,
            ).getRealTimeDepartures(canonical).finalizeDeparturesList()
        }
    }

    fun areEquivalent(
        previous: RealTimeDepartures?,
        current: RealTimeDepartures?
    ): Boolean = previous != null && current != null
        && previous.areTransfersIncluded() == current.areTransfersIncluded()
        && previous.getDepartures() == current.getDepartures()

}
