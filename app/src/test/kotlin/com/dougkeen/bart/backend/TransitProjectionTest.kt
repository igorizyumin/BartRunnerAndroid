package com.dougkeen.bart.backend

import com.dougkeen.bart.model.Alert
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.Line
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.model.TripLeg
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import com.dougkeen.bart.transit.gtfs.GtfsNetworkCatalog
import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitProjectionTest {
    @Test
    fun routeProjectionDerivesDeparturesFromOneCompleteFeed() {
        val snapshot = snapshotWithTripUpdate()
        val projection = RouteDepartureProjection(StationPair(Station.MONT, Station.RICH), testNetwork())
        val departure: Departure = projection.project(snapshot).getDepartures()[0]
        assertEquals(Station.RICH, departure.trainDestination)
        assertEquals(1, projection.project(snapshot).getDepartures().size)
    }

    @Test
    fun alertProjectionConvertsTheLatestAlertFeed() {
        val text = GtfsRealtime.TranslatedString.newBuilder()
            .addTranslation(GtfsRealtime.TranslatedString.Translation.newBuilder()
                .setText("Delay at Montgomery").build())
            .build()
        val alert = GtfsRealtime.Alert.newBuilder()
            .setHeaderText(text)
            .addActivePeriod(GtfsRealtime.TimeRange.newBuilder().setStart(1234L).setEnd(5678L).build())
            .build()
        val alerts = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(header(1000L))
            .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("alert-1").setAlert(alert).build())
            .build()
        val result: Alert.AlertList = AlertProjection()
            .project(TransitFeedSnapshot(emptyFeed(), alerts, 1_000_000L))
        assertEquals(1, result.getAlerts().size)
        assertEquals("Delay at Montgomery", result.getAlerts()[0].description)
        assertEquals(1_234_000L, result.getAlerts()[0].postedAtMillis)
        assertEquals(5_678_000L, result.getAlerts()[0].expiresAtMillis)
    }

    @Test
    fun tripProgressProjectionUpdatesExistingLegFromSameFeed() {
        val projection = TripProgressProjection(
            Station.MONT,
            Station.RICH,
            listOf(TripLeg(Line.RED, Station.MONT, Station.RICH, Station.RICH, "red-1", 0L, 0L, emptyList())),
            testNetwork(),
        )
        val updated = projection.project(snapshotWithTripUpdate())[0]
        assertEquals(1_200_000L, updated.departureTime)
        assertEquals(1_800_000L, updated.arrivalTime)
        assertEquals(Station.RICH, updated.trainDestination)
    }

    private fun snapshotWithTripUpdate(): TransitFeedSnapshot {
        val montgomeryTime = GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(1200L).build()
        val richmondTime = GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(1800L).build()
        val tripUpdate = GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("red-1").setRouteId("8").build())
            .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                .setStopSequence(1).setStopId("M20-1").setDeparture(montgomeryTime).setArrival(montgomeryTime).build())
            .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                .setStopSequence(2).setStopId("R60-1").setDeparture(richmondTime).setArrival(richmondTime).build())
            .build()
        val updates = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(header(1000L))
            .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("red-1").setTripUpdate(tripUpdate).build())
            .build()
        return TransitFeedSnapshot(updates, emptyFeed(), 1_000_000L)
    }

    private fun emptyFeed() = GtfsRealtime.FeedMessage.newBuilder().setHeader(header(1000L)).build()

    private fun testNetwork(): BartGtfsNetwork {
        val files = mutableMapOf(
            "stops.txt" to "stop_id,stop_name,zone_id\nM20-1,Montgomery,MONT\nR60-1,Richmond,RICH\n",
            "routes.txt" to "route_id,route_short_name,route_long_name\n8,Red-N,Richmond - Daly City\n",
            "trips.txt" to "route_id,service_id,trip_id\n8,weekday,red-1\n",
            "stop_times.txt" to "trip_id,stop_id,stop_sequence\nred-1,M20-1,1\nred-1,R60-1,2\n",
        )
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files))
    }

    private fun header(timestamp: Long) = GtfsRealtime.FeedHeader.newBuilder()
        .setGtfsRealtimeVersion("2.0").setTimestamp(timestamp).build()
}
