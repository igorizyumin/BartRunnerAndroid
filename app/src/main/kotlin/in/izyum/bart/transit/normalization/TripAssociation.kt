package `in`.izyum.bart.transit.normalization

import java.time.LocalDate
import java.util.Collections

enum class AssociationStatus {
    EXACT,
    HEURISTIC,
    REALTIME_ONLY,
    OPERATIONAL_TELEMETRY,
    UNKNOWN,
    REJECTED,
    NO_STATIC_COUNTERPART,
}

enum class AssociationConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

/** Explainable link (or deliberately absent link) from an RT observation to GTFS. */
data class TripAssociation(
    val observation: RealtimeTripObservation,
    val serviceDate: LocalDate,
    val status: AssociationStatus,
    val confidence: AssociationConfidence,
    val method: String,
    val evidence: String,
    val staticIdentity: StaticTripIdentity? = null,
    val candidateTripIds: List<String> = emptyList(),
    val rejectedCandidateIds: List<String> = emptyList(),
)

/**
 * Resolves only exact identity. Heuristic matching is intentionally not
 * promoted here: callers must provide a documented candidate scorer before an
 * observation can receive a heuristic association.
 */
object TripAssociator {
    @JvmStatic
    fun associate(
        staticTrips: Collection<StaticTripIdentity>,
        feed: NormalizedRealtimeFeed,
    ): List<TripAssociation> {
        val staticByTripId = staticTrips.groupBy { it.tripId }
        return immutableList(feed.tripUpdates.map { observation ->
            val serviceDate = RealtimeFeedNormalizer.serviceDateFor(observation, feed)
            val tripId = observation.tripId
            when {
                observation.isOperationalTelemetry -> TripAssociation(
                    observation = observation,
                    serviceDate = serviceDate,
                    status = AssociationStatus.OPERATIONAL_TELEMETRY,
                    confidence = AssociationConfidence.HIGH,
                    method = "numeric_operational_namespace",
                    evidence = "600–799 IDs are BART-to-Antioch operational telemetry",
                )
                tripId.isNullOrBlank() -> TripAssociation(
                    observation = observation,
                    serviceDate = serviceDate,
                    status = AssociationStatus.UNKNOWN,
                    confidence = AssociationConfidence.LOW,
                    method = "missing_trip_id",
                    evidence = "GTFS-Realtime entity has no usable trip_id",
                )
                else -> {
                    val candidates = staticByTripId[tripId].orEmpty()
                    val exact = candidates.singleOrNull { it.serviceDate == serviceDate }
                    when {
                        exact != null -> TripAssociation(
                            observation, serviceDate, AssociationStatus.EXACT,
                            AssociationConfidence.HIGH, "trip_id_and_service_date",
                            "trip_id=$tripId service_date=$serviceDate", exact,
                        )
                        candidates.isEmpty() -> TripAssociation(
                            observation, serviceDate, AssociationStatus.NO_STATIC_COUNTERPART,
                            AssociationConfidence.MEDIUM, "no_static_trip_id_match",
                            "No static trip has trip_id=$tripId",
                        )
                        else -> TripAssociation(
                            observation, serviceDate, AssociationStatus.REJECTED,
                            AssociationConfidence.MEDIUM, "service_date_conflict",
                            "Static trip ID exists but is inactive on service_date=$serviceDate",
                            candidateTripIds = immutableList(candidates.map { it.tripId }),
                        )
                    }
                }
            }
        })
    }
}

/**
 * A canonical DMU/electric service relation. The electric passenger identity
 * remains authoritative; the DMU ID is operational provenance for the
 * terminal movement attached at Pittsburg.
 */
data class RequiredTransferPair(
    val operationalObservation: RealtimeTripObservation,
    val passengerIdentity: StaticTripIdentity?,
    val electricAssociation: TripAssociation?,
    val status: RequiredCounterpartStatus,
    val confidence: AssociationConfidence,
    val evidence: String,
)

enum class RequiredCounterpartStatus {
    OBSERVED,
    REQUIRED_COUNTERPART_NOT_OBSERVED,
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
