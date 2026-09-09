package `in`.izyum.bart.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DepartureTest {
    @Test
    fun valuesAndTripLegCollectionsAreImmutable() {
        val departure = departure(1_000_000L, 1_060_000L, "trip-1")
        val changed = departure.copy(minEstimate = 2_000_000L, tripLegs = emptyList())
        assertEquals(1_000_000L, departure.minEstimate)
        assertEquals(1, departure.tripLegs.size)
        assertEquals(2_000_000L, changed.minEstimate)
        assertThrows(UnsupportedOperationException::class.java) {
            (departure.tripLegs as MutableList<TripLeg>).clear()
        }
    }

    @Test
    fun replacementReturnsFreshValuesAndReconcilesEstimates() {
        val first = departure(1_000_000L, 1_060_000L, "trip-1")
        val second = departure(1_020_000L, 1_080_000L, "trip-1")
        val firstValue = Departure.replaceFeed(emptyList(), listOf(first)) { 900_000L }[0]
        val secondValue = Departure.replaceFeed(listOf(firstValue), listOf(second)) { 900_000L }[0]
        assertNotSame(firstValue, secondValue)
        assertEquals(1_000_000L, firstValue.minEstimate)
        assertEquals(1_020_000L, secondValue.minEstimate)
        assertEquals(1_060_000L, secondValue.maxEstimate)
    }

    @Test
    fun followedTripMergeReturnsReplacementWithoutMutatingPreviousValue() {
        val followed = departure(1_000_000L, 1_060_000L, "trip-1")
        val feedUpdate = departure(1_020_000L, 1_080_000L, "trip-1")
        val replacement = Departure.merge(followed, feedUpdate, false) { 900_000L }
        assertNotSame(followed, replacement)
        assertEquals(1_000_000L, followed.minEstimate)
        assertEquals(1_020_000L, replacement.minEstimate)
    }

    @Test
    fun identitySurvivesEstimateChangesButTracksTripLegChanges() {
        val first = departure(1_000L, 2_000L, "trip-1")
        val refreshed = departure(2_000L, 3_000L, "trip-1")
        val different = departure(1_000L, 2_000L, "trip-2")
        assertEquals(first.identity, refreshed.identity)
        assertNotEquals(first.identity, different.identity)
    }

    private fun departure(minEstimate: Long, maxEstimate: Long, tripId: String): Departure =
        Departure.builder()
            .setOrigin(Station.CAST).setTrainDestination(Station.MLPT)
            .setPassengerDestination(Station.MLPT).setLine(Line.ORANGE)
            .setDirection("north").setPlatform("1")
            .setMinEstimate(minEstimate).setMaxEstimate(maxEstimate)
            .setTripLegs(listOf(TripLeg(
                Line.ORANGE, Station.CAST, Station.MLPT, Station.MLPT,
                tripId, minEstimate, maxEstimate, emptyList(),
            ))).build()
}
