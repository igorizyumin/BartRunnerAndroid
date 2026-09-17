package `in`.izyum.bart.backend

import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog
import `in`.izyum.bart.transit.normalization.RequiredCounterpartStatus
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.StationPair
import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalTransitSnapshotTest {
    @Test
    fun pairsObservedDmuTelemetryWithAnExactSameCaptureElectricObservation() {
        val feedTime = epoch("2026-09-07T09:45:00-07:00")
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0").setTimestamp(feedTime / 1000L))
            .addEntity(entity("electric", "PITT-1", epoch("2026-09-07T10:05:00-07:00"), "20260907"))
            .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("682")
                .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
                    .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                        .setTripId("682").setStartDate("20260907"))
                    .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                        .setStopId("PITT-1")
                        .setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                            .setTime(epoch("2026-09-07T10:04:00-07:00") / 1000L))
                        .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                            .setTime(epoch("2026-09-07T10:05:00-07:00") / 1000L)))
                    .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                        .setStopId("PCTR-1")
                        .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                            .setTime(epoch("2026-09-07T10:17:00-07:00") / 1000L))))
                .build())
            .build()

        val canonical = TransitFeedSnapshot(feed, emptyFeed(feedTime), feedTime)
            .getCanonicalSnapshot(network())

        assertEquals(1, canonical.requiredTransferPairs.size)
        val pair = canonical.requiredTransferPairs.single()
        assertEquals(RequiredCounterpartStatus.OBSERVED, pair.status)
        assertEquals("electric", pair.electricAssociation?.observation?.tripId)
        assertEquals(2, pair.operationalObservation.stops.size)
        val trip = canonical.correctedSchedule.trips.single()
        assertEquals(epoch("2026-09-07T10:04:00-07:00"), trip.stopAt(Station.PITT)?.arrivalTime)
        assertEquals(epoch("2026-09-07T10:05:00-07:00"), trip.stopAt(Station.PITT)?.departureTime)
        assertEquals(PredictionSource.REALTIME, trip.stopAt(Station.PITT)?.departureSource)
        assertEquals(epoch("2026-09-07T10:17:00-07:00"), trip.stopAt(Station.PCTR)?.departureTime)
        assertEquals(PredictionSource.REALTIME, trip.stopAt(Station.PCTR)?.departureSource)
    }

    @Test
    fun appliesHighConfidenceAntiochTelemetryToTheSeparateStaticPassengerTrip() {
        val feedTime = epoch("2026-09-07T09:45:00-07:00")
        val antdTime = epoch("2026-09-07T10:02:00-07:00")
        val pctrTime = epoch("2026-09-07T10:14:00-07:00")
        val pittTime = epoch("2026-09-07T10:26:00-07:00")
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0").setTimestamp(feedTime / 1000L))
            .addEntity(entityWithStops("682", "20260907", listOf(
                "ANTC-2" to antdTime,
                "PCTR-2" to pctrTime,
                "PITT-2" to pittTime,
            )))
            .build()

        val canonical = TransitFeedSnapshot(feed, emptyFeed(feedTime), feedTime)
            .getCanonicalSnapshot(southboundNetwork())
        val pair = canonical.requiredTransferPairs.single()
        val trip = canonical.correctedSchedule.trips.single()

        assertEquals("south", pair.passengerIdentity?.tripId)
        assertEquals("682", pair.operationalObservation.tripId)
        assertEquals(RequiredCounterpartStatus.REQUIRED_COUNTERPART_NOT_OBSERVED, pair.status)
        assertEquals(antdTime, trip.stopAt(Station.ANTC)?.departureTime)
        assertEquals(PredictionSource.REALTIME, trip.stopAt(Station.ANTC)?.departureSource)
        assertEquals(pctrTime, trip.stopAt(Station.PCTR)?.departureTime)
        assertEquals(PredictionSource.REALTIME, trip.stopAt(Station.PCTR)?.departureSource)
        assertEquals(pittTime, trip.stopAt(Station.PITT)?.departureTime)
        assertEquals(PredictionSource.REALTIME, trip.stopAt(Station.PITT)?.departureSource)
    }

    @Test
    fun routeProjectionUsesCorrelatedTelemetryWithoutChangingPassengerIdentity() {
        val feedTime = epoch("2026-09-07T09:45:00-07:00")
        val antdTime = epoch("2026-09-07T10:02:00-07:00")
        val pittArrivalTime = epoch("2026-09-07T10:26:00-07:00")
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0").setTimestamp(feedTime / 1000L))
            .addEntity(entityWithStops("682", "20260907", listOf(
                "ANTC-2" to antdTime,
                "PCTR-2" to epoch("2026-09-07T10:14:00-07:00"),
                "PITT-2" to pittArrivalTime,
            )))
            .build()

        val departures = RouteDepartureProjection(
            StationPair(Station.ANTC, Station.PITT), southboundNetwork(),
        ).project(TransitFeedSnapshot(feed, emptyFeed(feedTime), feedTime))
            .getDepartures()
        val leg = departures.single().tripLegs.single()

        assertEquals("south", leg.tripId)
        assertEquals(antdTime, leg.departureTime)
        assertEquals(PredictionSource.REALTIME, leg.departureSource)
        assertEquals(pittArrivalTime, leg.arrivalTime)
        assertEquals(PredictionSource.REALTIME, leg.arrivalSource)
        assertEquals(
            PredictionSource.REALTIME,
            leg.stops.first { it.station == Station.PITT }.arrivalSource,
        )
    }

    @Test
    fun doesNotProjectTelemetryOntoAnAdjacentTwentyMinutePassengerDeparture() {
        val feedTime = epoch("2026-09-07T09:45:00-07:00")
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0").setTimestamp(feedTime / 1000L))
            .addEntity(entityWithStops("682", "20260907", listOf(
                "ANTC-2" to epoch("2026-09-07T10:10:00-07:00"),
                "PCTR-2" to epoch("2026-09-07T10:22:00-07:00"),
            )))
            .build()

        val canonical = TransitFeedSnapshot(feed, emptyFeed(feedTime), feedTime)
            .getCanonicalSnapshot(southboundNetwork())

        assertEquals(1, canonical.requiredTransferPairs.size)
        assertEquals(null, canonical.requiredTransferPairs.single().passengerIdentity)
    }

    private fun entity(
        tripId: String,
        stopId: String,
        time: Long,
        startDate: String,
    ): GtfsRealtime.FeedEntity = GtfsRealtime.FeedEntity.newBuilder().setId(tripId)
        .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId(tripId).setStartDate(startDate))
            .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                .setStopId(stopId)
                .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(time / 1000L))))
        .build()

    private fun emptyFeed(timestamp: Long): GtfsRealtime.FeedMessage =
        GtfsRealtime.FeedMessage.newBuilder().setHeader(
            GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0")
                .setTimestamp(timestamp / 1000L)
        ).build()

    private fun entityWithStops(
        tripId: String,
        startDate: String,
        stops: List<Pair<String, Long>>,
    ): GtfsRealtime.FeedEntity = GtfsRealtime.FeedEntity.newBuilder().setId(tripId)
        .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId(tripId).setStartDate(startDate))
            .addAllStopTimeUpdate(stops.map { (stopId, time) ->
                GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                    .setStopId(stopId)
                    .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                        .setTime(time / 1000L))
                    .build()
            }))
        .build()

    private fun network(): BartGtfsNetwork = BartGtfsNetwork.fromCatalog(
        GtfsNetworkCatalog.fromFiles(
            mapOf(
                "stops.txt" to "stop_id,stop_name,zone_id\nMONT-1,Montgomery,MONT\nPITT-1,Pittsburg,PITT\nPCTR-1,Pittsburg Center,PCTR\nANTC-1,Antioch,ANTC\n",
                "routes.txt" to "route_id,route_short_name\n2,Yellow-N\n",
                "trips.txt" to "route_id,service_id,trip_id\n2,weekday,electric\n",
                "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nelectric,09:45:00,09:45:00,MONT-1,1\nelectric,10:05:00,10:05:00,PITT-1,2\nelectric,10:17:00,10:17:00,PCTR-1,3\nelectric,10:24:00,10:24:00,ANTC-1,4\n",
                "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nweekday,1,1,1,1,1,1,1,20260901,20260930\n",
            )
        )
    )

    private fun southboundNetwork(): BartGtfsNetwork = BartGtfsNetwork.fromCatalog(
        GtfsNetworkCatalog.fromFiles(
            mapOf(
                "stops.txt" to "stop_id,stop_name,zone_id\nMONT-2,Montgomery,MONT\nPITT-2,Pittsburg,PITT\nPCTR-2,Pittsburg Center,PCTR\nANTC-2,Antioch,ANTC\n",
                "routes.txt" to "route_id,route_short_name\n2,Yellow-S\n",
                "trips.txt" to "route_id,service_id,trip_id\n2,weekday,south\n",
                "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nsouth,10:00:00,10:00:00,ANTC-2,1\nsouth,10:12:00,10:12:00,PCTR-2,2\nsouth,10:24:00,10:24:00,PITT-2,3\nsouth,10:45:00,10:45:00,MONT-2,4\n",
                "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nweekday,1,1,1,1,1,1,1,20260901,20260930\n",
            )
        )
    )

    private fun epoch(value: String): Long =
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
}
