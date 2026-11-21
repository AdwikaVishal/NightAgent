# NightAgent — Implementation Roadmap

All tasks are derived from the audit findings. Complexity: LOW / MEDIUM / HIGH.
Acceptance criteria are testable statements. No source was modified during the
audit; these are proposed changes.

---

## P0 — Critical fixes (do first; unblock everything else)

| ID | Description | Modules/files | Deps | Complexity | Acceptance criteria |
|---|---|---|---|---|---|
| P0-1 | **Remove committed Firebase API key**: add `google-services.json` to `.gitignore`, inject at build time, rotate key in Firebase Console | `app/google-services.json`, `.gitignore`, CI config | — | LOW | `google-services.json` not tracked by git; build injects it |
| P0-2 | **Fix power-button SOS trigger**: `PowerButtonReceiver.onReceive` must call `SOSManager.triggerSOS(context)` on 2 presses within window; remove duplicate dynamic registration in `MainActivity` | `sos/PowerButtonReceiver.kt`, `MainActivity.kt` | P0-5 (permission handling) | LOW | 2 presses within window trigger full SOS flow (SMS + Firestore + tracking); no duplicate receiver |
| P0-3 | **Persist safety toggles**: move `SafetySettings` (voiceSOS, shakeDetection, powerButtonSOS, silentMode, autoRecording) to `EncryptedSharedPreferences`/`SharedPreferences` so they survive process death | `sos/SafetySettings.kt` | — | LOW | Toggles retain value after app restart |
| P0-4 | **Stop logging PII/location**: remove or guard `Log.d` calls emitting phone numbers, SMS bodies, locations, UIDs | `sos/SOSManager.kt`, `sos/LocationProvider.kt`, `streaming/CallViewModel.kt`, others | — | LOW | No PII/location in Logcat in release builds |
| P0-5 | **Set `allowBackup="false"`** and review backup rules | `AndroidManifest.xml`, `res/xml/backup_rules.xml` | — | LOW | `allowBackup="false"` in release manifest |

P0 tasks are independent of each other and can be done in any order.

---

## P1 — Core product improvements

| ID | Description | Modules/files | Deps | Complexity | Acceptance criteria |
|---|---|---|---|---|---|
| P1-1 | **Add foreground location tracking service** so Safe Walk and SOS tracking survive backgrounding | New `tracking/LocationTrackingService.kt`; `sos/SafeWalkManager.kt`; `sos/SOSManager.kt` | P0-3 (persisted settings), P0-2 | MEDIUM | Tracking continues after app is backgrounded; stops on explicit stop; foreground notification shown |
| P1-2 | **Add dependency injection (Hilt or Koin)** for ViewModels, repositories, Firebase instances, LocationProvider | `app/build.gradle.kts` (DI plugin), `*ViewModel`, `*Repository` | — | MEDIUM | All ViewModels injected; no manual `new Repository()` in UI |
| P1-3 | **Unify live-tracking data path**: SOS tracking and Safe Walk should write to a shared `live_tracking/{userId}/locations` collection so the web viewer (or a mobile guardian view) can consume one stream | `firebase/FirestoreManager.kt`, `sos/SOSManager.kt`, `sos/SafeWalkManager.kt`, `live-tracker/index.html` | P1-1 | MEDIUM | Single source of truth for live locations; viewer reads same path app writes |
| P1-4 | **Fix / remove dead code**: delete `sos/EvidenceUploader.kt` (wrong path, never called); remove or point `network/SafetyApiService` at a real backend | `sos/EvidenceUploader.kt`, `network/SafetyApiService.kt`, `network/NetworkModule.kt` | — | LOW | Dead code removed; no unused placeholder Retrofit |
| P1-5 | **Replace duplicate root `navigation/NavGraph.kt`** (package `com.example.womensafetyapp`, unused) — delete it | `navigation/NavGraph.kt` | — | LOW | File removed; app NavGraph is the single source |
| P1-6 | **Add consent + retention for location**: wire `locationSharing` toggle to tracking; add Firestore security rules (owner UID read/write); add data-retention cleanup | `sos/SafetySettings.kt`, Firestore rules, tracking code | P0-3 | MEDIUM | Location only shared when toggle on; rules enforced server-side |
| P1-7 | **Add TURN server for WebRTC** (streaming fails behind symmetric NAT with STUN only) | `streaming/WebRTCManager.kt` (iceServers), TURN service (e.g. coturn) | — | MEDIUM | Audio stream connects across symmetric NAT |

P1-1 depends on P0-2/P0-3. P1-3 depends on P1-1. Others are largely independent.

---

## P2 — Differentiating features (stealth product moat)

| ID | Description | Modules/files | Deps | Complexity | Acceptance criteria |
|---|---|---|---|---|---|
| P2-1 | **Guardian mobile live-view**: build an in-app guardian screen (Compose + OsmDroid/Map) that reads the unified live-tracking collection; replace detached `live-tracker/index.html` | New `tracking/` guardian screen; `live-tracker/index.html` (retire) | P1-3 | HIGH | Guardian can open app and see live location of active SOS/Safe Walk sessions |
| P2-2 | **Stealth onboarding flow**: first-launch prompts PIN setup + explains calculator disguise + 3-toggle trigger | `ui/stealth/StealthSettingsScreen.kt`, `MainActivity.kt` | P0-3 | LOW | New user must set PIN before stealth can be enabled; flow explained |
| P2-3 | **Evidence integrity UI**: show SHA-256 hashes and upload status per evidence file in EvidenceScreen | `evidence/EvidenceScreen.kt`, `evidence/EvidenceManager.kt` | — | LOW | User can see hash + upload state for each evidence item |
| P2-4 | **Speech-to-text transcription of evidence audio** (ML Kit or Cloud Speech-to-Text) for evidentiary value | `evidence/EvidenceManager.kt`, new transcription worker | P2-3 | HIGH | Audio evidence can be transcribed to text |
| P2-5 | **Fake Call polish**: complete the incoming-call simulation (ringtone, answer/decline, screen on lock) | `ui/activities/FakeCallActivity.kt` | — | MEDIUM | Fake call launches fullscreen, shows on lock screen, answer/decline behave |

P2-1 depends on P1-3. P2-4 depends on P2-3.

---

## P3 — Advanced capabilities

| ID | Description | Modules/files | Deps | Complexity | Acceptance criteria |
|---|---|---|---|---|---|
| P3-1 | **Anomaly detection on Safe Walk motion** (fall / sudden stillness auto-SOS) using accelerometer ML | `sos/ShakeDetector.kt`, new ML model | P1-1 | HIGH | Auto-SOS triggers on fall/stillness with low false positives |
| P3-2 | **Offline-first SOS queue**: queue SOS/evidence writes locally (Room) when offline, sync on reconnect | New `data/local/` Room DB, repository changes | P1-2 | HIGH | SOS works offline; data syncs when connectivity returns |
| P3-3 | **Guardian two-way audio** (guardian can speak back to victim) | `streaming/WebRTCManager.kt`, `streaming/CallViewModel.kt` | P1-7 | HIGH | Guardian microphone audio reaches victim device |
| P3-4 | **Safety-score route planning**: avoid "risky" Overpass zones when sharing ETA | `repository/SafetyRepository.kt`, `ui/screens/MapScreen.kt` | — | MEDIUM | Map can highlight/avoid risky zones |

P3-2 depends on P1-2. P3-3 depends on P1-7.

---

## P4 — Production hardening & release

| ID | Description | Modules/files | Deps | Complexity | Acceptance criteria |
|---|---|---|---|---|---|
| P4-1 | **Real test coverage**: JUnit + MockK for repositories/ViewModels; Compose UI tests for SOS, Safe Walk, stealth unlock | `app/src/test/`, `app/src/androidTest/` | P1-2 (DI enables testing) | HIGH | ≥70% coverage on domain layer; UI tests pass on CI |
| P4-2 | **Lint + baseline**: run `./gradlew :app:lint`, fix warnings, add lint baseline | `build.gradle.kts` | — | MEDIUM | Lint passes on CI |
| P4-3 | **ProGuard/R8 rules** for Firebase, WebRTC, CameraX, WorkManager; enable minify in release | `app/build.gradle.kts` (`isMinifyEnabled = true`), `proguard-rules.pro` | — | MEDIUM | Release APK minified and functional |
| P4-4 | **App signing + release config**: signing configs, versioning, Play Console setup | `app/build.gradle.kts` | — | MEDIUM | Signed release APK/AAB producible |
| P4-5 | **Deploy FCM Cloud Function** for voice-message push (documented in `VoiceMessageFirebase.kt` comments) | Firebase console / `functions/` (new) | — | MEDIUM | Voice-message push notifications delivered |
| P4-6 | **Battery optimization testing**: foreground service exemptions, Doze/Standby behavior | Manifest foreground service types | P1-1 | MEDIUM | Tracking/streaming survive Doze on test devices |

P4-1 depends on P1-2. P4-5 is independent but required for voice messaging to work end-to-end.

---

## Execution order (suggested)

```
P0-1, P0-2, P0-3, P0-4, P0-5     (critical fixes, any order)
   → P1-1 → P1-3                  (tracking foundation)
   → P1-2                         (DI, enables testing)
   → P1-4, P1-5, P1-6, P1-7       (cleanup + features)
   → P2-1, P2-2, P2-3             (differentiation)
   → P2-4, P2-5
   → P3-1, P3-2, P3-3, P3-4       (advanced)
   → P4-1, P4-2, P4-3, P4-4, P4-5, P4-6  (hardening + release)
```

**Independent tasks** (can start immediately): P0-1, P0-4, P0-5, P1-4, P1-5,
P2-3, P4-5.
**Blocking chain:** P0-2 → P1-1 → P1-3 → P2-1 (the core live-tracking
spine).
