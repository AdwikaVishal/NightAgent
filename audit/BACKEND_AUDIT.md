# NightAgent — Backend Audit (Person 6: Firebase Backend, Architecture & Integration)

**Repository:** `NightAgent-Final-sms-feature-safe/` (single Gradle module `:app`)
**Firebase project:** `nightagent-35d38` (see `app/google-services.json`)
**Package:** `com.example.nightagent`
**Method:** Evidence-based source inspection only. No source files modified. No build/tests executed (no Android SDK on this machine).

---

## Responsibility classification

| # | Responsibility | Classification | Summary |
|---|----------------|----------------|---------|
| 1 | Firebase Authentication | **Partially implemented** | Anonymous + email/password auth exist in `AuthManager.kt`; no Google/phone auth, no email verification, no password reset, no custom claims, no profile auto-creation on sign-up. |
| 2 | Cloud Firestore architecture & repositories | **Partially implemented** | Basic CRUD exists via `FirestoreManager.kt` + repository classes, but repositories are thin and frequently bypassed; UI/managers call Firebase SDK directly; no transactions/batches; no unified data layer. |
| 3 | Firebase Cloud Storage | **Partially implemented** | Voice audio uploads work (`VoiceMessageFirebase.uploadAudio` → `voice_messages/...`); evidence upload path exists via `EvidenceUploadWorker`/`EvidenceRepository` but is partially dead/unused; no Storage security rules; no file validation/size limits. |
| 4 | Firebase Cloud Messaging | **Partially implemented** | Client-side FCM reception (`MyFirebaseMessagingService`), token registration (`saveFCMToken`/`updateFcmToken`), data-message handling, deep links all exist. Server-side Cloud Function that actually sends pushes is **not** in repo (documented as JS comment only). |
| 5 | Firebase Cloud Functions (TypeScript) | **Not implemented** | No `functions/` directory, no `package.json`, no `index.ts`, no `firebase.json`. FCM send function exists only as a commented snippet inside `VoiceMessageFirebase.kt`. |
| 6 | Firestore & Storage security rules | **Not implemented** | No `.rules` files anywhere in the repo. Firestore/Storage presumed in test/open mode; no owner-UID rules, no validation, no retention enforcement. |
| 7 | Shared data models & repository contracts | **Partially implemented** | Data models exist (`VoiceMessage.kt`, `LocationModel.kt`, `SOSModel.kt`, `SafeWalkModel.kt`, `SafetyResponse.kt`) with inline schema docs. No formal repository interfaces/contracts, no shared data module, models scattered across packages. |
| 8 | Integration of six team members' modules | **Partially implemented** | All code co-located in `:app` and wired through `MainActivity` (god class) + `NavGraph`. No DI, no module boundaries, no explicit contracts between feature areas. Functional but fragile integration. |
| 9 | Firebase Emulator + GitHub Actions CI | **Not implemented** | No emulator config, no CI workflows, no `firebase.json`, no rules emulation, no test pipeline. |

---

## Existing classes/files to REUSE (do not duplicate)

These are the current backend assets. Any new implementation must extend or wrap these, not reimplement them.

### Firebase configuration & SDK instances
- `app/src/main/java/com/example/nightagent/firebase/FirebaseConfig.kt` — lazy singletons for `FirebaseFirestore`, `FirebaseStorage`, `FirebaseAuth`, `FirebaseMessaging` with 100 MB offline persistence. **Reuse as the single Firebase access point.**
- `app/google-services.json` — Firebase project config (contains live API key; see SECURITY_AUDIT S-01). **Do not overwrite; inject at build time.**

### Authentication
- `app/src/main/java/com/example/nightagent/firebase/AuthManager.kt` — `loginAnonymous()`, `loginWithEmail()`, `createUser()`, `logout()`, `getCurrentUserId()`, `addAuthStateListener()`, `saveFCMToken()`. **Reuse; extend with profile creation on sign-up.**

### Firestore (client)
- `app/src/main/java/com/example/nightagent/firebase/FirestoreManager.kt` — `startSOSSession(message, lat, lng)`, `updateLocation(lat, lng)`. Writes to `sos_alerts/{uid}/sessions/{sessionId}`. **Reuse; consider folding into a repository.**
- `app/src/main/java/com/example/nightagent/repository/SOSRepository.kt` — `sendSOS(SOSModel)` → `sos_logs`, `getCurrentUserId()`. Thin wrapper. **Reuse as the SOS data gateway.**
- `app/src/main/java/com/example/nightagent/repository/SafeWalkRepository.kt` — `startSafeWalk`, `updateLocation`, `endSafeWalk` → `safe_walks/{uid}` + `locations`. **Reuse.**
- `app/src/main/java/com/example/nightagent/repository/EvidenceRepository.kt` — `uploadFile(fileUri, userId, sosId)` → Storage + `sos_logs/{sosId}/evidenceUrl`. Currently unused/dead. **Reuse or remove.**
- `app/src/main/java/com/example/nightagent/repository/SafetyRepository.kt` — Overpass API safety score (non-Firebase; raw HTTP). **Reuse for map; not Firebase.**

### Cloud Storage
- `app/src/main/java/com/example/nightagent/voicemessage/firebase/VoiceMessageFirebase.kt` — `uploadAudio(file, senderId, messageId)` → `voice_messages/{senderId}/{messageId}.m4a` via `putStream`. **Reuse as the Storage upload pattern.**
- `app/src/main/java/com/example/nightagent/evidence/EvidenceUploadWorker.kt` — WorkManager background upload of Evidence dir. **Reuse for evidence upload resilience.**
- `app/src/main/java/com/example/nightagent/voicemessage/upload/VoiceUploadWorker.kt` — WorkManager voice upload with retry. **Reuse.**

### Cloud Messaging (client)
- `app/src/main/java/com/example/nightagent/firebase/MyFirebaseMessagingService.kt` — `onMessageReceived` (voice_message + generic), `onNewToken` → `AuthManager.saveFCMToken`, notification channel, deep-link `voicechat/{senderId}`. **Reuse; extend payload handling.**
- `AuthManager.saveFCMToken(token)` — writes `fcmToken` to `users/{uid}`. **Reuse.**

### Data models (shared)
- `app/src/main/java/com/example/nightagent/voicemessage/model/VoiceMessage.kt` — `VoiceMessage`, `UserProfile`, `ChatRoom`, `UploadStatus`, `MessageStatus`, `VoiceMessageUiModel`, `PlaybackState` + inline Firestore schema docs. **Reuse as the canonical voice-message model.**
- `app/src/main/java/com/example/nightagent/model/LocationModel.kt`, `SOSModel.kt`, `SafeWalkModel.kt`, `SafetyResponse.kt` (+ `SafetyFactors`). **Reuse.**

### Navigation / integration seams
- `app/src/main/java/com/example/nightagent/navigation/NavGraph.kt` — Compose NavHost routes (home, map, contacts, safety, settings, voicechat/{otherUserId}, etc.). **Reuse as the single navigation graph.** (The root `navigation/NavGraph.kt` is a dead duplicate — do not use.)
- `app/src/main/java/com/example/nightagent/MainActivity.kt` — single Compose host; wires auth, FCM, sensors, NavGraph. **Reuse but do not expand; it is already a god class.**

---

## What is MISSING (requires team confirmation)

| Item | Why it matters | Confirmation needed |
|------|----------------|---------------------|
| Cloud Functions source | FCM push for voice messages cannot be delivered without the `fcm_requests` trigger function deployed. | Who owns Firebase project `nightagent-35d38`? Who deploys functions? |
| Firestore/Storage security rules | No rules exist; data is readable/writable by anyone if in test mode. | What read/write policy per collection? Owner-only? Public reads for map? |
| Data retention policy | SOS locations written every 5 s with no TTL/deletion. | How long to keep `sos_alerts`, `safe_walks`, `voice_messages`? |
| FCM function runtime | Node.js version, region, billing plan for functions. | Confirm Blaze plan if needed (FCM send requires it). |
| Emulator suite | Needed for local backend testing before CI. | Which Firebase services to emulate (Auth, Firestore, Storage, Functions)? |
| CI provider | GitHub Actions assumed. | Confirm CI platform and secrets injection for `google-services.json`. |
| Phone normalization | `+91` hardcoded for 10-digit numbers (India-specific). | Is the app India-only or global? Affects E.164 formatting. |

---

## Implementation roadmap for Person 6 (backend focus)

### P0 — Critical backend hardening (unblock security & messaging)

| ID | Task | File paths | Dependencies | Priority | Acceptance criteria |
|----|------|------------|--------------|----------|---------------------|
| P0-1 | Create Firestore security rules (owner-UID read/write for `users`, `chats`, `sos_alerts`, `safe_walks`) | `firestore.rules` (new at repo root or `app/`) | — | P0 | Rules compile; owner-only access enforced; no wildcard read/write |
| P0-2 | Create Storage security rules (authenticated upload only, path-scoped to `voice_messages/{uid}/` and `evidence/{uid}/`) | `storage.rules` (new) | P0-1 | P0 | Only authenticated users can upload; path matches user UID |
| P0-3 | Implement FCM Cloud Function (TypeScript) that watches `fcm_requests` and sends push via Admin SDK | `functions/src/index.ts` (new), `functions/package.json` (new), `functions/tsconfig.json` (new) | Firebase project access | P0 | Function deploys; `fcm_requests` doc triggers a push; doc deleted after send |
| P0-4 | Wire `google-services.json` out of git; inject via CI/local.properties | `.gitignore`, `app/google-services.json`, CI config | — | P0 | `google-services.json` not tracked; build injects it |
| P0-5 | Auto-create `users/{uid}` profile on anonymous/email sign-up | `AuthManager.kt` | — | P0 | New user gets a `users/{uid}` doc on first sign-in |

### P1 — Backend architecture & repository contracts

| ID | Task | File paths | Dependencies | Priority | Acceptance criteria |
|----|------|------------|--------------|----------|---------------------|
| P1-1 | Define repository interfaces (contracts) for Auth, Firestore, Storage, FCM | New `data/repository/` interfaces (e.g. `AuthRepository`, `FirestoreRepository`) | — | P1 | Interfaces exist; current classes implement them |
| P1-2 | Centralize all Firebase writes behind repositories; stop direct SDK calls from UI/managers | `*Repository.kt` classes; `FirestoreManager.kt`, `VoiceMessageFirebase.kt` | P1-1 | P1 | UI/ViewModels call repositories, never `FirebaseConfig` directly |
| P1-3 | Add Firestore transactions/batched writes for SOS session + location + SMS log | `SOSRepository.kt`, `FirestoreManager.kt` | P1-2 | P1 | SOS start is atomic across session + initial location |
| P1-4 | Add data-retention/cleanup for `sos_alerts` and `safe_walks` (Cloud Function scheduled or client TTL) | `functions/src/` (new) or client cleanup | P0-1 | P1 | Old SOS/safe-walk docs auto-delete after retention window |

### P2 — Emulator & CI

| ID | Task | File paths | Dependencies | Priority | Acceptance criteria |
|----|------|------------|--------------|----------|---------------------|
| P2-1 | Add `firebase.json` + emulator config (Auth, Firestore, Storage, Functions) | `firebase.json` (new), `emulators/` | — | P2 | Emulators start locally; rules tested against emulator |
| P2-2 | Add GitHub Actions CI workflow: build + unit tests + rules lint | `.github/workflows/ci.yml` (new) | P0-4 | P2 | CI runs on push/PR; fails on rules or test failure |
| P2-3 | Add backend unit tests for repositories (Firestore/Storage/Auth) with emulator or mocks | `app/src/test/` | P1-1, P2-1 | P2 | ≥70% coverage on repository layer |

---

## Classification summary (Person 6 scope)

| Responsibility | Status | Primary files | Gap |
|---|--------|---------------|-----|
| 1. Firebase Auth | Partially implemented | `AuthManager.kt`, `FirebaseConfig.kt` | No profile creation, no social/phone auth, no reset |
| 2. Firestore + repositories | Partially implemented | `FirestoreManager.kt`, `*Repository.kt` | Repositories bypassed; no transactions; no contracts |
| 3. Cloud Storage | Partially implemented | `VoiceMessageFirebase.kt`, `EvidenceUploadWorker.kt` | No security rules; evidence path partially dead |
| 4. FCM | Partially implemented | `MyFirebaseMessagingService.kt`, `AuthManager.saveFCMToken` | Server send function missing |
| 5. Cloud Functions (TS) | Not implemented | — | No `functions/` at all |
| 6. Security rules | Not implemented | — | No `.rules` files |
| 7. Data models + contracts | Partially implemented | `VoiceMessage.kt`, `*Model.kt` | No interfaces; no shared module |
| 8. Module integration | Partially implemented | `MainActivity.kt`, `NavGraph.kt` | God class; no DI; no boundaries |
| 9. Emulator + CI | Not implemented | — | No `firebase.json`; no CI workflows |
