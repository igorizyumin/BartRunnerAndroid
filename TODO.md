# BartRunner modernization backlog

This backlog is intentionally forward-looking. Existing feed, GTFS, routing,
persistence, alarm, lint, and test improvements are treated as the baseline.
The remaining work should simplify the architecture before introducing new UI
or dependency frameworks.

## Execution order

Each milestone should remain independently buildable and should delete old
plumbing as soon as its replacement is working.

### Milestone 1 — simplify the boarded-departure service

- [x] Delete `BoardedDepartureServicePolicy` and its dedicated test. Keep the
  stop and polling decisions as private service logic, tested through service
  behavior.
- [x] Fold `BoardedDepartureMonitor` into `BoardedDepartureService`; it is a
  one-consumer subscription wrapper with no independent domain responsibility.
- [x] Replace stringly-typed service commands such as
  `cancelNotifications` and `clearDeparture` with explicit intent actions.
- [x] Rewrite the service in Kotlin around a service-owned coroutine scope.
  Remove the `HandlerThread`, `WeakReference`, `Message`, and duplicated
  handler scheduling paths.
- [x] Keep the foreground service for near-real-time trip notifications; do
  not replace this behavior with WorkManager.
- [x] Add service-level regression coverage for startup, shutdown, followed
  trip disappearance, departure, polling cadence, and notification updates.

### Milestone 2 — establish one immutable departure model

- [x] Make `Departure` the immutable Kotlin domain model, preferably a
  `data class` with immutable trip-leg collections.
- [x] Move realtime merge and estimate reconciliation into pure model
  functions that return new departures.
- [x] Remove the parallel `DepartureSnapshot` conversion layer once callers
  use the immutable model directly.
- [x] Remove `DepartureRealtimeState` when no caller needs a mutable
  aggregation holder.
- [x] Move selection, followed-trip, alarm, and other screen state outside the
  departure model.
- [x] Put stable departure identity on the domain model and remove the
  separate `DepartureIdentity` helper.
- [x] Finish injecting `TimeSource` anywhere domain or presentation code still
  calls the system clock directly.
- [x] Preserve regression coverage for snapshot replacement, estimate merging,
  trip-leg updates, identity matching, and followed-trip updates while this
  migration is performed.

### Milestone 3 — use one observation mechanism

- [x] Replace `TransitRepository.Subscription`, projection listener interfaces,
  and manual callback dispatch with a coroutine `Flow`/`StateFlow` API.
- [x] Keep feed polling centralized in `TransitRepository`; consumers should
  derive route, alert, and trip-progress state from the shared feed stream.
- [x] Remove ViewModel `start()`/`stop()` lifecycle plumbing where
  `viewModelScope` and lifecycle collection can own the subscription.
- [x] Remove the custom favorites lifecycle bridge (`FavoritesObserver` and
  `FavoritesRepository.observe`) in favor of direct `StateFlow` collection.
- [x] Verify that recreation and stopping a screen do not duplicate
  subscriptions or leave callbacks, timers, or background work running. Flow
  cancellation is covered at the repository boundary, and the screen
  recreation tests now compile against the immutable departure model.

### Milestone 4 — make screen ViewModels the state boundary

- [x] Add one immutable `RoutesUiState` containing favorites, first departures,
  alerts, loading, and error state.
- [x] Convert `RoutesListActivity` into a renderer and action dispatcher; move
  fare refresh, alert formatting decisions, and route state coordination out
  of the Activity.
- [x] Make departures and trip-progress ViewModels expose immutable UI state
  rather than Java listener callbacks and mutable `Departure` instances.
- [x] Move alarm, follow-trip, delete-trip, and service-command decisions out
  of Activities and into explicit ViewModel/repository actions.
- [x] Keep the XML layouts while these state boundaries are migrated.
- [ ] Defer Compose until all primary screens use the same state and event
  model; do not introduce Compose as a parallel UI architecture.

### Milestone 5 — remove object-graph transport and global UI plumbing

- [x] Stop passing whole `Departure` objects between Activities and the
  service. Pass station IDs, trip/departure identity, and screen mode, then
  rehydrate current state from the repository.
- [x] Remove `DepartureParcel`, `TripLegParcel`, and `TripStopParcel` after
  their callers are migrated.
- [x] Replace `StationPairParcel` with small primitive route arguments or a
  single route-arguments type at the Activity boundary.
- [x] Replace the global `Ticker` singleton and view-owned tick callbacks with
  lifecycle-bound timer state collected only while a screen is visible.
- [x] Remove obsolete adapter compatibility methods and make adapters consume
  immutable UI items with `ListAdapter`/`DiffUtil`.

## Platform and release modernization

- [ ] Replace `Date`, `Calendar`, `SimpleDateFormat`, and direct date-string
  construction with `java.time` and localized Android resources.
- [ ] Move all user-facing strings out of Java/Kotlin code and into resources,
  including notification text, adapter text, dialogs, and error messages.
- [ ] Review exact-alarm, notification, foreground-service, and background
  launch behavior against current Android platform rules.
- [ ] Add dependency version management through a version catalog and define
  compatibility checks for AGP, Gradle, Kotlin, and AndroidX.
- [ ] Validate and then raise the Java/Kotlin compilation baseline to Java 17.
- [ ] Replace Travis CI with CI covering unit tests, instrumentation tests,
  lint, debug builds, and release/R8 builds.
- [ ] Keep the project single-module unless a real ownership or build-time
  boundary emerges.

## Working agreement

- Keep each change small and independently buildable.
- Add regression coverage before changing feed, alarm, service, or persistence
  behavior.
- Prefer deleting a layer over adding an adapter or migration layer.
- Do not mix generated Gradle state with application changes.
- Treat user preferences as disposable unless a migration is specifically
  required.
