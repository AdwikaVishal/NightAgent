# Person 3 Audit — Safe Journey / Safe Walk

**Auditor mode:** Read-only. Based on direct source inspection of journey files, manifest, nav, rules, and functions.  
**Status legend:** COMPLETE / PARTIAL / NOT INTEGRATED / BROKEN / MISSING / BLOCKED.

## Scope Inspected
- `journey/**` (JourneyEngine, JourneyRepository, JourneyViewModel, JourneyTrackingService, LocationTracker, RouteProvider, RouteDeviationDetector, CheckInWatchdogWorker, JourneyActionReceiver, JourneyBootReceiver, JourneyRuntimeStore, JourneyNotificationHelper, model/JourneyModels.kt)
- `sos/SafeWalkManager.kt`, `repository/SafeWalkRepository.kt`, `model/SafeWalkModel.kt`
- `ui/screens/JourneyScreen.kt`, `ui/screens/SafeWalkScreen.kt`
- `MainActivity.kt`, `navigation/NavGraph.kt`, `AndroidManifest.xml`, tests under `app/src/test/.../journey/**`
- `firestore.rules`, `functions/src/index.ts`

## Sub-feature Assessment

| Sub-feature | Status | Evidence (file:line) | Callers / Execution Path | Gaps / Risks |
|---|---|---|---|---|
| JourneyEngine | **PARTIAL** | `JourneyEngine.kt:17-` singleton `getInstance`, `startJourney` creates Firestore journey, starts tracking service, schedules check-in watchdog, restores runtime; escalator default calls `SOSManager.triggerSOS(reason=...)` (`:24`) | `JourneyViewModel.kt:11,27-40` starts/observes; `JourneyTrackingService.kt:15-20` binds engine; `JourneyActionReceiver.kt:18` handles actions; `CheckInWatchdogWorker.kt:13` invokes engine | Direct SOS escalation bypasses TriggerManager; full runtime behavior not executed in audit. |
| JourneyRepository | **PARTIAL** | `JourneyRepository.kt:17-34` interface; `FirestoreJourneyRepository.kt:36-191` implements create/get/update/saveLocation/checkIns/deviations/history; writes `journeys/{journeyId}` and legacy `safe_walks/{userId}` | Used by JourneyEngine/JourneyViewModel (per codebase wiring) | Legacy safe_walks location write shape mismatch with rules (`rules` expect `lat,lng,time`; repo writes `latitude,longitude,timestamp,accuracy,journeyId` at `JourneyRepository.kt:91-99`). |
| JourneyViewModel | **PARTIAL** | `JourneyViewModel.kt:11-66` exposes engine state/history, `startJourney`, `respondToCheckIn`, `endJourney`, debug `triggerSOSNow` | `JourneyScreen.kt:47-49,144` and `SafeWalkScreen.kt` collect state/use actions | `triggerSOSNow` calls `SOSManager.triggerSOS` directly (`JourneyViewModel.kt:56`), bypassing trigger policy. |
| Journey screens | **PARTIAL** | `JourneyScreen.kt:45-280` start form, active journey, history, background location disclosure, route geocode/fetch; `SafeWalkScreen.kt:38-166` live/empty state with polylines, check-in actions | NavGraph routes `journey` and `safewalk` (`NavGraph.kt:154,202-204`); Settings and Home link to them | UI state not saved with `rememberSaveable`; runtime depends on location permissions/services. |
| Start/end journey | **PARTIAL** | Engine start method and service start in `JourneyEngine.kt` (startJourney ~:81+), end/cancel paths present; ViewModel exposes `endJourney` | UI start button and end actions | Not runtime-verified; error handling depends on Firestore/location availability. |
| Destination management | **PARTIAL** | `JourneyScreen.kt` has destination input, geocode via `RouteProvider.geocode`, route fetch; `RouteProvider.kt` exists | User enters destination in UI | Geocode/route provider behavior and failure modes not fully tested in audit. |
| Live GPS tracking | **PARTIAL** | `JourneyTrackingService.kt:15-20` foreground service obtains engine; manifest declares FGS location (`AndroidManifest.xml:137-142`); `LocationTracker.kt` exists | Started by engine; boot/action receivers registered | Background/Android 14+ FGS constraints not runtime-verified. |
| Foreground location service | **PARTIAL** | `AndroidManifest.xml:137-142` declares `JourneyTrackingService` `foregroundServiceType="location"` | `JourneyEngine`/service lifecycle | Needs runtime verification of notification/type compliance and start conditions. |
| Location updates in Firestore | **PARTIAL** | `JourneyRepository.kt:82-113` saves `journeys/{journeyId}/locations` and legacy `safe_walks/{userId}/locations` | Tracking engine on location updates | Legacy write shape conflicts with `firestore.rules` exact `lat,lng,time` requirement for safe_walks locations. |
| Check-in mechanism | **PARTIAL** | `CheckInWatchdogWorker.kt:9-19` runs engine timeout check; `JourneyEngine.kt` schedules `OneTimeWorkRequest` (`:383-427` in inspected area) and stores check-in records; `JourneyNotificationHelper.kt` handles notifications | Scheduled at journey/check-in creation; UI responds to check-in | End-to-end notification/action behavior not runtime-verified. |
| Deviation detection | **PARTIAL** | `RouteDeviationDetector.kt` exists; engine stores deviation events (`JourneyRepository.saveDeviationEvent`) | Engine location processing | Algorithm/runtime threshold behavior not audited by execution. |
| Missed check-in escalation | **PARTIAL** | Watchdog worker triggers engine; engine escalator calls SOSManager | Check-in timeout | Escalation bypasses TriggerManager; guardian notification chain still broken downstream. |
| SOS integration | **PARTIAL** | `JourneyEngine.kt:24` calls `SOSManager.triggerSOS`; `SafeWalkManager.kt` delegates to JourneyEngine | Check-in timeout/escalation/manual debug | SOS guardian delivery incomplete (Person 1/2). |
| Guardian notifications | **BROKEN / MISSING** | Depends on SOSManager/GuardianRepositoryImpl; guardian push not wired | Escalation path | No real guardian FCM delivery. |
| Recovery after process death | **PARTIAL** | `JourneyBootReceiver` declared (`AndroidManifest.xml:154-161`); `JourneyRuntimeStore.kt` persists runtime; engine restores on init | Boot completed / app restart | Runtime recovery not verified on device. |
| Background location permissions | **PARTIAL** | Manifest has `ACCESS_BACKGROUND_LOCATION` (`AndroidManifest.xml:11`); JourneyScreen has background disclosure flow (`JourneyScreen.kt:228-279`) | Journey start flow | Separate background-location grant flow and OEM restrictions not runtime-verified. |
| Offline handling | **PARTIAL** | Firestore writes via repository; WorkManager/check-ins local scheduling | Journey runtime | No explicit offline queue beyond Firestore SDK behavior; error states need verification. |
| Tests | **PARTIAL** | `app/src/test/.../journey/*`: `JourneyEngineTest`, `JourneyRepositoryContractTest`, `JourneyModelsTest`, `LocationTrackerFilterTest`, `RouteDeviationDetectorTest`, `FakeJourneyRepository` | JVM tests present | Not executed in this audit; environment/Android deps may affect run. |

## Duplicates / Canonical Candidates

| Area | Files | Canonical candidate | Notes |
|---|---|---|---|
| Journey vs SafeWalk | `journey/*`, `ui/screens/JourneyScreen.kt` vs `sos/SafeWalkManager.kt`, `repository/SafeWalkRepository.kt`, `model/SafeWalkModel.kt`, `ui/screens/SafeWalkScreen.kt` | **JourneyEngine/Journey* stack** as primary; SafeWalkManager is compatibility facade; SafeWalkScreen is active-journey monitor | Keep SafeWalk UI if product needs it, but back it by Journey state only. |

## Completion

| Dimension | % | Basis |
|---|---:|---|
| Code implementation | 75% | Substantial engine/service/repo/UI/test code present. |
| Application integration | 65% | Reachable screens and service wiring exist; backend/guardian/escalation E2E incomplete. |
| Runtime verification | 25% | Not executed in audit. |
| **Overall** | **59%** | Strongest feature area, still not fully verified E2E. |
