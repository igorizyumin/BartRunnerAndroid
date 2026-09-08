# BartRunner modernization backlog

The application has already migrated its core transit feed, routing, followed
trip, alarm, and screen-state paths to Kotlin, immutable departures, and Flow.
The next phase should reduce the remaining compatibility plumbing before any
new UI framework or feature work is introduced.

## Current baseline

- [x] GTFS-RT trip updates and alerts are the production transit source.
- [x] `Departure` is an immutable Kotlin domain model with stable identity.
- [x] Feed polling is shared through `TransitRepository` and exposed as Flow.
- [x] Primary screen state is owned by ViewModels.
- [x] Activities pass primitive route/trip arguments rather than transit objects.
- [x] Boarded-trip notifications use a foreground service.
- [x] Java/Kotlin compilation targets Java 17.
- [x] Debug build, unit tests, lint, and compatibility checks pass locally.
- [x] Instrumentation tests run in CI on an Android emulator.

## Execution order

Complete each item independently where practical. Delete the old layer as soon
as its replacement is verified. Preserve behavior around GTFS parsing, route
selection, departure merging, alarms, persistence, and foreground service
startup with regression tests.

### 1. Delete dead and no-op layers

- [x] Remove the unused static schedule API: `ScheduleProjection`,
  `StaticScheduleSource`, `ScheduleInformation`, and `ScheduleItem`.
- [x] Remove schedule-only parsing from `GtfsStaticData`: calendars,
  calendar exceptions, static stop times, and related private DTOs.
- [x] Remove `TimedTextSwitcher`; it adds no behavior beyond `TextSwitcher`.
- [x] Replace `CountdownTextView` with `TextView` or `AppCompatTextView` and
  delete the empty subclass.
- [x] Delete the unused `ViewModelFlowCollector`.
- [x] Replace `Assert.notNull` with standard null checks and delete `Assert`.
- [x] Remove unused legacy fields from `Constants`, including content-provider
  URIs/types and `MAP_URL`, after confirming no external compatibility is needed.

### 2. Remove min-SDK and resource compatibility noise

The app currently has `minSdk 31`, but retains branches and resources for much
older Android releases.

- [x] Remove pre-31 branches for vibration, foreground-service startup,
  `stopForeground`, and alarm scheduling.
- [x] Consolidate obsolete `drawable-*-v9` and `drawable-*-v11` resources;
  retain the adaptive `mipmap-anydpi-v26` launcher resource.
- [x] Resolve inconsistent portrait/landscape departure layout IDs and use a
  single `TextView` contract in the departures adapter.
- [x] Remove the unnecessary explicit `allowBackup` manifest configuration.
- [x] Remove obsolete `configChanges`, exported activity surface, and old MIME
  intent filters that had no documented external deep-link contract.
- [x] Add launcher monochrome metadata and clean notification/icon lint issues.

### 3. Collapse the remaining Java/Kotlin boundary

- [x] Convert all production Activities to Kotlin; shared argument, constants,
  base-activity, wake lock, checkable-layout, screen-ticker, route dialogs,
  persistence DTOs, adapters, and presentation helpers are now Kotlin.
- [ ] Convert the remaining legacy Java unit-test fixtures to Kotlin; these no
  longer affect the production Java/Kotlin boundary.
- [x] Delete `LifecycleFlowCollector` once Activities collect directly with
  lifecycle-scoped Flow collection.
- [x] Delete `RoutesViewModelFactory` and `DeparturesViewModelFactory` when
  ViewModels can use direct Kotlin construction or standard factories.
- [x] Remove redundant `runOnUiThread` calls around lifecycle-scoped Flow
  collection.
- [x] Replace the `TripServiceCommand` wrapper with direct service actions or
  one clearly owned service-start boundary.
- [x] Remove obsolete Activity adapter compatibility methods such as
  `getListAdapter` and `setListAdapter`.
- [x] Convert the departures screen's manual UI state holder to a Kotlin
  `data class`; retain defensive collection ownership in the ViewModel.
- [ ] Convert remaining manual UI state holders to Kotlin `data class`/sealed state types
  where that reduces custom copy and defensive-copy code without weakening
  defensive collection ownership.

### 4. Simplify feed polling and projections

- [x] Replace `TransitRepository`'s manual `ScheduledExecutorService`, consumer
  counter, `ScheduledFuture`, and duplicate scheduling paths with a coroutine
  polling flow using lifecycle-aware sharing; retain only the small lock needed
  to serialize synchronous refresh state.
- [x] Remove the compatibility `TransitFeedClient.fetch()` method and retain a
  single feed-fetch contract that supports partial trip/alert success.
- [x] Remove the generic `TransitProjection`/`TransitProjectionState` layer;
  repository projections now use standard Kotlin `Result` values.
- [x] Replace `RouteDepartureProjection` and `TripProgressProjection`'s
  nullable `Context`/network constructor variants with one application-owned
  injectable GTFS network supplier and context-free query functions.
- [x] Keep alert formatting out of the backend: retain raw alert timestamps and
  format them at the UI boundary.

### 5. Finish the immutable realtime result migration

- [x] Replace mutable `RealTimeDepartures` aggregation state with an immutable
  result containing the departure list and transfer-inclusion metadata.
- [x] Keep feed parsing mutable only inside `GtfsRealtimeContentHandler` while
  constructing that result.
- [x] Make `Departure` defensively immutable at every construction boundary;
  avoid duplicate list copying in the builder and `withTripLegs` path.
- [ ] Preserve tests for feed replacement, estimate merging, trip-leg updates,
  identity matching, transfer fallback, and followed-trip updates.

### 6. Consolidate application-owned concurrency and persistence

- [x] Simplify `FavoritesRepository`'s separate coroutine scope and
  `ExecutorService` into one serialized persistence executor; retain the small
  lock and pending queue needed while the initial file load is in flight.
- [x] Apply the same single-executor persistence model to
  `FollowedTripRepository` while retaining atomic file replacement and
  process-death restoration.
- [x] Make static GTFS data application-owned and injectable instead of relying
  on a nullable `Context` and global cache access.
- [x] Inject the clock into static-data loading so service-day and cache expiry
  decisions are deterministic; extend the seam to other time-dependent code as
  those paths are simplified.

### 7. Modernize build and release plumbing

- [x] Replace Travis CI's JDK 8/Android 28 configuration with GitHub Actions
  covering unit tests, instrumentation tests, lint, and debug builds.
- [ ] Update the version catalog in a staged change, starting with AndroidX,
  lifecycle, coroutines, OkHttp, Jackson, and GTFS-RT bindings.
- [x] Confirm compile/target SDK 36 after compatibility testing.
- [x] Replace deprecated Groovy space-assignment syntax in Gradle files.
- [x] Replace the string-search-based compatibility task with standard Gradle
  configuration checks and focused verification checks.
- [x] Fix remaining lint findings for plurals, string concatenation, RTL,
  overdraw, adapter update notifications, and resource density; debug and
  release lint reports are clean.

## Deliberately deferred

- [ ] Do not introduce Compose until the primary screens share one stable state
  and event model and the XML implementation has been simplified.
- [ ] Keep the project single-module unless a real ownership or build-time
  boundary emerges.
- [ ] Do not add a repository, use case, mapper, adapter, or compatibility
  interface solely to preserve an old call shape.

## Working agreement

- Keep changes small and independently buildable.
- Prefer deleting a layer over adding a migration layer.
- Add regression coverage before changing feed, routing, alarm, service, or
  persistence behavior.
- Treat user preferences and local caches as disposable unless migration is
  specifically required.
- Do not include generated Gradle state in application changes.
