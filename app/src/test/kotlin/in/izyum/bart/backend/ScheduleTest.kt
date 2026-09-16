package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.networktasks.GtfsRealtimeFeedIndex
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog
import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ScheduleTest {
    @Test
    fun staticGraphPreservesPittsburgShortTurnsAsPittsburgTrips() {
        val feedTime = epoch("2026-09-07T09:50:00-07:00")
        val schedule = Schedule.fromStatic(network(), feedTime, setOf(Line.YELLOW))

        val main = schedule.trips.first { it.key.tripId == "main" }
        assertEquals(Line.YELLOW, main.line)
        assertEquals(listOf(Station.MONT, Station.PITT), main.stops.map { it.station })
        assertEquals(Station.PITT, main.trainDestination)
        assertEquals(12 * 60 * 1000L, schedule.nominalTravelTimeMillis(Station.PITT, Station.PCTR))
        assertEquals(
            listOf(Station.MONT, Station.PITT, Station.PCTR, Station.ANTC),
            schedule.trips.first { it.key.tripId == "terminal-pattern" }.stops.map { it.station },
        )
    }

    @Test
    fun lateNightAugmentationDoesNotInventSfoForYellowTripsThatSkipIt() {
        val schedule = Schedule.fromStatic(
            lateNightYellowOnlyNetwork(),
            epoch("2026-09-07T21:50:00-07:00"),
            setOf(Line.YELLOW),
        )

        assertTrue(schedule.trips.any { it.line == Line.YELLOW && !it.synthetic })
        assertTrue(schedule.trips.none {
            it.synthetic && it.line == Line.YELLOW_LATE_NIGHT
        })
    }

    @Test
    fun realtimeDepartureCorrectsPittsburgShortTurnWithoutChangingItsDestination() {
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
        assertEquals(epoch("2026-09-07T10:05:00-07:00"), main.stops[0].departureTime)
        assertEquals(PredictionSource.REALTIME, main.stops[0].departureSource)
        assertEquals(epoch("2026-09-07T10:25:00-07:00"), main.stops[1].arrivalTime)
        assertEquals(PredictionSource.ESTIMATE, main.stops[1].arrivalSource)
        assertEquals(Station.PITT, main.trainDestination)
        assertEquals(listOf(Station.MONT, Station.PITT), main.stops.map { it.station })
    }

    @Test
    fun dmuTerminalUpdateDoesNotRewriteStaticTrips() {
        val feedTime = epoch("2026-09-07T09:50:00-07:00")
        val schedule = Schedule.fromStatic(heuristicNetwork(), feedTime, setOf(Line.YELLOW))
        val dmuPctrTime = epoch("2026-09-07T10:17:00-07:00")
        val dmuUpdate = GtfsRealtime.TripUpdate.newBuilder()
            .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("682").build())
            .addStopTimeUpdate(stopUpdate("PCTR-1", dmuPctrTime))
            .build()
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
            .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("682").setTripUpdate(dmuUpdate))
            .build()

        val corrected = schedule.applyRealtime(GtfsRealtimeFeedIndex.from(feed))
        assertEquals(schedule.trips, corrected.trips)
    }

    private fun stopUpdate(stopId: String, timeMillis: Long) =
        GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
            .setStopId(stopId)
            .setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                .setTime(timeMillis / 1000L).build())
            .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                .setTime(timeMillis / 1000L).build())
            .build()

    private fun heuristicNetwork(): BartGtfsNetwork = BartGtfsNetwork.fromCatalog(
        GtfsNetworkCatalog.fromFiles(
            mapOf(
                "stops.txt" to "stop_id,stop_name,zone_id\n" +
                    "MONT-1,Montgomery,MONT\n" +
                    "PITT-1,Pittsburg,PITT\n" +
                    "PCTR-1,Pittsburg Center,PCTR\n" +
                    "ANTC-1,Antioch,ANTC\n",
                "routes.txt" to "route_id,route_short_name\n2,Yellow-N\n",
                "trips.txt" to "route_id,service_id,trip_id\n" +
                    "2,weekday,electric\n2,weekday,decoy\n",
                "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    "electric,09:00:00,09:00:00,MONT-1,1\n" +
                    "electric,09:20:00,09:20:00,PITT-1,2\n" +
                    "electric,09:32:00,09:32:00,PCTR-1,3\n" +
                    "electric,09:39:00,09:39:00,ANTC-1,4\n" +
                    "decoy,09:44:00,09:44:00,MONT-1,1\n" +
                    "decoy,10:04:00,10:04:00,PITT-1,2\n" +
                    "decoy,10:16:00,10:16:00,PCTR-1,3\n" +
                    "decoy,10:23:00,10:23:00,ANTC-1,4\n",
                "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                    "weekday,1,1,1,1,1,0,0,20260901,20260930\n",
            )
        )
    )

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

    private fun lateNightYellowOnlyNetwork(): BartGtfsNetwork =
        BartGtfsNetwork.fromCatalog(
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
                        "main,22:00:00,22:00:00,MONT,1\n" +
                        "main,22:20:00,22:20:00,PITT,2\n" +
                        "terminal-pattern,22:30:00,22:30:00,MONT,1\n" +
                        "terminal-pattern,22:50:00,22:50:00,PITT,2\n" +
                        "terminal-pattern,23:02:00,23:02:00,PCTR,3\n" +
                        "terminal-pattern,23:09:00,23:09:00,ANTC,4\n",
                    "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                        "weekday,1,1,1,1,1,0,0,20260901,20260930\n",
                )
            )
        )

    private fun epoch(value: String): Long =
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
}
