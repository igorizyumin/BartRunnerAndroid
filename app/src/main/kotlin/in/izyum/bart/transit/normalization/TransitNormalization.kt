package `in`.izyum.bart.transit.normalization

import com.google.transit.realtime.GtfsRealtime
import `in`.izyum.bart.transit.BartDataPolicy
import java.time.Instant
import java.time.LocalDate
import java.util.Collections

/** Stable identity for a published static trip on one GTFS service date. */
data class StaticTripIdentity(
    val serviceDate: LocalDate,
    val tripId: String,
)

/** Provenance retained with a normalized feed instead of inferred by consumers. */
data class FeedProvenance(
    val feedTimestampMillis: Long,
    val receivedAtMillis: Long? = null,
    val sourceName: String? = null,
)

/** One lossless GTFS-Realtime stop observation in protobuf order. */
data class RealtimeStopObservation(
    val feedObservationOrder: Int,
    val stopId: String?,
    val stopSequence: Int?,
    val arrivalTimeMillis: Long?,
    val arrivalDelaySeconds: Int?,
    val departureTimeMillis: Long?,
    val departureDelaySeconds: Int?,
    val scheduleRelationship: GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship?,
)

/** One lossless GTFS-Realtime entity/trip-update observation. */
data class RealtimeTripObservation(
    val entityId: String?,
    val entityOrder: Int,
    val tripId: String?,
    val routeId: String?,
    val startDate: LocalDate?,
    val startTime: String?,
    val scheduleRelationship: GtfsRealtime.TripDescriptor.ScheduleRelationship?,
    val stops: List<RealtimeStopObservation>,
    /** The unmodified protobuf entity for legacy adapters during migration. */
    val rawEntity: GtfsRealtime.FeedEntity,
) {
    val explicitlyCanceled: Boolean
        get() = scheduleRelationship == GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED

    val isOperationalTelemetry: Boolean
        get() = tripId?.toIntOrNull()?.let { it in BartDataPolicy.DMU_TRIP_ID_RANGE } == true
}

/** A deterministic selection, with all raw observations still retained. */
data class DuplicateProjectionDecision(
    val tripId: String,
    val selectedEntityId: String?,
    val discardedEntityIds: List<String?>,
    val reason: String,
)

/** Immutable, lossless normalization of one GTFS-Realtime feed. */
data class NormalizedRealtimeFeed(
    val provenance: FeedProvenance,
    /** All raw feed entities, including alerts and entities without trip updates. */
    val entities: List<GtfsRealtime.FeedEntity>,
    val tripUpdates: List<RealtimeTripObservation>,
    val duplicateDecisions: List<DuplicateProjectionDecision>,
) {
    /**
     * One deterministic update per trip ID for a passenger projection. Numeric
     * 600–799 IDs remain present here; association decides whether they can
     * affect a passenger trip.
     */
    val projectedTripUpdatesById: Map<String, RealtimeTripObservation> by lazy {
        immutableMap(tripUpdates.filter { !it.tripId.isNullOrBlank() }
            .groupBy { requireNotNull(it.tripId) }
            .mapValues { (_, candidates) -> select(candidates) })
    }

    /** Consumer projection keyed by the complete static identity. */
    val projectedTripUpdatesByIdentity: Map<StaticTripIdentity, RealtimeTripObservation> by lazy {
        immutableMap(tripUpdates.filter { !it.tripId.isNullOrBlank() }
            .groupBy { observation ->
                StaticTripIdentity(
                    RealtimeFeedNormalizer.serviceDateFor(observation, this),
                    requireNotNull(observation.tripId),
                )
            }
            .mapValues { (_, candidates) -> select(candidates) })
    }

    private fun select(candidates: List<RealtimeTripObservation>): RealtimeTripObservation =
        candidates.sortedWith(
            compareByDescending<RealtimeTripObservation> { it.explicitlyCanceled }
                // One feed has one header timestamp, so stable protobuf order is
                // the final deterministic tie-break inside a snapshot.
                .thenBy { it.entityOrder }
        ).first()
}

/** Parses GTFS-Realtime without applying static-trip, terminal, or UI policy. */
object RealtimeFeedNormalizer {
    private val pacific = BartDataPolicy.PACIFIC_ZONE

    @JvmStatic
    fun normalize(
        feed: GtfsRealtime.FeedMessage,
        receivedAtMillis: Long? = null,
        sourceName: String? = null,
    ): NormalizedRealtimeFeed {
        val timestampMillis = if (feed.hasHeader() && feed.header.hasTimestamp()) {
            feed.header.timestamp * 1000L
        } else {
            receivedAtMillis ?: 0L
        }
        val observations = feed.entityList.mapIndexedNotNull { entityOrder, entity ->
            if (!entity.hasTripUpdate()) return@mapIndexedNotNull null
            val update = entity.tripUpdate
            val descriptor = update.takeIf { it.hasTrip() }?.trip
            RealtimeTripObservation(
                entityId = entity.takeIf { it.hasId() }?.id,
                entityOrder = entityOrder,
                tripId = descriptor?.takeIf { it.hasTripId() }?.tripId?.takeIf { it.isNotBlank() },
                routeId = descriptor?.takeIf { it.hasRouteId() }?.routeId?.takeIf { it.isNotBlank() },
                startDate = descriptor?.takeIf { it.hasStartDate() }?.startDate
                    ?.let(::parseServiceDate),
                startTime = descriptor?.takeIf { it.hasStartTime() }?.startTime,
                scheduleRelationship = descriptor?.takeIf { it.hasScheduleRelationship() }
                    ?.scheduleRelationship,
                stops = immutableList(update.stopTimeUpdateList.mapIndexed { stopOrder, stop ->
                    RealtimeStopObservation(
                        feedObservationOrder = stopOrder,
                        stopId = stop.takeIf { it.hasStopId() }?.stopId?.takeIf { it.isNotBlank() },
                        stopSequence = stop.takeIf { it.hasStopSequence() }?.stopSequence,
                        arrivalTimeMillis = stop.takeIf { it.hasArrival() && it.arrival.hasTime() }
                            ?.arrival?.time?.times(1000L),
                        arrivalDelaySeconds = stop.takeIf { it.hasArrival() && it.arrival.hasDelay() }
                            ?.arrival?.delay,
                        departureTimeMillis = stop.takeIf { it.hasDeparture() && it.departure.hasTime() }
                            ?.departure?.time?.times(1000L),
                        departureDelaySeconds = stop.takeIf { it.hasDeparture() && it.departure.hasDelay() }
                            ?.departure?.delay,
                        scheduleRelationship = stop.takeIf { it.hasScheduleRelationship() }
                            ?.scheduleRelationship,
                    )
                }),
                rawEntity = entity,
            )
        }
        val decisions = observations.filter { !it.tripId.isNullOrBlank() }
            .groupBy { requireNotNull(it.tripId) }
            .filterValues { it.size > 1 }
            .map { (tripId, candidates) ->
                val selected = candidates.sortedWith(
                    compareByDescending<RealtimeTripObservation> { it.explicitlyCanceled }
                        .thenBy { it.entityOrder }
                ).first()
                DuplicateProjectionDecision(
                    tripId = tripId,
                    selectedEntityId = selected.entityId,
                    discardedEntityIds = candidates.filterNot { it === selected }.map { it.entityId },
                    reason = if (selected.explicitlyCanceled) {
                        "explicit_cancellation"
                    } else {
                        "stable_entity_order"
                    },
                )
            }.sortedBy { it.tripId }
        return NormalizedRealtimeFeed(
            FeedProvenance(timestampMillis, receivedAtMillis, sourceName),
            immutableList(feed.entityList),
            immutableList(observations),
            immutableList(decisions),
        )
    }

    /** Uses the descriptor date when present, otherwise the feed's local date. */
    @JvmStatic
    fun serviceDateFor(
        observation: RealtimeTripObservation,
        feed: NormalizedRealtimeFeed,
    ): LocalDate = observation.startDate ?: Instant.ofEpochMilli(feed.provenance.feedTimestampMillis)
        .atZone(pacific).toLocalDate()

    private fun parseServiceDate(value: String): LocalDate? = runCatching {
        if (value.length != 8) return@runCatching null
        LocalDate.of(value.substring(0, 4).toInt(), value.substring(4, 6).toInt(), value.substring(6, 8).toInt())
    }.getOrNull()
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
