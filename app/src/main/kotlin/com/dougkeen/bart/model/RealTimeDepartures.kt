package com.dougkeen.bart.model

import com.dougkeen.bart.routing.TripPlanner
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import java.util.ArrayList
import java.util.Collections
import java.util.Locale

/** Realtime departures for one station query and its currently enabled routes. */
class RealTimeDepartures(
    private val origin: Station?,
    private val destination: Station?,
    routes: List<Route>,
    private val bartGtfsNetwork: BartGtfsNetwork
) {
    private var time = 0L
    private var transfersIncluded = false
    private var departures: MutableList<Departure>? = null
    private val unfilteredDepartures = mutableListOf<Departure>()
    private var routes: MutableList<Route> = routes.toMutableList()

    init {
        requireNotNull(bartGtfsNetwork) { "A validated GTFS network is required" }
    }

    fun getOrigin(): Station? = origin

    fun getDestination(): Station? = destination

    fun getTime(): Long = time

    fun setTime(time: Long) {
        this.time = time
    }

    fun getDepartures(): MutableList<Departure> {
        if (departures == null) {
            departures = mutableListOf()
        }
        return departures!!
    }

    fun setDepartures(departures: List<Departure>?) {
        this.departures = departures?.toMutableList() ?: mutableListOf()
    }

    fun areTransfersIncluded(): Boolean = transfersIncluded

    fun getEarliestDirectDeparture(): Departure? =
        getDepartures()
            .asSequence()
            .filter { !it.getRequiresTransfer() }
            .minByOrNull { it.getMinutes() }

    fun getEarliestTransferDeparture(): Departure? {
        val transferRoutes = TripPlanner.preferredTransferRoutes(
            origin,
            destination,
            bartGtfsNetwork
        )
        return unfilteredDepartures
            .asSequence()
            .filter { findRouteForDeparture(it, transferRoutes)?.hasTransfer() == true }
            .minByOrNull { it.getMinutes() }
    }

    fun includeTransferRoutes() {
        transfersIncluded = true
        routes += TripPlanner.preferredTransferRoutes(origin, destination, bartGtfsNetwork)
        rebuildFilteredDeparturesCollection()
    }

    fun includeDoubleTransferRoutes() {
        transfersIncluded = true
        routes += TripPlanner.doubleTransferRoutes(origin, destination, bartGtfsNetwork)
        rebuildFilteredDeparturesCollection()
    }

    /** Filters the already-indexed feed departures by direction. */
    fun filterByDirection(direction: String?) {
        if (direction.isNullOrEmpty()) {
            return
        }
        val normalizedDirection = direction.lowercase(Locale.ROOT)
        unfilteredDepartures.removeAll {
            !it.getDirection().orEmpty().lowercase(Locale.ROOT)
                .startsWith(normalizedDirection)
        }
        rebuildFilteredDeparturesCollection()
    }

    private fun rebuildFilteredDeparturesCollection() {
        getDepartures().clear()
        unfilteredDepartures.forEach(::addDepartureIfApplicable)
    }

    fun addDeparture(departure: Departure) {
        unfilteredDepartures += departure
        addDepartureIfApplicable(departure)
    }

    private fun addDepartureIfApplicable(departure: Departure) {
        val route = findRouteForDeparture(departure, routes) ?: return
        departure.setRequiresTransfer(route.hasTransfer())
        departure.setTransferScheduled(
            Line.YELLOW_ORANGE_SCHEDULED_TRANSFER == route.directLine
        )
        getDepartures() += departure
        departure.calculateEstimates(time)
    }

    private fun findRouteForDeparture(
        departure: Departure,
        routes: List<Route>
    ): Route? {
        val destination = Station.getByAbbreviation(
            departure.getTrainDestinationAbbreviation()
        )
        val line = departure.getLine() ?: return null
        return routes.firstOrNull { route ->
            route.trainDestinationIsApplicable(destination, line)
                && (route.destination == null
                || route.destination!!.includedInLimitedService
                || !departure.isLimited())
        }
    }

    fun sortDepartures() {
        getDepartures().sortBy { it.getMinutes() }
    }

    fun finalizeDeparturesList() {
        if (destination == null) {
            sortDepartures()
            return
        }
        val hasDirectRoute = getDepartures().any { !it.getRequiresTransfer() }
        if (hasDirectRoute) {
            getDepartures().removeAll { departure ->
                departure.getRequiresTransfer()
                    && (!departure.isTransferScheduled()
                    || (departure.getTrainDestination() != null
                    && bartGtfsNetwork.isBetween(
                    departure.getTrainDestination(),
                    origin,
                    destination,
                    departure.getLine()
                )))
            }
        }
        sortDepartures()
    }

    fun getRoutes(): List<Route> = Collections.unmodifiableList(ArrayList(routes))

    fun setRoutes(routes: List<Route>?) {
        this.routes = routes?.toMutableList() ?: mutableListOf()
    }

    override fun toString(): String =
        "RealTimeDepartures [origin=$origin, destination=$destination, " +
            "time=$time, departures=${getDepartures()}]"
}
