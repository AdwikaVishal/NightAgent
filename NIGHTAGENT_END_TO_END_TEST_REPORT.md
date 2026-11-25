# NightAgent End-to-End Test Report

Date: 2026-11-25  
Repository state: branch `main`, HEAD `0afca43`, all changes uncommitted.

## 1. Verification tiers

| Tier | What was actually run | Result | What it does **not** prove |
|---|---|---|---|
| Local static/build | Functions TypeScript build + Node contract tests; Android unit tests, lint, debug assemble; `git diff --check` | PASS | Real device behavior, FCM delivery, production config |
| Emulator | Firestore + Storage emulator rules suites, consent rules, journey/guardian rules, tracker contract suite | PASS | Callable runtime behavior with real Auth contexts; FCM delivery |
| Browser (headless) | Live tracker smoke page served from local HTTP server and executed in headless Chrome | PASS | Firebase Hosting deployment, real Firestore data, real auth |
| Physical device / Android emulator | Not run | NOT RUN | Runtime permission/FGS behavior, real token registration, real push receipts |
| Production deployment | Not run intentionally; no `.firebaserc` exists | NOT RUN | Deployed rules/functions/hosting behavior |

## 2. Exact commands and outputs

### 2.1 Functions

Command:

```bash
npm --prefix functions run build && npm --prefix functions test
```

Result: `BUILD` success; Node test runner reported **9 tests, 9 pass, 0 fail**. Tests cover callable exports, Admin SDK delivery, structured error codes, canonical voice path, SOS state updates, owner-provisioned guardian links, consent callables, per-session idempotency, and invalid-token cleanup.

These are source-contract tests. They do **not** prove that FCM accepted or delivered a message.

### 2.2 Firebase emulator rules and tracker contract

Command:

```bash
npx --no-install firebase emulators:exec --only firestore,storage \
  --project demo-nightagent \
  "GCLOUD_PROJECT=demo-nightagent npx --no-install jest --config jest.config.js --runInBand --silent"
```

Result: **5 suites passed, 105/105 tests passed**, emulator exited successfully.

Suites:
- `tests/rules/firestore.rules.test.js`
- `tests/rules/storage.rules.test.js`
- `tests/rules/journey.guardian.rules.test.js`
- `tests/rules/consent.guardian.rules.test.js`
- `tests/live-tracker/contract.test.js`

The `demo-nightagent` project ID explicitly avoids production targeting.

### 2.3 Android

Command:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Result: `BUILD SUCCESSFUL`; **93 unit tests from 14 XML result files, 0 failures, 0 errors, 0 skipped**; lint and debug APK assembly completed.

Added/updated JVM coverage includes:
- guardian notification response parsing (queued/not-delivered responses rejected);
- journey local-store restart recovery and owner scoping;
- pending update/location queue survival and deduplication;
- owner-scoped pending create and clear-on-signout;
- engine `pendingSync` exposure/replay and cached-state clearing.

### 2.4 Live tracker browser smoke

Server command from repository root:

```bash
python3 -m http.server 8765 --bind 127.0.0.1
```

Page: `http://127.0.0.1:8765/tests/live-tracker/browser-smoke.html`

The harness loads the real `live-tracker/index.html` with stubbed Firebase/Leaflet modules and was executed in headless Chrome because no desktop browser was connected to the agent session.

Result: **12/12 checks passed**:
1. Firebase Hosting init config consumed.
2. Initial unauthorized state explicit.
3. Tracker registered an auth callback.
4. Target UID set before sign-in.
5. Sign-in starts an active-journey listener.
6. Valid latest location renders live status.
7. Route rendering filters null island/out-of-range points.
8. Stale fix shows stale indicator.
9. Cached fresh fix shows offline indicator.
10. Null-island fix is not rendered as a location.
11. Empty location data handled without crash.
12. Sign-out clears journey view state.

## 3. Scenario coverage

| Requested scenario | Evidence | Strength |
|---|---|---|
| Guardian linking | Consent emulator tests plus static callable assertions | Emulator rules + static contract |
| Unauthorized linking | Rules deny client-authored `guardianUids`, fabricated invites, idempotency records; unaccepted candidate cannot read owner data | Emulator rules |
| Unlinking | Callable exported and transaction updates both sides; client cannot direct-write links | Static contract + rules |
| Missing tokens | `sendVoiceNotification` checks receiver FCM token; `sendEmergencyNotification` reports `not_delivered` when no recipient resolves | Static contract/client parser |
| Duplicate SOS calls | `emergency_notification_requests/{sosSessionId}` reuses `deliveredGuardianIds` | Static contract |
| Failed delivery | Android response parser rejects queued/not-delivered/empty recipients | JVM unit tests |
| Invalid-token cleanup | `clearInvalidFcmToken` asserted in source contract | Static contract |
| SOS fanout | Server resolves linked guardians; idempotency and delivery status coded and contract-tested | Static contract + rules; no real fanout run |
| Tracker live/stale/offline/null-island/browser | Browser smoke + Jest contract suite | Actual headless browser execution with mocks |
| Journey restart/account switch/network recovery | JVM tests for local store restart, owner scoping, pending queues, clear, engine replay | Local JVM only; no process-death device test |

## 4. FCM delivery honesty statement

No claim of real FCM delivery is made. The following are explicitly **not** treated as delivery evidence:

- queued `fcm_requests` or `emergency_notification_requests` records;
- audit/debug logs;
- callable responses reporting a request was accepted;
- emulator execution;
- static source-contract assertions.

Real delivery requires a device/emulator run with a registered FCM token and confirmation that the client received and displayed the notification. That run has not happened.

## 5. Not verified

- Physical-device or Android-emulator end-to-end flow.
- Real FCM token registration and push receipt.
- API 35 runtime foreground-service start with granted and denied permissions.
- Functions emulator callable invocation with authenticated contexts.
- Firebase Hosting deployment of the tracker.
- Production rules/functions/storage behavior.

## 6. Gate result

- Local and emulator evidence is strong enough to continue code review.
- The full end-to-end gate is **NO-GO** because device/runtime delivery and production-equivalent E2E verification are missing.
