# BART Runner modernization backlog

This backlog records the post-Compose audit. The current app builds successfully, but the migration still contains unreachable View/XML code and several platform seams that should be modernized in small, verifiable steps.

## Phase 1: remove dead pre-Compose code

- [x] Delete `FavoritesArrayAdapter` and `DepartureArrayAdapter`; neither had a runtime caller.
- [x] Delete `CheckableLinearLayout` and `ScreenTicker`; neither had a runtime caller.
- [x] Delete `AbstractRouteSelectionFragment`, `AddRouteDialogFragment`, and `QuickRouteDialogFragment`; route selection is implemented by `RoutePickerDialog` in Compose.
- [x] Delete `TrainAlarmDialogFragment`; alarm selection is implemented by `AlarmPickerDialog` in Compose.
- [x] Delete unused XML layouts: `main.xml`, `departures.xml`, `favorite_listing.xml`, `departure_listing.xml`, `uncertainty_textview.xml`, `trip_in_progress.xml`, `route_form.xml`, and `train_alarm_dialog.xml`.
- [x] Delete unused XML menus and their legacy action icons.
- [x] Remove obsolete styles, colors, dimensions, and strings left behind by those layouts.
- [x] Remove `viewBinding = true` once the XML layer is gone.

## Phase 2: simplify dependencies and activity plumbing

- [x] Remove unused `RecyclerView`, PhotoView, Material Components, and AppCompat dependencies after Phase 1.
- [x] Convert the four Compose activities from `AppCompatActivity` to `ComponentActivity`.
- [ ] Replace `ViewModelProvider(this)[...]` with `by viewModels()` where it improves readability.
- [ ] Remove duplicate or transitively supplied lifecycle dependencies after checking the resolved dependency graph.
- [ ] Update the stale migration documentation so it reflects that Compose is now the production UI.

## Phase 3: lifecycle-aware Compose state

- [ ] Add `lifecycle-runtime-compose` and replace `collectAsState()` with `collectAsStateWithLifecycle()` in all activities.
- [ ] Move activity-owned alarm state into a ViewModel/repository state flow where practical.
- [ ] Replace the UI-local `rememberSecondTick()` loop with a shared, testable time/ticker abstraction; keep the injected `TimeSource` authoritative.
- [ ] Replace remaining hardcoded user-visible text and content descriptions in `BartRunnerUi.kt` with `stringResource` and resource plurals.
- [ ] Re-run lint and add Compose UI tests for route selection, departures, trip following, alarm controls, and map zoom.

## Phase 4: persistence modernization

- [ ] Migrate small preference values from `SharedPreferences` to Preferences DataStore: route picker selection, static-feed timestamps, and alarm state.
- [ ] Decide whether followed-trip JSON should remain a file-backed cache or move to a typed Proto DataStore; preserve process-death restoration and atomic writes.
- [ ] Replace repository-owned `ExecutorService` instances with application-scoped coroutine dispatchers/scope where this does not weaken serialized writes.
- [ ] Keep persistence migrations backward-compatible for existing installed users.

## Phase 5: alarms and background execution

- [ ] Review `BoardedDepartureService`'s long-running `dataSync` foreground-service design against Android 15's time limits.
- [ ] Add and test `Service.onTimeout()` handling, or replace continuous polling with a bounded/background-work design where product behavior permits.
- [ ] Make the full-screen alarm notification the primary background entry point; avoid directly starting an activity from `AlarmBroadcastReceiver` unless required by tested alarm behavior.
- [ ] Move alarm audio/vibration ownership into a clearly bounded service lifecycle and audit the static `WakeLocker` state for process/re-entry edge cases.
- [ ] Add device tests for exact-alarm permission denial, notification permission denial, background alarm delivery, and full-screen intent denial.

## Phase 6: feed and build cleanup

- [ ] Consolidate per-favorite projection jobs in `RoutesViewModel` into one favorites-derived projection where practical.
- [ ] Remove the redundant explicit startup refresh if shared-feed subscription polling already covers it.
- [ ] Upgrade the version catalog in a staged compatibility change, starting with lifecycle, coroutines, AndroidX, Compose, OkHttp, Jackson, and GTFS-RT bindings.
- [ ] Re-evaluate compile/target SDK after device testing and update obsolete min-SDK resource folders.
- [ ] Make lint clean after the dead-resource deletion pass, then keep lint clean in CI.

## Verification checklist

- [x] `:app:testDebugUnitTest`
- [x] `:app:assembleDebug`
- [x] `:app:lintDebug`
- [ ] Instrumentation smoke tests on the connected Pixel 10
- [ ] Manual verification of route selection, live departures, trip following, map viewing, alarms, notification actions, and process-death restoration
