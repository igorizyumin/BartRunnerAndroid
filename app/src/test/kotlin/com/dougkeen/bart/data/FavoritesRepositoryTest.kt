package com.dougkeen.bart.data

import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.StationPair
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesRepositoryTest {
    @Test
    fun addingExistingRouteDoesNotCreateDuplicate() {
        val saved = StationPair(Station._12TH, Station.SFIA, "$4.75", 1L, 0, 0)
        val favorites = mutableListOf(saved)

        addFavoriteIfAbsent(favorites, saved.withFare(null, 0L))

        assertEquals(listOf(saved), favorites)
    }

    @Test
    fun duplicatePersistedRoutesAreCollapsedWhileKeepingFirstEntry() {
        val saved = StationPair(Station._12TH, Station.SFIA, "$4.75", 1L, 0, 0)
        val duplicate = saved.withFare(null, 0L)
        val other = StationPair(Station.CAST, Station.PITT)

        assertEquals(
            listOf(saved, other),
            deduplicateFavorites(listOf(saved, duplicate, other)),
        )
    }
}
