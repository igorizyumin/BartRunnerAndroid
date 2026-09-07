package com.dougkeen.bart.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class DepartureTest {
    @Test
    public void valuesAndTripLegCollectionsAreImmutable() {
        Departure departure = departure(1_000_000L, 1_060_000L, "trip-1");
        Departure changed = departure.copy(
                departure.getOrigin(), departure.getTrainDestination(),
                departure.getPassengerDestination(), departure.getLine(),
                departure.getTrainDestinationColorHex(),
                departure.getTrainDestinationColorText(), departure.getPlatform(),
                departure.getDirection(), departure.isBikeAllowed(),
                departure.getTrainLength(), departure.getRequiresTransfer(),
                departure.isTransferScheduled(), departure.isLimited(),
                departure.isCanceled(), departure.getMinutes(), 2_000_000L,
                departure.getMaxEstimate(), departure.getEstimatedTripTime(),
                departure.beganAsDeparted(), departure.getArrivalTimeOverride(),
                departure.isListedInETDs(), Collections.emptyList());

        assertEquals(1_000_000L, departure.getMinEstimate());
        assertEquals(1, departure.getTripLegs().size());
        assertEquals(2_000_000L, changed.getMinEstimate());
        assertThrows(UnsupportedOperationException.class,
                () -> departure.getTripLegs().clear());
    }

    @Test
    public void replacementReturnsFreshValuesAndReconcilesEstimates() {
        Departure first = departure(1_000_000L, 1_060_000L, "trip-1");
        Departure second = departure(1_020_000L, 1_080_000L, "trip-1");

        Departure firstValue = Departure.replaceFeed(
                Collections.emptyList(), Collections.singletonList(first),
                () -> 900_000L).get(0);
        Departure secondValue = Departure.replaceFeed(
                Collections.singletonList(firstValue),
                Collections.singletonList(second),
                () -> 900_000L).get(0);

        assertNotSame(firstValue, secondValue);
        assertEquals(1_000_000L, firstValue.getMinEstimate());
        assertEquals(1_020_000L, secondValue.getMinEstimate());
        assertEquals(1_060_000L, secondValue.getMaxEstimate());
    }

    @Test
    public void followedTripMergeReturnsReplacementWithoutMutatingPreviousValue() {
        Departure followed = departure(1_000_000L, 1_060_000L, "trip-1");
        Departure feedUpdate = departure(1_020_000L, 1_080_000L, "trip-1");

        Departure replacement = Departure.merge(
                followed, feedUpdate, false, () -> 900_000L);

        assertNotSame(followed, replacement);
        assertEquals(1_000_000L, followed.getMinEstimate());
        assertEquals(1_020_000L, replacement.getMinEstimate());
    }

    @Test
    public void identitySurvivesEstimateChangesButTracksTripLegChanges() {
        Departure first = departure(1_000L, 2_000L, "trip-1");
        Departure refreshed = departure(2_000L, 3_000L, "trip-1");
        Departure different = departure(1_000L, 2_000L, "trip-2");

        assertEquals(first.getIdentity(), refreshed.getIdentity());
        assertNotEquals(first.getIdentity(), different.getIdentity());
    }

    private static Departure departure(long minEstimate, long maxEstimate,
                                       String tripId) {
        return Departure.builder()
                .setOrigin(Station.CAST)
                .setTrainDestination(Station.MLPT)
                .setPassengerDestination(Station.MLPT)
                .setLine(Line.ORANGE)
                .setDirection("north")
                .setPlatform("1")
                .setMinEstimate(minEstimate)
                .setMaxEstimate(maxEstimate)
                .setTripLegs(Collections.singletonList(new TripLeg(
                        Line.ORANGE,
                        Station.CAST,
                        Station.MLPT,
                        Station.MLPT,
                        tripId,
                        minEstimate,
                        maxEstimate,
                        Collections.emptyList())))
                .build();
    }
}
