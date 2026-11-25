# NightAgent Six-Contributor Integration Completion & Readiness Matrix

Date: 2026-11-25  
Scope: controlled integration of Persons 1–6 in `/Users/shantanu/NightAgent` on `main` at `0afca43`.  
Status: **code-level integration and automated verification complete; NOT production ready / no deployment evidence**.

---

## 1. Repository protection and change control

| Item | Evidence / status |
|---|---|
| Backup ref created | `backup/pre-integration-20261125` → `0afca43` (verified with `git show-ref`) |
| Pre-integration dirty diff preserved | `/private/var/folders/d9/2l__tx31251fjff54rhrt1bc0000gn/T/opencode/nightagent-protection/tracked.patch`, `status.txt`, `untracked.txt`, `functions-lib-before/` |
| Work performed | Working tree only. No commit, merge, checkout, force-push, or push to `origin/main` |
| Current tree | Dirty: 56 paths; `git diff --stat` = 34 tracked files changed, 1997 insertions / 570 deletions, plus untracked integration/test artifacts |
| HEAD / branch | Still `main` at `0afca43`; backup ref points to the same commit |

All contributor work was preserved. The large tracked working-tree diff is the integration itself, not a cleanup of user work.

---

## 2. Six-contributor completion matrix

Completion percentages are engineering estimates for the integrated code, not a claim of production readiness.

| Person | Original contribution | Current integrated state | Remaining / incomplete | Est. |
|---|---|---|---|---|
| **1 — SOS engine** | `a22b2dd` / `1ea126a`: `SOSEngine`, `TriggerManager`, `SOSManager`, triggers, local-first sessions, alarm/SMS/location, 35 JVM tests | Reason-aware triggers; canonical session persistence; guardian callable request; owner ack listener now matches backend session writes; journey location relay; session `message` always non-empty; linked-guardian session reads; live-tracker compatibility | Timed escalation still local/stub with no scheduler or next-guardian fanout; evidence capture only starts on manual SOS; journey escalation bypasses `TriggerManager`; power-button copy vs behavior mismatch; dead `sosactive` / `SOSRepository` / `EvidenceUploader` remain; no on-device trigger tests | **75%** |
| **2 — Guardian / notifications** | Local guardian CRUD, invites, validation, UI; original `requestSOSNotification` was a TODO stub | Callable `sendEmergencyNotification` with real Admin SDK FCM fanout and confirmed-recipient parser; phone→UID sync writes authoritative `users/{uid}.guardianUids`; guardian FCM `sos_alert` deep link plus Acknowledge/Escalate actions; owner-linked guardian session reads | Guardian UID resolution still depends on separate phone-registration screen; no guardian inbox UI; timed escalation not scheduled; SMS fallback remains TODO; no real FCM delivery proof | **70%** |
| **3 — Safe Journey / tracking** | `8265bb0`: `JourneyEngine`, tracking service, check-ins, deviation detection, rules, live tracker | Local-store-backed repository, auth guard, deviation dedupe, watchdog, canonical + legacy location writes, journey→SOS reason escalation, history index added, live-tracker `validPoint` bug fixed, rule tests green | Offline fallback covers journeys only, not locations/check-ins/deviations/history; `CheckInWatchdogWorker` uses non-local-store repo; duplicate canonical + legacy paths; Home “Safe Walk” does not start journey; web tracker identity requires email sign-in matching phone-linked UID; no device/GPS verification | **80%** |
| **4 — Evidence** | Canonical `EvidenceCaptureService` + `EvidenceManager` + `EvidenceUploadWorker` in `:app`; separate, much richer standalone `person4-evidence/` prototype | API 35 FGS hardening; audio chunks written under `Evidence/{sosId}/audio` and legacy `temp` migrated; photos (`image/jpeg`, `image/png`) accepted by worker and Storage rules; WorkManager tag matches UI; unauthenticated uploads blocked; Firestore-backed Evidence Vault replaces demo data; 2 new JVM mapping tests | Standalone `person4-evidence/` is not shipping code; capture starts only from manual SOS; no API 35 device runtime proof; no encrypted-at-rest prototype migration into `:app` | **78%** |
| **5 — UI / navigation** | Broad Compose shell, bottom nav, routes, screens | Routes to guardians/journey/vault/permissions/register exist; Vault now real Firestore data; Journey/Guardian screens use real repositories; lint clean | Bottom nav `Contacts` maps to `guardians` while a separate `contacts` route exists; SOS navigates to `evidence` rather than `sosactive`; no UI tests | **85%** |
| **6 — Firebase / backend** | v2 callables, signed voice URLs, rules, Storage paths, token registration | `HttpsError` codes; per-UID rate limiting (still per-instance); signed URLs restricted to exact canonical voice object path; real FCM for voice and emergency; `fcm_requests` client writes retired and denied; registration no longer clears FCM tokens; functions config/emulator and missing history index added; 6 contract tests added | No deployed functions/rules/index evidence; no Functions-emulator callable invocation tests; anonymous startup auth remains; real FCM delivery unverified; distributed rate limiting not implemented | **75%** |

---

## 3. Six required workflows

| # | Workflow | Code status | Evidence | Caveat |
|---|---|---|---|---|
| 1 | SOS → session → guardian notification → ack → escalation | **Wired end to end in code** | `SOSManager` → `GuardianRepositoryImpl` → `sendEmergencyNotification` → `MyFirebaseMessagingService` → `SosAlertActionReceiver` → `acknowledgeEmergency`/`escalateEmergency` → canonical session → owner listener | Manual escalation via notification action works in code; timed auto-escalation is still not scheduled. No real device/FCM proof |
| 2 | Journey → GPS → deviation → SOS | **Wired in code** | `JourneyEngine` deviation/hand-off to `SOSManager` with reason; `JourneyEngineTest` covers escalation | Runtime GPS/FGS/WorkManager behavior untested |
| 3 | Evidence capture → local → upload → Firestore → Vault | **Wired in code** | `EvidenceCaptureService` → `EvidenceManager` → `EvidenceUploadWorker` → `evidence/{sosId}` → `EvidenceVaultScreen` | Only manual SOS starts capture; no API 35 runtime test |
| 4 | Voice → upload → chat → notification | **Wired in code** | `VoiceMessageRepository` → `uploadAudio` → `chats/{chatId}/messages` → `sendVoiceNotification` → FCM `voice_message` → `MyFirebaseMessagingService` (`chatId`/legacy `conversationId`) | Backend delivery not executed against deployed project |
| 5 | Auth → profile → guardians → FCM token | **Mostly wired** | Anonymous/email auth; profile registration preserves existing token; `syncGuardianLinks` provisions owner `guardianUids`; `AuthManager.saveFCMToken` on launch/refresh | Guardian linking requires phone registration; no real-token/FCM verification |
| 6 | Live tracking → authenticated location updates → guardian view | **Wired in code + rules** | Canonical `journeys/{id}/locations` + legacy mirror; owner/linked-guardian reads; history index; live-tracker contract tests; `validPoint` fixed | Guardian web view requires matching account identity; no Hosting/Firebase deployment evidence |

---

## 4. Fixes completed in this integration pass

- **Evidence / API 35 foreground services**
  - `EvidenceCaptureService` only calls `beginCapture()` after successful typed `startForeground`; passes `sosId` into chunked recording.
  - `EvidenceManager.createEvidenceFile()` writes audio to `Evidence/{sosId}/audio`.
  - `EvidenceUploadWorker` migrates legacy `Evidence/temp` chunks, accepts JPEG/PNG, uploads under the canonical tag, records file sizes/location, refuses unknown users, and enqueues `evidence_upload` with an `addTag` matching `EvidenceScreen`.
  - `AudioRecordingService` uses typed `startForeground` on API 29+ and refuses to start without `RECORD_AUDIO`.
  - `storage.rules` allows only the exact evidence media types produced by the app.

- **Evidence Vault**
  - Replaced `DemoData.evidenceRecords` with an owner-scoped Firestore query and mapping of real `evidence/{sosId}` records.

- **Voice notification**
  - Replaced writes to retired `fcm_requests` with callable `sendVoiceNotification`.
  - `registerCurrentUser()` no longer clears an existing FCM token.
  - FCM service accepts both `chatId` and `conversationId`.

- **Guardian / SOS**
  - Added `syncGuardianLinks` callable that resolves phone numbers (local/E.164 formats) and writes authoritative `users/{owner}.guardianUids`.
  - Guardian delivery requires those links and reports only confirmed FCM delivery.
  - Added `sos_alert` notification handling with Acknowledge/Escalate actions and a non-exported receiver.
  - `acknowledgeEmergency` and `escalateEmergency` now write canonical SOS session fields in addition to audit records.
  - Linked guardians can read the SOS session but still cannot write it directly.
  - `EmergencySessionRepository` always writes a non-empty `message`, matching session rules.

- **Backend hardening**
  - Replaced plain `Error` with structured `HttpsError` codes.
  - Rate limiting keyed by authenticated UID.
  - Signed voice URLs restricted to the exact canonical object path.
  - Legacy no-recipient emergency requests now create an audit record instead of returning a misleading success.
  - `fcm_requests` client create path denied; rules tests updated.
  - `firebase.json` now declares the functions source/runtime and functions emulator.
  - Added missing `journeys(userId ASC, createdAt DESC)` index.
  - Fixed `live-tracker/index.html` `validPoint` reference.

---

## 5. Verification evidence

All commands were run from `/Users/shantanu/NightAgent`.

| Check | Command | Result |
|---|---|---|
| Android compile + lint | `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:compileDebugKotlin :app:lintDebug --console=plain` | **BUILD SUCCESSFUL**; lint HTML report generated with no errors |
| Android tests + assemble | `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` | **BUILD SUCCESSFUL**; 14 suites, **89 tests**, 0 failures/errors/skips |
| Standalone P4 evidence app | `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew -p person4-evidence :app:testDebugUnitTest --console=plain` | **BUILD SUCCESSFUL**; 38 suites, **227 tests**, 0 failures/errors/skips (separate, non-shipping app) |
| Functions TypeScript build | `npm --prefix functions run build` | **Success** (`tsc`) |
| Functions contract tests | `npm --prefix functions test` | **6/6 passed** |
| Firestore + Storage + tracker tests | `npx firebase emulators:exec --only firestore,storage --project nightagent-rules-test 'npx jest --config jest.config.js --runInBand --silent'` | 4 suites, **101/101 passed** (includes 7 live-tracker contract tests) |
| Live tracker contract standalone | `npx jest --config jest.config.js --runInBand --silent tests/live-tracker` | **7/7 passed** |
| Repository protection | `git show-ref --verify refs/heads/backup/pre-integration-20261125` | Backup ref verified at `0afca43` |

Transient note: one emulator run timed out on a single chat read while a full Gradle build was running concurrently; the isolated rerun passed 101/101. No code change was needed for that timeout.

---

## 6. Blockers and honest readiness conclusion

**Not production ready.** Do not deploy, merge to `main`, or push without explicit approval and the verification below.

1. **No deployment evidence** — no Firebase project deployment of functions, rules, indexes, or Hosting; no `.firebaserc`/project selection verification for the Android project (`nightagent-5ed69`) vs the rules test project (`nightagent-rules-test`).
2. **No real FCM delivery proof** — Admin SDK calls and payload handling are statically verified and contract-tested, but no device/token received a message.
3. **No API 35 device/emulator run** — microphone foreground-service behavior, notification actions, background location, and process-death recovery are statically/build verified only.
4. **No Functions-emulator callable invocation tests** — TypeScript build and source contract tests pass, but callable authorization paths have not been executed end-to-end.
5. **Runtime escalation remains incomplete** — manual notification action is wired; timed auto-escalation/next-guardian fanout is still local-only.
6. **Identity weak points** — startup anonymous auth; phone registration is a separate screen; web tracker email identity may not match the phone-linked Firebase UID.
7. **Duplicate/dead implementations remain** — standalone P4 app, canonical + legacy journey/SOS paths, dead screens/repositories. They were preserved rather than deleted without approval.
8. **No UI/instrumentation tests** — Compose navigation and notification interaction are unverified at runtime.

Overall engineering estimate: **~78% code-level integration**, **~35–45% production readiness** because runtime, deployment, and real-delivery evidence are absent.

---

## 7. Recommended next gate before any merge

1. Commit the current working tree on a feature/integration branch (not `main`).
2. Run Functions emulator with authenticated callable invocation tests for voice, emergency, ack, escalate, link sync, and signed URL rejection cases.
3. Verify Firestore indexes and rules against the actual Android Firebase project.
4. Run API 35 device/emulator E2E for all six workflows with real FCM tokens.
5. Schedule or explicitly descope timed escalation; remove or rewire dead paths in a separate reviewed change.
6. Obtain explicit approval before merging or pushing.
