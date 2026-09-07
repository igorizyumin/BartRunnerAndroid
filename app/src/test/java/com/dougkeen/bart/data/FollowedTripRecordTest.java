package com.dougkeen.bart.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.model.TripStop;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

public class FollowedTripRecordTest {
    @Test
    public void jsonRoundTripRestoresFollowedTripState() throws Exception {
        TripLeg leg = new TripLeg(Line.RED, Station.MONT, Station.RICH,
                Station.RICH, "red-1", 1_000_000L, 1_500_000L,
                java.util.Collections.singletonList(
                        new TripStop(Station.EMBR, 1_200_000L, 1_210_000L)));
        Departure original = Departure.builder()
                .setOrigin(Station.MONT)
                .setTrainDestination(Station.RICH)
                .setPassengerDestination(Station.DUBL)
                .setLine(Line.RED)
                .setTrainDestinationColorHex("#ff0000")
                .setTrainDestinationColorText("Red")
                .setPlatform("2")
                .setDirection("north")
                .setBikeAllowed(true)
                .setTrainLength("10")
                .setRequiresTransfer(true)
                .setTransferScheduled(true)
                .setLimited(true)
                .setCanceled(false)
                .setListedInETDs(false)
                .setMinutes(8)
                .setMinEstimate(1_000_000L)
                .setMaxEstimate(1_060_000L)
                .setArrivalTimeOverride(1_800_000L)
                .setEstimatedTripTime(600)
                .setTripLegs(java.util.Collections.singletonList(leg))
                .build();

        ObjectMapper mapper = new ObjectMapper();
        byte[] bytes = mapper.writeValueAsBytes(
                FollowedTripRecord.fromDeparture(original));
        FollowedTripRecord record = mapper.readValue(bytes,
                FollowedTripRecord.class);
        Departure restored = record.toDeparture();

        assertEquals(FollowedTripRecord.CURRENT_VERSION, record.version);
        assertEquals(Station.MONT, restored.getOrigin());
        assertEquals(Station.DUBL, restored.getPassengerDestination());
        assertEquals(Line.RED, restored.getLine());
        assertEquals("#ff0000", restored.getTrainDestinationColorHex());
        assertEquals("2", restored.getPlatform());
        assertEquals(1_000_000L, restored.getMinEstimate());
        assertEquals(1, restored.getTripLegs().size());
        assertEquals("red-1", restored.getTripLegs().get(0).getTripId());
        assertNotNull(restored.getTripLegs().get(0).getStops());
        assertEquals(1, restored.getTripLegs().get(0).getStops().size());
        assertEquals(Station.EMBR,
                restored.getTripLegs().get(0).getStops().get(0).getStation());
    }
}
