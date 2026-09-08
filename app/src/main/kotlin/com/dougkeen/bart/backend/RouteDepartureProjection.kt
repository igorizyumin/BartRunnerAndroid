package com.dougkeen.bart.backend

import com.dougkeen.bart.model.RealTimeDepartures
import com.dougkeen.bart.model.Route
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.networktasks.GtfsRealtimeContentHandler
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex
import com.dougkeen.bart.networktasks.GtfsStaticScheduleFeed
import com.dougkeen.bart.routing.TripPlanner
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
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

    fun project(snapshot: TransitFeedSnapshot): RealTimeDepartures {
        val network = networkSupplier.get()
        val routes = resolveRoutes(query, network)
        val feedIndex = snapshot.getTripUpdateIndex()
        val feedTime = snapshot.getTripUpdatesTimestampMillis()
        val lines = routes.flatMap { route ->
            listOf(route.directLine) + route.transferLines
        }.filterNotNull().toSet()
        val staticIndex = GtfsStaticScheduleFeed.indexFor(network, feedTime, lines)
        // BART's trip-update feed is not a complete schedule: it can omit a
        // future train entirely. Keep both sources in the projection, while
        // letting a fresh live update win whenever both describe the same trip.
        val combinedIndex = GtfsRealtimeFeedIndex.merge(
            feedIndex,
            staticIndex,
            staleTripIds(feedIndex, network, feedTime)
        )
        return projectWithRouting(
            routes,
            network,
            combinedIndex,
            feedTime
        )
    }

    private fun projectWithRouting(
        routes: List<Route>,
        network: BartGtfsNetwork,
        feedIndex: GtfsRealtimeFeedIndex,
        feedTime: Long,
    ): RealTimeDepartures {
        var result = projectRoutes(routes, network, feedIndex, feedTime)

        if (result.getDepartures().isEmpty() && query.destination != null) {
            val transferRoutes = TripPlanner.preferredTransferRoutes(
                query.origin,
                query.destination,
                network
            ) + TripPlanner.lateNightSfoMillbraeRoutes(
                query.origin,
                query.destination,
                network
            )
            val transferResult = projectRoutes(
                routes + transferRoutes,
                network,
                feedIndex,
                feedTime
            )
            if (transferResult.getDepartures().isNotEmpty()) {
                result = transferResult.includeTransferRoutes()
            }
        }
        result = result.sortDepartures()
        if (result.getDepartures().isEmpty() && query.destination != null) {
            val doubleTransferRoutes = TripPlanner.doubleTransferRoutes(
                query.origin,
                query.destination,
                network
            )
            val doubleTransferResult = projectRoutes(
                routes + doubleTransferRoutes,
                network,
                feedIndex,
                feedTime
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
    ): RealTimeDepartures = GtfsRealtimeContentHandler(
        query.origin!!,
        query.destination,
        routes,
        ignoreDirection,
        network
    ).getRealTimeDepartures(feedIndex, feedTime)

    private fun staleTripIds(
        feedIndex: GtfsRealtimeFeedIndex,
        network: BartGtfsNetwork,
        feedTime: Long,
    ): Set<String> {
        if (feedTime <= 0L) {
            return emptySet()
        }
        return feedIndex.tripUpdateEntities.mapNotNull { entity ->
            if (!entity.hasTripUpdate() || !entity.tripUpdate.hasTrip()) {
                return@mapNotNull null
            }
            val trip = entity.tripUpdate.trip
            if (!trip.hasTripId() || trip.tripId.isEmpty()
                || (trip.hasScheduleRelationship()
                && trip.scheduleRelationship ==
                GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED)
            ) {
                return@mapNotNull null
            }
            val originTimes = entity.tripUpdate.stopTimeUpdateList
                .filter { network.stationForStopId(it.stopId) == query.origin }
                .mapNotNull { stopTime ->
                    when {
                        stopTime.hasDeparture() && stopTime.departure.hasTime() ->
                            stopTime.departure.time * 1000L
                        stopTime.hasArrival() && stopTime.arrival.hasTime() ->
                            stopTime.arrival.time * 1000L
                        else -> null
                    }
                }
            if (originTimes.isEmpty()
                || originTimes.minOrNull()!! < feedTime - STALE_DEPARTURE_TOLERANCE_MILLIS
            ) {
                trip.tripId
            } else {
                null
            }
        }.toSet()
    }

    private companion object {
        private const val STALE_DEPARTURE_TOLERANCE_MILLIS = 2 * 60 * 1000L

        fun resolveRoutes(query: StationPair, network: BartGtfsNetwork): List<Route> =
            TripPlanner.routesFor(query.origin, query.destination, network)
    }
}
