package `in`.izyum.bart.networktasks

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import `in`.izyum.bart.transit.gtfs.GtfsNetworkCatalog

@Entity(tableName = "gtfs_feed")
data class GtfsFeedEntity(
    @PrimaryKey val id: Int = 1,
    val importedAtMillis: Long,
    val feedVersion: String?,
)

@Entity(tableName = "gtfs_stop")
data class GtfsStopEntity(
    @PrimaryKey val stopId: String,
    val name: String?,
    val parentStationId: String?,
    val zoneId: String?,
)

@Entity(
    tableName = "gtfs_route",
    indices = [Index(value = ["routeId"])],
)
data class GtfsRouteEntity(
    @PrimaryKey val routeId: String,
    val shortName: String?,
    val longName: String?,
    val color: String?,
    val textColor: String?,
    val routeType: Int?,
)

@Entity(
    tableName = "gtfs_trip",
    indices = [Index(value = ["routeId"]), Index(value = ["serviceId"])],
)
data class GtfsTripEntity(
    @PrimaryKey val tripId: String,
    val routeId: String,
    val serviceId: String?,
    val directionId: String?,
    val headsign: String?,
)

@Entity(
    tableName = "gtfs_stop_time",
    primaryKeys = ["tripId", "sequence"],
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["tripId", "departureSeconds"]),
        Index(value = ["tripId", "arrivalSeconds"]),
    ],
)
data class GtfsStopTimeEntity(
    val tripId: String,
    val stopId: String,
    val sequence: Int,
    val arrivalSeconds: Int?,
    val departureSeconds: Int?,
)

@Entity(tableName = "gtfs_calendar")
data class GtfsCalendarEntity(
    @PrimaryKey val serviceId: String,
    val monday: Boolean,
    val tuesday: Boolean,
    val wednesday: Boolean,
    val thursday: Boolean,
    val friday: Boolean,
    val saturday: Boolean,
    val sunday: Boolean,
    val startDate: String,
    val endDate: String,
)

@Entity(
    tableName = "gtfs_calendar_date",
    primaryKeys = ["serviceId", "date"],
    indices = [Index(value = ["date"])],
)
data class GtfsCalendarDateEntity(
    val serviceId: String,
    val date: String,
    val exceptionType: Int,
)

@Entity(tableName = "gtfs_transfer")
data class GtfsTransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromStopId: String,
    val toStopId: String,
    val transferType: Int,
    val minimumTransferSeconds: Int?,
    val fromRouteId: String?,
    val toRouteId: String?,
)

@Entity(tableName = "gtfs_pattern")
data class GtfsPatternEntity(
    @PrimaryKey val patternId: Int,
    val routeId: String,
    val directionId: String?,
)

@Entity(
    tableName = "gtfs_pattern_stop",
    primaryKeys = ["patternId", "sequence"],
    indices = [Index(value = ["stopId"])],
)
data class GtfsPatternStopEntity(
    val patternId: Int,
    val sequence: Int,
    val stopId: String,
)

@Entity(tableName = "gtfs_pattern_trip", primaryKeys = ["patternId", "tripId"])
data class GtfsPatternTripEntity(
    val patternId: Int,
    val tripId: String,
)

@Entity(tableName = "gtfs_pattern_headsign", primaryKeys = ["patternId", "headsign"])
data class GtfsPatternHeadsignEntity(
    val patternId: Int,
    val headsign: String,
)

@Entity(
    tableName = "gtfs_fare",
    primaryKeys = ["key", "riderCategoryId"],
)
data class GtfsFareEntity(
    val key: String,
    val price: String,
    val riderCategoryId: String = BASE_RIDER_CATEGORY_ID,
)

@Entity(tableName = "gtfs_rider_category")
data class GtfsRiderCategoryEntity(
    @PrimaryKey val riderCategoryId: String,
    val description: String,
)

internal const val BASE_RIDER_CATEGORY_ID = ""

@Dao
abstract class GtfsStaticDao {
    @Query("SELECT * FROM gtfs_feed WHERE id = 1 LIMIT 1")
    abstract fun metadata(): GtfsFeedEntity?

    @Query("SELECT * FROM gtfs_stop")
    abstract fun stops(): List<GtfsStopEntity>

    @Query("SELECT * FROM gtfs_route")
    abstract fun routes(): List<GtfsRouteEntity>

    @Query("SELECT * FROM gtfs_trip")
    abstract fun trips(): List<GtfsTripEntity>

    @Query("SELECT * FROM gtfs_calendar")
    abstract fun calendars(): List<GtfsCalendarEntity>

    @Query("SELECT * FROM gtfs_calendar_date")
    abstract fun calendarDates(): List<GtfsCalendarDateEntity>

    @Query("SELECT * FROM gtfs_transfer")
    abstract fun transfers(): List<GtfsTransferEntity>

    @Query("SELECT * FROM gtfs_pattern")
    abstract fun patterns(): List<GtfsPatternEntity>

    @Query("SELECT * FROM gtfs_pattern_stop ORDER BY patternId, sequence")
    abstract fun patternStops(): List<GtfsPatternStopEntity>

    @Query("SELECT * FROM gtfs_pattern_trip")
    abstract fun patternTrips(): List<GtfsPatternTripEntity>

    @Query("SELECT * FROM gtfs_pattern_headsign")
    abstract fun patternHeadsigns(): List<GtfsPatternHeadsignEntity>

    @Query("SELECT * FROM gtfs_fare")
    abstract fun fares(): List<GtfsFareEntity>

    @Query("SELECT * FROM gtfs_fare WHERE `key` = :key AND riderCategoryId = :riderCategoryId LIMIT 1")
    abstract fun fare(key: String, riderCategoryId: String): GtfsFareEntity?

    @Query("SELECT * FROM gtfs_rider_category ORDER BY riderCategoryId")
    abstract fun riderCategories(): List<GtfsRiderCategoryEntity>

    @Query(
        """
        SELECT DISTINCT stopTime.tripId
        FROM gtfs_stop_time AS stopTime
        INNER JOIN gtfs_trip AS trip ON trip.tripId = stopTime.tripId
        WHERE trip.routeId IN (:routeIds)
          AND trip.serviceId IN (:serviceIds)
          AND (
              stopTime.departureSeconds BETWEEN :minimumSeconds AND :maximumSeconds
              OR stopTime.arrivalSeconds BETWEEN :minimumSeconds AND :maximumSeconds
          )
        """
    )
    abstract fun candidateTripIds(
        routeIds: List<String>,
        serviceIds: List<String>,
        minimumSeconds: Int,
        maximumSeconds: Int,
    ): List<String>

    @Query("SELECT * FROM gtfs_trip WHERE tripId IN (:tripIds)")
    abstract fun tripsById(tripIds: List<String>): List<GtfsTripEntity>

    @Query(
        "SELECT * FROM gtfs_stop_time "
            + "WHERE tripId IN (:tripIds) ORDER BY tripId, sequence"
    )
    abstract fun stopTimesByTripId(tripIds: List<String>): List<GtfsStopTimeEntity>

    @Query("DELETE FROM gtfs_feed")
    abstract fun clearFeed()

    @Query("DELETE FROM gtfs_stop")
    abstract fun clearStops()

    @Query("DELETE FROM gtfs_route")
    abstract fun clearRoutes()

    @Query("DELETE FROM gtfs_trip")
    abstract fun clearTrips()

    @Query("DELETE FROM gtfs_stop_time")
    abstract fun clearStopTimes()

    @Query("DELETE FROM gtfs_calendar")
    abstract fun clearCalendars()

    @Query("DELETE FROM gtfs_calendar_date")
    abstract fun clearCalendarDates()

    @Query("DELETE FROM gtfs_transfer")
    abstract fun clearTransfers()

    @Query("DELETE FROM gtfs_pattern")
    abstract fun clearPatterns()

    @Query("DELETE FROM gtfs_pattern_stop")
    abstract fun clearPatternStops()

    @Query("DELETE FROM gtfs_pattern_trip")
    abstract fun clearPatternTrips()

    @Query("DELETE FROM gtfs_pattern_headsign")
    abstract fun clearPatternHeadsigns()

    @Query("DELETE FROM gtfs_fare")
    abstract fun clearFares()

    @Query("DELETE FROM gtfs_rider_category")
    abstract fun clearRiderCategories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertFeed(value: GtfsFeedEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertStops(values: List<GtfsStopEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertRoutes(values: List<GtfsRouteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertTrips(values: List<GtfsTripEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertStopTimes(values: List<GtfsStopTimeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertCalendars(values: List<GtfsCalendarEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertCalendarDates(values: List<GtfsCalendarDateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertTransfers(values: List<GtfsTransferEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertPatterns(values: List<GtfsPatternEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertPatternStops(values: List<GtfsPatternStopEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertPatternTrips(values: List<GtfsPatternTripEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertPatternHeadsigns(values: List<GtfsPatternHeadsignEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertFares(values: List<GtfsFareEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertRiderCategories(values: List<GtfsRiderCategoryEntity>)

    @Transaction
    open fun replace(snapshot: GtfsDatabaseSnapshot) {
        clearFeed()
        clearStops()
        clearRoutes()
        clearTrips()
        clearStopTimes()
        clearCalendars()
        clearCalendarDates()
        clearTransfers()
        clearPatterns()
        clearPatternStops()
        clearPatternTrips()
        clearPatternHeadsigns()
        clearFares()
        clearRiderCategories()
        insertStops(snapshot.stops)
        insertRoutes(snapshot.routes)
        insertTrips(snapshot.trips)
        insertStopTimes(snapshot.stopTimes)
        insertCalendars(snapshot.calendars)
        insertCalendarDates(snapshot.calendarDates)
        insertTransfers(snapshot.transfers)
        insertPatterns(snapshot.patterns)
        insertPatternStops(snapshot.patternStops)
        insertPatternTrips(snapshot.patternTrips)
        insertPatternHeadsigns(snapshot.patternHeadsigns)
        insertFares(snapshot.fares)
        insertRiderCategories(snapshot.riderCategories)
        insertFeed(snapshot.feed)
    }
}

@Database(
    entities = [
        GtfsFeedEntity::class,
        GtfsStopEntity::class,
        GtfsRouteEntity::class,
        GtfsTripEntity::class,
        GtfsStopTimeEntity::class,
        GtfsCalendarEntity::class,
        GtfsCalendarDateEntity::class,
        GtfsTransferEntity::class,
        GtfsPatternEntity::class,
        GtfsPatternStopEntity::class,
        GtfsPatternTripEntity::class,
        GtfsPatternHeadsignEntity::class,
        GtfsFareEntity::class,
        GtfsRiderCategoryEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class GtfsStaticDatabase : RoomDatabase() {
    abstract fun dao(): GtfsStaticDao
}

data class GtfsDatabaseSnapshot(
    val feed: GtfsFeedEntity,
    val stops: List<GtfsStopEntity>,
    val routes: List<GtfsRouteEntity>,
    val trips: List<GtfsTripEntity>,
    val stopTimes: List<GtfsStopTimeEntity>,
    val calendars: List<GtfsCalendarEntity>,
    val calendarDates: List<GtfsCalendarDateEntity>,
    val transfers: List<GtfsTransferEntity>,
    val patterns: List<GtfsPatternEntity>,
    val patternStops: List<GtfsPatternStopEntity>,
    val patternTrips: List<GtfsPatternTripEntity>,
    val patternHeadsigns: List<GtfsPatternHeadsignEntity>,
    val fares: List<GtfsFareEntity>,
    val riderCategories: List<GtfsRiderCategoryEntity> = emptyList(),
) {
    companion object {
        fun fromCatalog(
            catalog: GtfsNetworkCatalog,
            fares: Map<String, String>,
            feedVersion: String?,
            importedAtMillis: Long,
            discountedFares: Map<String, Map<String, String>> = emptyMap(),
            riderCategories: List<GtfsRiderCategoryEntity> = emptyList(),
        ): GtfsDatabaseSnapshot {
            val patterns = catalog.patterns.mapIndexed { index, pattern ->
                GtfsPatternEntity(index, pattern.routeId, pattern.directionId)
            }
            val patternStops = catalog.patterns.flatMapIndexed { index, pattern ->
                pattern.stopIds.mapIndexed { sequence, stopId ->
                    GtfsPatternStopEntity(index, sequence, stopId)
                }
            }
            val patternTrips = catalog.patterns.flatMapIndexed { index, pattern ->
                pattern.tripIds.map { tripId -> GtfsPatternTripEntity(index, tripId) }
            }
            val patternHeadsigns = catalog.patterns.flatMapIndexed { index, pattern ->
                pattern.headsigns.map { headsign -> GtfsPatternHeadsignEntity(index, headsign) }
            }
            return GtfsDatabaseSnapshot(
                feed = GtfsFeedEntity(
                    importedAtMillis = importedAtMillis,
                    feedVersion = feedVersion,
                ),
                stops = catalog.stopsById.values.map { stop ->
                    GtfsStopEntity(stop.stopId, stop.name, stop.parentStationId, stop.zoneId)
                },
                routes = catalog.routesById.values.map { route ->
                    GtfsRouteEntity(
                        route.routeId,
                        route.shortName,
                        route.longName,
                        route.color,
                        route.textColor,
                        route.routeType,
                    )
                },
                trips = catalog.tripsById.values.map { trip ->
                    GtfsTripEntity(
                        trip.tripId,
                        trip.routeId,
                        trip.serviceId,
                        trip.directionId,
                        trip.headsign,
                    )
                },
                stopTimes = catalog.stopTimesByTripId.flatMap { (tripId, stopTimes) ->
                    stopTimes.map { stopTime ->
                        GtfsStopTimeEntity(
                            tripId,
                            stopTime.stopId,
                            stopTime.sequence,
                            stopTime.arrivalSeconds,
                            stopTime.departureSeconds,
                        )
                    }
                },
                calendars = catalog.calendarsByServiceId.values.map { calendar ->
                    GtfsCalendarEntity(
                        calendar.serviceId,
                        calendar.monday,
                        calendar.tuesday,
                        calendar.wednesday,
                        calendar.thursday,
                        calendar.friday,
                        calendar.saturday,
                        calendar.sunday,
                        calendar.startDate.toString(),
                        calendar.endDate.toString(),
                    )
                },
                calendarDates = catalog.calendarDatesByServiceId.flatMap { (serviceId, dates) ->
                    dates.map { (date, exceptionType) ->
                        GtfsCalendarDateEntity(serviceId, date.toString(), exceptionType)
                    }
                },
                transfers = catalog.transfers.map { transfer ->
                    GtfsTransferEntity(
                        fromStopId = transfer.fromStopId,
                        toStopId = transfer.toStopId,
                        transferType = transfer.transferType,
                        minimumTransferSeconds = transfer.minimumTransferSeconds,
                        fromRouteId = transfer.fromRouteId,
                        toRouteId = transfer.toRouteId,
                    )
                },
                patterns = patterns,
                patternStops = patternStops,
                patternTrips = patternTrips,
                patternHeadsigns = patternHeadsigns,
                fares = fares.map { (key, price) -> GtfsFareEntity(key, price) } +
                    discountedFares.flatMap { (key, categoryPrices) ->
                        categoryPrices.map { (categoryId, price) ->
                            GtfsFareEntity(key, price, categoryId)
                        }
                    },
                riderCategories = riderCategories,
            )
        }
    }
}

internal fun GtfsStaticDao.readNetworkParts(): GtfsNetworkParts = GtfsNetworkParts(
    metadata = metadata() ?: error("GTFS database metadata is missing"),
    stops = stops(),
    routes = routes(),
    trips = trips(),
    calendars = calendars(),
    calendarDates = calendarDates(),
    transfers = transfers(),
    patterns = patterns(),
    patternStops = patternStops(),
    patternTrips = patternTrips(),
    patternHeadsigns = patternHeadsigns(),
)

internal data class GtfsNetworkParts(
    val metadata: GtfsFeedEntity,
    val stops: List<GtfsStopEntity>,
    val routes: List<GtfsRouteEntity>,
    val trips: List<GtfsTripEntity>,
    val calendars: List<GtfsCalendarEntity>,
    val calendarDates: List<GtfsCalendarDateEntity>,
    val transfers: List<GtfsTransferEntity>,
    val patterns: List<GtfsPatternEntity>,
    val patternStops: List<GtfsPatternStopEntity>,
    val patternTrips: List<GtfsPatternTripEntity>,
    val patternHeadsigns: List<GtfsPatternHeadsignEntity>,
)
