# Person 1 Audit — SOS Emergency Engine

**Auditor mode:** Read-only. Evidence from direct source inspection and completed subagent audit.  
**Status legend:** COMPLETE / PARTIAL / NOT INTEGRATED / BROKEN / MISSING / BLOCKED.

## Scope Inspected
- `app/src/main/java/com/example/nightagent/sos/**` (`SOSManager.kt`, `engine/SOSEngine.kt`, `TriggerManager.kt`, `PowerButtonReceiver.kt`, `ShakeDetector.kt`, `VoiceSOSManager.kt`, `LocationProvider.kt`, `SOSNotification.kt`, `SOSActionReceiver.kt`, `EvidenceRecorder.kt`, `EvidenceUploader.kt`, `SafeWalkManager.kt`, `SafetySettings.kt`)
- `model/EmergencySession.kt`, `repository/EmergencySessionRepository.kt`, `repository/SOSRepository.kt`
- `MainActivity.kt`, `navigation/NavGraph.kt`, `ui/screens/HomeScreen.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`
- `app/src/test/java/com/example/nightagent/sos/**`
- Related Firebase rules/functions for SOS paths

## Sub-feature Assessment

| Sub-feature | Status | Evidence (file:line) | Callers / Execution Path | Gaps / Risks |
|---|---|---|---|---|
| State machine | **PARTIAL (core strong)** | `SOSEngine.kt:26-33` transitions; `:75-87` idempotent initiate; `:110-130` transition validation; `:144-163` max-duration expiry; `:165-206` restore/reset/update | `SOSManager.engine` (`SOSManager.kt:42`); used by trigger/ack/resolve/cancel/restore/auth (`SOSManager.kt:118,170,203,220,241,271,323,338,427`); UI observes `engine.sessionState` (`HomeScreen.kt:41-46`) | No explicit expired state; terminal behavior encoded as CANCELLED/RESOLVED. |
| Activation / cancellation | **PARTIAL** | `TriggerManager.onTrigger` gates/dedupes/dispatches (`TriggerManager.kt:24-62`); `SOSManager.triggerSOSInternal` initiates, notification, alarm, save, location, SMS, activate, tracking, auto-resolve (`SOSManager.kt:69-197`); cancel/resolve/ack (`:202-256`) | Manual: `NavGraph.kt:99-142` starts evidence, triggers MANUAL, navigates evidence. Notification: `SOSActionReceiver.kt:20-27`. UI cancel: `HomeScreen.kt:129-130`. | `JourneyEngine.kt:24`, `JourneyViewModel.kt:56` bypass TriggerManager; `sosactive` route dead (`NavGraph.kt:231-257`, no navigate caller); callback single-slot (`SOSManager.kt:53,109`). |
| Power button trigger | **PARTIAL** | `PowerButtonReceiver.kt:17-36` counts 2 screen toggles within 2s, checks `SafetySettings.powerButtonSOS`, calls `TriggerManager.POWER_BUTTON` | Dynamically registered in `MainActivity.kt:137-142`; unregistered `onDestroy:192` | Not manifest-registered; no background reliability unless process alive. UI text says "3-press" (`SafetyScreen.kt:120-124`) but code triggers on 2. |
| Shake trigger | **PARTIAL** | `ShakeDetector.kt:10-90` accel threshold, 2 shakes within 1s, 2s cooldown, checks setting | Foreground in `MainActivity.kt:103-106,172-188`; background in `StealthDetectionService.kt:89-104` | Duplicate listeners possible (MainActivity + StealthDetectionService); dedupe mitigates double SOS but wastes sensors/CPU. |
| Voice trigger | **PARTIAL** | `VoiceSOSManager.kt:16-223` keyword recognition, 5s cooldown, enable/disable, foreground-only by design | `MainActivity.kt:107-127,172-188` enables/disables by setting and lifecycle | Foreground-only; stops on pause. No background voice trigger. |
| Emergency session persistence | **PARTIAL** | `EmergencySessionRepository.kt:59-103` local-first save; `:111-146` Firestore sync to `sos_alerts/{userId}/sessions/{sessionId}`; `:152-184` location updates; `:192-225` retry; `:231-251` unfinished/unsynced; `:274-288` reassign anonymous | `SOSManager` saves on trigger/activate/ack/resolve/cancel/restore/auth (`SOSManager.kt:139,172,357,209,227,248,279,330`) | Rules require non-empty `message` (`firestore.rules:153-166`) but repo defaults `message=""` (`EmergencySessionRepository.kt:62,131`) - sync may fail rule validation on some saves. |
| Location tracking | **PARTIAL** | `LocationProvider.kt:14-70` fused current location; `SOSManager.kt:488-521` 5s live loop while ACTIVE/ACKNOWLEDGED; `EmergencySessionRepository.kt:152-184` writes `location_updates` | Started after activation (`SOSManager.kt:185`) and restore (`:345-350`); stopped by `stopSideEffects` (`:388-401`) | No SOS foreground service; Handler/main-process bound. Offline location updates not queued. |
| Duplicate trigger prevention | **PARTIAL** | `TriggerManager.kt:16,37-55` per-type 2s dedupe + active session guard; `SOSManager.kt:97-107` second guard; `SOSEngine.kt:83-86` idempotent initiate | All UI/service/receiver triggers through TriggerManager except Journey direct calls | In-memory dedupe lost on process death; direct SOSManager calls bypass TriggerManager dedupe/settings. |
| Process-death recovery | **PARTIAL** | `MainActivity.kt:58-59` calls `restoreUnfinishedSession`; `SOSManager.kt:311-360` resyncs, restores engine, expires if overdue, INITIATED->ACTIVE, shows notification, starts tracking, listens ack, reschedules auto-resolve | On app startup | No alarm restart, no evidence restart, no SOS FGS, background execution not guaranteed. |
| Guardian notification integration | **BROKEN / MISSING** | `SOSManager.kt:450-481` listens for `acknowledgedBy`/ACKNOWLEDGED on session doc; SMS to `ContactManager` contacts (`SOSManager.kt:529-604`) | SOS activation sends SMS; no FCM/guardian push in SOSManager | No guardian push delivery. Backend `sendEmergencyNotification` exists (`functions/src/index.ts:237-261`) but not called by Android; it only records notification. Rules owner-only block guardian access (`firestore.rules:148-179`). |
| SOS-to-evidence integration | **PARTIAL** | NavGraph manual SOS starts `EvidenceCaptureService` with timestamp sosId and stops on completion (`NavGraph.kt:100-133`); SOSManager stops evidence on side-effect cleanup (`SOSManager.kt:388-401`) | Manual home SOS only | Other triggers (shake/voice/power/stealth/journey) do not start evidence capture. EvidenceCaptureService not tied to EmergencySession.id. |
| SOS-to-safe-journey escalation | **PARTIAL** | `JourneyEngine.kt:24` default escalator calls `SOSManager.triggerSOS(reason=...)`; `JourneyViewModel.kt:56` direct call | Journey check-in timeout/escalation path | Bypasses TriggerManager. Reason passed but audit needed for full escalation semantics. |
| Navigation to active SOS screen | **PARTIAL / BROKEN UX** | `HomeScreen.kt:119-146` shows cancel button for active session; notification opens MainActivity (`SOSNotification.kt:59-67`); route `sosactive` exists (`NavGraph.kt:231-257`) | Home screen only | No auto-navigation to active SOS screen; `sosactive` route unreachable/dead. |
| Offline behavior | **PARTIAL** | Local-first session save and retry (`EmergencySessionRepository.kt:65-102,192-225`); SMS works offline/device-side | SOS trigger, resync on startup | Location updates not queued offline (`addLocationUpdate` fails and returns failure, no replay). Guardian push absent. |
| Android permissions / FGS | **PARTIAL** | Manifest: CAMERA, RECORD_AUDIO, SEND_SMS, FINE/COARSE/BACKGROUND location, FGS types, POST_NOTIFICATIONS, WAKE_LOCK (`AndroidManifest.xml:5-28`); EvidenceCaptureService FGS mic|location (`:129-135`) | MainActivity requests core runtime permissions (`MainActivity.kt:196-212`) | No SOS-specific foreground service. Background location not separately requested. Power receiver dynamic only. |
| Unit / integration tests | **PARTIAL** | `SOSEngineTest.kt` (state/idempotency/max-duration/restore/auth), `TriggerManagerTest.kt` (gating/dedupe/callbacks/restore), `EmergencySessionRepositoryTest.kt` (local save/failure/retry/reassign/unsynced) | JVM unit tests; MockK/coroutines-test configured (`app/build.gradle.kts:121-124`) | No SOSManager integration tests, no receiver/sensor/service instrumentation, no rules contract tests for SOS payloads, no runtime execution in this audit. |

## Duplicates / Dead Code

| Item | Evidence | Used? | Risk | Recommendation |
|---|---|---|---|---|
| `SOSEngine` vs `SOSManager` | `SOSManager.kt:42` owns engine | Both used | Low | Keep separation; document responsibilities. |
| `EmergencySessionRepository` vs `SOSRepository`/`SOSModel` | `SOSRepository.kt:10-24` writes `sos_logs`; no callers found | Legacy/dead | Medium confusion | Deprecate/remove after confirming no backend dependency. |
| `sos/EvidenceRecorder` vs `evidence/EvidenceCaptureService` | `HomeScreen.kt:243-289` uses EvidenceRecorder; NavGraph/SOS uses EvidenceCaptureService | Both used, different purposes | High confusion | Rename/clarify: manual note recorder vs SOS auto evidence pipeline. |
| `sos/EvidenceUploader.kt` | Hardcoded 3gp, no callers found | Dead | Medium | Remove or wire with tests. |
| `sosactive` route | `NavGraph.kt:231-257` | Dead | Low | Remove or wire to active session state. |

## Completion

| Dimension | % | Basis |
|---|---:|---|
| Code implementation | 72% | Core engine, manager, triggers, persistence, tests exist. |
| Application integration | 60% | Manual SOS wired; guardian push/evidence-all-triggers/active SOS UX incomplete. |
| Runtime verification | 20% | Not run on device/emulator in audit; unit tests present but not executed here. |
| **Overall** | **55%** | Core exists, end-to-end emergency workflow incomplete. |
