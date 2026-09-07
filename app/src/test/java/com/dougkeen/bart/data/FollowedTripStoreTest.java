package com.dougkeen.bart.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Station;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

public class FollowedTripStoreTest {
    private File directory;
    private File storageFile;
    private FollowedTripStore store;

    @Before
    public void setUp() throws Exception {
        directory = Files.createTempDirectory("bart-followed-trip").toFile();
        storageFile = new File(directory, "followed_trip.json");
        store = new FollowedTripStore(storageFile);
    }

    @After
    public void tearDown() {
        delete(directory);
    }

    @Test
    public void saveAndLoadRestoresTheTripAcrossStoreInstances() {
        Departure original = departure();

        store.save(original);
        Departure restored = new FollowedTripStore(storageFile).load();

        assertEquals(Station.MONT, restored.getOrigin());
        assertEquals(Station.RICH, restored.getPassengerDestination());
        assertEquals(Line.RED, restored.getLine());
        assertEquals("trip-1", restored.getTripLegs().get(0).getTripId());
        assertTrue(storageFile.exists());
    }

    @Test
    public void clearingRemovesTheDurableState() {
        store.save(departure());
        store.save(null);

        assertFalse(storageFile.exists());
        assertNull(store.load());
    }

    @Test
    public void malformedStateIsDiscardedInsteadOfRetriedForever() throws Exception {
        Files.write(storageFile.toPath(), "not-json".getBytes());

        assertNull(store.load());
        assertFalse(storageFile.exists());
    }

    private static Departure departure() {
        Departure departure = new Departure();
        departure.setOrigin(Station.MONT);
        departure.setTrainDestination(Station.RICH);
        departure.setPassengerDestination(Station.RICH);
        departure.setLine(Line.RED);
        departure.setDirection("n");
        departure.setPlatform("2");
        departure.setMinEstimate(4_000_000_000L);
        departure.setMaxEstimate(4_000_060_000L);

        com.dougkeen.bart.model.TripLeg leg = new com.dougkeen.bart.model.TripLeg(
                Line.RED, Station.MONT, Station.RICH, Station.RICH, "trip-1",
                0L, 0L, java.util.Collections.emptyList());
        departure.setTripLegs(java.util.Collections.singletonList(leg));
        return departure;
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }
}
