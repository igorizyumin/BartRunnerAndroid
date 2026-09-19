package `in`.izyum.bart.data

import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Itinerary
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FollowedTripStoreTest {
    private lateinit var directory: File
    private lateinit var storageFile: File
    private lateinit var store: FollowedTripStore

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bart-followed-trip").toFile()
        storageFile = File(directory, "followed_trip.json")
        store = FollowedTripStore(storageFile)
    }

    @After
    fun tearDown() {
        delete(directory)
    }

    @Test
    fun saveAndLoadRestoresAnItineraryAcrossStoreInstances() {
        val itinerary = Itinerary.fromDeparture(departure())!!
        store.saveItinerary(itinerary)

        val restored = FollowedTripStore(storageFile).loadItinerary()!!

        assertEquals(itinerary.origin, restored.origin)
        assertEquals(itinerary.destination, restored.destination)
        assertEquals("trip-1", restored.legs.single().tripId)
    }

    @Test
    fun clearingRemovesTheDurableState() {
        store.saveItinerary(Itinerary.fromDeparture(departure()))
        store.saveItinerary(null)
        assertFalse(storageFile.exists())
        assertNull(store.loadItinerary())
    }

    @Test
    fun malformedStateIsDiscardedInsteadOfRetriedForever() {
        Files.write(storageFile.toPath(), "not-json".toByteArray())
        assertNull(store.loadItinerary())
        assertFalse(storageFile.exists())
    }

    private fun departure(): Departure = Departure.builder()
        .setOrigin(Station.MONT).setTrainDestination(Station.RICH)
        .setPassengerDestination(Station.RICH).setLine(Line.RED)
        .setPlatform("2")
        .setMinEstimate(4_000_000_000L).setMaxEstimate(4_000_060_000L)
        .setTripLegs(listOf(TripLeg(
            Line.RED, Station.MONT, Station.RICH, Station.RICH,
            "trip-1", 0L, 0L, emptyList(),
        ))).build()

    private fun delete(file: File?) {
        if (file == null || !file.exists()) return
        file.listFiles()?.forEach(::delete)
        file.delete()
    }
}
