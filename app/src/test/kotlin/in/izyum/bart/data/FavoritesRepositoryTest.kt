package `in`.izyum.bart.data

import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.StationPair
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesRepositoryTest {
    @Test
    fun addingExistingRouteDoesNotCreateDuplicate() {
        val saved = StationPair(Station._12TH, Station.SFIA)
        val favorites = mutableListOf(saved)

        addFavoriteIfAbsent(favorites, StationPair(Station._12TH, Station.SFIA))

        assertEquals(listOf(saved), favorites)
    }

    @Test
    fun duplicatePersistedRoutesAreCollapsedWhileKeepingFirstEntry() {
        val saved = StationPair(Station._12TH, Station.SFIA)
        val duplicate = StationPair(Station._12TH, Station.SFIA)
        val other = StationPair(Station.CAST, Station.PITT)

        assertEquals(
            listOf(saved, other),
            deduplicateFavorites(listOf(saved, duplicate, other)),
        )
    }
}
