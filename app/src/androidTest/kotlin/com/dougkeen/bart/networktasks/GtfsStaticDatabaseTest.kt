package com.dougkeen.bart.networktasks

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GtfsStaticDatabaseTest {
    private lateinit var database: GtfsStaticDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            GtfsStaticDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun candidateQueryUsesRouteServiceAndTimeWindow() {
        val dao = database.dao()
        dao.insertTrips(
            listOf(
                GtfsTripEntity("in-window", "yellow", "weekday", "n", "Richmond"),
                GtfsTripEntity("outside-window", "yellow", "weekday", "n", "Richmond"),
                GtfsTripEntity("wrong-service", "yellow", "weekend", "n", "Richmond"),
                GtfsTripEntity("wrong-route", "blue", "weekday", "n", "Daly City"),
            )
        )
        dao.insertStopTimes(
            listOf(
                GtfsStopTimeEntity("in-window", "A", 1, 3_600, 3_600),
                GtfsStopTimeEntity("in-window", "B", 2, 4_200, 4_200),
                GtfsStopTimeEntity("outside-window", "A", 1, 7_200, 7_200),
                GtfsStopTimeEntity("outside-window", "B", 2, 7_800, 7_800),
                GtfsStopTimeEntity("wrong-service", "A", 1, 3_600, 3_600),
                GtfsStopTimeEntity("wrong-route", "A", 1, 3_600, 3_600),
            )
        )

        assertEquals(
            listOf("in-window"),
            dao.candidateTripIds(
                routeIds = listOf("yellow"),
                serviceIds = listOf("weekday"),
                minimumSeconds = 3_000,
                maximumSeconds = 5_000,
            )
        )
    }

    @Test
    fun fareQuerySelectsOneCategorySpecificFareWithoutMixingBaseFare() {
        val dao = database.dao()
        dao.insertFares(
            listOf(
                GtfsFareEntity("A>B", "${'$'}7.55"),
                GtfsFareEntity("A>B", "${'$'}3.75", "5"),
            )
        )

        assertEquals("${'$'}7.55", dao.fare("A>B", "")?.price)
        assertEquals("${'$'}3.75", dao.fare("A>B", "5")?.price)
        assertEquals(null, dao.fare("A>B", "2"))
    }

    @Test
    fun staticDataFindsRoomDatabaseAfterProcessRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databasePath = context.getDatabasePath("gtfs_static_schedule.db")
        val databasePreviouslyExisted = databasePath.isFile

        try {
            if (!databasePreviouslyExisted) {
                databasePath.parentFile?.mkdirs()
                databasePath.writeBytes(byteArrayOf(1))
            }

            assertTrue(GtfsStaticData(context).hasDatabaseCache())
        } finally {
            if (!databasePreviouslyExisted) {
                databasePath.delete()
            }
        }
    }
}
