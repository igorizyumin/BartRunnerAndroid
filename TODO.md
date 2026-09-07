# BartRunner remediation backlog

This backlog turns the architecture audit into incremental, verifiable work.

## P0 — correctness and release safety

- [x] Make lint a real gate (`abortOnError = true`) after fixing the existing lint errors.
- [x] Remove the foreground-service polling leak after shutdown.
- [x] Add bounded OkHttp call/read/write timeouts.
- [x] Define independent failure/staleness handling for trip updates and alerts.
- [x] Fix the favorites restore/update race.
- [x] Persist and reconcile alarm lead time and pending state across process death.
- [x] Replace custom `Observable` usage with lifecycle-aware `StateFlow`/`SharedFlow`.
- [x] Test alarm timing/state policy, service stop/poll policy, and followed-trip persistence.
- [ ] Add an instrumented activity recreation test for the followed-trip screen.

## P1 — architecture and maintainability

- [x] Make the route and trip-leg models immutable.
- [ ] Separate mutable realtime aggregation state from immutable transit values.
- [ ] Move transit subscriptions and presentation decisions out of RecyclerView adapters.
- [x] Centralize transit polling and index each feed once for all route queries.
- [x] Extract trip planning into a platform-independent `TripPlanner` and add graph-wide routing invariants.
- [x] Define an immutable static-GTFS `NetworkCatalog` for stops, route patterns, service calendars, and route metadata.
- [x] Use catalog-backed route patterns for validated production direct and transfer planning.
- [x] Use catalog-backed stop IDs and route names for validated production realtime resolution.
- [x] Remove the legacy hardcoded topology/stop resolver; missing catalog data is now a hard failure.
- [x] Parse static GTFS transfer edges into the immutable catalog.
- [x] Use static transfer edges for route-specific transfer eligibility.
- [x] Apply GTFS minimum transfer times during departure pairing.
- [ ] Keep app-specific station identity, display aliases, routing exceptions, transfer preferences, and timing tolerances outside GTFS.
- [x] Add fixture-based GTFS parser tests and structural feed-drift validation.
- [x] Validate the catalog against BART-specific station/route invariants before making it authoritative.
- [x] Check in an official GTFS snapshot and validate daytime/nighttime cross-line routing against it.
- [x] Remove the static application-context accessor and inject dependencies at boundaries.
- [x] Move trip planning into platform-independent Kotlin.
- [x] Move the core transit models, GTFS adapter, routing, feed values, and pure projections into Kotlin.
- [x] Move the transit repository, realtime feed adapters, and static GTFS loader into Kotlin.
- [x] Replace durable `Parcel.marshall()` storage with a versioned persistence format.
- [ ] Replace manual adapter merging with `ListAdapter`/`DiffUtil`.

## P2 — modernization and cleanup

- [ ] Upgrade dependencies through a version catalog and compatibility checks.
- [ ] Raise the Java/Kotlin compilation baseline to Java 17 when validated.
- [ ] Replace legacy date/time and string formatting with `java.time` and resources.
- [ ] Review alarm UX against current background-launch and notification rules.
- [x] Enable and verify R8 for release builds.
- [ ] Replace the obsolete Travis configuration with CI that runs build, lint, and tests.
- [ ] Remove unused legacy resources and the unused drag-sort-listview project.
- [ ] Decide whether to modernize the XML UI incrementally or adopt Compose per screen.

## Working agreement

- Keep each change small and independently buildable.
- Add regression coverage before changing feed, alarm, or persistence behavior.
- Do not mix generated Gradle state with application changes.
- Minimize cruft.  Do not add migration layers.  Assume any user preferences are disposable.
