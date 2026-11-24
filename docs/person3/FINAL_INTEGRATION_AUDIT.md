# Person 3 — Final Verification, Bug Fixing & Completion Audit

**Date:** 2026-11-24
**Scope:** Final verification of the existing NightAgent Safe Journey & Live Location implementation (Person 3). No module was rewritten; fixes were minimal and targeted.
**Honesty note:** No physical-device or emulator verification was possible in this session. Nothing below is marked DEVICE VERIFIED.

## Status labels used

`IMPLEMENTED` · `AUTOMATED TEST VERIFIED` · `DEVICE VERIFIED` · `CODE INSPECTED` · `NOT VERIFIED` · `BLOCKED` · `OPTIONAL ENHANCEMENT`

---

## 1. Six deliverables — implementation status

| Deliverable | Status | Evidence / limits |
|---|---|---|
| A. Journey Engine | IMPLEMENTED, AUTOMATED TEST VERIFIED, CODE INSPECTED | `journey/JourneyEngine.kt`. 11 `JourneyEngineTest` tests cover start, complete, cancel, duplicate prevention, state transitions, ETA escalation, check-in ack/miss/help, deviation escalation, location persistence, restoration, SOS dedupe. Device behaviour NOT VERIFIED. |
| B. Live Location Tracker | IMPLEMENTED, AUTOMATED TEST VERIFIED, CODE INSPECTED | `journey/LocationTracker.kt` uses `FusedLocationProviderClient` continuous updates + one-shot current location with 30 s max age; 9 filter tests cover accuracy, stale-by-age, future clock skew, out-of-order timestamps, duplicates, implausible jumps (even at high reported accuracy), normal movement. Offline writes rely on Firestore cache; no explicit retry queue. |
| C. Check-in Manager | IMPLEMENTED, AUTOMATED TEST VERIFIED (engine tests), CODE INSPECTED | Engine scheduling/ack/miss/help + `CheckInWatchdogWorker`, `JourneyActionReceiver`, notifications, runtime store. No dedicated notification/WorkManager instrumentation test; Android behaviour NOT VERIFIED. |
| D. Route Deviation Detector | IMPLEMENTED, AUTOMATED TEST VERIFIED, CODE INSPECTED | `RouteDeviationDetector.kt` (point-to-segment distance, accuracy slack, sustained count); 5 detector tests + engine escalation test. Planned route comes from OSRM; if route fetch fails, deviation detection is disabled (documented fallback). |
| E. Journey Repository | IMPLEMENTED, AUTOMATED TEST VERIFIED (contract fake), CODE INSPECTED | `JourneyRepository.kt` + `FirestoreJourneyRepository.kt` (journeys, locations, check-ins, deviations, history, legacy `safe_walks` dual-write). 4 contract tests run against `FakeJourneyRepository`; live Firestore reads/writes NOT VERIFIED. |
| F. Maps Integration | IMPLEMENTED, CODE INSPECTED, NOT VERIFIED on device | OsmDroid map in `SafeWalkScreen.kt`: current-location marker, destination marker, planned route (blue), in-session actual path (red), OSRM route fetch and Nominatim geocoding in `RouteProvider.kt`; empty/error text states exist. No tile/network/device test was performed. Historical map replay is OPTIONAL ENHANCEMENT. |

**No Google Maps replacement was made.** OsmDroid remains the map implementation.

## 2. Nine Person 3 responsibilities — verification status

| # | Responsibility | Status | Verification |
|---|---|---|---|
| 1 | Safe Journey lifecycle engine | IMPLEMENTED, AUTOMATED TEST VERIFIED | 11 engine tests; code inspected |
| 2 | Live GPS tracking & filtering | IMPLEMENTED, AUTOMATED TEST VERIFIED | 9 filter tests + engine persistence test |
| 3 | Safety check-ins (schedule, respond, miss, cancel) | IMPLEMENTED, AUTOMATED TEST VERIFIED | engine tests + code inspection of receiver/worker/notifications |
| 4 | Route deviation & delayed arrival detection | IMPLEMENTED, AUTOMATED TEST VERIFIED | 5 detector tests + 2 engine escalation tests |
| 5 | Journey/history/event persistence | IMPLEMENTED, AUTOMATED TEST VERIFIED (fake contract) | Firestore implementation CODE INSPECTED only |
| 6 | Maps / OSRM / geocoding | IMPLEMENTED, CODE INSPECTED | OSRM `foot` endpoint returned HTTP 200 with a route during this audit (network evidence only); no device verification |
| 7 | Foreground service, permissions, background execution | IMPLEMENTED, CODE INSPECTED | Android 12+ boot-start and OEM timing limitations documented; NOT VERIFIED on device |
| 8 | SOS integration (Person 1 boundary) | IMPLEMENTED, AUTOMATED TEST VERIFIED (escalator calls), CODE INSPECTED | Reason metadata not persisted as a structured SOS field; see §9 |
| 9 | Tests, documentation, build verification | IMPLEMENTED, AUTOMATED TEST VERIFIED | 34/34 unit tests pass; 4/4 Gradle verification commands pass; docs updated in this pass |

## 3. Evidence categories

- **Verified through code inspection:** all six deliverables, manifest declarations, receivers, notification actions, Firestore paths, rules, SOS call sites, duplicate polling locations, historical data availability.
- **Verified through automated tests:** engine lifecycle, check-in response/miss/help, ETA escalation, deviation escalation, SOS escalation dedupe, location filters, repository contract (fake), model defaults.
- **Verified on a physical device:** **none**.
- **Still requiring physical device testing:** every runtime behaviour in §11, including background tracking, notification actions, boot recovery, permission revocation, OEM battery behaviour, live Firestore writes and map rendering.

## 4. Files created and modified in this pass

**Modified (fixes)**
- `app/src/main/java/com/example/nightagent/journey/JourneyEngine.kt` — injectable `CoroutineScope`/dispatcher (proper test scheduling), `withContext(dispatcher)` instead of hard-coded `Dispatchers.Default`, persistence-failure logging; no behaviour change in production defaults.
- `app/src/main/java/com/example/nightagent/journey/LocationTracker.kt` — wall-clock stale/future timestamp rejection, accuracy-aware implausible-jump rejection, `CurrentLocationRequest.setMaxUpdateAgeMillis(30 s)` for one-shot fixes.
- `app/src/main/java/com/example/nightagent/journey/model/JourneyModels.kt` — `JourneyConfig.maxLocationAgeMs` + default constant.
- `app/src/test/java/com/example/nightagent/journey/JourneyEngineTest.kt` — fixed P0 test hang/incorrect virtual-time assumptions and added 3 tests (help escalation, live persistence + no SOS on completion, deviation escalation).
- `app/src/test/java/com/example/nightagent/journey/LocationTrackerFilterTest.kt` — fixed clock injection and added 4 filter tests.
- all eight `docs/person3/*.md` files.

**Previously created/modified by the Person 3 implementation (unchanged in this pass except as above)**
- `app/src/main/java/com/example/nightagent/journey/`: `JourneyEngine.kt`, `JourneyRepository.kt`, `LocationTracker.kt`, `RouteDeviationDetector.kt`, `RouteProvider.kt`, `JourneyTrackingService.kt`, `JourneyViewModel.kt`, `JourneyNotificationHelper.kt`, `JourneyActionReceiver.kt`, `JourneyBootReceiver.kt`, `JourneyRuntimeStore.kt`, `CheckInWatchdogWorker.kt`, `model/JourneyModels.kt`.
- `app/src/main/java/com/example/nightagent/ui/screens/JourneyScreen.kt`, `SafeWalkScreen.kt`.
- `app/src/main/java/com/example/nightagent/sos/SafeWalkManager.kt` (legacy facade), `app/src/main/java/com/example/nightagent/sos/SOSManager.kt` (backward-compatible `reason` parameter added earlier).
- `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `firestore.rules`.
- `app/src/test/java/com/example/nightagent/journey/`: `FakeJourneyRepository.kt`, `JourneyModelsTest.kt`, `JourneyRepositoryContractTest.kt`, `RouteDeviationDetectorTest.kt`.

## 5. Automated test results

Commands executed after the **final** code changes, from a clean build:

```
./gradlew :app:clean                  -> BUILD SUCCESSFUL
./gradlew :app:compileDebugKotlin     -> BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest      -> BUILD SUCCESSFUL
./gradlew :app:assembleDebug          -> BUILD SUCCESSFUL
```

| Test class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `ExampleUnitTest` | 1 | 0 | 0 | 0 |
| `JourneyEngineTest` | 11 | 0 | 0 | 0 |
| `JourneyModelsTest` | 4 | 0 | 0 | 0 |
| `JourneyRepositoryContractTest` | 4 | 0 | 0 | 0 |
| `LocationTrackerFilterTest` | 9 | 0 | 0 | 0 |
| `RouteDeviationDetectorTest` | 5 | 0 | 0 | 0 |
| **Total** | **34** | **0** | **0** | **0** |

## 6. Critical bugs found and fixed

| Priority | Issue | Root cause | Fix | Status |
|---|---|---|---|---|
| P0 | `JourneyEngineTest` hung forever; test suite could never complete (an older run was observed burning CPU for ~55 minutes) | `JourneyEngine` runs infinite `while` loops on the test dispatcher; `runTest` finalization (`advanceUntilIdle`) kept scheduling delayed tasks | `JourneyEngine` accepts an injectable scope; tests inject `TestScope` and cancel monitoring jobs inside the `runTest` body | AUTOMATED TEST VERIFIED |
| P1 | Five engine tests failed once the hang was removed | `advanceTimeBy` in kotlinx-coroutines-test 1.8.1 is exclusive of the target time and `advanceTimeBy(0)` does not run current-time tasks | Test advances use +1 ms at boundaries and `yield()` to drain queued tasks | AUTOMATED TEST VERIFIED |
| P1 | Stale cached GPS fixes could be accepted (no wall-clock age check); implausible jumps were accepted when reported accuracy was ≤10 m | `shouldAccept()` only compared timestamps to the previous fix and gated speed checks on accuracy | Age/future checks + accuracy-aware speed check; one-shot fix uses 30 s max age | AUTOMATED TEST VERIFIED |
| P1 | Location/check-in/deviation write failures were silently ignored | `Result` values discarded by engine | Logged failures via `onFailure` (no flow change) | CODE INSPECTED |
| P2 | Engine injected dispatcher was overridden by `Dispatchers.Default` in public methods | Hard-coded `withContext(Dispatchers.Default)` | Use injected dispatcher; production default unchanged | AUTOMATED TEST VERIFIED |

## 7. Android compatibility findings (code inspection only)

| Area | Finding | Status |
|---|---|---|
| Runtime location permissions | Fine/coarse requested in `JourneyScreen`/`SafeWalkScreen`; engine refuses start without them | IMPLEMENTED, CODE INSPECTED |
| Background location (Android 10+) | `ACCESS_BACKGROUND_LOCATION` declared; disclosure dialog + Android 10 request / Android 11+ Settings flow present. User can decline and still start; tracking then depends on FGS while-in-use access | IMPLEMENTED, CODE INSPECTED, NOT VERIFIED |
| Foreground service | `JourneyTrackingService`, `exported=false`, `foregroundServiceType="location"`, persistent notification, `startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)` on Q+, `START_STICKY` | IMPLEMENTED, CODE INSPECTED |
| Notifications (Android 13+) | `POST_NOTIFICATIONS` requested; channels created; `notify()` catches `SecurityException` so denial is non-fatal but check-in prompts are then invisible | IMPLEMENTED, CODE INSPECTED |
| Service start/shutdown | Started from UI after `startJourney` success; stops on complete/cancel/inactive via state collector; `ACTION_STOP` stops foreground + service | IMPLEMENTED, CODE INSPECTED |
| Android 12–15 background FGS start from boot | `JourneyBootReceiver` calls `ContextCompat.startForegroundService` from `BOOT_COMPLETED`. Android 12+ generally prohibits starting a location FGS from the background; the exception is caught and logged, so boot recovery may be BLOCKED on those versions | BLOCKED, NOT VERIFIED |
| WorkManager watchdog | One-time `CheckInWatchdogWorker` with `+5 s` delay; WorkManager is deferrable, not an exact alarm. OEM Doze may delay missed-check-in detection | IMPLEMENTED, CODE INSPECTED, NOT VERIFIED |
| Permission revocation during active journey | No dedicated receiver; `LocationTracker` flow closes with `SecurityException`, engine surfaces error but does not auto-cancel journey or stop FGS | CODE INSPECTED, NOT VERIFIED (partial behaviour) |
| Process recreation | Engine singleton + `SharedPreferences` runtime store + Firestore restore + START_STICKY + boot receiver. Restore paths are CODE INSPECTED only | IMPLEMENTED, CODE INSPECTED |
| Battery optimisation | 15 s interval / 5 s fastest fixes, FGS notification, dedupe; OEM battery optimisation behaviour NOT VERIFIED | IMPLEMENTED, CODE INSPECTED |

## 8. Firebase security findings

- `firestore.rules` exists in the repo (the earlier audit note saying it did not was outdated) and is **NOT DEPLOYED**.
- Journey documents: create/read/update/delete restricted to `request.auth.uid == userId`; `userId` cannot be changed by update; `journeyId` must match document ID on create.
- Journey subcollections (`locations`, `checkins`, `deviations`, catch-all): access only when the parent journey's `userId` matches the authenticated user.
- Legacy `safe_walks/{userId}` and its subcollections are owner-only.
- Default rule is deny; unauthenticated access is denied everywhere.
- **Out-of-scope risk:** baseline rules for `sos_alerts`, `sessions`, `locations`, `location_updates`, `chats`, `evidence`, etc. allow any signed-in user. Person 3 did not change Person 1/other teams' data model; these must be tightened by their owners before production.
- Firebase rules validation with emulator/staging: NOT VERIFIED.

**Exact deployment command (requires explicit authorization; DO NOT run without it):**

```
firebase projects:list
firebase use <your-project-id>      # verify it matches google-services.json / app environment
firebase use                        # confirm active project
firebase deploy --only firestore:rules
```

## 9. SOS integration findings (Person 1 boundary)

| Check | Finding | Status |
|---|---|---|
| I Need Help | UI and notification action call `JourneyEngine.requestHelpOnCheckIn()` → persisted `HELP_REQUESTED`/`response=HELP` → `SOSManager.triggerSOS(reason=...)`; manual help without pending check-in calls `SOSManager.triggerSOS()` directly | AUTOMATED TEST VERIFIED + CODE INSPECTED |
| Missed check-in escalation | `markCheckInMissed()` marks MISSED once and escalates through the same SOS escalator | AUTOMATED TEST VERIFIED |
| Delayed arrival escalation | `monitorArrival()` checks ETA + grace and 100 m proximity; escalates once | AUTOMATED TEST VERIFIED |
| Route deviation escalation | Sustained deviation persists an event and escalates once per episode | AUTOMATED TEST VERIFIED |
| Duplicate SOS prevention | Engine dedupe keys + `SOSManager.sosPending` 30 s guard; second help/miss call adds no second SOS reason in tests | AUTOMATED TEST VERIFIED (in-process); device SMS/alert dedupe NOT VERIFIED |
| Emergency contacts / SMS | Unchanged except that the SOS reason line is included in the message; contact loading/sending logic is Person 1's | CODE INSPECTED |
| Journey completion | `completeJourney()`/`cancelJourney()` do not call the SOS escalator; test asserts no SOS reason on completion | AUTOMATED TEST VERIFIED |
| Reason metadata | `SOSManager.triggerSOS(context, reason?, onSOSComplete)` is backward compatible; reason appears in notification/SMS/logs but is **not** written as a structured Firestore SOS field | CODE INSPECTED, limitation |
| `JourneyEngine.markEmergency()` | Sets status EMERGENCY but is not wired to any UI/SOS call; do not report it as an SOS feature | CODE INSPECTED, dead path |

No second SOS implementation was introduced.

## 10. Duplicate GPS polling audit

| Component | Location request | Destination |
|---|---|---|
| `SOSManager.startLiveTracking()` (`sos/SOSManager.kt`) | `LocationProvider.getLocation()` one-shot `PRIORITY_HIGH_ACCURACY`, `maxUpdateAge=15 s`, repeated every 5 s while `sosPending` (30 s window) | `FirestoreManager.updateLocation()` → `sos_alerts/{uid}/sessions/{sid}/location_updates` |
| `JourneyTrackingService` + `JourneyEngine.startMonitoring()` → `LocationTracker.locationUpdates()` | Fused `requestLocationUpdates` `PRIORITY_HIGH_ACCURACY`, 15 s interval / 5 s fastest | `journeys/{journeyId}/locations` + legacy `safe_walks/{uid}/locations` |
| `SafeWalkManager` | Legacy facade only; delegates to `JourneyEngine` + `JourneyTrackingService`, no independent GPS loop | — |

**Finding:** duplicate high-accuracy location acquisition **does occur** when an SOS is triggered during an active journey (e.g. check-in "I Need Help" or a missed check-in). It is bounded to the 30 s SOS window (roughly six extra one-shot fixes) and is additive battery/GPS usage. The two paths serve different persistence and emergency purposes (responder SOS session vs. journey history/deviation), so neither can be removed without breaking Person 1 or Person 3 behaviour. A shared-location strategy would require coupling SOS to the journey engine and adding a safe fallback for SOS with no journey; that is an architectural change beyond this audit's mandate. **Decision: documented limitation, no code change.** Status: CODE INSPECTED, OPTIONAL ENHANCEMENT for a future coordinated design.

## 11. Physical device / emulator status

`adb devices` reports one attached device (`32211FDH2008K7`) with state **unauthorized**; no approved USB debugging session was available, and no emulator was running. Therefore no end-to-end workflow step was executed.

| Step (required workflow) | Status |
|---|---|
| 1. Open NightAgent | NOT TESTED |
| 2. Start a Safe Journey | NOT TESTED |
| 3. Set a destination | NOT TESTED |
| 4. Set an ETA | NOT TESTED |
| 5. Confirm journey ACTIVE | NOT TESTED (state assertion exists only in unit tests) |
| 6. Lock phone / background tracking | NOT TESTED |
| 7. Receive safety check-in notification | NOT TESTED |
| 8. Respond "I'm Safe" | NOT TESTED |
| 9. Verify response persisted | NOT TESTED (unit-tested with fake repository only) |
| 10. Allow another check-in to expire | NOT TESTED |
| 11. Verify missed-check-in escalation | NOT TESTED (unit-tested only) |
| 12. Trigger "I Need Help" / verify SOS | NOT TESTED (escalator call unit-tested only; SMS/status not tested) |
| 13. Complete a journey | NOT TESTED |
| 14. Verify tracking/check-ins stop | NOT TESTED |
| 15. Reopen app / verify history | NOT TESTED |
| 16. Verify real GPS route, OSM tiles, OSRM/Nominatim on device | NOT TESTED |

## 12. Historical journey data

- Location history is persisted at `journeys/{journeyId}/locations` and retrievable through `JourneyRepository.getLocationHistory()`.
- Check-ins and deviation events are persisted at `journeys/{journeyId}/checkins` and `/deviations` and are retrievable by Firestore query; engine/worker reads check-ins for reconciliation.
- History summaries are displayed in `JourneyScreen` via `getJourneyHistory()`.
- The live map renders only the **in-session** actual path; it does not load persisted locations for a past journey, and there is no historical replay screen. No existing project specification requires replay, so this is an OPTIONAL ENHANCEMENT rather than a missing deliverable.

## 13. Remaining prioritized issues

| Priority | Issue | Status |
|---|---|---|
| P0 | Test suite hang / incorrect virtual-time tests | FIXED, AUTOMATED TEST VERIFIED |
| P1 | Stale/implausible GPS acceptance | FIXED, AUTOMATED TEST VERIFIED |
| P1 | Silent persistence failures | FIXED (logging), CODE INSPECTED |
| P1 | Android 12+ boot FGS restart likely blocked | BLOCKED, requires product/device decision; no safe minimal fix chosen |
| P1 | Runtime location permission revocation while journey active is not handled explicitly (tracking error surfaced, journey stays ACTIVE) | BLOCKED for device verification; behaviour CODE INSPECTED |
| P2 | Duplicate GPS polling during SOS + journey overlap | DOCUMENTED limitation, OPTIONAL ENHANCEMENT |
| P2 | Firestore baseline rules for non-Person-3 collections are signed-in-wide | BLOCKED on owning teams / explicit authorization to deploy |
| P2 | SOS reason not stored as structured Firestore metadata | DOCUMENTED limitation; needs Person 1 coordination |
| P2 | OSRM/Nominatim public endpoints have usage/policy limits; no in-app loading spinner for tiles | OPTIONAL ENHANCEMENT |
| P3 | Historical journey map replay / persisted path visualisation | OPTIONAL ENHANCEMENT |

## 14. Exact steps before production deployment

1. Obtain explicit authorization to deploy rules and confirm the target Firebase project matches the app config.
2. Review and tighten non-Person-3 baseline rules with the owning teams; validate Person 3 owner-only rules in the Firebase emulator or a staging project (unauthenticated read/write denied; cross-user journey/location/check-in/deviation access denied).
3. Deploy rules only with `firebase deploy --only firestore:rules` (see §8) — **not performed in this audit**.
4. Authorize USB debugging or provide an emulator and execute all 16 workflow steps in §11 on Android 10, 13, and 14/15 devices; verify FGS notification, background location, notification actions, boot/process recovery, and OEM battery behaviour.
5. Decide how to handle Android 12+ boot FGS restrictions and runtime permission revocation; if required, implement with the app's existing architecture and re-verify.
6. Replace public OSRM/Nominatim endpoints with a production-approved service if usage policy requires it; add explicit map loading/error UI if needed.
7. Coordinate with Person 1 on SOS metadata and on any future shared-location strategy; do not change SOS semantics unilaterally.
8. Re-run the four Gradle verification commands from a clean state and confirm all 34 tests still pass before release.

## 15. Completion percentage (methodology)

Three evidence levels are counted per deliverable: (1) implemented in code, (2) automated-test verified, (3) device verified.

- Code implemented: 6/6 deliverables.
- Automated-test verified: 5/6 (maps has no automated test).
- Device verified: 0/6.
- **Verified completion = (6 + 5 + 0) / 18 = 61.1%.**

This deliberately excludes runtime claims. "Implementation completeness" alone is 6/6 = 100%, but that is not the same as verified completion.
