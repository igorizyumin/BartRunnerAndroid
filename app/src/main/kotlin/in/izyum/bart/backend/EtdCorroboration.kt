package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.RealTimeDepartures
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.networktasks.EtdStationCache
import `in`.izyum.bart.networktasks.EtdLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class EtdMatch {
    MATCHED,
    CANCELED,
    ABSENT,
    UNKNOWN,
}

data class EtdLegDecision(
    val match: EtdMatch,
    val departureTimeMillis: Long? = null,
)

data class EtdLegKey(
    val tripId: String,
    val origin: Station,
    val destination: Station?,
    val scheduledDepartureTime: Long,
)

/** Applies ETD only to suspicious missing-trip candidates. */
object EtdCorroborator {
    const val SUSPICIOUS_WINDOW_MILLIS = 60L * 60L * 1000L
    const val MATCH_TOLERANCE_MILLIS = 8L * 60L * 1000L

    fun suspiciousLegs(
        departures: List<Departure>,
        snapshot: TransitFeedSnapshot,
    ): List<TripLeg> {
        val feedIds = snapshot.getTripUpdateIndex().tripUpdatesById.keys
        val feedTime = snapshot.getTripUpdatesTimestampMillis()
        return departures
            .flatMap { it.tripLegs }
            .filter { leg ->
                val tripId = leg.tripId
                tripId != null
                    && tripId !in feedIds
                    && leg.origin != null
                    && leg.scheduledDepartureTime in
                    feedTime..(feedTime + SUSPICIOUS_WINDOW_MILLIS)
            }
            .distinctBy(::key)
    }

    fun applyBoards(
        departures: RealTimeDepartures,
        suspicious: List<TripLeg>,
        boards: Map<Station, EtdLookup>,
    ): RealTimeDepartures {
        val decisions = decide(suspicious, boards)
        return departures.filterDepartures { departure ->
            departure.tripLegs.none { leg ->
                decisions[keyOrNull(leg)] in setOf(EtdMatch.ABSENT, EtdMatch.CANCELED)
            }
        }
    }

    fun decide(
        suspicious: List<TripLeg>,
        boards: Map<Station, EtdLookup>,
    ): Map<EtdLegKey, EtdMatch> = decideDetailed(suspicious, boards)
        .mapValues { it.value.match }

    fun decideDetailed(
        suspicious: List<TripLeg>,
        boards: Map<Station, EtdLookup>,
    ): Map<EtdLegKey, EtdLegDecision> {
        val decisions = LinkedHashMap<EtdLegKey, EtdLegDecision>()
        suspicious
            .groupBy { MatchGroup(it.origin, it.line, it.trainDestination) }
            .forEach { (group, legs) ->
                val board = group.station?.let { boards[it]?.board }
                val candidates = board?.departuresFor(group.line, group.destination).orEmpty()
                val used = mutableSetOf<Int>()
                legs.sortedBy { it.scheduledDepartureTime }.forEach { leg ->
                    val matchIndex = candidates.indices
                        .filter { it !in used }
                        .minByOrNull {
                            kotlin.math.abs(
                                candidates[it].departureTimeMillis -
                                    leg.scheduledDepartureTime
                            )
                        }
                    val match = matchIndex?.let { candidates[it] }
                    val decision = if (match != null && kotlin.math.abs(
                            match.departureTimeMillis - leg.scheduledDepartureTime
                        ) <= MATCH_TOLERANCE_MILLIS
                    ) {
                        used += matchIndex
                        EtdLegDecision(
                            if (match.canceled) EtdMatch.CANCELED else EtdMatch.MATCHED,
                            match.departureTimeMillis.takeUnless { match.canceled },
                        )
                    } else {
                        if (boardCoversCandidate(board, leg.scheduledDepartureTime)) {
                            EtdLegDecision(EtdMatch.ABSENT)
                        } else {
                            EtdLegDecision(EtdMatch.UNKNOWN)
                        }
                    }
                    decisions[key(leg)] = decision
                }
            }
        return decisions
    }

    /**
     * A successful station response with no train in the requested direction
     * is meaningful once the response covers the candidate's time. An empty
     * board is treated as a valid no-service response for the normal
     * suspicious window; a failed request never reaches this function because
     * its board is null.
     */
    private fun boardCoversCandidate(
        board: `in`.izyum.bart.networktasks.EtdStationBoard?,
        candidateTimeMillis: Long,
    ): Boolean {
        if (board == null) return false
        val latestStationDeparture = board.departures.maxOfOrNull {
            it.departureTimeMillis
        }
        return latestStationDeparture != null && latestStationDeparture >=
            candidateTimeMillis - MATCH_TOLERANCE_MILLIS ||
            board.departures.isEmpty() && candidateTimeMillis <=
            board.receivedAtMillis + SUSPICIOUS_WINDOW_MILLIS
    }

    fun departureOverrides(
        suspicious: List<TripLeg>,
        boards: Map<Station, EtdLookup>,
    ): Map<Pair<String, Station>, Long> = decideDetailed(suspicious, boards)
        .mapNotNull { (key, decision) ->
            if (decision.match == EtdMatch.MATCHED
                && decision.departureTimeMillis != null
            ) {
                (key.tripId to key.origin) to decision.departureTimeMillis
            } else {
                null
            }
        }
        .toMap()

    fun suppressedTripIds(
        suspicious: List<TripLeg>,
        boards: Map<Station, EtdLookup>,
    ): Set<String> = decide(suspicious, boards)
        .filterValues { it == EtdMatch.ABSENT || it == EtdMatch.CANCELED }
        .keys
        .map { it.tripId }
        .toSet()

    private data class MatchGroup(
        val station: Station?,
        val line: Line?,
        val destination: Station?,
    )

    private fun key(leg: TripLeg): EtdLegKey = EtdLegKey(
        requireNotNull(leg.tripId),
        requireNotNull(leg.origin),
        leg.destination,
        leg.scheduledDepartureTime,
    )

    private fun keyOrNull(leg: TripLeg): EtdLegKey? =
        if (leg.tripId != null && leg.origin != null) key(leg) else null
}

/** Adds the asynchronous ETD step after the ordinary projection identifies candidates. */
class EtdAwareRouteDepartureProjection(
    private val projection: RouteDepartureProjection,
    private val cache: EtdStationCache,
) {
    suspend fun project(snapshot: TransitFeedSnapshot): RealTimeDepartures {
        val result = projection.project(snapshot)
        val suspicious = EtdCorroborator.suspiciousLegs(result.getDepartures(), snapshot)
        if (suspicious.isEmpty()) return result
        val boards = withContext(Dispatchers.IO) {
            cache.getAll(suspicious.mapNotNull { it.origin }.toSet())
        }
        val detailed = EtdCorroborator.decideDetailed(suspicious, boards)
        val excludedTripIds = detailed
            .filterValues {
                it.match == EtdMatch.ABSENT || it.match == EtdMatch.CANCELED
            }
            .keys
            .map { it.tripId }
            .toSet()
        val departureOverrides = detailed
            .mapNotNull { (key, decision) ->
                if (decision.match == EtdMatch.MATCHED
                    && decision.departureTimeMillis != null
                ) {
                    (key.tripId to key.origin) to decision.departureTimeMillis
                } else {
                    null
                }
            }
            .toMap()
        return if (excludedTripIds.isEmpty() && departureOverrides.isEmpty()) {
            result
        } else {
            projection.project(snapshot, excludedTripIds, departureOverrides)
        }
    }

    fun areEquivalent(
        previous: RealTimeDepartures?,
        current: RealTimeDepartures?,
    ): Boolean = projection.areEquivalent(previous, current)
}
