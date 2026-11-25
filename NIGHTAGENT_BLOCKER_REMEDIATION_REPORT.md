# NightAgent Blocker Remediation Report

Date: 2026-11-25  
Repository state: branch `main`, HEAD `0afca43` (unchanged). All remediation work remains uncommitted in the working tree.

## 1. Preservation and safety

- Initial git state was recorded before edits: branch `main`, HEAD `0afca43`, dirty tracked tree plus pre-existing untracked audit files.
- Backup created at:
  `/private/var/folders/d9/2l__tx31251fjff54rhrt1bc0000gn/T/opencode/nightagent-backup-20261125/`
  containing `working-tree.patch`, `status.txt`, and `untracked.tgz`.
- No `git reset --hard`, `git clean`, destructive checkout, force-push, commit, merge, push, or production deployment was performed.
- Final `git diff --check` produced no whitespace errors.

## 2. Blockers addressed in code

### 2.1 Guardian consent and linking

- Invitations are now server-owned in `functions/src/index.ts` via `inviteGuardian`, `getGuardianInvitations`, `respondGuardianInvite`, and `unlinkGuardian`.
- No guardian link exists until the candidate calls `respondGuardianInvite`; the callable updates both sides of the relationship in a Firestore transaction.
- Phone/email candidates are resolved through Firebase Auth (`authUser.phoneNumber`, `authUser.emailVerified`) rather than trusting self-asserted Firestore fields.
- `firestore.rules` denies all client access to `guardianInvites/{inviteId}` and denies direct client writes to `users/{uid}.guardianUids`.
- Android now exposes incoming invitations and Accept/Decline actions through `GuardianViewModel` and `GuardianScreen`; `GuardianRepositoryImpl` is wired to the server-backed consent callables.
- Added emulator rules coverage in `tests/rules/consent.guardian.rules.test.js` for:
  - rejecting client-authored `guardianUids`;
  - unaccepted candidates and strangers lacking read access;
  - server-created links granting read-only access;
  - rejection of client-fabricated invitations and idempotency records.

### 2.2 SOS → guardian FCM delivery

- `sendEmergencyNotification` uses server-resolved guardian UIDs and the Admin SDK; it no longer trusts a client-writable queue.
- Idempotency is keyed by `sosSessionId` in `emergency_notification_requests/{sosSessionId}`, with prior `deliveredGuardianIds` reused on duplicate calls.
- Delivery result is explicit: `delivered` only when at least one recipient send resolves; otherwise `not_delivered`.
- Invalid FCM tokens are cleared server-side through `clearInvalidFcmToken`.
- Android `GuardianNotificationResponse.parse` rejects queued/requested/empty recipient responses, so the UI cannot report delivery from a queued request.
- Contract tests assert consent callables, idempotency, invalid-token cleanup, and Auth-verified lookups.

### 2.3 Live tracker

- `live-tracker/contract.js` rejects null-island and out-of-range coordinates and reads only the newest canonical location document.
- `live-tracker/index.html` now provides manual live/cached/stale/offline rendering controls for smoke verification.
- Browser smoke harness (`tests/live-tracker/browser-smoke.html`, mocks, Leaflet stub) runs against a local HTTP server with headless Chrome.
- Final browser smoke result: **12/12 checks passed**.

### 2.4 Honest journey offline state

- `JourneyRepository.createJourney` documents `Result.success` as local durability only; `hasPendingSync()` and `pendingSync` expose unconfirmed remote sync.
- `JourneyLocalStore` persists the active journey and pending create/update/location queues with owner scoping.
- `JourneyEngine.retryPendingSync()` replays queued writes and refreshes `pendingSync`; `clearCachedJourney()` clears local state on sign-out/account switch.
- Added JVM tests for restart recovery, owner scoping, pending queue deduplication/survival, clear-on-signout, and pending-sync state transitions.

### 2.5 Firebase config and rules

- `firebase.json` declares Firestore, Storage, Functions, Hosting, and emulator ports; no `.firebaserc` exists, so production targeting is impossible in this state.
- Firestore rules were hardened for server-owned links/invitations/idempotency, SOS state forging, and legacy top-level collections.
- Emulator rules suite result: **105/105 tests passed** across Firestore, Storage, journey/guardian, consent, and tracker contract suites.

### 2.6 Android API 35 foreground-service crash risks

- `AudioStreamingService` now checks `RECORD_AUDIO`, starts the manifest-declared microphone FGS type on Android 14+, and stops cleanly instead of crashing on `SecurityException`.
- `StealthDetectionService` now checks coarse/fine location, starts the manifest-declared location FGS type on Android 14+, and stops cleanly on permission/security failure.
- `AudioRecordingService` already used the microphone type and remains guarded by its existing failure handling.
- Android compile/lint/unit tests pass after these changes, but runtime behavior on an API 35 device is still unverified.

## 3. Verification summary

| Gate | Command / evidence | Result |
|---|---|---|
| Functions compile + contract tests | `npm --prefix functions run build && npm --prefix functions test` | PASS, 9/9 |
| Firebase rules/contract tests | `npx --no-install firebase emulators:exec --only firestore,storage --project demo-nightagent "GCLOUD_PROJECT=demo-nightagent npx --no-install jest --config jest.config.js --runInBand --silent"` | PASS, 105/105 |
| Android unit tests, lint, debug assemble | `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` | PASS, 93 unit tests / 0 failures |
| Tracker browser smoke | local HTTP server + headless Chrome against `http://127.0.0.1:8765/tests/live-tracker/browser-smoke.html` | PASS, 12/12 |
| Whitespace/diff sanity | `git diff --check` | PASS, no output |

## 4. Residual blockers / not proven

- No physical-device or Android-emulator end-to-end run was performed.
- No real FCM delivery proof exists. Queued requests, audit logs, callable return values, and emulator results are **not** treated as delivery evidence.
- No callable runtime invocation suite was run against the Functions emulator with authenticated users; function-level coverage is static contract coverage plus rules emulator coverage.
- API 35 FGS behavior is statically and compile-time hardened but not runtime-verified with permissions granted/denied on a real device.
- Known integration debt remains: anonymous startup auth, duplicate/dead SOS and evidence writers, `person4-evidence` not shipping as a module, dead routes (`contacts`, `sosactive`, streaming routes), unused settings, and absent UI/instrumentation tests.
- No production deployment was attempted; production behavior remains unverified by design.

## 5. Remediation gate status

- **Local build/static gates:** PASS.
- **Emulator gates:** PASS for Firestore/Storage rules and browser smoke.
- **Physical-device gates:** NOT RUN.
- **Production-deployment gates:** NOT RUN (intentionally).
- **Commit/merge/push gate:** FAIL / NO-GO until device and runtime FCM evidence exist and residual integration debt is resolved.
