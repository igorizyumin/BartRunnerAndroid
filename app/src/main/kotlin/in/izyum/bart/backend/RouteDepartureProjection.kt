package `in`.izyum.bart.backend

import `in`.izyum.bart.model.RealTimeDepartures
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.networktasks.GtfsRealtimeContentHandler
import `in`.izyum.bart.networktasks.GtfsRealtimeFeedIndex
import `in`.izyum.bart.performance.PerformanceTrace
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import com.google.transit.realtime.GtfsRealtime
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

    fun project(snapshot: TransitFeedSnapshot): RealTimeDepartures =
        project(snapshot, emptySet())

    /** Projects while excluding trips corroborated as unavailable by ETD. */
    fun project(
        snapshot: TransitFeedSnapshot,
        excludedTripIds: Set<String>,
    ): RealTimeDepartures = project(snapshot, excludedTripIds, emptyMap())

    /** Projects with operational departure times for ETD-correlated trips. */
    fun project(
        snapshot: TransitFeedSnapshot,
        excludedTripIds: Set<String>,
        departureOverrides: Map<Pair<String, Station>, Long>,
    ): RealTimeDepartures {
        val name = "BART route ${query.origin?.abbreviation.orEmpty()}-${query.destination?.abbreviation.orEmpty()}"
        return PerformanceTrace.section(name) {
            val network = networkSupplier.get()
            val feedIndex = snapshot.getTripUpdateIndex()
            val feedTime = snapshot.getTripUpdatesTimestampMillis()
            // A later fallback may add transfer routes whose lines are not present
            // in the first route set. Build one complete time-scoped graph so the
            // fallback cannot accidentally lose its static connecting trains.
            val schedule = snapshot.getCorrectedSchedule(network)
                .applyDepartureOverrides(departureOverrides)
            val routes = schedule.routesFor(query.origin, query.destination)
            projectWithRouting(
                routes,
                network,
                feedIndex,
                feedTime,
                schedule,
                excludedTripIds,
            )
        }
    }

    private fun projectWithRouting(
        routes: List<Route>,
        network: BartGtfsNetwork,
        feedIndex: GtfsRealtimeFeedIndex,
        feedTime: Long,
        schedule: Schedule,
        excludedTripIds: Set<String>,
    ): RealTimeDepartures {
        var result = projectRoutes(
            routes, network, feedIndex, feedTime, schedule, excludedTripIds
        )

        if (result.getDepartures().isEmpty() && query.destination != null) {
            val lateNightRoutes = if (schedule.isLateNightSfoMillbraeService()) {
                schedule.lateNightSfoMillbraeRoutes(
                    query.origin,
                    query.destination,
                )
            } else {
                emptyList()
            }
            val transferRoutes = schedule.preferredTransferRoutes(
                query.origin,
                query.destination
            ) + lateNightRoutes
            val transferResult = projectRoutes(
                routes + transferRoutes,
                network,
                feedIndex,
                feedTime,
                schedule,
                excludedTripIds,
            )
            if (transferResult.getDepartures().isNotEmpty()) {
                result = transferResult.includeTransferRoutes()
            }
        }
        result = result.sortDepartures()
        if (result.getDepartures().isEmpty() && query.destination != null) {
            val doubleTransferRoutes = schedule.doubleTransferRoutes(
                query.origin,
                query.destination
            )
            val doubleTransferResult = projectRoutes(
                routes + doubleTransferRoutes,
                network,
                feedIndex,
                feedTime,
                schedule,
                excludedTripIds,
            )
            if (doubleTransferResult.getDepartures().isNotEmpty()) {
                result = doubleTransferResult.includeDoubleTransferRoutes()
            }
        }
        return result.finalizeDeparturesList()
    }

    fun areEquivalent(
        previous: RealTimeDepartures?,
        current: RealTimeDepartures?
    ): Boolean = previous != null && current != null
        && previous.areTransfersIncluded() == current.areTransfersIncluded()
        && previous.getDepartures() == current.getDepartures()

    private fun projectRoutes(
        routes: List<Route>,
        network: BartGtfsNetwork,
        feedIndex: GtfsRealtimeFeedIndex,
        feedTime: Long,
        schedule: Schedule,
        excludedTripIds: Set<String>,
    ): RealTimeDepartures = GtfsRealtimeContentHandler(
        query.origin!!,
        query.destination,
        routes,
        ignoreDirection,
        network
    ).getRealTimeDepartures(feedIndex, feedTime, schedule, excludedTripIds)

}
