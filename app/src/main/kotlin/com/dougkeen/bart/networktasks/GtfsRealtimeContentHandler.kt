package com.dougkeen.bart.networktasks

import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.Line
import com.dougkeen.bart.model.RealTimeDepartures
import com.dougkeen.bart.model.Route
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.TripLeg
import com.dougkeen.bart.model.TripStop
import com.dougkeen.bart.routing.TransferConnectionValidator
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import com.google.transit.realtime.GtfsRealtime
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Converts BART's GTFS-RT trip updates into the app's departure model. */
class GtfsRealtimeContentHandler(
    private val origin: Station,
    private val destination: Station?,
    private val routes: List<Route>,
    private val ignoreDirection: Boolean,
    private val bartGtfsNetwork: BartGtfsNetwork
) {
    init {
        requireNotNull(bartGtfsNetwork) { "A validated GTFS network is required" }
    }

    fun getRealTimeDepartures(feed: GtfsRealtime.FeedMessage): RealTimeDepartures =
        getRealTimeDepartures(GtfsRealtimeFeedIndex.from(feed), feedTime(feed))

    fun getRealTimeDepartures(
        feedIndex: GtfsRealtimeFeedIndex,
        feedTime: Long
    ): RealTimeDepartures {
        val departures = RealTimeDepartures(origin, destination, routes, bartGtfsNetwork)
        departures.setTime(feedTime)

        val trips = parseTrips(feedIndex.tripUpdateEntities, feedTime)
        trips.forEach { trip -> addTripUpdate(departures, trip, trips) }
        return departures
    }

    /**
     * Replaces the stop estimates for an already-selected itinerary using
     * the latest update for each exact train. A train may no longer include
     * the passenger's origin in the feed after it has departed, so missing
     * passed stops are deliberately retained from the previous snapshot.
     */
    fun updateTripLegs(
        feed: GtfsRealtime.FeedMessage,
        existingLegs: List<TripLeg>,
        feedTime: Long
    ): List<TripLeg> = updateTripLegs(GtfsRealtimeFeedIndex.from(feed), existingLegs, feedTime)

    fun updateTripLegs(
        feedIndex: GtfsRealtimeFeedIndex,
        existingLegs: List<TripLeg>,
        feedTime: Long
    ): List<TripLeg> {
        val trips = parseTrips(feedIndex.tripUpdateEntities, feedTime)
        val tripsById = trips
            .filter { !it.tripId.isNullOrEmpty() }
            .associateBy { it.tripId!! }

        return existingLegs.map { existing ->
            val current = tripsById[existing.tripId] ?: findMatchingTrip(existing, trips)
            if (current == null) existing else updateTripLeg(existing, current)
        }
    }

    private fun parseTrips(
        entities: List<GtfsRealtime.FeedEntity>,
        feedTime: Long
    ): List<TripSnapshot> = buildList {
        for (entity in entities) {
            if (entity.hasTripUpdate()) {
                parseTrip(entity.getTripUpdate(), feedTime)?.let(::add)
            }
        }
    }

    private fun findMatchingTrip(leg: TripLeg, trips: List<TripSnapshot>): TripSnapshot? =
        trips.firstOrNull { trip ->
            trip.line == leg.line
                && trip.trainDestination == leg.trainDestination
                && trip.canServe(leg.origin, leg.destination)
        }

    private fun updateTripLeg(existing: TripLeg, trip: TripSnapshot): TripLeg {
        val originPoint = trip.pointAt(existing.origin)
        val destinationPoint = trip.pointAt(existing.destination)
        val departureTime = originPoint?.departureTime ?: existing.departureTime
        val arrivalTime = destinationPoint?.arrivalTime ?: existing.arrivalTime
        val stops = existing.stops.map { existingStop ->
            val stop = trip.pointAt(existingStop.station)
            if (stop == null) {
                existingStop
            } else {
                TripStop(existingStop.station, stop.arrivalTime, stop.departureTime)
            }
        }
        return TripLeg(
            lineForDestination(trip.line, trip.trainDestination),
            existing.origin,
            existing.destination,
            trip.trainDestination,
            existing.tripId,
            departureTime,
            arrivalTime,
            stops
        )
    }

    private fun addTripUpdate(
        departures: RealTimeDepartures,
        trip: TripSnapshot,
        allTrips: List<TripSnapshot>
    ) {
        if (destination != null && !ignoreDirection && !origin.ignoreRoutingDirection
            && !isDirectionApplicable(trip.direction)
        ) {
            return
        }
        val originPoint = trip.pointAt(origin)
        if (originPoint == null || originPoint.departureTime <= 0) {
            return
        }
        val route = findRoute(trip) ?: return

        val departure = Departure()
        departure.setOrigin(origin)
        departure.setTrainDestination(trip.trainDestination)
        departure.setLine(lineForDestination(trip.line, trip.trainDestination))
        departure.setDirection(trip.direction)
        departure.setPlatform(trip.platform)
        departure.setLimited(false)
        departure.setCanceled(trip.canceled)
        departure.setTrainDestinationColorText(departure.getLine()!!.name)
        departure.setTrainDestinationColorHex(colorForLine(departure.getLine()!!))

        val minutes = maxOf(0L, (originPoint.departureTime - departures.getTime()) / 60000L).toInt()
        departure.setMinutes(minutes)
        departures.addDeparture(departure)
        departure.setMinEstimate(originPoint.departureTime - ESTIMATE_TOLERANCE_MILLIS)
        departure.setMaxEstimate(originPoint.departureTime + ESTIMATE_TOLERANCE_MILLIS)

        val legs = buildTripLegs(route, trip, allTrips)
        if (legs.isNotEmpty()) {
            departure.setTripLegs(legs)
            val finalLeg = legs.last()
            if (finalLeg.hasArrivalTime()) {
                departure.setEstimatedTripTime(
                    (finalLeg.arrivalTime - originPoint.departureTime).toInt()
                )
            }
        }
    }

    private fun parseTrip(
        tripUpdate: GtfsRealtime.TripUpdate,
        feedTime: Long
    ): TripSnapshot? {
        if (!tripUpdate.hasTrip()) {
            return null
        }
        val trip = tripUpdate.getTrip()
        var routeId = if (trip.hasRouteId() && trip.getRouteId().isNotEmpty()) {
            trip.getRouteId()
        } else {
            null
        }
        if (routeId.isNullOrEmpty()) {
            routeId = bartGtfsNetwork.routeIdForTrip(trip.getTripId())
        }
        val line = bartGtfsNetwork.lineForRouteId(routeId) ?: return null

        val result = TripSnapshot(
            tripId = trip.getTripId(),
            line = line,
            direction = directionForLine(line, routeId)
        )
        var updateIndex = 0
        for (update in tripUpdate.getStopTimeUpdateList()) {
            if (isSkipped(update)) {
                updateIndex++
                continue
            }
            val station = bartGtfsNetwork.stationForStopId(update.getStopId())
            val departure = departureTime(update)
            val arrival = arrivalTime(update)
            if (station != null && station != Station.SPCL && (departure > 0 || arrival > 0)) {
                val point = StopTimePoint(
                    station = station,
                    order = stopOrder(update, updateIndex),
                    departureTime = if (departure > 0) departure else arrival,
                    arrivalTime = if (arrival > 0) arrival else departure
                )
                result.points += point
                if (result.trainDestination == null || point.order > result.lastOrder) {
                    result.trainDestination = station
                    result.lastOrder = point.order
                }
                if (station == origin && result.platform == null) {
                    result.platform = platformForStopId(update.getStopId())
                }
            }
            updateIndex++
        }
        if (result.trainDestination == null) {
            return null
        }
        result.canceled = trip.hasScheduleRelationship() &&
            trip.getScheduleRelationship() ==
            GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED
        if (result.pointAt(origin) == null && result.canceled) {
            // A canceled trip can still be displayed if BART supplies its
            // start time, matching the old ETD behavior.
            val start = scheduledStartTime(trip, feedTime)
            if (start > 0) {
                result.points += StopTimePoint(
                    station = origin,
                    order = Int.MIN_VALUE,
                    departureTime = start,
                    arrivalTime = start
                )
            }
        }
        return result
    }

    private fun findRoute(trip: TripSnapshot): Route? =
        routes.firstOrNull { it.trainDestinationIsApplicable(trip.trainDestination, trip.line) }

    private fun buildTripLegs(
        route: Route,
        firstTrip: TripSnapshot,
        allTrips: List<TripSnapshot>
    ): List<TripLeg> {
        // A station-only lookup has no requested passenger destination, but
        // the selected train still gives us the endpoint to display. Treat it
        // as a single-leg trip, just like a lookup made directly to that
        // endpoint.
        val tripDestination = destination ?: firstTrip.trainDestination ?: return emptyList()
        val lines = route.lines
        if (lines.isEmpty()) {
            return emptyList()
        }
        val transfers = route.transferStations
        val result = mutableListOf<TripLeg>()
        var currentTrip = firstTrip
        var legOrigin = origin
        for (i in lines.indices) {
            val legDestination = if (i < transfers.size) transfers[i] else tripDestination
            if (i > 0) {
                val arrivingLeg = result[i - 1]
                currentTrip = findConnectingTrip(
                    lines[i],
                    legOrigin,
                    legDestination,
                    arrivingLeg,
                    allTrips
                ) ?: break
            }
            val departure = currentTrip.pointAt(legOrigin) ?: break
            val arrival = currentTrip.pointAt(legDestination)
            val stops = currentTrip.pointsBetween(legOrigin, legDestination).map {
                TripStop(it.station, it.arrivalTime, it.departureTime)
            }
            result += TripLeg(
                lineForDestination(currentTrip.line, currentTrip.trainDestination),
                legOrigin,
                legDestination,
                currentTrip.trainDestination,
                currentTrip.tripId,
                departure.departureTime,
                arrival?.arrivalTime ?: 0L,
                stops
            )
            legOrigin = legDestination
        }
        return result
    }

    private fun findConnectingTrip(
        line: Line,
        origin: Station,
        destination: Station,
        arrivingLeg: TripLeg,
        allTrips: List<TripSnapshot>
    ): TripSnapshot? {
        var best: TripSnapshot? = null
        for (trip in allTrips) {
            if (trip.line != line || !trip.canServe(origin, destination)) {
                continue
            }
            val departure = trip.pointAt(origin)
            if (departure == null || !TransferConnectionValidator.canConnect(
                    arrivingLeg.arrivalTime,
                    departure.departureTime,
                    origin,
                    arrivingLeg.line,
                    line,
                    bartGtfsNetwork
                )
            ) {
                continue
            }
            val bestDeparture = best?.pointAt(origin)?.departureTime
            if (best == null || departure.departureTime < bestDeparture!!) {
                best = trip
            }
        }
        return best
    }

    private class StopTimePoint(
        val station: Station,
        val order: Int,
        val departureTime: Long,
        val arrivalTime: Long
    )

    private class TripSnapshot(
        val tripId: String?,
        val line: Line,
        val direction: String?
    ) {
        var trainDestination: Station? = null
        var platform: String? = null
        var canceled = false
        var lastOrder = Int.MIN_VALUE
        val points = mutableListOf<StopTimePoint>()

        fun pointAt(station: Station?): StopTimePoint? =
            points.firstOrNull { it.station == station }

        fun canServe(origin: Station?, destination: Station?): Boolean {
            val start = pointAt(origin)
            val end = pointAt(destination)
            return start != null && end != null && end.order > start.order
        }

        fun pointsBetween(origin: Station?, destination: Station?): List<StopTimePoint> {
            val start = pointAt(origin)
            val end = pointAt(destination)
            if (start == null || end == null) {
                return emptyList()
            }
            return points.filter { it.order in start.order..end.order }
        }
    }

    private fun isDirectionApplicable(direction: String?): Boolean =
        direction != null && routes.any { it.direction == direction }

    private fun directionForLine(line: Line, routeId: String?): String? {
        routes.firstOrNull {
            it.directLine == line || it.transferLines.contains(line)
        }?.let { return it.direction }
        return bartGtfsNetwork.directionForRouteId(routeId)
    }

    private fun lineForDestination(line: Line, trainDestination: Station?): Line =
        if (line == Line.YELLOW && (trainDestination == Station.MLBR || origin == Station.MLBR)) {
            Line.YELLOW_LATE_NIGHT
        } else {
            line
        }

    companion object {
        private const val ESTIMATE_TOLERANCE_MILLIS = 30000L
        private val PACIFIC_TIME = TimeZone.getTimeZone("America/Los_Angeles")

        private fun feedTime(feed: GtfsRealtime.FeedMessage): Long =
            if (feed.hasHeader() && feed.getHeader().hasTimestamp()
                && feed.getHeader().getTimestamp() > 0
            ) {
                feed.getHeader().getTimestamp() * 1000L
            } else {
                System.currentTimeMillis()
            }

        private fun departureTime(update: GtfsRealtime.TripUpdate.StopTimeUpdate): Long {
            if (update.hasDeparture() && update.getDeparture().hasTime()) {
                return update.getDeparture().getTime() * 1000L
            }
            if (update.hasArrival() && update.getArrival().hasTime()) {
                return update.getArrival().getTime() * 1000L
            }
            return 0L
        }

        private fun arrivalTime(update: GtfsRealtime.TripUpdate.StopTimeUpdate): Long {
            if (update.hasArrival() && update.getArrival().hasTime()) {
                return update.getArrival().getTime() * 1000L
            }
            if (update.hasDeparture() && update.getDeparture().hasTime()) {
                return update.getDeparture().getTime() * 1000L
            }
            return 0L
        }

        private fun scheduledStartTime(
            trip: GtfsRealtime.TripDescriptor,
            feedTime: Long
        ): Long {
            if (!trip.hasStartTime()) {
                return 0L
            }
            val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                timeZone = PACIFIC_TIME
            }
            val startDate = if (trip.hasStartDate()) {
                trip.getStartDate()
            } else {
                dateFormat.format(Date(feedTime))
            }
            val timeParts = trip.getStartTime().split(":")
            if (timeParts.size != 3) {
                return 0L
            }
            return try {
                val hour = timeParts[0].toInt()
                val minute = timeParts[1].toInt()
                val second = timeParts[2].toInt()
                val parsedDate = dateFormat.parse(startDate) ?: return 0L
                val date = Calendar.getInstance(PACIFIC_TIME, Locale.US).apply {
                    clear()
                    time = parsedDate
                    add(Calendar.HOUR_OF_DAY, hour)
                    add(Calendar.MINUTE, minute)
                    add(Calendar.SECOND, second)
                }
                date.timeInMillis
            } catch (_: NumberFormatException) {
                0L
            } catch (_: ParseException) {
                0L
            }
        }

        private fun isSkipped(update: GtfsRealtime.TripUpdate.StopTimeUpdate): Boolean =
            update.hasScheduleRelationship() &&
                update.getScheduleRelationship() ==
                GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED

        private fun stopOrder(
            update: GtfsRealtime.TripUpdate.StopTimeUpdate,
            listIndex: Int
        ): Int {
            // BART currently omits stop_sequence from its GTFS-RT feed. The feed
            // order is still the trip order, so use it when no sequence exists.
            return if (update.hasStopSequence()) update.getStopSequence() else listIndex
        }

        private fun platformForStopId(stopId: String?): String? {
            if (stopId == null) {
                return null
            }
            val separator = stopId.lastIndexOf('-')
            return if (separator >= 0 && separator + 1 < stopId.length) {
                stopId.substring(separator + 1)
            } else {
                null
            }
        }

        private fun colorForLine(line: Line): String = when (line) {
            Line.RED -> "#ffff0000"
            Line.ORANGE -> "#ffff9933"
            Line.YELLOW, Line.YELLOW_LATE_NIGHT -> "#ffffff33"
            Line.GREEN -> "#ff339933"
            Line.BLUE -> "#ff0099cc"
            else -> "#ffffffff"
        }
    }
}
