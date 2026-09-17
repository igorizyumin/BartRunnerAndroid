package `in`.izyum.bart.networktasks

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.RealTimeDepartures
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.backend.Schedule
import `in`.izyum.bart.backend.TransitFeedSnapshot
import `in`.izyum.bart.transit.normalization.RequiredCounterpartStatus
import `in`.izyum.bart.transit.normalization.RealtimeFeedNormalizer
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog
import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GtfsRealtimeContentHandlerTest {
    @Test
    fun dmuTerminalUpdateDoesNotOverrideAccurateStaticSchedule() {
        val network = antiochNetwork()
        val feedTime = epoch("2026-09-07T10:00:00-07:00")
        val schedule = Schedule.fromStatic(network, feedTime, setOf(Line.YELLOW))
        val route = Route.direct(
            Station.MONT, Station.ANTC, Line.YELLOW, "n",
            listOf(Station.MONT, Station.PITT, Station.PCTR, Station.ANTC),
        )
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0")
                .setTimestamp(feedTime / 1000L))
            .addEntity(entity(
                "",
                "682",
                arrayOf("PCTR-1", "ANTC-1"),
                longArrayOf(
                    epoch("2026-09-07T10:17:00-07:00") / 1000L,
                    epoch("2026-09-07T10:24:00-07:00") / 1000L,
                ),
            ))
            .build()

        val departures = GtfsRealtimeContentHandler(
            Station.MONT, Station.ANTC, listOf(route), false, network,
        ).getRealTimeDepartures(feed)

        val scheduled = departures.getDepartures().firstOrNull {
            it.tripLegs.singleOrNull()?.tripId == "scheduled"
        } ?: throw AssertionError("static Antioch departure missing: ${departures.getDepartures()}")
        val leg = scheduled.tripLegs.single()
        assertEquals(Station.MONT, leg.origin)
        assertEquals(PredictionSource.SCHEDULE, leg.departureSource)
        assertEquals(epoch("2026-09-07T10:30:00-07:00"), leg.departureTime)
        assertFalse("technical DMU update leaked into passenger output",
            departures.getDepartures().any { it.tripLegs.any { trip -> trip.tripId == "682" } })
    }

    @Test
    fun northboundDmuDoesNotJoinToElectricTrainWithLaterPittTime() {
        val network = terminalDirectionNetwork()
        val feedTime = epoch("2026-09-07T09:45:00-07:00")
        val schedule = Schedule.fromStatic(network, feedTime, setOf(Line.YELLOW))
        val route = Route.direct(
            Station.MONT, Station.ANTC, Line.YELLOW, "n",
            listOf(Station.MONT, Station.PITT, Station.PCTR, Station.ANTC),
        )
        val electricPittTime = epoch("2026-09-07T10:29:00-07:00")
        val feed = terminalJoinFeed(
            feedTime,
            routeId = "yellow-n",
            electricTripId = "electric-live-n",
            electricStops = arrayOf("MONT-1", "PITT-1"),
            electricTimes = longArrayOf(
                epoch("2026-09-07T10:00:00-07:00") / 1000L,
                electricPittTime / 1000L,
            ),
            dmuStops = arrayOf("PCTR-1", "ANTC-1"),
            dmuTimes = longArrayOf(
                epoch("2026-09-07T10:17:00-07:00") / 1000L,
                epoch("2026-09-07T10:24:00-07:00") / 1000L,
            ),
        )

        val departures = GtfsRealtimeContentHandler(
            Station.MONT, Station.ANTC, listOf(route), false, network,
        ).getRealTimeDepartures(feed)

        assertFalse(
            "PCTR at 10:17 cannot belong to the electric train reaching PITT at " +
                "10:29; its northbound PCTR projection is 10:41",
            departures.getDepartures().any {
                it.tripLegs.any { leg -> leg.tripId == "electric-live-n" }
            },
        )
    }

    @Test
    fun dmuTelemetryStaysSeparateFromThePassengerTripIdentity() {
        val network = terminalDirectionNetwork()
        val feedTime = epoch("2026-09-07T09:45:00-07:00")
        val route = Route.direct(
            Station.PITT, Station.PCTR, Line.YELLOW, "n",
            listOf(Station.MONT, Station.PITT, Station.PCTR, Station.ANTC),
        )
        val feed = terminalJoinFeed(
            feedTime,
            routeId = "yellow-n",
            electricTripId = "scheduled-n",
            electricStops = arrayOf("PITT-1"),
            electricTimes = longArrayOf(epoch("2026-09-07T10:05:00-07:00") / 1000L),
            dmuStops = arrayOf("PCTR-1", "ANTC-1"),
            dmuTimes = longArrayOf(
                epoch("2026-09-07T10:17:00-07:00") / 1000L,
                epoch("2026-09-07T10:24:00-07:00") / 1000L,
            ),
        )

        val canonical = TransitFeedSnapshot(feed, emptyFeed(feedTime / 1000L), feedTime)
            .getCanonicalSnapshot(network)

        assertEquals(1, canonical.requiredTransferPairs.size)
        assertEquals("682", canonical.requiredTransferPairs.single().operationalObservation.tripId)
        assertEquals(
            RequiredCounterpartStatus.REQUIRED_COUNTERPART_NOT_OBSERVED,
            canonical.requiredTransferPairs.single().status,
        )
    }

    @Test
    fun southboundDmuProjectsAntiochTimeBackFromPitt() {
        val network = terminalDirectionNetwork()
        val feedTime = epoch("2026-09-07T09:45:00-07:00")
        val schedule = Schedule.fromStatic(network, feedTime, setOf(Line.YELLOW))
        val route = Route.direct(
            Station.PITT, Station.MONT, Line.YELLOW, "s",
            listOf(Station.ANTC, Station.PCTR, Station.PITT, Station.MONT),
        )
        val feed = terminalJoinFeed(
            feedTime,
            routeId = "yellow-s",
            electricTripId = "electric-live-s",
            electricStops = arrayOf("PITT-2", "MONT-2"),
            electricTimes = longArrayOf(
                epoch("2026-09-07T10:29:00-07:00") / 1000L,
                epoch("2026-09-07T10:50:00-07:00") / 1000L,
            ),
            dmuStops = arrayOf("ANTC-2", "PCTR-2"),
            dmuTimes = longArrayOf(
                epoch("2026-09-07T10:10:00-07:00") / 1000L,
                epoch("2026-09-07T10:22:00-07:00") / 1000L,
            ),
        )

        val departures = GtfsRealtimeContentHandler(
            Station.PITT, Station.MONT, listOf(route), false, network,
        ).getRealTimeDepartures(feed)

        assertTrue(
            "the DMU at Antioch should join the electric train leaving PITT at 10:29",
            departures.getDepartures().any {
                it.tripLegs.any { leg -> leg.tripId == "electric-live-s" }
            },
        )
    }

    @Test
    fun platformsBelongToOriginStopsAndRealtimeCanOverrideStaticPlatform() {
        val network = platformNetwork()
        val feedTime = epoch("2026-09-07T08:00:00-07:00")
        val schedule = Schedule.fromStatic(network, feedTime, setOf(Line.BLUE))
        val emptyIndex = RealtimeFeedNormalizer.normalize(emptyFeed(feedTime / 1000L))

        val southRoute = schedule.routesFor(Station.CAST, Station.DALY).single()
        val southHandler = GtfsRealtimeContentHandler(
            Station.CAST, Station.DALY, listOf(southRoute), false, network,
        )
        val southDeparture = southHandler
            .getRealTimeDepartures(emptyIndex, feedTime, schedule)
            .getDepartures()
            .single()
        assertEquals("2", southDeparture.platform)
        assertEquals("2", southDeparture.tripLegs.single().platform)

        val northRoute = schedule.routesFor(Station.CAST, Station.DUBL).single()
        val northDeparture = GtfsRealtimeContentHandler(
            Station.CAST, Station.DUBL, listOf(northRoute), false, network,
        ).getRealTimeDepartures(emptyIndex, feedTime, schedule)
            .getDepartures()
            .single()
        assertEquals("1", northDeparture.platform)
        assertEquals("1", northDeparture.tripLegs.single().platform)

        val realtimeEvent = GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
            .setTime(epoch("2026-09-07T08:31:00-07:00") / 1000L)
            .build()
        val realtimeUpdate = GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                .setRouteId("11")
                .setTripId("blue-south"))
            .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                .setStopId("CAST-1")
                .setArrival(realtimeEvent)
                .setDeparture(realtimeEvent))
            .build()
        val realtimeFeed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0")
                .setTimestamp(feedTime / 1000L))
            .addEntity(GtfsRealtime.FeedEntity.newBuilder()
                .setId("blue-south")
                .setTripUpdate(realtimeUpdate))
            .build()

        val overriddenDeparture = southHandler
            .getRealTimeDepartures(
                RealtimeFeedNormalizer.normalize(realtimeFeed),
                feedTime,
                schedule.applyRealtime(RealtimeFeedNormalizer.normalize(realtimeFeed)),
            )
            .getDepartures()
            .single()
        assertEquals("1", overriddenDeparture.platform)
        assertEquals("1", overriddenDeparture.tripLegs.single().platform)
    }

    @Test
    fun staticScheduleSuppliesTerminalWhenRealtimeOmitsItsPrediction() {
        val network = network()
        val route = Schedule.fromStatic(network, 0L)
            .routesFor(Station.MONT, Station.DALY)
            .first { it.directLine == Line.BLUE }
        val update = GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                .setRouteId("12").setTripId("blue-valid"))
            .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                .setStopId("MONT")
                .setStopSequence(1)
                .setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                    .setTime(1_000L))
                .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                    .setTime(1_000L)))
            .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                .setStopId("DALY")
                .setStopSequence(2))
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0").setTimestamp(900L))
            .addEntity(GtfsRealtime.FeedEntity.newBuilder()
                .setId("blue-valid")
                .setTripUpdate(update))
            .build()

        val departures = GtfsRealtimeContentHandler(
            Station.MONT, Station.DALY, listOf(route), false, network,
        ).getRealTimeDepartures(feed)

        assertEquals(1, departures.getDepartures().size)
        val departure = departures.getDepartures()[0]
        assertEquals(Station.DALY, departure.trainDestination)
        assertEquals(Station.DALY, departure.tripLegs[0].destination)
        assertEquals(0L, departure.tripLegs[0].arrivalTime)
    }

    @Test
    fun keepsOnlyConnectingTripsThatMeetFeedMinimum() {
        val network = network()
        val route: Route = Schedule.fromStatic(network, 0L).routesFor(Station.LAKE, Station.DALY)[0]
        assertTrue(
            "route=$route lines=${route.lines} transfers=${route.transferStations} yellow=${route.getStationSequence(Line.YELLOW)}",
            route.trainDestinationIsApplicable(Station.MONT, Line.YELLOW),
        )
        val departures: RealTimeDepartures = GtfsRealtimeContentHandler(
            Station.LAKE, Station.DALY, listOf(route), false, network,
        ).getRealTimeDepartures(feed())
        assertEquals(1, departures.getDepartures().size)
        val legs: List<TripLeg> = departures.getDepartures()[0].tripLegs
        assertEquals(2, legs.size)
        assertEquals("blue-valid", legs[1].tripId)
        assertEquals(1_150_000L, legs[1].departureTime)
        assertEquals(1_210_000L, legs[1].arrivalTime)
        assertEquals(90, legs[0].minimumTransferSecondsAfter)
    }

    @Test
    fun progressRefreshReplacesAConnectionInvalidatedByADelay() {
        val network = network()
        val route = Schedule.fromStatic(network, 0L)
            .routesFor(Station.LAKE, Station.DALY)[0]
        val handler = GtfsRealtimeContentHandler(
            Station.LAKE, Station.DALY, listOf(route), false, network,
        )
        val initialLegs = handler.getRealTimeDepartures(feed())
            .getDepartures()[0].tripLegs

        val delayedFeed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0").setTimestamp(900L))
            .addEntity(entity(
                "1", "yellow-first", arrayOf("LAKE", "MONT"),
                longArrayOf(1_000L, 2_000L),
            ))
            .addEntity(canceledEntity(
                "12", "blue-early", arrayOf("MONT", "DALY"),
                longArrayOf(1_100L, 1_160L),
            ))
            .addEntity(entity(
                "12", "blue-valid", arrayOf("MONT", "DALY"),
                longArrayOf(2_100L, 2_160L),
            ))
            .build()

        val refreshed = handler.updateTripLegs(
            RealtimeFeedNormalizer.normalize(delayedFeed),
            initialLegs,
            900_000L,
            Schedule.fromStatic(network, 0L).applyRealtime(
                RealtimeFeedNormalizer.normalize(delayedFeed)
            ),
        )

        assertEquals(2, refreshed.size)
        assertEquals("blue-valid", refreshed[1].tripId)
        assertTrue(refreshed[1].departureTime >= refreshed[0].arrivalTime)
    }

    private fun canceledEntity(
        routeId: String,
        tripId: String,
        stops: Array<String>,
        times: LongArray,
    ): GtfsRealtime.FeedEntity {
        val original = entity(routeId, tripId, stops, times)
        val tripUpdate = original.tripUpdate.toBuilder()
            .setTrip(original.tripUpdate.trip.toBuilder()
                .setScheduleRelationship(
                    GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED
                ))
            .build()
        return original.toBuilder().setTripUpdate(tripUpdate).build()
    }

    @Test
    fun dropsTripsThatLeftTheOriginLongAgo() {
        val network = network()
        val route = Schedule.fromStatic(network, 0L).routesFor(Station.MONT, Station.DALY)[0]
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0").setTimestamp(900L))
            .addEntity(entity(
                "12",
                "blue-stale",
                arrayOf("MONT", "DALY"),
                longArrayOf(700L, 760L),
            ))
            .build()

        val departures = GtfsRealtimeContentHandler(
            Station.MONT, Station.DALY, listOf(route), false, network,
        ).getRealTimeDepartures(feed)

        assertTrue(departures.getDepartures().isEmpty())
    }

    @Test
    fun dropsTripsThatLeftTheOriginMoreThan45SecondsAgo() {
        val network = network()
        val route = Schedule.fromStatic(network, 0L)
            .routesFor(Station.MONT, Station.DALY)[0]
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0").setTimestamp(900L))
            .addEntity(entity(
                "12",
                "blue-46-seconds-stale",
                arrayOf("MONT", "DALY"),
                longArrayOf(854L, 914L),
            ))
            .build()

        val departures = GtfsRealtimeContentHandler(
            Station.MONT, Station.DALY, listOf(route), false, network,
        ).getRealTimeDepartures(feed)

        assertTrue(departures.getDepartures().isEmpty())
    }

    @Test
    fun returnsAnImmutableDepartureList() {
        val departures = GtfsRealtimeContentHandler(
            Station.LAKE,
            Station.DALY,
            listOf(Schedule.fromStatic(network(), 0L).routesFor(Station.LAKE, Station.DALY)[0]),
            false,
            network(),
        ).getRealTimeDepartures(feed())
        assertThrows(UnsupportedOperationException::class.java) {
            (departures.getDepartures() as MutableList).clear()
        }
    }

    private fun feed() = GtfsRealtime.FeedMessage.newBuilder()
        .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(900L))
        .addEntity(entity("1", "yellow-first", arrayOf("LAKE", "MONT"), longArrayOf(1_000L, 1_060L)))
        .addEntity(entity("12", "blue-early", arrayOf("MONT", "DALY"), longArrayOf(1_100L, 1_160L)))
        .addEntity(entity("12", "blue-valid", arrayOf("MONT", "DALY"), longArrayOf(1_150L, 1_210L)))
        .build()

    private fun entity(routeId: String, tripId: String, stops: Array<String>, times: LongArray): GtfsRealtime.FeedEntity {
        val update = GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setRouteId(routeId).setTripId(tripId))
        stops.indices.forEach { index ->
            val event = GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(times[index]).build()
            update.addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                .setStopId(stops[index]).setStopSequence(index + 1).setArrival(event).setDeparture(event))
        }
        return GtfsRealtime.FeedEntity.newBuilder().setId(tripId).setTripUpdate(update).build()
    }

    private fun network(): BartGtfsNetwork {
        val files = mutableMapOf(
            "stops.txt" to "stop_id,stop_name,zone_id\nLAKE,Lake Merritt,LAKE\nMONT,Montgomery St.,MONT\nDALY,Daly City,DALY\n",
            "routes.txt" to "route_id,route_short_name\n1,Yellow-N\n12,Blue-N\n",
            "trips.txt" to "route_id,service_id,trip_id\n1,weekday,yellow-first\n12,weekday,blue-early\n12,weekday,blue-valid\n",
            "stop_times.txt" to "trip_id,stop_id,stop_sequence\nyellow-first,LAKE,1\nyellow-first,MONT,2\nblue-early,MONT,1\nblue-early,DALY,2\nblue-valid,MONT,1\nblue-valid,DALY,2\n",
            "transfers.txt" to "from_stop_id,to_stop_id,transfer_type,min_transfer_time,from_route_id,to_route_id\nMONT,MONT,2,90,1,12\n",
        )
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files))
    }

    private fun antiochNetwork(): BartGtfsNetwork {
        val files = mapOf(
            "stops.txt" to "stop_id,stop_name,zone_id\n" +
                "MONT,Montgomery St.,MONT\n" +
                "PITT,Pittsburg/Bay Point,PITT\n" +
                "PCTR-1,Pittsburg Center,PCTR\n" +
                "ANTC-1,Antioch,ANTC\n",
            "routes.txt" to "route_id,route_short_name\nyellow-n,Yellow-N\n",
            "trips.txt" to "route_id,service_id,trip_id\nyellow-n,weekday,scheduled\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "scheduled,10:30:00,10:30:00,MONT,1\n" +
                "scheduled,10:45:00,10:45:00,PITT,2\n" +
                "scheduled,10:57:00,10:57:00,PCTR-1,3\n" +
                "scheduled,11:04:00,11:04:00,ANTC-1,4\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                "weekday,1,1,1,1,1,0,0,20260901,20260930\n",
        )
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files))
    }

    private fun terminalDirectionNetwork(): BartGtfsNetwork {
        val files = mapOf(
            "stops.txt" to "stop_id,stop_name,zone_id\n" +
                "MONT-1,Montgomery St.,MONT\nMONT-2,Montgomery St.,MONT\n" +
                "PITT-1,Pittsburg/Bay Point,PITT\nPITT-2,Pittsburg/Bay Point,PITT\n" +
                "PCTR-1,Pittsburg Center,PCTR\nPCTR-2,Pittsburg Center,PCTR\n" +
                "ANTC-1,Antioch,ANTC\nANTC-2,Antioch,ANTC\n",
            "routes.txt" to "route_id,route_short_name\nyellow-s,Yellow-S\nyellow-n,Yellow-N\n",
            "trips.txt" to "route_id,service_id,trip_id\n" +
                "yellow-n,weekday,scheduled-n\nyellow-s,weekday,scheduled-s\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "scheduled-n,11:50:00,11:50:00,MONT-1,1\n" +
                "scheduled-n,12:10:00,12:10:00,PITT-1,2\n" +
                "scheduled-n,12:22:00,12:22:00,PCTR-1,3\n" +
                "scheduled-n,12:29:00,12:29:00,ANTC-1,4\n" +
                "scheduled-s,11:30:00,11:30:00,ANTC-2,1\n" +
                "scheduled-s,11:37:00,11:37:00,PCTR-2,2\n" +
                "scheduled-s,11:49:00,11:49:00,PITT-2,3\n" +
                "scheduled-s,12:09:00,12:09:00,MONT-2,4\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                "weekday,1,1,1,1,1,0,0,20260901,20260930\n",
        )
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files))
    }

    private fun terminalJoinFeed(
        feedTime: Long,
        routeId: String,
        electricTripId: String,
        electricStops: Array<String>,
        electricTimes: LongArray,
        dmuStops: Array<String>,
        dmuTimes: LongArray,
    ): GtfsRealtime.FeedMessage = GtfsRealtime.FeedMessage.newBuilder()
        .setHeader(GtfsRealtime.FeedHeader.newBuilder()
            .setGtfsRealtimeVersion("2.0")
            .setTimestamp(feedTime / 1000L))
        .addEntity(entity(routeId, electricTripId, electricStops, electricTimes))
        .addEntity(entity("", "682", dmuStops, dmuTimes))
        .build()

    private fun platformNetwork(): BartGtfsNetwork {
        val files = mutableMapOf(
            "stops.txt" to "stop_id,stop_name,zone_id\n" +
                "DUBL-1,Dublin/Pleasanton,DUBL\n" +
                "DUBL-2,Dublin/Pleasanton,DUBL\n" +
                "CAST-1,Castro Valley,CAST\n" +
                "CAST-2,Castro Valley,CAST\n" +
                "DALY-1,Daly City,DALY\n" +
                "DALY-2,Daly City,DALY\n",
            "routes.txt" to "route_id,route_short_name\n" +
                "11,Blue-S\n" +
                "12,Blue-N\n",
            "trips.txt" to "route_id,service_id,trip_id\n" +
                "11,weekday,blue-south\n" +
                "12,weekday,blue-north\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "blue-south,08:00:00,08:00:00,DUBL-2,1\n" +
                "blue-south,08:30:00,08:30:00,CAST-2,2\n" +
                "blue-south,09:00:00,09:00:00,DALY-2,3\n" +
                "blue-north,08:05:00,08:05:00,DALY-2,1\n" +
                "blue-north,08:35:00,08:35:00,CAST-1,2\n" +
                "blue-north,09:05:00,09:05:00,DUBL-1,3\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                "weekday,1,1,1,1,1,0,0,20260901,20260930\n",
        )
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files))
    }

    private fun emptyFeed(timestamp: Long) = GtfsRealtime.FeedMessage.newBuilder()
        .setHeader(GtfsRealtime.FeedHeader.newBuilder()
            .setGtfsRealtimeVersion("2.0")
            .setTimestamp(timestamp))
        .build()

    private fun epoch(value: String): Long =
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
}
