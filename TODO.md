# BART Runner cleanup and efficiency plan

This is the post-Compose cleanup backlog. The major migration work is complete;
the remaining work should reduce duplicate computation, remove accidental state,
and keep the implementation simple.

## Working principles

- Favorites persist only their origin and destination.
- Fares and other schedule-derived values are transient UI data.
- Prefer a small cache or shared projection over a new abstraction layer.
- Keep the existing serialized persistence writers unless batching or coalescing
  is needed.
- Measure startup and rendering changes before optimizing Compose recomposition.
- Do not migrate storage technologies merely for modernization.

## Phase 1: remove dead pre-Compose code

- [x] Delete `FavoritesArrayAdapter` and `DepartureArrayAdapter`.
- [x] Delete `CheckableLinearLayout` and `ScreenTicker`.
- [x] Delete the obsolete route-selection and alarm dialog fragments.
- [x] Delete unused XML layouts, menus, and legacy action icons.
- [x] Remove obsolete styles, colors, dimensions, and strings.
- [x] Remove `viewBinding` after the XML layer was removed.

## Phase 2: simplify dependencies and activity plumbing

- [x] Remove unused RecyclerView, PhotoView, Material Components, and AppCompat dependencies.
- [x] Convert Compose activities to `ComponentActivity`.
- [x] Use `by viewModels()` where it improves readability.
- [x] Remove duplicate or transitively supplied lifecycle dependencies.
- [x] Update migration documentation to describe Compose as the production UI.

## Phase 3: lifecycle-aware Compose state

- [x] Add lifecycle-aware collection with `collectAsStateWithLifecycle()`.
- [x] Move alarm state into repository/ViewModel state.
- [x] Replace the UI-local clock with the shared `TimeSource`-based ticker.
- [x] Move user-visible text and content descriptions into resources.
- [x] Add baseline Compose UI coverage for the home screen, route picker,
  departures, trip actions, alarm picker, and map controls.
- [ ] Expand interaction coverage for route selection, following a trip, alarm
  cancellation, permission denial, and process restoration.

## Phase 4: make favorite persistence minimal

- [x] Persist favorites as records containing only `origin` and `destination`.
- [x] Stop serializing fare, fare timestamps, average trip length, and sample
  count as part of favorite state.
- [x] Remove `updateFare()` from `FavoritesRepository` and `RoutesViewModel`.
- [x] Remove `fareLastUpdated`, `averageTripLength`, and
  `averageTripSampleCount` from `StationPair` if no remaining callers need them.
- [x] Load fares as transient derived data from the cached static GTFS data.
- [x] Existing persisted data is intentionally not supported during this
  zero-user development phase; the next write uses the minimal schema.

## Phase 5: remove duplicate feed and projection work

- [x] Establish a clear base-schedule versus realtime-corrected-schedule
  contract between `Schedule` and `GtfsRealtimeContentHandler`.
- [x] Ensure each feed snapshot applies realtime corrections only once.
- [x] Avoid rebuilding a protobuf feed and `GtfsRealtimeFeedIndex` from an
  already-indexed entity list during normal projection.
- [x] Cache the corrected schedule/feed context per `TransitFeedSnapshot` so
  multiple consumers reuse it.
- [ ] Consolidate per-favorite projection jobs in `RoutesViewModel` where this
  remains simpler than maintaining one full projection per favorite.
- [x] Cache immutable GTFS-derived route patterns per line in `BartGtfsNetwork`.
- [ ] Re-evaluate the explicit startup refresh after measuring first-render
  latency; remove it if shared-feed subscription polling is sufficient.

## Phase 6: keep persistence efficient without adding machinery

- [ ] Keep the single-thread persistence writers for deterministic ordering.
- [x] Coalesce pending favorite writes when several real favorite changes occur
  in quick succession, especially drag-to-reorder operations.
- [x] Coalesce followed-trip cache writes when successive realtime updates do
  not materially change durable state.
- [ ] Keep atomic temporary-file replacement for followed-trip and static-feed
  caches, and improve replacement behavior if the platform permits a safer
  atomic move.

## Phase 7: static-feed cache reliability and derived-data caching

- [x] Make a corrupt or unparsable static cache fall back to a refresh instead
  of failing from the fresh-cache path.
- [x] Persist normalized static GTFS data in Room/SQLite so process restarts
  load topology from the database and query only active, time-windowed trips.
- [ ] Avoid holding the static-data lock across network I/O if profiling shows
  contention; do not redesign this preemptively.

## Phase 8: lint, resource, and manifest cleanup

- [x] Remove the stale lint suppression for deleted `train_alarm_dialog.xml`.
- [x] Remove unused `ACCESS_NETWORK_STATE` and `WAKE_LOCK` permissions.
- [x] Remove lint-reported unused strings and plurals.
- [x] Move the map bitmap to an appropriate density-independent resource folder.
- [x] Move the adaptive launcher icon to `mipmap-anydpi-v31` because `minSdk` is 31.
- [x] Replace the raster notification icon with a white vector drawable and
  make the cancel-alarm action asset density-independent.
- [x] Add API annotations around full-screen-intent settings access and remove
  redundant SDK guards made unnecessary by `minSdk`.
- [x] Fix the remaining low-risk Compose lint hints.
- [x] Keep debug lint at zero errors; the two remaining resource-layout warnings
  are intentional for the adaptive launcher/resource setup.

## Phase 9: staged dependency and SDK maintenance

- [x] Upgrade the version catalog, including lifecycle, coroutines, AndroidX,
  Compose, OkHttp, Jackson, GTFS-RT, and the AndroidX test libraries.
- [x] Run unit tests, lint, and debug assembly after the dependency upgrade.
- [x] Run the release assembly and device smoke tests on the Pixel 10a
  emulator.
- [x] Re-evaluate compile/target SDK against the installed SDK 37; target SDK
  is now 37.
- [ ] Remove obsolete resource qualifiers only after confirming the supported
  device range.

## Verification checklist

- [x] `:app:testDebugUnitTest`
- [x] `:app:assembleDebug`
- [x] `:app:lintDebug`
- [x] Instrumentation smoke tests on the connected Pixel 10a emulator.
- [ ] Manually verify route selection and editing.
- [ ] Manually verify live departures and trip following.
- [ ] Manually verify alarm setup, cancellation, exact-alarm denial, and
  notification/full-screen-intent denial.
- [ ] Manually verify background alarm delivery and notification actions.
- [ ] Manually verify process-death restoration of favorites and followed trips.
- [ ] Decide whether connected instrumentation should run periodically in CI;
  keep normal CI fast if emulator startup remains too expensive.

## Explicitly deferred unless evidence changes

- Do not migrate tiny preference values to DataStore solely for modernization.
- Do not move followed-trip JSON to Proto DataStore unless the current schema or
  file-backed cache becomes a real maintenance problem.
- Do not replace the serialized executors with application coroutine scopes
  unless it simplifies the code or fixes a measured issue.
- Do not redesign the Compose ticker unless profiling shows meaningful UI cost.
