# Person 3 — Implementation Report

**Status labels used:** `IMPLEMENTED` · `AUTOMATED TEST VERIFIED` · `DEVICE VERIFIED` · `CODE INSPECTED` · `NOT VERIFIED` · `BLOCKED` · `OPTIONAL ENHANCEMENT`. No item below is `DEVICE VERIFIED`.

## Executive summary

Implemented a real Safe Journey & Live Location subsystem in the existing NightAgent app. Added journey domain models, Firestore repository, location tracker with filtering, route deviation detector, check-in scheduling/watchdog, foreground tracking service, notification action receiver, boot recovery, Journey ViewModel, and real Journey/SafeWalk UI. Kept existing SOSManager integration by reusing `triggerSOS(context, reason = ...)`. A final verification pass fixed a P0 test hang, timing-sensitive tests, and stale/implausible GPS acceptance; all 34 unit tests and all four Gradle verification commands pass. No device or emulator verification was possible.

## Deliverables

1. **Journey Engine** - `journey/JourneyEngine.kt`: start/complete/cancel/emergency lifecycle, ETA/arrival monitoring, check-in scheduling, deviation escalation, dedupe, runtime recovery.
2. **Live Location Tracker** - `journey/LocationTracker.kt`: FusedLocationProvider continuous updates, current location, filtering (invalid/stale/inaccurate/duplicate/implausible).
3. **Check-in Manager** - implemented inside engine + `JourneyActionReceiver`, `JourneyNotificationHelper`, `CheckInWatchdogWorker`, runtime store. Real notification actions and missed-check-in escalation.
4. **Route Deviation Detector** - `journey/RouteDeviationDetector.kt`: point-to-route segment distance, accuracy-aware threshold, sustained deviation count.
5. **Journey Repository** - `journey/JourneyRepository.kt`: Firestore CRUD for journeys, locations, check-ins, deviations, history; legacy `safe_walks` dual-write.
6. **Maps Integration** - `ui/screens/SafeWalkScreen.kt`: OsmDroid live map with planned route (blue), actual path (red), destination marker, user marker, status/actions. `RouteProvider` fetches OSRM walking route/geocoding.

## Key files created

- `app/src/main/java/com/example/nightagent/journey/model/JourneyModels.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyRepository.kt`
- `app/src/main/java/com/example/nightagent/journey/LocationTracker.kt`
- `app/src/main/java/com/example/nightagent/journey/RouteDeviationDetector.kt`
- `app/src/main/java/com/example/nightagent/journey/RouteProvider.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyEngine.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyTrackingService.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyViewModel.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyNotificationHelper.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyActionReceiver.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyBootReceiver.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyRuntimeStore.kt`
- `app/src/main/java/com/example/nightagent/journey/CheckInWatchdogWorker.kt`
- `app/src/test/java/com/example/nightagent/journey/*Test.kt` + `FakeJourneyRepository.kt`
- `docs/person3/*.md`

## Key files modified

- `app/src/main/AndroidManifest.xml` - ACCESS_BACKGROUND_LOCATION, JourneyTrackingService, JourneyActionReceiver, JourneyBootReceiver.
- `app/build.gradle.kts` - added coroutines-test, mockk test deps.
- `app/src/main/java/com/example/nightagent/ui/screens/JourneyScreen.kt` - replaced demo with real setup/active/history/check-in UI.
- `app/src/main/java/com/example/nightagent/ui/screens/SafeWalkScreen.kt` - replaced simple local demo with real active journey map bound to engine.
- `app/src/main/java/com/example/nightagent/sos/SafeWalkManager.kt` - legacy facade delegating to JourneyEngine to avoid dual tracking.

## Design decisions

- **OsmDroid kept**: existing project uses OsmDroid; no Google Maps key dependency introduced. Maps integration completed on OsmDroid.
- **SOS integration**: reused existing `SOSManager` with a backward-compatible optional `reason` parameter; no new SOS manager. Event reasons appear in logs/SMS/notifications and escalations are deduped per event key. Structured Firestore SOS reason metadata remains a Person 1 coordination item; `markEmergency()` is a status-only path and is not wired to SOS.
- **Route fetch**: OSRM public endpoint used for planned route; failure is non-fatal and deviation detection is disabled when no route points available.
- **Legacy compatibility**: dual-write to `safe_walks` to avoid breaking live-tracker and older readers.
- **Background location**: declared; runtime flow is consent-aware. Full always-on background behavior depends on user granting background location and OEM restrictions.

## Known limitations / external blockers

- No device/emulator runtime verification in this environment (`adb devices` showed one `unauthorized` device; no emulator). All 16 workflow steps are `NOT TESTED`. See `FINAL_INTEGRATION_AUDIT.md` §11.
- Background location runtime request UX is minimal; Play policy-compliant in-app disclosure may need product/legal text refinement. Android 12+ boot FGS restart is `BLOCKED`, `NOT VERIFIED`.
- `SOSManager` now accepts an optional reason string but does not persist it as structured Firestore SOS metadata; that needs Person 1 coordination.
- Runtime location-permission revocation during an active journey surfaces a tracking error but does not auto-cancel the journey or stop the FGS (`CODE INSPECTED`, `NOT VERIFIED`).
- OSRM/Nominatim public endpoints have usage/policy limits; production should use configured routing/geocoding service. OSRM returned HTTP 200 during inspection; device tile/network behaviour is `NOT VERIFIED`.
- Firestore security rules are in the repo at `firestore.rules` (`IMPLEMENTED`, `CODE INSPECTED`) but are **not deployed** (`BLOCKED` pending explicit authorization). Rules validation on emulator/staging is `NOT VERIFIED`.
- Overlapping GPS polling during an SOS + active journey is real but bounded to the 30 s SOS window; documented as `OPTIONAL ENHANCEMENT`, no code change.
- Historical data is persisted/retrievable, but map replay is `OPTIONAL ENHANCEMENT`; only the in-session path is rendered.

## Final verification results

| Check | Status | Result |
|---|---|---|
| `./gradlew :app:clean` | `AUTOMATED TEST VERIFIED` | BUILD SUCCESSFUL |
| `./gradlew :app:compileDebugKotlin` | `AUTOMATED TEST VERIFIED` | BUILD SUCCESSFUL |
| `./gradlew :app:testDebugUnitTest` | `AUTOMATED TEST VERIFIED` | BUILD SUCCESSFUL — 34 tests, 0 failures, 0 errors, 0 skipped |
| `./gradlew :app:assembleDebug` | `AUTOMATED TEST VERIFIED` | BUILD SUCCESSFUL |
| Physical device / emulator workflow | `NOT TESTED` | Attached device unauthorized; no emulator |

Fixes made during verification (no module rewrites):

1. **P0** — `JourneyEngineTest` hang: injected `CoroutineScope` into `JourneyEngine`; tests inject `TestScope` and cancel monitoring inside `runTest`.
2. **P1** — five timing-sensitive tests: use `advanceTimeBy(... + 1)` and `yield()` for kotlinx-coroutines-test 1.8.1 boundary semantics.
3. **P1** — stale/future/implausible GPS: added wall-clock age and future-skew rejection, accuracy-aware jump rejection, and 30 s max age on one-shot fused fixes; added four tests.
4. **P1** — silent write failures: persistence `Result`s are logged.
5. **P2** — injected dispatcher was overridden by `Dispatchers.Default`: public lifecycle methods now use the injected dispatcher.

See `TEST_REPORT.md` for the full test breakdown and `FINAL_INTEGRATION_AUDIT.md` for the 15-section audit.

## Status summary

**Verified completion score:** 61.1% — `(6 implemented + 5 automated-test verified + 0 device verified) / 18`. Methodology is in `FINAL_INTEGRATION_AUDIT.md` §15.

| Deliverable | Status |
|---|---|
| A. Journey Engine | `IMPLEMENTED`, `AUTOMATED TEST VERIFIED`, `CODE INSPECTED` |
| B. Live Location Tracker | `IMPLEMENTED`, `AUTOMATED TEST VERIFIED`, `CODE INSPECTED` |
| C. Check-in Manager | `IMPLEMENTED`, `AUTOMATED TEST VERIFIED` (engine), `CODE INSPECTED` |
| D. Route Deviation Detector | `IMPLEMENTED`, `AUTOMATED TEST VERIFIED`, `CODE INSPECTED` |
| E. Journey Repository | `IMPLEMENTED`, `AUTOMATED TEST VERIFIED` (fake contract), `CODE INSPECTED` |
| F. Maps Integration | `IMPLEMENTED`, `CODE INSPECTED`, `NOT VERIFIED` on device |
| Device workflow | `NOT TESTED` |
| Firestore rules deployment | `BLOCKED` |
| Historical replay / shared GPS strategy | `OPTIONAL ENHANCEMENT` |
