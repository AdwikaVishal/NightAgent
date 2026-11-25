# Person 6 Audit — Firebase & Backend

**Auditor mode:** Read-only. Evidence from direct source inspection and completed subagent audit.  
**Status legend:** COMPLETE / PARTIAL / NOT INTEGRATED / BROKEN / MISSING / BLOCKED.

## Scope Inspected
- `functions/src/index.ts`, `functions/package.json`, `functions/tsconfig.json`, `functions/lib/**`
- `firebase.json`, `firestore.rules`, `firestore.indexes.json`, `storage.rules`
- `app/google-services.json`, `app/src/google-services.json`, `app/build.gradle.kts`
- Android Firebase classes: `AuthManager.kt`, `FirebaseConfig.kt`, `FirestoreManager.kt`, `MyFirebaseMessagingService.kt`
- Voice: `voicemessage/firebase/VoiceMessageFirebase.kt`, `repository/VoiceMessageRepository.kt`, `upload/VoiceUploadWorker.kt`, `viewmodel/VoiceMessageViewModel.kt`
- Evidence upload: `evidence/EvidenceUploadWorker.kt`
- Guardian/Journey SOS repos/integration: `GuardianRepositoryImpl.kt`, `JourneyRepository.kt`, `EmergencySessionRepository.kt`
- `streaming/SignalingRepository.kt`, `tests/rules/*.js`, `jest.config.js`, root `package.json`

## Safe Checks Run

| Check | Result | Status |
|---|---|---|
| `node -v`, `npm -v` | Node v26.7.0, npm 12.2.1 | PASS |
| `functions npm run build` | `sh: tsc: command not found` | **BLOCKED/FAIL** |
| `.firebaserc` | not present (subagent check) | MISSING |
| Firebase emulator/rules tests | not executed | BLOCKED |
| Deploy | not executed (forbidden) | NOT RUN |

## Backend Function Inventory

| Function | Type | Path | Auth / Validation | Android caller | Status |
|---|---|---|---|---|---|
| `getSignedVoiceUrl` | Callable | `functions/src/index.ts:106-162` | Intended: verifyCaller + chat membership + sender/receiver; **bug**: `verifyCaller(request.auth)` checks `context.auth` (`:34-39`) | `VoiceMessageFirebase.kt:416-434`, `VoiceMessageViewModel.kt:293-314` | **BROKEN** (auth bug likely rejects all) |
| `sendVoiceNotification` | Callable | `functions/src/index.ts:166-221` | Same auth bug; checks chat membership | None found | **NOT INTEGRATED / BROKEN** |
| `sendEmergencyNotification` | Callable | `functions/src/index.ts:237-261` | Same auth bug + SOS ownership | None found | **NOT INTEGRATED / BROKEN**; also only records notification, does not fan out to guardians |
| `acknowledgeEmergency` | Callable | `functions/src/index.ts:263-282` | Same auth bug + ownership | None found | **NOT INTEGRATED / BROKEN**; doesn't update SOS session state |
| `escalateEmergency` | Callable | `functions/src/index.ts:284-301` | Same auth bug + ownership | None found | **NOT INTEGRATED / BROKEN** |
| `resolveEmergency` | Callable | `functions/src/index.ts:303-324` | Same auth bug + ownership | None found | **NOT INTEGRATED / BROKEN**; schema differs (`resolved/resolvedAt`) from app model |

**Trigger assessment:** No Firestore/Storage triggers found. `fcm_requests` is created by Android but no trigger consumes it.

## Rules / Schema / Integration Assessment

| Area | Status | Evidence | Gaps |
|---|---|---|---|
| Firebase Auth | **PARTIAL** | `AuthManager.kt` anonymous/email login, token save; MainActivity logs in anonymously on startup (`MainActivity.kt:70-81`) | No full user identity flow; anonymous sessions complicate guardian linking. |
| User profile creation | **PARTIAL** | `VoiceMessageFirebase.registerUser` writes `users/{uid}`; rules allow owner read/write (`firestore.rules:56-61`) | Sender reads receiver `users/{uid}` for FCM queueing, blocked by owner-only rule. `findUidByPhone` also blocked cross-user. |
| Firestore repositories | **PARTIAL** | EmergencySessionRepository, JourneyRepository, EvidenceUploadWorker, VoiceMessageFirebase have real Firestore paths | GuardianRepositoryImpl local-only; legacy FirestoreManager duplicates SOS writes. |
| Firestore rules coverage | **PARTIAL / GAPPY** | Rules for users/chats/messages/fcm_requests/sos_alerts/journeys/evidence/safe_walks/sos_logs (`firestore.rules`) | **No `calls/*` signaling rules** (default deny blocks WebRTC signaling). Broad legacy `sessions/locations/location_updates` rules remain (`:255-274`). |
| Firestore indexes | **MISSING/PARTIAL** | `firestore.indexes.json` has only `users.phoneNumber` | Missing likely journey indexes (`userId+status`, `userId+createdAt`); `firebase.json` does not reference indexes file. |
| Storage rules | **PARTIAL / MISMATCHED** | `storage.rules` enforces owner/participant/size/content-type | Voice canonical metadata expects `participants` list, Android sends comma-separated string (`VoiceMessageFirebase.kt:166-171`); image evidence not allowed; legacy voice path readable only by sender. |
| Voice upload/download | **PARTIAL / BROKEN** | Upload to canonical storage path and Firestore message write; signed URL callable exists | Storage metadata mismatch likely blocks uploads; signed URL callable auth bug; client can fall back to direct URL (weaker). |
| Evidence upload | **PARTIAL** | `EvidenceUploadWorker.kt` uploads `evidence/{userId}/{sosId}/...` to Storage and writes `evidence/{sosId}` | JPG photos skipped by content-type allowlist and storage rules don't allow images. |
| FCM token registration | **PARTIAL** | `AuthManager.saveFCMToken`, `MainActivity`, `MyFirebaseMessagingService.onNewToken` | Token stored in `users/{uid}.fcmToken`; no guardian linking/targeting model. |
| FCM delivery | **BROKEN** | `MyFirebaseMessagingService` handles `voice_message` and generic only (`:39-87`); `sendVoiceNotification` unused; `fcm_requests` no trigger | No SOS alert handler; no working voice push backend path. |
| Callable auth/request.auth handling | **BROKEN** | `verifyCaller` takes wrong arg shape (`functions/src/index.ts:34-39`) | All callables likely fail. |
| SOS/voice/guardian triggers | **MISSING** | No `onDocumentCreated/Updated` triggers in functions | Voice queue and guardian flows lack backend automation. |
| Signed download URLs | **PARTIAL / BROKEN** | `getSignedVoiceUrl` implemented with 15-min expiry | Auth bug blocks it. |
| Guardian notification backend | **MISSING/NOT INTEGRATED** | `sendEmergencyNotification` records only; no guardian fanout | No account linking, no delivery, no ack state update. |
| Emulator config | **PARTIAL** | `firebase.json` has auth/firestore/storage/ui emulators | No functions emulator config; no `.firebaserc`. |
| TS compilation/JS sync | **BLOCKED / RISKY** | `functions/src` and `functions/lib` present | `tsc` not found; lib appears in sync but committed generated JS can drift. |
| Backend tests | **PARTIAL/BLOCKED** | `tests/rules/firestore.rules.test.js`, `tests/rules/storage.rules.test.js`, `jest.config.js` exist | Not executed; storage test harness likely incomplete; project ID mismatch (`nightagent-35d38` vs app `nightagent-5ed69`). |
| Credential exposure | **RISK PRESENT** | `app/google-services.json` and `app/src/google-services.json` identical, committed; Firebase API key/project IDs present | Mobile API key is not admin secret but should be restricted; duplication/config drift risk. |
| Android <-> Firebase schema consistency | **PARTIAL** | Voice message shape aligned; SOS session mostly aligned; evidence doc aligned | Voice storage metadata mismatch; safe_walks legacy location field mismatch; calls rules missing; image evidence unsupported. |

## Completion

| Dimension | % | Basis |
|---|---:|---|
| Code implementation | 64% | Rules/functions/repo integrations exist in parts. |
| Application integration | 45% | Key backend callables/flows not reachable or broken by auth/schema mismatches. |
| Runtime verification | 15% | No emulator/deploy/tests executed. |
| **Overall** | **45%** | Backend foundation exists but critical contracts are broken/unwired. |
