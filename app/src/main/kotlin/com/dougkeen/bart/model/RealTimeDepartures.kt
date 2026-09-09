package com.dougkeen.bart.model

import com.dougkeen.bart.backend.Schedule
import java.util.ArrayList
import java.util.Collections

/** Immutable realtime departures for one station query. */
class RealTimeDepartures internal constructor(
    private val origin: Station?,
    private val destination: Station?,
    private val time: Long,
    routes: List<Route>,
    unfilteredDepartures: List<Departure>,
    departures: List<Departure>,
    private val schedule: Schedule,
    private val transfersIncluded: Boolean = false,
) {
    private val routes = immutableList(routes)
    private val unfilteredDepartures = immutableList(unfilteredDepartures)
    private val departures = immutableList(departures)

    init {
        requireNotNull(schedule) { "A schedule is required" }
    }

    fun getOrigin(): Station? = origin

    fun getDestination(): Station? = destination

    fun getTime(): Long = time

    fun getDepartures(): List<Departure> = departures

    fun areTransfersIncluded(): Boolean = transfersIncluded

    fun getEarliestDirectDeparture(): Departure? =
        departures.asSequence()
            .filter { !it.requiresTransfer }
            .minByOrNull { it.minutes }

    fun getEarliestTransferDeparture(): Departure? {
        val transferRoutes = schedule.preferredTransferRoutes(
            origin,
            destination,
        )
        return unfilteredDepartures.asSequence()
            .filter { findRouteForDeparture(it, transferRoutes)?.hasTransfer() == true }
            .minByOrNull { it.minutes }
    }

    fun includeTransferRoutes(): RealTimeDepartures = withAdditionalRoutes(
        schedule.preferredTransferRoutes(origin, destination)
    )

    fun includeDoubleTransferRoutes(): RealTimeDepartures = withAdditionalRoutes(
        schedule.doubleTransferRoutes(origin, destination)
    )

    fun sortDepartures(): RealTimeDepartures = copy(
        departures = departures.sortedBy { it.minutes }
    )

    fun finalizeDeparturesList(): RealTimeDepartures {
        if (destination == null) {
            return sortDepartures()
        }
        return sortDepartures()
    }

    private fun withAdditionalRoutes(additionalRoutes: List<Route>): RealTimeDepartures {
        val nextRoutes = routes + additionalRoutes
        return copy(
            routes = nextRoutes,
            departures = unfilteredDepartures.mapNotNull { departure ->
                findRouteForDeparture(departure, nextRoutes)?.let { route ->
                    departure.copy(
                        requiresTransfer = route.hasTransfer(),
                        transferScheduled = Line.YELLOW_ORANGE_SCHEDULED_TRANSFER == route.directLine,
                    )
                }
            },
            transfersIncluded = true,
        )
    }

    private fun copy(
        routes: List<Route> = this.routes,
        departures: List<Departure> = this.departures,
        transfersIncluded: Boolean = this.transfersIncluded,
    ): RealTimeDepartures = RealTimeDepartures(
        origin,
        destination,
        time,
        routes,
        unfilteredDepartures,
        departures,
        schedule,
        transfersIncluded,
    )

    private fun findRouteForDeparture(
        departure: Departure,
        routes: List<Route>,
    ): Route? {
        val trainDestination = Station.getByAbbreviation(
            departure.trainDestination?.abbreviation
        )
        val line = departure.line ?: return null
        return routes.firstOrNull { route ->
            route.trainDestinationIsApplicable(trainDestination, line)
                && (route.destination == null
                || route.destination!!.includedInLimitedService
                || !departure.limited)
        }
    }

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    override fun toString(): String =
        "RealTimeDepartures [origin=$origin, destination=$destination, " +
            "time=$time, departures=$departures]"
}
