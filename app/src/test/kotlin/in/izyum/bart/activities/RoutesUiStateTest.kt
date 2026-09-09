package `in`.izyum.bart.activities

import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.StationPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoutesUiStateTest {
    @Test
    fun stateCopiesAndFreezesRouteCollections() {
        val favorite = StationPair(Station.CAST, Station.MLPT)
        val favorites = mutableListOf(favorite)
        val state = RoutesUiState(favorites = favorites)

        favorites.clear()

        assertEquals(listOf(favorite), state.favorites)
        assertThrows(UnsupportedOperationException::class.java) {
            (state.favorites as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (state.firstDepartures as MutableMap).clear()
        }
    }
}
