package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Itinerary
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.TripStop
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog
import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItineraryRefreshProjectorTest {
    @Test
    fun typedItineraryOwnsTheReplannedLegs() {
        val fixture = fixture()
        val schedule = schedule(fixture)
        val itinerary = Itinerary(
            Station.RICH,
            Station.SFIA,
            itinerary(schedule, "old-yellow", "old-blue"),
        )
        val canonical = TransitFeedSnapshot(
            feedWithCancellation(fixture.feedTime, "old-yellow"),
            emptyFeed(fixture.feedTime),
            fixture.feedTime,
        ).getCanonicalSnapshot(fixture.network)

        val refreshed = ItineraryRefreshProjector(
            Station.RICH,
            Station.SFIA,
            fixture.network,
        ).project(canonical, itinerary)

        assertEquals(Station.RICH, refreshed.origin)
        assertEquals(Station.SFIA, refreshed.destination)
        assertItinerary(refreshed.legs, "alt-yellow", "alt-blue")
    }

    @Test
    fun feasibleItineraryRemainsUnchanged() {
        val fixture = fixture()
        val schedule = schedule(fixture)
        val existing = itinerary(schedule, "old-yellow", "old-blue")

        val refreshed = project(fixture, emptyFeed(fixture.feedTime), existing)

        assertItinerary(refreshed, "old-yellow", "old-blue")
        assertEquals(existing.map(::legShape), refreshed.map(::legShape))
    }

    @Test
    fun alarmRefreshUpdatesOnlyTheFollowedDepartureLeg() {
        val fixture = fixture()
        val schedule = schedule(fixture)
        val existing = Itinerary(
            Station.RICH,
            Station.SFIA,
            itinerary(schedule, "old-yellow", "old-blue"),
        )
        val feedTime = epoch("2026-09-07T09:45:00-07:00")
        val updated = FollowedItineraryAlarmProjection(fixture.network).project(
            TransitFeedSnapshot(
                feedWithDelay(
                    feedTime,
                    "old-yellow",
                    Station.RICH,
                    epoch("2026-09-07T10:05:00-07:00"),
                ),
                emptyFeed(feedTime),
                feedTime,
            ),
            existing,
        )

        assertEquals(epoch("2026-09-07T10:05:00-07:00"), updated.legs.first().departureTime)
        assertEquals(legShape(existing.legs[1]), legShape(updated.legs[1]))
    }

    @Test
    fun missedConnectionTriggersCompleteRaptorReplanning() {
        val fixture = fixture()
        val schedule = schedule(fixture)
        val existing = itinerary(schedule, "old-yellow", "old-blue")
        val feedTime = epoch("2026-09-07T10:02:00-07:00")
        val feed = feedWithDelay(
            feedTime,
            "old-yellow",
            Station.RICH,
            epoch("2026-09-07T10:40:00-07:00"),
        )

        val refreshed = project(fixture.copy(feedTime = feedTime), feed, existing)

        assertItinerary(refreshed, "alt-yellow", "alt-blue")
        assertTrue(refreshed.none { it.tripId == "old-blue" })
    }

    @Test
    fun canceledFutureTripTriggersReplanning() {
        val fixture = fixture()
        val schedule = schedule(fixture)
        val existing = itinerary(schedule, "old-yellow", "old-blue")

        val refreshed = project(
            fixture,
            feedWithCancellation(fixture.feedTime, "old-yellow"),
            existing,
        )

        assertItinerary(refreshed, "alt-yellow", "alt-blue")
        assertTrue(refreshed.none { it.tripId == "old-yellow" })
    }

    @Test
    fun alreadyCompletedLegsRemainIntact() {
        val fixture = fixture()
        val schedule = schedule(fixture)
        val existing = itinerary(schedule, "old-yellow", "old-blue")
        val feedTime = epoch("2026-09-07T10:11:00-07:00")

        val refreshed = project(
            fixture.copy(feedTime = feedTime),
            feedWithCancellation(feedTime, "old-blue"),
            existing,
        )

        assertItinerary(refreshed, "old-yellow", "alt-blue")
        assertEquals(legShape(existing.first()), legShape(refreshed.first()))
    }

    @Test
    fun partiallyCompletedLegIsTruncatedAtLastTraveledStop() {
        val fixture = fixture(includeAlternatives = false)
        val schedule = schedule(fixture)
        val existing = itinerary(schedule, "old-yellow", "old-blue")
        val feedTime = epoch("2026-09-07T10:05:00-07:00")

        val refreshed = project(
            fixture.copy(feedTime = feedTime),
            feedWithCancellation(feedTime, "old-blue"),
            existing,
        )

        assertEquals(1, refreshed.size)
        assertEquals("old-yellow", refreshed.single().tripId)
        assertEquals(Station.RICH, refreshed.single().origin)
        assertEquals(Station.DALY, refreshed.single().destination)
        assertEquals(listOf(Station.RICH, Station.DALY), stations(refreshed.single()))
        assertEquals(epoch("2026-09-07T10:04:00-07:00"), refreshed.single().arrivalTime)
    }

    @Test
    fun noValidContinuationReturnsOnlyTheTraveledPrefix() {
        val fixture = fixture(includeAlternatives = false)
        val schedule = schedule(fixture)
        val existing = itinerary(schedule, "old-yellow", "old-blue")
        val feedTime = epoch("2026-09-07T10:11:00-07:00")

        val refreshed = project(
            fixture.copy(feedTime = feedTime),
            feedWithCancellation(feedTime, "old-blue"),
            existing,
        )

        assertEquals(1, refreshed.size)
        assertEquals(legShape(existing.first()), legShape(refreshed.single()))
        assertEquals(Station.MONT, refreshed.single().destination)
    }

    private data class Fixture(
        val network: BartGtfsNetwork,
        val feedTime: Long,
    )

    private fun fixture(includeAlternatives: Boolean = true): Fixture = Fixture(
        network = BartGtfsNetwork.fromCatalog(
            GtfsNetworkCatalog.fromFiles(
                mapOf(
                    "stops.txt" to "stop_id,stop_name,zone_id\n" +
                        "RICH,Richmond,RICH\n" +
                        "DALY,Daly City,DALY\n" +
                        "MONT,Montgomery,MONT\n" +
                        "SFIA,SFO Airport,SFIA\n",
                    "routes.txt" to "route_id,route_short_name\n" +
                        "yellow-old,Yellow-N\n" +
                        "yellow-alt,Yellow-N\n" +
                        "blue-old,Blue-N\n" +
                        "blue-alt,Blue-N\n",
                    "trips.txt" to "route_id,service_id,trip_id\n" +
                        "yellow-old,weekday,old-yellow\n" +
                        "blue-old,weekday,old-blue\n" +
                        if (includeAlternatives) {
                            "yellow-alt,weekday,alt-yellow\n" +
                                "blue-alt,weekday,alt-blue\n"
                        } else {
                            ""
                        },
                    "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                        "old-yellow,10:00:00,10:00:00,RICH,1\n" +
                        "old-yellow,10:04:00,10:04:00,DALY,2\n" +
                        "old-yellow,10:10:00,10:10:00,MONT,3\n" +
                        "old-blue,10:12:00,10:12:00,MONT,1\n" +
                        "old-blue,10:35:00,10:35:00,SFIA,2\n" +
                        if (includeAlternatives) {
                            "alt-yellow,10:45:00,10:45:00,RICH,1\n" +
                                "alt-yellow,10:46:00,10:46:00,MONT,2\n" +
                                "alt-blue,10:48:00,10:48:00,MONT,1\n" +
                                "alt-blue,11:10:00,11:10:00,SFIA,2\n"
                        } else {
                            ""
                        },
                    "transfers.txt" to "from_stop_id,to_stop_id,transfer_type,min_transfer_time,from_route_id,to_route_id\n" +
                        "MONT,MONT,2,90,yellow-old,blue-old\n" +
                        "MONT,MONT,2,90,yellow-alt,blue-alt\n",
                    "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                        "weekday,1,1,1,1,1,1,1,20260901,20260930\n",
                ),
            ),
        ),
        feedTime = epoch("2026-09-07T09:30:00-07:00"),
    )

    private fun schedule(fixture: Fixture): Schedule = Schedule.fromStatic(
        fixture.network,
        fixture.feedTime,
        setOf(Line.YELLOW, Line.BLUE),
    )

    private fun itinerary(
        schedule: Schedule,
        firstTripId: String,
        secondTripId: String,
    ): List<TripLeg> {
        val first = schedule.trips.first { it.key.tripId == firstTripId }
        val second = schedule.trips.first { it.key.tripId == secondTripId }
        return listOf(
            leg(first, Station.RICH, Station.MONT, 0),
            leg(second, Station.MONT, Station.SFIA),
        )
    }

    private fun leg(
        trip: Schedule.Trip,
        origin: Station,
        destination: Station,
        minimumTransferSecondsAfter: Int = 0,
    ): TripLeg {
        val start = trip.stops.indexOfFirst { it.station == origin }
        val end = trip.stops.indexOfFirst { it.station == destination }
        return TripLeg(
            line = trip.line,
            origin = origin,
            destination = destination,
            trainDestination = trip.trainDestination,
            tripId = trip.key.tripId,
            departureTime = trip.stopAt(origin)?.departureTime ?: 0L,
            arrivalTime = trip.stopAt(destination)?.arrivalTime ?: 0L,
            stops = trip.stops.subList(start, end + 1).map { stop ->
                TripStop(
                    station = stop.station,
                    arrivalTime = stop.arrivalTime,
                    departureTime = stop.departureTime,
                    scheduledArrivalTime = stop.scheduledArrivalTime,
                    scheduledDepartureTime = stop.scheduledDepartureTime,
                    arrivalSource = stop.arrivalSource,
                    departureSource = stop.departureSource,
                )
            },
            minimumTransferSecondsAfter = minimumTransferSecondsAfter,
            scheduledDepartureTime = trip.stopAt(origin)?.scheduledDepartureTime ?: 0L,
            scheduledArrivalTime = trip.stopAt(destination)?.scheduledArrivalTime ?: 0L,
            departureSource = trip.stopAt(origin)?.departureSource ?: PredictionSource.UNKNOWN,
            arrivalSource = trip.stopAt(destination)?.arrivalSource ?: PredictionSource.UNKNOWN,
            serviceDate = trip.key.serviceDate,
        )
    }

    private fun project(
        fixture: Fixture,
        tripUpdates: GtfsRealtime.FeedMessage,
        existing: List<TripLeg>,
    ): List<TripLeg> = ItineraryRefreshProjector(
        Station.RICH,
        Station.SFIA,
        fixture.network,
    ).project(
        TransitFeedSnapshot(tripUpdates, emptyFeed(fixture.feedTime), fixture.feedTime)
            .getCanonicalSnapshot(fixture.network),
        existing,
    )

    private fun feedWithCancellation(
        feedTime: Long,
        tripId: String,
    ): GtfsRealtime.FeedMessage = feedWithUpdate(
        feedTime,
        GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(
                GtfsRealtime.TripDescriptor.newBuilder()
                    .setTripId(tripId)
                    .setStartDate("20260907")
                    .setScheduleRelationship(
                        GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED,
                    ),
            )
            .build(),
    )

    private fun feedWithDelay(
        feedTime: Long,
        tripId: String,
        station: Station,
        time: Long,
    ): GtfsRealtime.FeedMessage = feedWithUpdate(
        feedTime,
        GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(
                GtfsRealtime.TripDescriptor.newBuilder()
                    .setTripId(tripId)
                    .setStartDate("20260907"),
            )
            .addStopTimeUpdate(
                GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                    .setStopId(station.abbreviation.uppercase())
                    .setArrival(
                        GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                            .setTime(time / 1000L),
                    )
                    .setDeparture(
                        GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                            .setTime(time / 1000L),
                    ),
            )
            .build(),
    )

    private fun feedWithUpdate(
        feedTime: Long,
        update: GtfsRealtime.TripUpdate,
    ): GtfsRealtime.FeedMessage = GtfsRealtime.FeedMessage.newBuilder()
        .setHeader(
            GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0")
                .setTimestamp(feedTime / 1000L),
        )
        .addEntity(
            GtfsRealtime.FeedEntity.newBuilder()
                .setId(update.trip.tripId)
                .setTripUpdate(update),
        )
        .build()

    private fun emptyFeed(feedTime: Long): GtfsRealtime.FeedMessage =
        GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(
                GtfsRealtime.FeedHeader.newBuilder()
                    .setGtfsRealtimeVersion("2.0")
                    .setTimestamp(feedTime / 1000L),
            )
            .build()

    private fun assertItinerary(legs: List<TripLeg>, vararg tripIds: String) {
        assertEquals(tripIds.toList(), legs.map { it.tripId })
        assertEquals(listOf(Line.YELLOW, Line.BLUE), legs.map { it.line })
        assertEquals(listOf(Station.MONT), legs.dropLast(1).map { it.destination })
        assertEquals(Station.SFIA, legs.last().destination)
    }

    private fun legShape(leg: TripLeg): List<Any?> = listOf(
        leg.line,
        leg.origin,
        leg.destination,
        leg.trainDestination,
        leg.tripId,
        leg.departureTime,
        leg.arrivalTime,
        leg.scheduledDepartureTime,
        leg.scheduledArrivalTime,
        leg.serviceDate,
        stations(leg),
    )

    private fun stations(leg: TripLeg): List<Station?> = leg.stops.map { it.station }

    private fun epoch(value: String): Long =
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
}
