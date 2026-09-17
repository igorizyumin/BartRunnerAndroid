package `in`.izyum.bart.backend

import `in`.izyum.bart.transit.normalization.AssociationStatus
import `in`.izyum.bart.transit.normalization.AssociationConfidence
import `in`.izyum.bart.transit.normalization.NormalizedRealtimeFeed
import `in`.izyum.bart.transit.normalization.RequiredCounterpartStatus
import `in`.izyum.bart.transit.normalization.RequiredTransferPair
import `in`.izyum.bart.transit.normalization.StaticTripIdentity
import `in`.izyum.bart.transit.normalization.TripAssociation
import `in`.izyum.bart.transit.normalization.TripAssociator
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import java.util.Collections

enum class PassengerTripAvailability {
    SCHEDULE_ONLY,
    REALTIME_OBSERVED,
    CANCELED_EXPLICIT,
}

/** One consumer-neutral passenger trip state derived from the canonical feed. */
data class PassengerTripState(
    val trip: Schedule.Trip,
    val availability: PassengerTripAvailability,
    val association: TripAssociation?,
    val transferPair: RequiredTransferPair? = null,
)

/**
 * The single normalization boundary for all app consumers. Static schedule,
 * raw realtime facts, identity decisions, and terminal telemetry stay
 * separate here instead of being reconstructed in each projection. The
 * returned schedule already includes any unambiguous DMU stop-time merge.
 */
class CanonicalTransitSnapshot private constructor(
    val normalizedFeed: NormalizedRealtimeFeed,
    val correctedSchedule: Schedule,
    val associations: List<TripAssociation>,
    val passengerTrips: List<PassengerTripState>,
    val requiredTransferPairs: List<RequiredTransferPair>,
) {
    companion object {
        @JvmStatic
        fun create(baseSchedule: Schedule, normalizedFeed: NormalizedRealtimeFeed): CanonicalTransitSnapshot {
            val staticTrips = baseSchedule.trips
                .map { StaticTripIdentity(it.key.serviceDate, it.key.tripId) }
            val associations = TripAssociator.associate(staticTrips, normalizedFeed)
            val byIdentity = associations.filter { it.status == AssociationStatus.EXACT }
                .mapNotNull { association -> association.staticIdentity?.let { it to association } }
                .toMap()
            val exactCorrected = baseSchedule.applyRealtime(normalizedFeed)
            val requiredPairs = buildRequiredTransferPairs(exactCorrected, associations)
            val corrected = exactCorrected.applyDmuTransferPairs(requiredPairs)
            val transferByIdentity = requiredPairs.mapNotNull { pair ->
                pair.passengerIdentity?.let { it to pair }
            }.toMap()
            val passengerTrips = corrected.trips.map { trip ->
                val identity = StaticTripIdentity(trip.key.serviceDate, trip.key.tripId)
                val association = byIdentity[identity]
                val transferPair = transferByIdentity[identity]
                val availability = when {
                    association?.observation?.explicitlyCanceled == true ->
                        PassengerTripAvailability.CANCELED_EXPLICIT
                    association != null || transferPair != null ->
                        PassengerTripAvailability.REALTIME_OBSERVED
                    else -> PassengerTripAvailability.SCHEDULE_ONLY
                }
                PassengerTripState(trip, availability, association, transferPair)
            }
            return CanonicalTransitSnapshot(
                normalizedFeed,
                corrected,
                immutableList(associations),
                immutableList(passengerTrips),
                immutableList(requiredPairs),
            )
        }
    }
}

private fun buildRequiredTransferPairs(
    schedule: Schedule,
    associations: List<TripAssociation>,
): List<RequiredTransferPair> {
    val exactPassengerIds = associations.asSequence()
        .filter { it.status == AssociationStatus.EXACT }
        .mapNotNull { it.staticIdentity }
        .toSet()
    val exactYellow = associations.filter { association ->
        association.status == AssociationStatus.EXACT
            && association.staticIdentity in exactPassengerIds
            && association.staticIdentity?.let { identity ->
                schedule.trips.any {
                    StaticTripIdentity(it.key.serviceDate, it.key.tripId) == identity
                        && it.line == Line.YELLOW
                        && !it.canceled
                }
            } == true
    }
    val usedPassengerIds = mutableSetOf<StaticTripIdentity>()
    return associations.asSequence()
        .filter { it.status == AssociationStatus.OPERATIONAL_TELEMETRY }
        .sortedBy { it.observation.entityOrder }
        .map { telemetry ->
            val observed = telemetry.observation.stops.mapNotNull { stop ->
                val station = schedule.stationForStopId(stop.stopId) ?: return@mapNotNull null
                val time = stop.departureTimeMillis ?: stop.arrivalTimeMillis
                    ?: return@mapNotNull null
                station to (stop to time)
            }.let { entries ->
                // Stop sequence is the preferred order for association. If
                // it is absent, the normalizer's protobuf order is retained.
                val ordered = if (entries.all { it.second.first.stopSequence != null }) {
                    entries.sortedBy { it.second.first.stopSequence }
                } else {
                    entries
                }
                ordered.associate { entry -> entry.first to entry.second.second }
            }
            val firstObserved = observed.entries.firstOrNull()
            // Keep the complete normalized DMU stop sequence. In particular,
            // BART may include a PITT prediction in the DMU entity even though
            // PITT is the electric handoff station. Station classification is
            // not a reason to discard an observed stop.
            val compatible = if (observed.isNotEmpty() && firstObserved != null) {
                schedule.trips.filter { trip ->
                    val identity = StaticTripIdentity(trip.key.serviceDate, trip.key.tripId)
                    trip.key.serviceDate == telemetry.serviceDate
                        && trip.line == Line.YELLOW
                        && !trip.canceled
                        && identity !in usedPassengerIds
                        && trip.stops.map { it.station }.containsAll(TERMINAL_STATIONS)
                        && observed.keys.map { station ->
                            trip.stops.indexOfFirst { it.station == station }
                        }.zipWithNext().all { (before, after) ->
                            before >= 0 && after > before
                        }
                        && trip.stopAt(firstObserved.key)?.scheduledDepartureTime?.let { scheduled ->
                            kotlin.math.abs(scheduled - firstObserved.value) <=
                                OPERATIONAL_TIMING_WINDOW_MILLIS
                        } == true
                }.singleOrNull()
            } else {
                null
            }
            val identity = compatible?.let {
                StaticTripIdentity(it.key.serviceDate, it.key.tripId)
            }
            if (identity != null) usedPassengerIds += identity
            val electric = exactYellow.firstOrNull { it.staticIdentity == identity }
            RequiredTransferPair(
                operationalObservation = telemetry.observation,
                passengerIdentity = identity,
                electricAssociation = electric,
                status = if (electric == null) {
                    RequiredCounterpartStatus.REQUIRED_COUNTERPART_NOT_OBSERVED
                } else {
                    RequiredCounterpartStatus.OBSERVED
                },
                confidence = if (identity == null) AssociationConfidence.LOW
                else if (electric == null) AssociationConfidence.HIGH
                else AssociationConfidence.HIGH,
                evidence = when {
                    identity == null ->
                        "No unique same-date Yellow passenger trip matched the DMU stop sequence and five-minute schedule window"
                    electric == null ->
                        "DMU telemetry matched a unique same-date Yellow passenger trip; electric realtime counterpart was not observed"
                    else ->
                        "DMU telemetry linked to the exact electric passenger trip at PITT"
                },
            )
        }.toList()
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private val TERMINAL_STATIONS = setOf(Station.PITT, Station.PCTR, Station.ANTC)
// The Antioch shuttle normally runs on a 20-minute cadence. A five-minute
// window leaves room for real delay while preventing one shuttle observation
// from being projected onto the adjacent scheduled passenger departure.
private const val OPERATIONAL_TIMING_WINDOW_MILLIS = 5L * 60L * 1000L
