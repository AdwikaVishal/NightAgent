# NightAgent — Integration Roadmap (Person 6: Firebase Backend, Architecture & Integration)

**Repository:** `NightAgent-Final-sms-feature-safe/` — single Gradle module `:app`
**Firebase project:** `nightagent-35d38` (project number `480993110584`)
**Package:** `com.example.nightagent`
**Status:** READ-ONLY audit. No source files modified. All items below marked [EXISTING] (present in repo) or [PROPOSED] (to be built).

---

## Person 6 responsibility snapshot

| # | Responsibility | Classification | Key existing files |
|---|----------------|----------------|--------------------|
| 1 | Firebase Authentication | Partially implemented | `firebase/AuthManager.kt`, `firebase/FirebaseConfig.kt` |
| 2 | Cloud Firestore + repositories | Partially implemented | `firebase/FirestoreManager.kt`, `repository/SOSRepository.kt`, `repository/SafeWalkRepository.kt`, `repository/EvidenceRepository.kt`, `repository/SafetyRepository.kt` |
| 3 | Firebase Cloud Storage | Partially implemented | `voicemessage/firebase/VoiceMessageFirebase.kt` (uploadAudio), `evidence/EvidenceUploadWorker.kt`, `voicemessage/upload/VoiceUploadWorker.kt` |
| 4 | Firebase Cloud Messaging | Partially implemented | `firebase/MyFirebaseMessagingService.kt`, `firebase/AuthManager.kt` (saveFCMToken) |
| 5 | Cloud Functions (TypeScript) | Not implemented | — (documented as JS comment only in `VoiceMessageFirebase.kt`) |
| 6 | Firestore & Storage security rules | Not implemented | — (no `.rules` files) |
| 7 | Shared data models + repository contracts | Partially implemented | `voicemessage/model/VoiceMessage.kt`, `model/LocationModel.kt`, `model/SOSModel.kt`, `model/SafeWalkModel.kt`, `model/SafetyResponse.kt` |
| 8 | Integration of six team modules | Partially implemented | `MainActivity.kt` (god class), `navigation/NavGraph.kt` |
| 9 | Firebase Emulator + GitHub Actions CI | Not implemented | — (no `firebase.json`, no `.github/workflows`) |

---

## Files to REUSE (do not duplicate)

Extend/wrap these. Do not reimplement.

### Firebase core
- `app/src/main/java/com/example/nightagent/firebase/FirebaseConfig.kt` — lazy `firestore`, `storage`, `auth`, `messaging` singletons + 100 MB offline persistence.
- `app/google-services.json` — Firebase config (contains live API key; inject at build time, do not commit).

### Auth
- `app/src/main/java/com/example/nightagent/firebase/AuthManager.kt` — `loginAnonymous`, `loginWithEmail`, `createUser`, `logout`, `getCurrentUserId`, `addAuthStateListener`, `saveFCMToken`.

### Firestore (client)
- `.../firebase/FirestoreManager.kt` — `startSOSSession`, `updateLocation` (callbacks; not suspend).
- `.../repository/SOSRepository.kt` — `sendSOS(SOSModel)` → `sos_logs`.
- `.../repository/SafeWalkRepository.kt` — `startSafeWalk`, `updateLocation`, `endSafeWalk` → `safe_walks`.
- `.../repository/EvidenceRepository.kt` — `uploadFile` → Storage + `sos_logs.evidenceUrl` (UNUSED).
- `.../repository/SafetyRepository.kt` — Overpass safety score (non-Firebase).

### Storage
- `.../voicemessage/firebase/VoiceMessageFirebase.kt` — `uploadAudio` → `voice_messages/{senderId}/{messageId}.m4a` via `putStream`; `saveMessage`; `messagesFlow`; `queuePushNotification`; `registerUser`; `findUidByPhone`; `ensureChatRoom`; `updateFcmToken`.
- `.../evidence/EvidenceUploadWorker.kt` — WorkManager evidence upload with retry.
- `.../voicemessage/upload/VoiceUploadWorker.kt` — WorkManager voice upload with retry.

### FCM (client)
- `.../firebase/MyFirebaseMessagingService.kt` — `onMessageReceived` (voice_message/generic), `onNewToken` → `saveFCMToken`, notification channel, deep link `voicechat/{senderId}`.

### Data models
- `.../voicemessage/model/VoiceMessage.kt` — `VoiceMessage`, `UserProfile`, `ChatRoom`, `UploadStatus`, `MessageStatus` + `toFirestoreMap()`.
- `.../model/LocationModel.kt`, `SOSModel.kt`, `SafeWalkModel.kt`, `SafetyResponse.kt`.

### Navigation
- `.../navigation/NavGraph.kt` — Compose NavHost (the app's real one; root `navigation/NavGraph.kt` is a dead duplicate — ignore).

---

## Roadmap by phase

### Phase 0 — Critical security & backend foundation (P0)

Blocks everything else. Do first. No inter-blocking dependencies within Phase 0.

| ID | Task | Type | File paths | Deps | Priority | Acceptance criteria |
|----|------|------|------------|------|----------|---------------------|
| P0-1 | Rotate Firebase API key; stop committing `google-services.json`; inject at build time via CI/local.properties | SECURITY | `.gitignore`, `app/google-services.json`, CI config | — | P0 | `google-services.json` untracked; build injects it; key rotated in Firebase Console |
| P0-2 | Create `firestore.rules` — owner-UID read/write for `users`, `chats`, `chats/{chatId}/messages`, `sos_alerts`, `safe_walks`; `fcm_requests` client-write-only | SECURITY | `firestore.rules` (new) | P0-1 | P0 | Rules compile; emulator tests pass; no `allow read, write: if true` |
| P0-3 | Create `storage.rules` — authenticated upload, path-scoped to `{uid}/`, 50 MB cap, audio/video content-type | SECURITY | `storage.rules` (new) | P0-1 | P0 | Emulator tests pass; size cap enforced |
| P0-4 | Implement FCM Cloud Function (TypeScript) watching `fcm_requests` → Admin SDK send → delete doc | FUNCTION | `functions/src/index.ts` (new), `functions/package.json` (new), `functions/tsconfig.json` (new) | Firebase project access (Blaze plan) | P0 | Function deploys; `fcm_requests` create triggers push; doc deleted after send |
| P0-5 | Auto-create `users/{uid}` profile on anonymous/email sign-up | AUTH | `firebase/AuthManager.kt` | — | P0 | New user gets `users/{uid}` doc on first sign-in |
| P0-6 | Fix FCM payload key mismatch: sender writes `chatId`, receiver reads `conversationId` | INTEGRATION | `voicemessage/firebase/VoiceMessageFirebase.kt`, `firebase/MyFirebaseMessagingService.kt` | — | P0 | Unified key; deep link `voicechat/{senderId}` opens correctly |

### Phase 1 — Repository contracts & unified data layer (P1)

Introduce formal contracts so the data layer is testable and UI/managers stop calling Firebase directly.

| ID | Task | Type | File paths | Deps | Priority | Acceptance criteria |
|----|------|------|------------|------|----------|---------------------|
| P1-1 | Define repository interfaces: `AuthRepository`, `UserRepository`, `ChatRepository`, `SosRepository`, `StorageRepository`, `FcmRepository` | CONTRACT | `data/repository/*.kt` (new interfaces) | — | P1 | Interfaces exist; existing classes implement them |
| P1-2 | Route all Firestore/Storage writes through repositories; remove direct `FirebaseConfig` / `FirebaseFirestore.getInstance()` calls from UI, managers, services | ARCHITECTURE | `*Repository.kt`, `FirestoreManager.kt`, `VoiceMessageFirebase.kt`, `MainActivity.kt`, `sos/*`, `stealth/*`, `evidence/*`, `streaming/*` | P1-1 | P1 | No UI/manager calls Firebase SDK directly; all via repository |
| P1-3 | Unify parallel SOS paths: merge `sos_alerts/{uid}/sessions/...` and `sos_logs/{auto-id}` into one canonical `sos_sessions/{uid}` collection with `SosSession` + `SosLocationUpdate` models | SCHEMA | `data/model/SosSession.kt` (new), `data/model/SosLocationUpdate.kt` (new), `repository/SosRepository.kt` (new/refactor) | P1-1, FIR01 (rules) | P1 | Single SOS write path; `SOSModel` gains `message` field or is deprecated |
| P1-4 | Add Firestore transactions/batched writes for SOS session start + initial location + SMS log | RELIABILITY | `repository/SosRepository.kt` | P1-3 | P1 | SOS start is atomic across session + initial location |
| P1-5 | Persist `SafetySettings` toggles (voiceSOS, shakeDetection, powerButtonSOS, silentMode, autoRecording, locationSharing) to EncryptedSharedPreferences/SharedPreferences | RELIABILITY | `sos/SafetySettings.kt` | — | P1 | Toggles survive process death/restart |
| P1-6 | Add data retention / TTL for `sos_alerts` and `safe_walks` locations (scheduled Cloud Function or client cleanup) | PRIVACY | `functions/src/` (new) or client cleanup | P0-2 | P1 | Old SOS/safe-walk docs auto-delete after retention window |
| P1-7 | Restrict `users/{uid}/fcmToken` read to owner only; `fcm_requests` read/delete by service account only | SECURITY | `firestore.rules`, `functions/src/index.ts` | P0-2, P0-4 | P1 | Token not enumerable by other users |

### Phase 2 — Emulator, CI, and testing (P2)

| ID | Task | Type | File paths | Deps | Priority | Acceptance criteria |
|----|------|------|------------|------|----------|---------------------|
| P2-1 | Add `firebase.json` + emulator config (Auth, Firestore, Storage, Functions) | INFRA | `firebase.json` (new), `emulators/` (new) | P0-2, P0-3 | P2 | Emulators start locally; rules tested against emulator |
| P2-2 | Add GitHub Actions CI: build debug + unit tests + Firestore/Storage rules unit tests | INFRA | `.github/workflows/ci.yml` (new) | P0-1 | P2 | CI runs on push/PR; fails on rules/test failure |
| P2-3 | Add backend unit tests for repositories (mock Firebase or use emulator) | TEST | `app/src/test/` | P1-1, P2-1 | P2 | ≥70% coverage on repository layer |
| P2-4 | Add instrumentation tests for FCM token flow and voice send pipeline | TEST | `app/src/androidTest/` | P2-1 | P2 | Token refresh writes to Firestore; voice send creates message + fcm_request |

### Phase 3 — Live tracking unification & integration seams (P3)

| ID | Task | Type | File paths | Deps | Priority | Acceptance criteria |
|----|------|------|------------|------|----------|---------------------|
| P3-1 | Unify live-tracking data path: SOS tracking and Safe Walk write to shared `live_tracking/{userId}/locations` | SCHEMA | `firebase/FirestoreManager.kt`, `sos/SOSManager.kt`, `sos/SafeWalkManager.kt`, `repository/SafeWalkRepository.kt`, `live-tracker/index.html` | P1-1, P0-2 | P3 | Single source of truth; viewer reads same path app writes |
| P3-2 | Add foreground `LocationTrackingService` so Safe Walk and SOS tracking survive backgrounding | RELIABILITY | New `tracking/LocationTrackingService.kt`; manifest service declaration | P1-5 (persisted settings) | P3 | Tracking continues after app backgrounded; foreground notification shown |
| P3-3 | Add dependency injection (Hilt or Koin) for ViewModels, repositories, Firebase instances, LocationProvider | ARCHITECTURE | `app/build.gradle.kts` (DI plugin), `*ViewModel`, `*Repository` | P1-1 | P3 | All ViewModels injected; no manual `new Repository()` in UI |
| P3-4 | Build in-app guardian live-view (Compose + map) reading unified tracking collection; retire detached `live-tracker/index.html` | FEATURE | New `tracking/` guardian screen | P3-1 | P3 | Guardian can open app and see live location of active SOS/Safe Walk sessions |

### Phase 4 — Production hardening (P4)

| ID | Task | Type | File paths | Deps | Priority | Acceptance criteria |
|----|------|------|------------|------|----------|---------------------|
| P4-1 | Enable ProGuard/R8 minify in release; add rules for Firebase, WebRTC, CameraX, WorkManager | RELEASE | `app/build.gradle.kts` (`isMinifyEnabled = true`), `proguard-rules.pro` | — | P4 | Release APK minified and functional |
| P4-2 | Add app signing + release config; versioning; Play Console setup | RELEASE | `app/build.gradle.kts` | — | P4 | Signed release APK/AAB producible |
| P4-3 | Run lint + baseline; fix warnings | QUALITY | `build.gradle.kts` | — | P4 | `./gradlew :app:lint` passes on CI |
| P4-4 | Battery optimization testing: foreground service exemptions, Doze/Standby | QUALITY | Manifest foreground service types | P3-2 | P4 | Tracking/streaming survive Doze on test devices |
| P4-5 | TURN server for WebRTC (streaming fails behind symmetric NAT with STUN only) | RELIABILITY | `streaming/WebRTCManager.kt` (iceServers), external TURN service | — | P4 | Audio stream connects across symmetric NAT |

---

## Execution order (suggested)

```
P0-1, P0-2, P0-3, P0-4, P0-5, P0-6     (critical security + backend foundation)
   → P1-1 → P1-2                        (contracts + centralize data access)
   → P1-3 → P1-4 → P1-5 → P1-6 → P1-7   (unify SOS, transactions, persist settings, retention, token access)
   → P2-1 → P2-2 → P2-3 → P2-4          (emulator, CI, tests)
   → P3-1 → P3-2 → P3-3 → P3-4          (tracking unification, foreground service, DI, guardian view)
   → P4-1 → P4-2 → P4-3 → P4-4 → P4-5   (hardening + release)
```

**Independent tasks (can start immediately):** P0-1, P0-2, P0-3, P1-4, P1-5, P2-1, P4-5 (scoping).
**Blocking chain:** P0-1 → P0-2/P0-3 → P1-1 → P1-2 → P1-3 → P3-1 → P3-2 (core backend spine).

---

## Existing vs proposed — clear distinction

**EXISTING (reuse, do not duplicate):** `FirebaseConfig.kt`, `AuthManager.kt`, `FirestoreManager.kt`, `MyFirebaseMessagingService.kt`, all `*Repository.kt` classes, `VoiceMessageFirebase.kt`, `VoiceMessage.kt` models, `NavGraph.kt` (app module), `EvidenceUploadWorker.kt`, `VoiceUploadWorker.kt`.

**PROPOSED (new files Person 6 creates):**
- `firestore.rules`, `storage.rules`, `firebase.json`
- `functions/src/index.ts`, `functions/package.json`, `functions/tsconfig.json`
- `data/repository/*.kt` (interfaces), `data/model/SosSession.kt`, `data/model/SosLocationUpdate.kt`, `data/model/EvidenceFile.kt`, `data/model/FcmRequest.kt`
- `repository/SosRepository.kt` (unified), `tracking/LocationTrackingService.kt`
- `.github/workflows/ci.yml`
- `res/xml/network_security_config.xml`

**DO NOT CREATE (duplicates of existing):**
- A second `FirebaseConfig` — use the existing one.
- A second `NavGraph` — use `app/src/main/.../navigation/NavGraph.kt`; ignore root `navigation/NavGraph.kt`.
- A second auth manager — extend `AuthManager.kt`.
- A second FCM service — extend `MyFirebaseMessagingService.kt`.

---

## Missing information requiring team confirmation

| # | Item | Why it blocks | Owner to confirm |
|---|------|---------------|------------------|
| M-1 | Firebase project ownership (`nightagent-35d38`) | Needed to rotate API key, deploy functions, configure rules | Person 6 / project owner |
| M-2 | Blaze plan for Cloud Functions | FCM send via Admin SDK requires paid plan | Person 6 / billing owner |
| M-3 | Firestore/Storage current mode (test vs locked) | Determines urgency of rules deployment | Person 6 |
| M-4 | Retention window for SOS/safe-walk locations | Needed for TTL/cleanup function | Product lead |
| M-5 | `conversationId` vs `chatId` FCM payload key | Determines which side to fix for deep links | Person 6 + voice team |
| M-6 | Emergency contacts: local-only or sync to Firestore? | Determines if `users/{uid}/contacts` collection needed | Contacts team |
| M-7 | Phone normalization: India (`+91`) or global? | Affects `findUidByPhone` E.164 matching | Product lead |
| M-8 | CI platform (GitHub Actions assumed) & secrets injection | Needed for `google-services.json` build injection | DevOps |
| M-9 | Firebase Emulator: which services to emulate? | Determines `firebase.json` config | Person 6 |
| M-10 | DI library choice (Hilt vs Koin vs hand-rolled) | Affects `app/build.gradle.kts` and all ViewModels | Architecture lead |

---

## Deliverables produced by this audit

| Document | File path | Purpose |
|----------|-----------|---------|
| Backend Audit | `audit/BACKEND_AUDIT.md` | Responsibility classification, reuse list, roadmap P0–P4 |
| Firebase Schema | `audit/FIREBASE_SCHEMA.md` | Complete Firestore + Storage schema, collection paths, indexes |
| Data Models | `audit/DATA_MODELS.md` | All existing models + proposed models, traceability |
| API Contracts | `audit/API_CONTRACTS.md` | Repository interfaces, FCM payload, storage, integration seams |
| Security Audit | `audit/SECURITY_AUDIT.md` | Severity-classified findings, rules templates, remediation |
| Integration Roadmap | `audit/INTEGRATION_ROADMAP.md` | This document — phased execution order with acceptance criteria |
