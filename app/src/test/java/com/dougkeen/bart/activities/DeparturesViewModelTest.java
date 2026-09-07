package com.dougkeen.bart.activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.TripLeg;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DeparturesViewModelTest {
    @Test
    public void replacementStateDropsDeparturesMissingFromTheLatestFeed() {
        DeparturesViewModel viewModel = new DeparturesViewModel();
        Departure first = departure("trip-1", 1_000_000L);
        Departure second = departure("trip-2", 1_060_000L);

        List<Departure> initial = viewModel.replace(Arrays.asList(first, second));
        List<Departure> replacement = viewModel.replace(
                Collections.singletonList(departure("trip-2", 1_080_000L)));

        assertEquals(2, initial.size());
        assertEquals(1, replacement.size());
        assertEquals("trip-2", replacement.get(0).getTripLegs().get(0).getTripId());
        assertNotSame(initial.get(1), replacement.get(0));
        assertEquals(DeparturesViewModel.Status.CONTENT,
                viewModel.getState().getStatus());

        viewModel.clear();

        assertEquals(DeparturesViewModel.Status.EMPTY,
                viewModel.getState().getStatus());
        assertEquals(0, viewModel.getState().getDepartures().size());
    }

    private static Departure departure(String tripId, long estimate) {
        return Departure.builder()
                .setOrigin(Station.CAST)
                .setTrainDestination(Station.MLPT)
                .setLine(Line.ORANGE)
                .setDirection("north")
                .setPlatform("1")
                .setMinEstimate(estimate)
                .setMaxEstimate(estimate + 60_000L)
                .setTripLegs(Collections.singletonList(new TripLeg(
                Line.ORANGE,
                Station.CAST,
                Station.MLPT,
                Station.MLPT,
                tripId,
                estimate,
                estimate + 30 * 60_000L,
                Collections.emptyList())))
                .build();
    }
}
