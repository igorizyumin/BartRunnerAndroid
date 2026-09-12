package `in`.izyum.bart.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.backend.Schedule
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog

import org.junit.Test

import java.util.HashMap

class ScheduleRoutingTest {
    private val TEST_NETWORK: BartGtfsNetwork = testNetwork()

    @Test
    fun directRoutesIncludeAllUsableLinesAndPreserveEndpoints() {
        val routes: List<Route> = routesFor(Station.MONT,
                Station.RICH, TEST_NETWORK)

        assertTrue("routes=" + routes, containsDirectLine(routes, Line.RED))
        for (route in routes) {
            if (route.hasTransfer()) continue
            assertEquals(Station.MONT, route.origin)
            assertEquals(Station.RICH, route.destination)
            assertNotNull(route.directLine)
            assertTrue(route.getStationSequence(route.directLine)
                    .contains(Station.MONT))
            assertTrue(route.getStationSequence(route.directLine)
                    .contains(Station.RICH))
        }
    }

    @Test
    fun directRoutesHaveValidDirectionForEveryStationPair() {
        for (origin in Station.values()) {
            for (destination in Station.values()) {
                if (origin == destination) {
                    continue
                }
                for (route in routesFor(origin, destination,
                        TEST_NETWORK)) {
                    if (route.hasTransfer()) {
                        continue
                    }
                    var line: Line? = route.directLine
                    val sequence: List<Station> = route.getStationSequence(line)
                    var originIndex = sequence.indexOf(origin)
                    var destinationIndex = sequence.indexOf(destination)
                    assertTrue("direct route must contain origin", originIndex >= 0)
                    assertTrue("direct route must contain destination",
                            destinationIndex >= 0)
                    assertTrue("direction=" + route.direction,
                            "n".equals(route.direction)
                                    || "s".equals(route.direction))
                }
            }
        }
    }

    @Test
    fun transferRoutesAreValidPathsAndHaveNoDuplicates() {
        for (origin in Station.values()) {
            for (destination in Station.values()) {
                if (origin == destination) {
                    continue
                }
                val routes: List<Route> = transferRoutes(origin,
                        destination, TEST_NETWORK)
                val signatures: MutableSet<String> = HashSet()
                for (route in routes) {
                    assertTrue(route.hasTransfer())
                    assertEquals(origin, route.origin)
                    assertEquals(destination, route.destination)
                    assertEquals(route.transferStations.size + 1,
                            route.lines.size)
                    assertEquals(route.lines.get(0), route.directLine)
                    assertFalse("transfer repeats origin: " + route,
                            route.transferStations.contains(origin))
                    assertFalse("transfer repeats destination: " + route
                                    + " lines=" + route.lines
                                    + " transfers=" + route.transferStations,
                            route.transferStations.contains(destination))
                    assertTrue("direction=" + route.direction,
                            "n".equals(route.direction)
                                    || "s".equals(route.direction))

                    for (i in 0 until route.lines.size) {
                        val segmentOrigin: Station? = if (i == 0) origin
                                else route.transferStations[i - 1]
                        val segmentDestination: Station? = if (i == route.lines.size - 1)
                                destination
                                else route.transferStations[i]
                        val line: Line? = route.lines[i]
                        val sequence: List<Station> = route.getStationSequence(line)
                        assertTrue(sequence.contains(segmentOrigin))
                        assertTrue(sequence.contains(segmentDestination))
                        assertTrue(sequence.indexOf(segmentOrigin)
                                != sequence.indexOf(segmentDestination))
                        if (i < route.lines.size - 1) {
                            assertFalse("destination is passed before final leg: "
                                            + route,
                                    sequence.contains(destination))
                        }
                        if (i > 0) {
                            assertFalse("origin is revisited after departure: "
                                            + route,
                                    sequence.contains(origin))
                        }
                    }

                    val signature = route.lines.toString() + ":" + route.transferStations
                    assertTrue("duplicate route: " + signature,
                            signatures.add(signature))
                }
            }
        }
    }

    @Test
    fun preferredEastBayRouteUsesLakeMerrittAndNineteenthStreet() {
        val routes: List<Route> = preferredTransferRoutes(Station.DUBL,
                Station.ANTC, TEST_NETWORK)

        assertFalse(routes.isEmpty())
        val route = routes[0]
        assertEquals(asLines(Line.BLUE, Line.ORANGE, Line.YELLOW),
                route.lines)
        assertEquals(asStations(Station.LAKE, Station._19TH),
                route.transferStations)
    }

    @Test
    fun antiochToCastroValleyUsesOrangeAtMacArthurAndLakeMerritt() {
        val routes: List<Route> = preferredTransferRoutes(Station.ANTC,
                Station.CAST, TEST_NETWORK)

        assertFalse("routes=" + routes, routes.isEmpty())
        val route = routes[0]
        assertEquals(asLines(Line.YELLOW, Line.ORANGE, Line.BLUE), route.lines)
        assertEquals(asStations(Station.MCAR, Station.LAKE),
                route.transferStations)
    }

    @Test
    fun richmondToSfoUsesMacArthurForTheSouthboundYellowTransfer() {
        val routes: List<Route> = preferredTransferRoutes(Station.RICH,
                Station.SFIA, TEST_NETWORK)

        assertFalse("routes=" + routes, routes.isEmpty())
        val route = routes[0]
        assertEquals(asLines(Line.ORANGE, Line.YELLOW), route.lines)
        assertEquals(asStations(Station.MCAR), route.transferStations)
    }

    @Test
    fun allStationPairingsUseTheExpectedTransfersInBothDirections() {
        val expected: Map<Pair<Station, Station>, List<Station>> = mapOf(
            (Station.RICH to Station.SFIA) to emptyList(),
            (Station.RICH to Station.BERY) to emptyList(),
            (Station.RICH to Station.DUBL) to asStations(Station.BALB),
            (Station.RICH to Station.ANTC) to asStations(Station._19TH),
            (Station.SFIA to Station.RICH) to emptyList(),
            (Station.SFIA to Station.BERY) to asStations(Station.BALB),
            (Station.SFIA to Station.DUBL) to asStations(Station.BALB),
            (Station.SFIA to Station.ANTC) to emptyList(),
            (Station.BERY to Station.RICH) to emptyList(),
            (Station.BERY to Station.SFIA) to asStations(Station.MCAR),
            (Station.BERY to Station.DUBL) to asStations(Station.LAKE),
            (Station.BERY to Station.ANTC) to asStations(Station._19TH),
            (Station.DUBL to Station.RICH) to asStations(Station.BALB),
            (Station.DUBL to Station.SFIA) to asStations(Station.BALB),
            (Station.DUBL to Station.BERY) to asStations(Station.LAKE),
            (Station.DUBL to Station.ANTC) to asStations(Station.LAKE, Station._19TH),
            (Station.ANTC to Station.RICH) to asStations(Station.MCAR),
            (Station.ANTC to Station.SFIA) to emptyList(),
            (Station.ANTC to Station.BERY) to asStations(Station.MCAR),
            (Station.ANTC to Station.DUBL) to asStations(Station.MCAR, Station.LAKE),
        )

        val mismatches = mutableListOf<String>()
        for ((pair, transfers) in expected) {
            val route = routesFor(pair.first, pair.second, TEST_NETWORK).firstOrNull()
            if (route == null || route.transferStations != transfers) {
                mismatches += "${pair.first} -> ${pair.second}: expected=$transfers " +
                    "actual=${route?.transferStations} lines=${route?.lines}"
            }
        }
        assertTrue(mismatches.joinToString("; "), mismatches.isEmpty())
    }

    @Test
    fun routesForUsesDirectRoutesBeforeTransferFallback() {
        val network: BartGtfsNetwork = TEST_NETWORK
        val direct: List<Route> = routesFor(Station.MONT, Station.RICH,
                network)
        assertTrue(containsDirectLine(direct, Line.RED))

        val transfer: List<Route> = routesFor(Station.DUBL, Station.ANTC,
                network)
        assertFalse(transfer.isEmpty())
        assertTrue(transfer.get(0).hasTransfer())
    }

    @Test
    fun routesForIncludesTransferAlternativeAlongsideDirectRoute() {
        val routes: List<Route> = routesFor(Station.DBRK, Station.POWL, TEST_NETWORK)

        assertTrue("routes=" + routes,
                containsRoute(routes, listOf(Line.RED)))
        val orangeYellow: Route? = routeWithLines(routes,
                listOf(Line.ORANGE, Line.YELLOW))
        assertNotNull("routes=" + routes, orangeYellow)
        assertEquals(listOf(Station.MCAR),
                orangeYellow!!.transferStations)
    }

    @Test
    fun stationOnlyAndInvalidQueriesReturnEmptyOrBoardingRoutes() {
        val network: BartGtfsNetwork = TEST_NETWORK
        val stationOnly: List<Route> = routesFor(Station.MONT, null,
                network)
        assertFalse(stationOnly.isEmpty())
        for (route in stationOnly) {
            assertEquals(Station.MONT, route.origin)
            assertNull(route.destination)
            assertFalse(route.hasTransfer())
            assertTrue(route.getStationSequence(route.directLine)
                    .contains(Station.MONT))
        }

        assertTrue(routesFor(null, Station.MONT, network).isEmpty())
        assertTrue(transferRoutes(Station.MONT, null, network)
                .isEmpty())
        assertTrue(routesFor(Station.MONT, Station.MONT, network)
                .isEmpty())
        assertTrue(transferRoutes(Station.SFIA, Station.MLBR, network)
                .isEmpty())
    }

    @Test
    fun routeCopiesAndProtectsTopologyCollections() {
        val directSequence: MutableList<Station> = ArrayList(listOf(
                Station.MONT, Station.EMBR, Station.RICH))
        val direct = Route.direct(Station.MONT, Station.RICH, Line.RED, "n",
                directSequence)
        directSequence.clear()

        assertEquals(3, direct.getStationSequence(Line.RED).size)
        assertThrows(UnsupportedOperationException::class.java,
                { (direct.lines as MutableList).add(Line.BLUE) })
        assertThrows(UnsupportedOperationException::class.java,
                { (direct.getStationSequence(Line.RED) as MutableList).add(Station.DUBL) })

        val lines: MutableList<Line> = ArrayList(listOf(Line.BLUE, Line.ORANGE))
        val transfers: MutableList<Station> = ArrayList(
                listOf(Station.BAYF))
        val sequences: MutableMap<Line, List<Station>> = HashMap()
        sequences.put(Line.BLUE, ArrayList(listOf(
                Station.MONT, Station.BAYF, Station.DUBL)))
        sequences.put(Line.ORANGE, ArrayList(listOf(
                Station.BAYF, Station.RICH)))
        val transfer = Route.transfer(Station.MONT, Station.RICH, lines,
                transfers, "n", sequences)
        lines.clear()
        transfers.clear()
        (sequences[Line.BLUE] as MutableList).clear()

        assertEquals(listOf(Line.BLUE, Line.ORANGE), transfer.lines)
        assertEquals(listOf(Station.BAYF),
                transfer.transferStations)
        assertThrows(UnsupportedOperationException::class.java,
                { (transfer.transferLines as MutableList).add(Line.GREEN) })
        assertThrows(UnsupportedOperationException::class.java,
                { (transfer.transferStations as MutableList).add(Station.MCAR) })
        assertEquals(3, transfer.getStationSequence(Line.BLUE).size)
    }

    @Test
    fun plannerResultsAreImmutable() {
        val directRoutes: List<Route> = routesFor(Station.MONT,
                Station.RICH, TEST_NETWORK)
        assertThrows(UnsupportedOperationException::class.java,
                { (directRoutes as MutableList).clear() })

        val transferRoutes: List<Route> = transferRoutes(Station.DUBL,
                Station.ANTC, TEST_NETWORK)
        assertThrows(UnsupportedOperationException::class.java,
                { (transferRoutes as MutableList).add(transferRoutes[0]) })
    }

    private fun containsDirectLine(routes: List<Route>, line: Line): Boolean {
        for (route in routes) {
            if (!route.hasTransfer() && route.directLine == line) {
                return true
            }
        }
        return false
    }

    private fun containsRoute(routes: List<Route>, lines: List<Line>): Boolean {
        return routeWithLines(routes, lines) != null
    }

    private fun routeWithLines(routes: List<Route>, lines: List<Line>): Route? {
        for (route in routes) {
            if (route.lines.equals(lines)) return route
        }
        return null
    }

    private fun routesFor(origin: Station?, destination: Station?,
                          network: BartGtfsNetwork): List<Route> {
        return Schedule.fromStatic(network, 0L,
                Line.values().toSet())
                .routesFor(origin, destination)
    }

    private fun transferRoutes(origin: Station?, destination: Station?,
                               network: BartGtfsNetwork): List<Route> {
        return Schedule.fromStatic(network, 0L,
                Line.values().toSet())
                .transferRoutes(origin, destination)
    }

    private fun preferredTransferRoutes(origin: Station, destination: Station,
                                        network: BartGtfsNetwork): List<Route> {
        return Schedule.fromStatic(network, 0L,
                Line.values().toSet())
                .preferredTransferRoutes(origin, destination)
    }

    private fun asLines(vararg lines: Line): List<Line> {
        return lines.toList()
    }

    private fun asStations(vararg stations: Station): List<Station> {
        return stations.toList()
    }

    private fun testNetwork(): BartGtfsNetwork {
        val files: MutableMap<String, String> = HashMap()
        val stops = StringBuilder("stop_id,stop_name,zone_id\n")
        for (station in Station.getStationList()) {
            stops.append(station.abbreviation).append(",")
                    .append(station.toString()).append(",")
                    .append(station.abbreviation).append("\n")
        }
        files.put("stops.txt", stops.toString())

        val routes = StringBuilder(
                "route_id,route_short_name,route_long_name\n")
        val trips = StringBuilder("route_id,service_id,trip_id\n")
        val stopTimes = StringBuilder(
                "trip_id,stop_id,stop_sequence\n")
        val routeIdsByLine: MutableMap<Line, String> = HashMap()
        var routeId = 1
        for (line in arrayOf(Line.RED, Line.ORANGE, Line.YELLOW,
                Line.BLUE, Line.GREEN)) {
            for (direction in arrayOf("N", "S")) {
                val id = routeId++.toString()
                if ("N".equals(direction)) {
                    routeIdsByLine.put(line, id)
                }
                routes.append(id).append(",").append(lineName(line)).append("-")
                        .append(direction).append(",").append(line.name)
                        .append("\n")
                val tripId = line.name.lowercase() + "-test-" + direction.lowercase()
                trips.append(id).append(",weekday,").append(tripId)
                        .append("\n")
                val pattern = testPattern(line).toMutableList()
                if ("S".equals(direction)) {
                    pattern.reverse()
                }
                var sequence = 1
                for (station in pattern) {
                    stopTimes.append(tripId).append(",")
                            .append(station.abbreviation).append(",")
                            .append(sequence++).append("\n")
                }
            }
        }
        val transfers = StringBuilder(
                "from_stop_id,to_stop_id,transfer_type,min_transfer_time,"
                        + "from_route_id,to_route_id\n")
        val lines = arrayOf(Line.RED, Line.ORANGE, Line.YELLOW,
                Line.BLUE, Line.GREEN)
        for (first in 0 until lines.size) {
            for (second in 0 until lines.size) {
                if (first == second) {
                    continue
                }
                for (station in testPattern(lines[first])) {
                    if (testPattern(lines[second]).contains(station)) {
                        transfers.append(station.abbreviation).append(",")
                                .append(station.abbreviation).append(",2,0,")
                                .append(routeIdsByLine.get(lines[first])).append(",")
                                .append(routeIdsByLine.get(lines[second])).append("\n")
                    }
                }
            }
        }
        files.put("routes.txt", routes.toString())
        files.put("trips.txt", trips.toString())
        files.put("stop_times.txt", stopTimes.toString())
        files.put("transfers.txt", transfers.toString())
        return BartGtfsNetwork.fromCatalog(GtfsNetworkCatalog.fromFiles(files))
    }

    private fun testPattern(line: Line): List<Station> {
        val abbreviations = when (line) {
            Line.RED -> "sfia,mlbr,sbrn,ssan,colm,daly,balb,glen,24th,16th,civc,powl,mont,embr,woak,12th,19th,mcar,ashb,dbrk,nbrk,plza,deln,rich"
            Line.ORANGE -> "bery,mlpt,warm,frmt,ucty,shay,hayw,bayf,sanl,cols,ftvl,lake,12th,19th,mcar,ashb,dbrk,nbrk,plza,deln,rich"
            Line.YELLOW -> "sfia,sbrn,ssan,colm,daly,balb,glen,24th,16th,civc,powl,mont,embr,woak,12th,19th,mcar,rock,orin,lafy,wcrk,phil,conc,ncon,pitt,pctr,antc"
            Line.BLUE -> "daly,balb,glen,24th,16th,civc,powl,mont,embr,woak,lake,ftvl,cols,sanl,bayf,cast,wdub,dubl"
            Line.GREEN -> "daly,balb,glen,24th,16th,civc,powl,mont,embr,woak,lake,ftvl,cols,sanl,bayf,hayw,shay,ucty,frmt,warm,mlpt,bery"
            else -> error("$line")
        }
        return abbreviations.split(",").map { Station.getByAbbreviation(it)!! }
    }

    private fun lineName(line: Line): String {
        return when (line) {
            Line.RED -> "Red"
            Line.ORANGE -> "Orange"
            Line.YELLOW -> "Yellow"
            Line.BLUE -> "Blue"
            Line.GREEN -> "Green"
            else -> error("$line")
        }
    }
}
