package com.dougkeen.bart.backend

import com.dougkeen.bart.model.Line
import com.dougkeen.bart.model.PredictionSource
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork
import com.dougkeen.bart.transit.gtfs.GtfsNetworkCatalog
import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ScheduleTest {
    @Test
    fun staticGraphProvidesAnEstimatedAntiochContinuation() {
        val feedTime = epoch("2026-09-07T09:50:00-07:00")
        val schedule = Schedule.fromStatic(network(), feedTime, setOf(Line.YELLOW))

        val continuation = schedule.trips.first { it.key.tripId == "main-after-dmu" }
        assertEquals(Line.YELLOW_DMU, continuation.line)
        assertEquals(listOf(Station.PITT, Station.PCTR, Station.ANTC), continuation.stops.map { it.station })
        assertEquals(PredictionSource.ESTIMATE, continuation.stops[1].arrivalSource)
        assertEquals(12 * 60 * 1000L, schedule.nominalTravelTimeMillis(Station.PITT, Station.PCTR))
        assertTrue(schedule.edgesFrom(Station.PITT).any { it.trip.key.tripId == "main-after-dmu" })
    }

    @Test
    fun realtimeDepartureCorrectsTheMainTripAndMovesItsContinuation() {
        val feedTime = epoch("2026-09-07T09:50:00-07:00")
        val schedule = Schedule.fromStatic(network(), feedTime, setOf(Line.YELLOW))
        val update = GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("main").build())
            .addStopTimeUpdate(
                GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                    .setStopId("MONT")
                    .setDeparture(
                        GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                            .setTime(epoch("2026-09-07T10:05:00-07:00") / 1000L)
                            .build()
                    )
                    .build()
            )
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(
                GtfsRealtime.FeedHeader.newBuilder()
                    .setGtfsRealtimeVersion("2.0")
                    .build()
            )
            .addEntity(
                GtfsRealtime.FeedEntity.newBuilder()
                    .setId("main")
                    .setTripUpdate(update.build())
                    .build()
            )
            .build()

        val corrected = schedule.applyRealtime(GtfsRealtimeFeedIndex.from(feed))
        val main = corrected.trips.first { it.key.tripId == "main" }
        val continuation = corrected.trips.first { it.key.tripId == "main-after-dmu" }
        assertEquals(epoch("2026-09-07T10:05:00-07:00"), main.stops[0].departureTime)
        assertEquals(PredictionSource.REALTIME, main.stops[0].departureSource)
        assertEquals(epoch("2026-09-07T10:25:00-07:00"), main.stops[1].arrivalTime)
        assertEquals(PredictionSource.ESTIMATE, main.stops[1].arrivalSource)
        assertEquals(epoch("2026-09-07T10:25:00-07:00"), continuation.stops[0].departureTime)
        assertEquals(epoch("2026-09-07T10:44:00-07:00"), continuation.stops[2].arrivalTime)
    }

    private fun network(): BartGtfsNetwork = BartGtfsNetwork.fromCatalog(
        GtfsNetworkCatalog.fromFiles(
            mapOf(
                "stops.txt" to "stop_id,stop_name,zone_id\n" +
                    "MONT,Montgomery,MONT\n" +
                    "PITT,Pittsburg,PITT\n" +
                    "PCTR,Pittsburg Center,PCTR\n" +
                    "ANTC,Antioch,ANTC\n",
                "routes.txt" to "route_id,route_short_name\n2,Yellow-N\n",
                "trips.txt" to "route_id,service_id,trip_id\n" +
                    "2,weekday,main\n2,weekday,terminal-pattern\n",
                "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    "main,10:00:00,10:00:00,MONT,1\n" +
                    "main,10:20:00,10:20:00,PITT,2\n" +
                    "terminal-pattern,09:00:00,09:00:00,MONT,1\n" +
                    "terminal-pattern,09:20:00,09:20:00,PITT,2\n" +
                    "terminal-pattern,09:32:00,09:32:00,PCTR,3\n" +
                    "terminal-pattern,09:39:00,09:39:00,ANTC,4\n",
                "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                    "weekday,1,1,1,1,1,0,0,20260901,20260930\n",
            )
        )
    )

    private fun epoch(value: String): Long =
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
}
