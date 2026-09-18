# Canonical consumer audit

The application has one passenger-trip truth after feed acquisition:
`TransitFeedSnapshot` → `CanonicalTransitSnapshot` → RAPTOR/projection.

| Consumer | Canonical input | Stored or delivered result |
| --- | --- | --- |
| Route and departure screens | `DepartureProjector` | `Departure` projections |
| Followed trip state | `FollowedItineraryAlarmProjection` and `ItineraryRefreshProjector` | persisted `Itinerary`/`TripLeg` values |
| Alarm scheduling | followed `Itinerary` | one pending departure alarm |
| Background polling | `FollowedItineraryAlarmProjection` | refreshed followed itinerary |
| Alarm notification | `FollowedTripRepository.handleAlarmTriggered()` | the claimed followed itinerary |
| Service alerts | `AlertProjection` from the shared feed snapshot | projected alert list |

No persistence or notification consumer reconstructs a passenger trip from a
raw or normalized realtime entity. DMU observations remain canonical
provenance and transfer evidence only.
