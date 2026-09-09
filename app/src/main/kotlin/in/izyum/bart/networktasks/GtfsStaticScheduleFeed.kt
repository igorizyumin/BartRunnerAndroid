package `in`.izyum.bart.networktasks

import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsScheduledTrip
import `in`.izyum.bart.transit.gtfs.GtfsStopTime
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.performance.PerformanceTrace
import com.google.transit.realtime.GtfsRealtime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Builds a small GTFS-RT-shaped view of scheduled trips missing from RT. */
internal object GtfsStaticScheduleFeed {
    private val PACIFIC_ZONE = ZoneId.of("America/Los_Angeles")
    private const val LOOK_AHEAD_MILLIS = 2L * 60L * 60L * 1000L
    private const val LOOK_BEHIND_MILLIS = 30L * 60L * 1000L

    private data class TimedTrip(
        val serviceDate: LocalDate,
        val trip: GtfsScheduledTrip
    )

    fun indexFor(
        network: BartGtfsNetwork,
        feedTime: Long,
        lines: Set<Line>
    ): GtfsRealtimeFeedIndex = PerformanceTrace.section("BART static schedule feed") {
        if (feedTime <= 0L) {
            return@section GtfsRealtimeFeedIndex.from(emptyFeed())
        }
        val serviceDate = Instant.ofEpochMilli(feedTime)
            .atZone(PACIFIC_ZONE)
            .toLocalDate()
        val scheduledTrips = linkedMapOf<String, TimedTrip>()
        val routeIds = lines.flatMap { network.routeIdsForLine(it) }.toSet()
        listOf(serviceDate.minusDays(1), serviceDate).forEach { date ->
            network.scheduledTripsFor(date)
                .filter { it.trip.routeId in routeIds }
                .forEach { trip ->
                if (trip.stopTimes.any { stopTime ->
                        val epoch = epochMillis(date, stopTime)
                        epoch in (feedTime - LOOK_BEHIND_MILLIS)..(feedTime + LOOK_AHEAD_MILLIS)
                    }
                ) {
                    scheduledTrips.putIfAbsent(trip.trip.tripId, TimedTrip(date, trip))
                }
            }
        }

        val entities = scheduledTrips.values.mapIndexed { index, scheduled ->
            toEntity(index, scheduled)
        }
        GtfsRealtimeFeedIndex.from(
            GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(
                    GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(feedTime / 1000L)
                )
                .addAllEntity(entities)
                .build()
        )
    }

    private fun toEntity(
        index: Int,
        timedTrip: TimedTrip
    ): GtfsRealtime.FeedEntity {
        val scheduled = timedTrip.trip
        val serviceDate = timedTrip.serviceDate
        val trip = scheduled.trip
        val update = GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(
                GtfsRealtime.TripDescriptor.newBuilder()
                    .setTripId(trip.tripId)
                    .setRouteId(trip.routeId)
                    .setScheduleRelationship(
                        GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED
                    )
            )
        scheduled.stopTimes.forEach { stopTime ->
            val updateBuilder = GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                .setStopId(stopTime.stopId)
            val arrival = stopTime.arrivalSeconds ?: stopTime.departureSeconds
            val departure = stopTime.departureSeconds ?: stopTime.arrivalSeconds
            if (arrival != null) {
                updateBuilder.setArrival(
                    GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                        .setTime(epochMillis(serviceDate, arrival) / 1000L)
                )
            }
            if (departure != null) {
                updateBuilder.setDeparture(
                    GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                        .setTime(epochMillis(serviceDate, departure) / 1000L)
                )
            }
            update.addStopTimeUpdate(updateBuilder)
        }
        return GtfsRealtime.FeedEntity.newBuilder()
            .setId("static-$index-${trip.tripId}")
            .setTripUpdate(update)
            .build()
    }

    private fun epochMillis(serviceDate: LocalDate, stopTime: GtfsStopTime): Long =
        epochMillis(serviceDate, stopTime.departureSeconds ?: stopTime.arrivalSeconds ?: 0)

    private fun epochMillis(serviceDate: LocalDate, seconds: Int): Long =
        serviceDate.atStartOfDay(PACIFIC_ZONE).toInstant().toEpochMilli() + seconds * 1000L

    private fun emptyFeed(): GtfsRealtime.FeedMessage =
        GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(
                GtfsRealtime.FeedHeader.newBuilder()
                    .setGtfsRealtimeVersion("2.0")
            )
            .build()
}
