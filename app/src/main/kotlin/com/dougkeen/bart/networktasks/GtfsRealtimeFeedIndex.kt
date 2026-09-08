package com.dougkeen.bart.networktasks

import com.google.transit.realtime.GtfsRealtime
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

/** Immutable one-pass index of the entities in a GTFS realtime feed. */
class GtfsRealtimeFeedIndex private constructor(
    entities: Iterable<GtfsRealtime.FeedEntity>
) {
    val tripUpdateEntities: List<GtfsRealtime.FeedEntity>
    val tripUpdatesById: Map<String, GtfsRealtime.FeedEntity>
    val alertEntities: List<GtfsRealtime.FeedEntity>
    val alertsById: Map<String, GtfsRealtime.FeedEntity>

    init {
        val tripEntities = mutableListOf<GtfsRealtime.FeedEntity>()
        val trips = LinkedHashMap<String, GtfsRealtime.FeedEntity>()
        val entitiesWithAlerts = mutableListOf<GtfsRealtime.FeedEntity>()
        val alerts = LinkedHashMap<String, GtfsRealtime.FeedEntity>()
        for (entity in entities) {
            if (entity.hasTripUpdate()) {
                tripEntities += entity
                val tripUpdate = entity.tripUpdate
                val trip = if (tripUpdate.hasTrip()) tripUpdate.trip else null
                if (trip != null && trip.hasTripId() && trip.tripId.isNotEmpty()) {
                    trips[trip.tripId] = entity
                } else if (entity.hasId()) {
                    trips[entity.id] = entity
                }
            }
            if (entity.hasAlert()) {
                entitiesWithAlerts += entity
                if (entity.hasId()) {
                    alerts[entity.id] = entity
                }
            }
        }
        tripUpdateEntities = immutableList(tripEntities)
        tripUpdatesById = immutableMap(trips)
        alertEntities = immutableList(entitiesWithAlerts)
        alertsById = immutableMap(alerts)
    }

    companion object {
        @JvmStatic
        fun from(feed: GtfsRealtime.FeedMessage?): GtfsRealtimeFeedIndex =
            GtfsRealtimeFeedIndex(requireNotNull(feed) { "feed" }.entityList)

        /** Adds fallback entities without replacing a live update for the same trip. */
        @JvmStatic
        fun merge(
            primary: GtfsRealtimeFeedIndex,
            fallback: GtfsRealtimeFeedIndex
        ): GtfsRealtimeFeedIndex {
            val primaryTripIds = primary.tripUpdatesById.keys
            return GtfsRealtimeFeedIndex(
                primary.tripUpdateEntities + fallback.tripUpdateEntities.filter { entity ->
                    val tripId = if (entity.hasTripUpdate() && entity.tripUpdate.hasTrip()
                        && entity.tripUpdate.trip.hasTripId()
                    ) {
                        entity.tripUpdate.trip.tripId
                    } else {
                        null
                    }
                    tripId == null || tripId !in primaryTripIds
                }
            )
        }
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
