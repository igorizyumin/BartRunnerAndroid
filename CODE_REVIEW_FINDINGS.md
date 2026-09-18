# Code Review Findings — Bart Runner (`in.izyum.bart`)

Architecture and implementation review of the Android app in this repository.
Scope: project structure, build setup, data flow, routing engine, platform
integrations, tests, and documentation. Review-only — no code changes.

## 1. Executive summary

This is an unusually disciplined codebase for its size. The core is a single,
well-documented data pipeline (static GTFS + GTFS-Realtime → lossless
normalization → one canonical snapshot → RAPTOR routing → departure/trip
projections → ViewModels → Compose), with a custom RAPTOR router that is
correctly optimized and carries genuine domain knowledge about BART operations
(transfer preferences, DMU/electric pairing at Pittsburg, the 600–799 Antioch
operational-train namespace). Test strategy is strong: live-captured protobuf
fixtures with per-station ETD XML corroboration and an opt-in exhaustive-pair
audit.

The main risks are: (a) a version-alignment question in the toolchain
(KSP 2.3.10 vs Kotlin 2.4.20), (b) inconsistent recovery semantics when one of
the two realtime feeds fails, (c) followed-trip alarms not surviving a device
reboot, and (d) a 94 KB single-file UI. None of these are architectural; the
architecture itself is sound and worth keeping.

## 2. Architecture

### 2.1 Data flow

```
Weekly:   bart.gov google_transit.zip ──> GtfsStaticData (download, version-check, 15-min retry backoff)
                                          ├─> Room (GtfsStaticDatabase, 10 entities) ─> GtfsNetworkCatalog
                                          └─> BartGtfsNetwork (stations/lines/transfers/patterns,
                                                                  lazy scheduled-trip loader via Room DAO)

Polling:  TransitRepository (subscription-count-driven coroutine poll, 15s cadence)
          └─> HttpTransitFeedClient (okhttp, sync, both feeds)
              └─> TransitFeedSnapshot (raw protobuf + receivedAt)
                  └─> RealtimeFeedNormalizer (lossless; deterministic duplicate decisions)
                      └─> TripAssociator (exact trip_id+service_date only; status/confidence/evidence)
                          └─> CanonicalTransitSnapshot  ← the single normalization boundary
                              (Schedule.fromStatic → applyRealtime → DMU required-transfer pairs)
                              ├─> DepartureProjector (RAPTOR journeys → Departure, min/max estimates)
                              ├─> ItineraryRefreshProjector / TripProgressProjection (itinerary repair)
                              └─> AlertProjection

UI:       Activity + AndroidViewModel ← TransitRepository.projectedState(project, areEquivalent)
         (snapshot → projection on Dispatchers.Default, equivalence-deduped)
         └─> Compose screens in ui/BartRunnerUi.kt

Background: FollowedTripRepository (JSON persistence, single-threaded best-effort writer)
           ├─> DepartureAlarmScheduler (exact setAlarmClock, permission-checked)
           └─> DeparturePollingAlarm (one-shot exact wakeups, adaptive delay)
                └─> DeparturePollingReceiver (goAsync) ─> refreshTripUpdatesNow + re-projection

OfflineStatusController: validated-network callbacks + feed-error state → ongoing
                         status notification, 15s retry loop
```

### 2.2 What the design gets right

- **One canonical boundary.** `TODO.md` states the architectural rules
  explicitly (static owns schedule facts; GTFS-RT owns observed timing;
  omitted entity = unknown, not canceled; DMU telemetry is provenance, not
  passenger trips; projections must not re-normalize). The production code
  honors this — the projectors only consume `CanonicalTransitSnapshot` and
  never touch raw protobuf entities.
- **Explainable normalization.** `NormalizedRealtimeFeed` is immutable and
  lossless; every dedup produces a `DuplicateProjectionDecision` with a
  reason, and every association gets `status/confidence/method/evidence`
  (`transit/normalization/TripAssociation.kt`). This is the right design for
  a transit app where "why did my departure move" is a real support question.
- **Correct RAPTOR, well optimized** (`routing/RaptorRouter.kt`, 658 lines):
  non-overtaking route grouping so one active trip per pattern scan is
  sufficient, `firstMarkedStop` bounds per round, binary search for catchable
  trips, transfer-margin offsets computed once per (station, incoming,
  outgoing) pattern key (probed at `arrivalTime = 1` and reused as an offset,
  keeping `TransferPolicy` out of the hot loop), parent-linked labels so
  journeys are reconstructed by walking, not copying. The Pareto frontier
  keeps at-most-k journeys by arrival/boardings with a BART preference score
  as tie-break.
- **Domain correctness.** `routing/TransferPolicy.kt` encodes BART-specific
  routing knowledge with a feed-derived floor: feed `transfers.txt` minimums
  always win (`maxOf`), a 5-minute buffer is added only for unofficial
  transfer pairs, preferred stations are computed from reachability (e.g.,
  LAKE only if both line segments actually traverse it in order), and the
  Orange↔Yellow preference flips between MacArthur and 19th based on the
  Yellow leg's station order — not on a feed direction label. That is exactly
  the kind of thing that breaks when you trust GTFS direction labels.
- **Robust static-feed loading** (`networktasks/GtfsStaticData.kt`): 7-day
  cache, content-version check to skip re-import when the feed is unchanged,
  15-minute retry backoff that does not trap the app for a week after a
  failed attempt, corrupted/failed caches retry immediately, migration from
  the legacy zip-cache format on first launch, and feed validation before the
  network object is trusted.
- **Careful Android plumbing**: `goAsync()` in `DeparturePollingReceiver`;
  exact-alarm permission checked *before* every `setAlarmClock` with
  `SecurityException` handled; proper `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`
  settings flow with app-settings fallback
  (`activities/TripInProgressActivity.kt:220-253`); `USE_ALARM` vibration
  pattern that works on silent mode; separate silent/vibrating alarm
  channels; a low-importance ongoing notification for offline-follow state;
  atomic alarm claiming in
  `FollowedTripRepository.handleAlarmTriggered` (lock-held, single winner).
- **Testability seams**: `TimeSource` injection everywhere time matters,
  `Supplier<BartGtfsNetwork>` instead of a hard network reference in
  projectors, constructor-injectable clients, `PerformanceTrace` that no-ops
  unless system tracing is on. 32 JVM test classes covering router,
  normalization, association, schedule, projections, alarm policy, and stores,
  plus live-fixture regression suites and an emulator instrumentation set.

## 3. Issues and risks

### 3.1 Likely defect: KSP vs Kotlin version alignment — verify first (high priority)

`gradle/libs.versions.toml` pins `ksp = "2.3.10"` against
`kotlin = "2.4.20"`. KSP compiler-plugin releases pair with a specific Kotlin
compiler; a KSP build targeting the 2.3.x compiler running inside a 2.4.20
compilation is a classic source of *silent* Room schema/processor divergence.
The root `verifyCompatibility` gate (root `build.gradle`) pins AGP, Kotlin,
androidx-core, Gradle, and JDK — but not KSP.

> Note: the exact pairing could not be confirmed against KSP release notes
> while this review was written offline. Treat this as "confirm, then fix":
> if `2.3.10` targets Kotlin 2.3.x, bump KSP to the 2.4.20-matched release
> and add KSP to the `verifyCompatibility` gate.

### 3.2 Inconsistent recovery on partial feed failure (high priority)

`backend/TransitRepository.kt`, `publishFetchResult` (lines 279–317): if
*either* the trip-updates or the alerts request fails, the merged result is
replaced by the app-supplied **schedule-only offline snapshot**, discarding
the feed that *did* succeed. The deliberate "don't present stale realtime
corrections as current" policy is stated in the comment and is defensible —
but the two refresh paths disagree:

- Full refresh (foreground): one failed feed ⇒ the good feed is dropped too.
- `refreshTripUpdatesNow` (background polling, lines 166–214): failed trip
  updates ⇒ the *previous* trip-updates feed is retained and only the
  offline flag is set.

BART's alerts endpoint is the weaker of the two, so a flaky alerts feed can
push the whole UI to schedule-only while trip updates are perfectly fresh.
Suggested fix: retain last-known-good **per feed** with an explicit staleness
marker, instead of an all-or-nothing snapshot swap.

### 3.3 Followed-trip alarms do not survive a device reboot (medium-high)

One-shot `setAlarmClock` alarms are cleared on reboot, and the app has no
boot/completion receiver to re-arm them. `DepartureAlarmScheduler`'s init
block restores pending state, and `RoutesListActivity.onResume` re-schedules
pending alarms — but only if the app process starts. After a reboot with a
followed trip pending, the departure alarm silently never fires until the
user happens to open the app. Suggested fix: add a minimal boot re-arm (the
exact-alarm permission model already exists), or document this as an accepted
limitation in the UI/README.

### 3.4 `TransitRepository.refreshNow()` blocks the caller thread (medium)

Documented as "intended for tests and explicit refresh actions," and every
current call site is off the main thread (`RoutesListActivity` lifecycle on
`Dispatchers.IO`, `OfflineStatusController` on `Dispatchers.IO`, the polling
receiver). But the contract is only in a KDoc; a future main-thread call is
an ANR. Suggested fix: a `suspend` variant (or an explicit guard) would make
the API honest.

### 3.5 `ui/BartRunnerUi.kt` is a 94 KB, 2,033-line monolith (medium)

34 composables covering theme, home, departures, trip-in-progress, system
map, dialogs, and text formatting all in one file (it even contains
150-character one-liners, e.g. line 2007). It is readable today, but it will
be the top merge-conflict source and the hardest file to navigate in the app.
Suggested fix: split one file per screen (Home / Departures / Trip / Map /
Dialogs), which aligns with the activity structure at near-zero risk.

### 3.6 Duplicated, hardcoded BART policy (medium)

- **Line colors** are hardcoded in `DepartureProjector.colorForLine`
  (fallback: white) even though GTFS route colors are already persisted in
  `GtfsRouteEntity.color` — the same fact exists in two places, and the GTFS
  value would also track BART rebrands.
- **Transfer-preference magic numbers** in
  `TransferPolicy.routeScore` (−200 for the East Bay Blue→Orange→Yellow
  pattern, +80 avoided, +10 non-preferred, +4 BAYF-instead-of-LAKE, +3 busy)
  and the avoided/busy station sets form a coherent mini-policy;
  centralizing them (one `BartRoutingPolicy` object with named constants and
  comments) would make the tuning surface visible in one place.
- **The DMU operational namespace `600..799`** is hardcoded in
  `transit/normalization/TransitNormalization.kt:51`; the Oakland-Airport
  stop filter matches the literal string `"OAKL"` in
  `transit/gtfs/BartGtfsNetwork.kt:440-442`. Fine as-is, but these are the
  facts most likely to change with a BART feed update, and they would benefit
  from named constants plus a `docs/bart_data_audit/` note.
- **`ZoneId.of("America/Los_Angeles")`** is declared independently in at
  least three files (`GtfsStaticData`, `RealtimeFeedNormalizer`, `Schedule`).
  Extract a shared constant.

### 3.7 Smaller items (low)

- **Credential in source**: `LEGACY_API_KEY` in
  `networktasks/BartApiConfig.kt:5` is sent in a query string
  (`ElevatorStatusClient`). BART's legacy key is semi-public by design, but
  it is still a credential in git history and in the app bundle; consider
  local-only secrets injection.
- **Room**: `fallbackToDestructiveMigration(dropAllTables = true)` with zero
  `Migration` objects (`GtfsStaticData.kt:218`). Acceptable for a re-fetchable
  feed, but there is no signal distinguishing "we upgraded the schema" from
  "the file was corrupted" — both just nuke and re-download.
- **Stale build config**: release `ndk.debugSymbolLevel = 'FULL'`
  ("include native debug symbols") in `app/build.gradle` — the app has no
  native code.
- **Backup**: no `allowBackup=false` / no `dataExtractionRules`; the default
  full backup will include the entire GTFS Room DB and `followed_trip.json`.
  Excluding the large DB would keep user backups small.
- **Docs/repo hygiene**: `AGENTS.md` adb examples still use the old package
  `com.dougkeen.bart`; `app/src/main/kotlin/in/izyum/bart/services/` is an
  empty source dir; `app/src/test/java/in/izyum/bart/{routing,transit}` are
  empty legacy trees (tests live under `app/src/test/kotlin`).
- **Prefs growth**: `DepartureAlarmScheduler` writes
  `alarm.<origin>|<dest>|<line>|<direction>|<platform>{.leadTimeMinutes,.pending,.tracking}`
  per distinct departure and never deletes them (unbounded, low-impact).
- **Background cost**: `DeparturePollingProcessor` runs a full RAPTOR
  projection each wakeup to update one departure — cheap on BART's
  ~50-station network, but worth a note if the model generalizes to bigger
  networks.
- **PendingIntent hygiene**: request code `1241` is shared between the
  polling broadcast and its show-activity intent
  (`platform/DeparturePollingAlarm.kt`). It works (different PendingIntent
  types), but per-purpose codes would be less fragile.
- `AlertProjection.translation` takes `translationList[0]` without a language
  check — acceptable for BART (English-only feed).
- `RoutesListActivity` handles static-data warm-up, rider categories, and
  vibration prefs in activity code rather than a ViewModel — a minor
  separation-of-concerns nit.

## 4. Strengths worth preserving

1. The canonical-snapshot boundary and its written rules in `TODO.md` — keep
   enforcing "projectors never touch raw entities."
2. The lossless/provenance normalization design
   (`DuplicateProjectionDecision`, association evidence) — rarer than it
   should be.
3. The fixture strategy: timestamped live captures (including night service
   and terminal-only captures), per-station ETD XML as an independent
   corroboration source, and the `runAllPairs` exhaustive audit gate. The
   open items in `TODO.md` §5/§6 (edge-case fixture matrix: partial stop
   lists, stale origins, duplicate IDs, skew; connected tests;
   persistence-consumer audit) are honest and correctly scoped.
4. The RAPTOR implementation with its BART-specific tie-break scoring — the
   tests around it (overtaking splits, margin offsets, destination pruning)
   match the claims in the code comments.
5. The exact-alarm/notification layer, which handles the permission-revocation
   paths (`SecurityException` caught around every notify) better than most
   alarm apps.

## 5. Suggested priority order

1. **Verify/fix the KSP↔Kotlin pairing** and add KSP to
   `verifyCompatibility` (§3.1).
2. **Decide the partial-failure policy explicitly** and make both refresh
   paths honor it (per-feed last-known-good with staleness labels) (§3.2).
3. **Re-arm followed-trip state after reboot**, or document the limitation
   (§3.3).
4. **Split `BartRunnerUi.kt` per screen** (§3.5).
5. **Consolidate BART policy constants**; derive line colors from the GTFS
   route data already in the DB (§3.6).
6. **Hygiene sweep**: stale NDK flag, empty source dirs, AGENTS.md package
   name, backup rules, shared `PACIFIC_ZONE` constant (§3.7).

---

### Appendix: files examined

Build/config: `build.gradle`, `settings.gradle`, `gradle.properties`,
`gradle/libs.versions.toml`, `app/build.gradle`,
`app/src/main/AndroidManifest.xml`.

Application: `BartRunnerApplication.kt`.

Backend: `TransitRepository.kt`, `TransitFeedClient.kt`,
`HttpTransitFeedClient.kt`, `TransitFeedSnapshot.kt`, `TransitFeedFetchResult.kt`,
`Schedule.kt`, `CanonicalTransitSnapshot.kt`, `DepartureProjector.kt`,
`ItineraryRefreshProjector.kt`, `TripProgressProjection.kt`,
`AlertProjection.kt`, `RouteDepartureProjection.kt`,
`CanonicalRoutingAdapter.kt`.

Routing: `RaptorRouter.kt`, `TransferPolicy.kt`.

Transit: `BartGtfsNetwork.kt`, `GtfsNetworkCatalog.kt`,
`normalization/TransitNormalization.kt`, `normalization/TripAssociation.kt`,
`normalization/StaticTransitNormalizer.kt`.

Network: `GtfsStaticData.kt`, `GtfsStaticDatabase.kt`, `BartApiConfig.kt`,
`ElevatorStatusClient.kt`, `NetworkUtils.kt`.

Platform: `DepartureAlarmScheduler.kt`, `DepartureAlarmPolicy.kt`,
`DepartureAlarmState.kt`, `DeparturePollingAlarm.kt`,
`DeparturePollingProcessor.kt`, `ExactAlarmPermission.kt`,
`OfflineStatusController.kt`.

Receivers: `AlarmBroadcastReceiver.kt`, `DeparturePollingReceiver.kt`.

Data: `FollowedTripRepository.kt`, `FollowedTripStore.kt`,
`FollowedTripRecord.kt`, `FollowedTripState.kt`, `FavoritesRepository.kt`,
`FavoritesUiState.kt`, `AlarmPreferences.kt`, `FareDiscountPreferences.kt`.

Activities: `RoutesListActivity.kt`, `RoutesViewModel.kt`,
`RoutesUiState.kt`, `TripInProgressActivity.kt`, `TripProgressViewModel.kt`,
`TripActionsViewModel.kt`, `DeparturesViewModel.kt`, `ViewDeparturesActivity.kt`,
`ViewMapActivity.kt`, `AboutActivity.kt`, `RouteArguments.kt`.

UI/models/perf: `ui/BartRunnerUi.kt`, `model/Departure.kt`,
`presentation/DepartureTextFormatter.kt`, `performance/PerformanceTrace.kt`.

Tests: 32 classes under `app/src/test/kotlin` plus fixture resources
(GTFS zips, GTFS-RT `.pb` captures 2026-09-08…17, per-station ETD XML);
androidTest set under `app/src/androidTest`.

Docs: `TODO.md`, `AGENTS.md`, `README.md`, `docs/bart_data_audit/`.
