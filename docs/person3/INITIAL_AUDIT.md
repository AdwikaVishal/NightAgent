# Person 3 — Safe Journey & Live Location: Initial Audit

**Date**: 2026-11-24  
**Scope**: `com.example.nightagent` (NightAgent Android, Kotlin/Compose, Firebase, minSdk 24/target 35)

> **Historical baseline only.** This document records the state found *before* the Person 3 work. The notes below are intentionally left as the original audit snapshot; the current verified state is in `FINAL_INTEGRATION_AUDIT.md` and reconciled in §5 below.

**Status labels used:** `IMPLEMENTED` · `AUTOMATED TEST VERIFIED` · `DEVICE VERIFIED` · `CODE INSPECTED` · `NOT VERIFIED` · `BLOCKED` · `OPTIONAL ENHANCEMENT`. No item in this audit set is `DEVICE VERIFIED`.

## 1. Existing implementation

### SafeWalkManager (`sos/SafeWalkManager.kt`)
- **Singleton object** with in-memory state (`isActive`, handler, start/last location, bearings).
- **Start**: guards `isActive`, sets timers, launches IO coroutine to `SafeWalkRepository.startSafeWalk(uid,startTime)`, captures start location, sends SMS with Google Maps link to `ContactManager` contacts, starts handler monitoring.
- **Monitoring**: `Handler(Looper.getMainLooper())` posts every 5s; one-shot `LocationProvider.getLocation` per tick; runs `checkMovement`, `checkDeviation`, `saveLocation`, `checkTimeout`.
- **Heuristics**: MAX_DURATION 5min, GRACE_PERIOD 60s, NO_MOVEMENT_LIMIT 120s, MAX_DISTANCE_FROM_START 300m, bearing deviation >60° after >10m movement triggers auto-SOS; no movement 120s after grace triggers SOS; >300m from start triggers SOS; timeout 5min triggers SOS.
- **SOS integration**: calls `SOSManager.triggerSOS(context)` (no reason/context passed). Sets `isActive=false`, removes callbacks, shows toast.
- **Stop**: sets inactive, removes callbacks, `repo.endSafeWalk(uid)`.
- **Gaps**: no foreground service, not background-reliable, no journey destination/ETA/route, no check-ins, no persistent recovery, ad-hoc coroutine scopes, no permission/location-sharing consent checks, repo Result ignored, duplicate UI polling loop in SafeWalkScreen, session keyed by user only (overwrites), no arrival confirmation.

### LocationProvider (`sos/LocationProvider.kt`)
- One-shot fused `getCurrentLocation(PRIORITY_HIGH_ACCURACY, maxUpdateAgeMillis=15000)`, checks Play Services and FINE_LOCATION, cancels CTS. No continuous updates, no lastLocation fallback.

### SafeWalkRepository (`repository/SafeWalkRepository.kt`)
- Firestore `safe_walks/{userId}` set on start (`SafeWalkModel(userId,startTime,isActive)`), `safe_walks/{userId}/locations/{autoId}` add `LocationModel(lat,lng,timestamp)`, end updates `isActive=false`. All `Result<Unit>`, IO dispatcher. No journey history, no endTime, no destination, no check-ins.

### Models
- `LocationModel(latitude, longitude, timestamp)` - Parcelable.
- `SafeWalkModel(userId, startTime, isActive)` - Parcelable.

### UI
- **SafeWalkScreen**: local Compose state; when walking polls `LocationProvider` every 3s, draws OsmDroid polyline/marker; Start calls `SafeWalkManager.startSafeWalk`, End calls `stopSafeWalk`. No ViewModel, no permission handling, no lifecycle map detach, no recovery, no destination/ETA/check-in UI.
- **JourneyScreen**: explicitly demo/UI-only. Simulated timer/distance/check-in countdown; missed check-in shows banner "guardians would be alerted (demo)" but no real SOS; history from `DemoData.journeys`; "Open live Safe Walk map" navigates to `safewalk`.
- **MapScreen/SafetyMapOverlay**: real OsmDroid map with safety score overlay, POI fetch, lifecycle handling. Reusable overlay helpers.

### Navigation (`navigation/NavGraph.kt`)
- `safewalk` -> `SafeWalkScreen()`, `journey` -> `JourneyScreen(onOpenSafeWalk={navigate("safewalk")})`. Home has SafeWalk quick action; Settings has Journey Check-in link. `safewalk` is full-screen route (no bottom bar).

### Manifest / Permissions
- Declares `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_CAMERA`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `INTERNET`.
- **No dedicated journey/location tracking foreground service declared**. Existing FGS: `StealthDetectionService(location)`, `AudioRecordingService(microphone)`, `AudioStreamingService(microphone)`, `EvidenceCaptureService(microphone|location)`.
- **Missing**: `ACCESS_BACKGROUND_LOCATION` not declared (needed for background tracking on Android 10+ if requested).

### Build / dependencies
- `osmdroid-android:6.1.16`, `play-services-location` (via catalog), `work-runtime-ktx:2.9.0`, Firebase BOM 32.7.0 (auth/firestore/storage/messaging), coroutines 1.8.1, accompanist-permissions 0.34.0, lifecycle-service 2.7.0. No mockk/coroutines-test/turbine/robolectric in test deps.

### Firebase / Auth
- `FirebaseConfig.firestore` has 100MB persistent cache. `AuthManager.getCurrentUserId()` returns uid or null. MainActivity does anonymous login on startup.
- Firestore paths: `safe_walks/{uid}` and `safe_walks/{uid}/locations/{autoId}` (SafeWalk); `sos_alerts/{uid}/sessions/{sid}/location_updates/{autoId}` (SOS). Live tracker reads `safe_walks/{uid}/locations` and assumes timestamp in seconds (mismatch: app writes millis).

### SOS integration
- `SOSManager.triggerSOS(context, onSOSComplete={})` - no reason param, in-memory `sosPending`, handler-based 30s window, sends SMS, starts Firestore SOS session, starts in-process 5s live tracking to `sos_alerts/.../location_updates`. `SafeWalkManager` calls `triggerSOS(context)` only.

### Existing tests
- Only `ExampleUnitTest` and `ExampleInstrumentedTest` templates. No safewalk/journey/location tests.

## 2. Functional / partial / broken / missing

| Area | Status | Notes |
|---|---|---|
| SafeWalk start/stop | Partial | Works foreground-only; no FGS, no recovery, no destination |
| Live GPS tracking | Partial | One-shot polling, no continuous Fused updates, no FGS |
| Location persistence | Partial | `safe_walks/{uid}/locations`, no journey history, no offline retry strategy beyond Firestore cache |
| Check-ins | Missing (demo UI) | JourneyScreen simulates; no real scheduling/persistence/SOS |
| Route deviation | Partial | Bearing/distance-from-start heuristics only; no planned route comparison |
| ETA/delayed arrival | Missing | 5-min hard timeout only; no destination ETA |
| Journey repository | Partial | SafeWalkRepository only; no journey lifecycle/history/checkins |
| Maps integration | Partial | OsmDroid in SafeWalkScreen/MapScreen; no destination/route/progress for journey |
| Background tracking | Missing | No location FGS for journey; handler-based |
| Active journey recovery | Missing | In-memory only |
| SOS integration for journey events | Partial | Generic triggerSOS only; no event reason/context, no dedupe by event |

## 3. Integration dependencies / risks
- **Person 1 SOS**: `SOSManager.triggerSOS` signature has no reason/metadata; changing it may affect other callers. Need backward-compatible extension.
- **Existing SafeWalkManager callers**: SafeWalkScreen, possibly others. Keep public API compatible.
- **Firestore schema**: existing `safe_walks` used by live-tracker; changing schema breaks tracker. Need compatibility/migration strategy.
- **Background location**: requires ACCESS_BACKGROUND_LOCATION declaration and careful runtime flow; Play policy sensitive. Must be explicit and consent-driven.
- **FGS location type**: already declared in manifest; service must declare `foregroundServiceType="location"` and start appropriately.
- **No Firestore rules in repo (initial finding — now outdated)**: `firestore.rules` was added in this work; it is `IMPLEMENTED` in repo but `BLOCKED` on deployment. See §5.

## 4. Planned changes
- Add `journey/` domain package: models, state, engine, repository, location tracker, check-in manager, route deviation detector, FGS.
- Keep `SafeWalkManager` as compatibility facade delegating to new engine where possible.
- Upgrade SafeWalkRepository or add JourneyRepository with `journeys` collection; keep `safe_walks` writes for live-tracker compatibility during active journey.
- Add real Journey UI (replace demo behavior), update SafeWalkScreen to use ViewModel/engine state.
- Add WorkManager check-in scheduler + notification actions; add FGS notification with actions.
- Add route calculation via OSRM/OSMDroid road manager or simple route polyline from open routing; if unavailable, implement distance-to-polyline deviation against stored route points.
- Add unit tests with fakes; add test deps.
- Document all external config/blockers.

## 5. Current-state reconciliation (2026-11-24)

| Initial gap / risk | Current status | Evidence / limit |
|---|---|---|
| No journey engine, check-ins, ETA or route deviation | `IMPLEMENTED`, `AUTOMATED TEST VERIFIED`, `CODE INSPECTED` | `journey/JourneyEngine.kt` + 11 engine tests; device behaviour `NOT VERIFIED` |
| Handler-based foreground-only GPS | `IMPLEMENTED`, `AUTOMATED TEST VERIFIED` (filter/persistence), `CODE INSPECTED` | `LocationTracker` Fused updates + 9 filter tests; real GPS `NOT VERIFIED` |
| No foreground service / recovery | `IMPLEMENTED`, `CODE INSPECTED` | `JourneyTrackingService`, runtime store, Firestore restore; Android 12+ boot FGS restart `BLOCKED`, `NOT VERIFIED` |
| `safe_walks` only persistence | `IMPLEMENTED`, `AUTOMATED TEST VERIFIED` (fake contract) | `journeys` schema + legacy dual-write in `FirestoreJourneyRepository`; live Firestore `NOT VERIFIED` |
| Demo Journey UI / no destination route | `IMPLEMENTED`, `CODE INSPECTED`, `NOT VERIFIED` | ViewModel-backed `JourneyScreen`/`SafeWalkScreen`, OsmDroid + OSRM/Nominatim; no device/network tile verification |
| SOS had no reason/metadata | `IMPLEMENTED`, `AUTOMATED TEST VERIFIED` (escalator), `CODE INSPECTED` | Backward-compatible `SOSManager.triggerSOS(context, reason = ...)`; reason not a structured Firestore SOS field |
| No Firestore rules in repo (initial finding) | `IMPLEMENTED` in repo, `BLOCKED` deployment | `firestore.rules` exists with owner-only Person 3 rules; not deployed without explicit authorization |
| Only template tests | `AUTOMATED TEST VERIFIED` | 34/34 unit tests pass; clean/compile/test/assemble all BUILD SUCCESSFUL |
| Physical/device behaviour | `NOT TESTED` | Attached device `32211FDH2008K7` is `unauthorized`; no emulator. All 16 workflow steps unexecuted |
| Overlapping SOS + journey GPS polling | `CODE INSPECTED`, `OPTIONAL ENHANCEMENT` | Real but bounded 30 s SOS overlap; no safe minimal fix without Person 1 coupling |
| Historical map replay | `OPTIONAL ENHANCEMENT` | History data persisted/retrievable; replay screen not required by spec |
