# BART-specific policy notes

The app keeps source facts and presentation choices separate. The shared
policy object at `app/src/main/kotlin/in/izyum/bart/transit/BartDataPolicy.kt`
contains only operational facts or routing preferences that are intentionally
BART-specific:

- `America/Los_Angeles` is the service-date timezone used by static GTFS and
  realtime normalization.
- Numeric trip IDs from 600 through 799 are Antioch DMU operational telemetry;
  they are transfer evidence, not independent passenger trips.
- `OAKL` identifies the non-revenue Oakland Airport connector stop in the
  static-feed validation rules.
- Transfer-ranking weights and the avoided/busy station sets are application
  routing preferences. GTFS route colors remain UI-owned so themes can choose
  their own display colors.

The static Room database is deliberately migrated destructively because it is
only a re-fetchable GTFS cache. User-followed-trip state is stored separately
in `followed_trip.json` and is retained by the backup rules.

Background followed-trip refreshes perform one canonical projection through
RAPTOR on each wakeup. The network is small and this bounded cost keeps the
stored itinerary, alarm timing, and notification state derived from the same
canonical passenger-trip truth.
