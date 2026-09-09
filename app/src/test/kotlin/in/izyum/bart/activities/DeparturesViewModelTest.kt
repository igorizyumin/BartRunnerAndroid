package `in`.izyum.bart.activities

import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class DeparturesViewModelTest {
    @Test
    fun replacementStateDropsDeparturesMissingFromTheLatestFeed() {
        val viewModel = DeparturesViewModel { 900_000L }
        val first = departure("trip-1", 1_000_000L)
        val second = departure("trip-2", 1_060_000L)

        val initial = viewModel.replace(listOf(first, second))
        val replacement = viewModel.replace(listOf(departure("trip-2", 1_080_000L)))

        assertEquals(2, initial.size)
        assertEquals(1, replacement.size)
        assertEquals("trip-2", replacement[0].tripLegs[0].tripId)
        assertNotSame(initial[1], replacement[0])
        assertEquals(DeparturesViewModel.Status.CONTENT, viewModel.getState().status)

        viewModel.clear()
        assertEquals(DeparturesViewModel.Status.EMPTY, viewModel.getState().status)
        assertEquals(0, viewModel.getState().departures.size)
    }

    private fun departure(tripId: String, estimate: Long): Departure = Departure.builder()
        .setOrigin(Station.CAST)
        .setTrainDestination(Station.MLPT)
        .setLine(Line.ORANGE)
        .setDirection("north")
        .setPlatform("1")
        .setMinEstimate(estimate)
        .setMaxEstimate(estimate + 60_000L)
        .setTripLegs(listOf(TripLeg(
            Line.ORANGE,
            Station.CAST,
            Station.MLPT,
            Station.MLPT,
            tripId,
            estimate,
            estimate + 30 * 60_000L,
            emptyList(),
        )))
        .build()
}
