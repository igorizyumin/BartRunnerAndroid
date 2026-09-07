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
        Departure original = new Departure();
        original.setOrigin(Station.MONT);
        original.setTrainDestination(Station.RICH);
        original.setPassengerDestination(Station.DUBL);
        original.setLine(Line.RED);
        original.setTrainDestinationColorHex("#ff0000");
        original.setTrainDestinationColorText("Red");
        original.setPlatform("2");
        original.setDirection("north");
        original.setBikeAllowed(true);
        original.setTrainLength("10");
        original.setRequiresTransfer(true);
        original.setTransferScheduled(true);
        original.setLimited(true);
        original.setCanceled(false);
        original.setListedInETDs(false);
        original.setMinutes(8);
        original.setMinEstimate(1_000_000L);
        original.setMaxEstimate(1_060_000L);
        original.setArrivalTimeOverride(1_800_000L);
        original.setEstimatedTripTime(600);

        TripLeg leg = new TripLeg(Line.RED, Station.MONT, Station.RICH,
                Station.RICH, "red-1", 1_000_000L, 1_500_000L,
                java.util.Collections.singletonList(
                        new TripStop(Station.EMBR, 1_200_000L, 1_210_000L)));
        original.setTripLegs(java.util.Collections.singletonList(leg));

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
