# NightAgent Person 3 — Safe Journey / Live Tracking Final Integration Report

Date: 2026-11-25  
Repository: `/Users/shantanu/NightAgent`  
Branch: `main`  
HEAD at start and finish: `0afca43` (`chore: pre-push review fixes - remove machine-specific JDK path, add gitignore entries`)

## 1. Outcome

Person 3's Safe Journey and Live Tracking work is integrated into the shipping app and verified as far as this environment allows:

- One canonical Safe Journey lifecycle owner: `JourneyEngine`.
- One SOS lifecycle owner: `SOSManager` / `SOSEngine`.
- Canonical Firestore schema: `journeys/{journeyId}` with live fixes in `journeys/{journeyId}/locations`.
- `safe_walks/{userId}` is retained only as a compatibility mirror/fallback for older Live Tracker readers.
- Guardian delivery is reported as successful only when the trusted Cloud Function returns `status == "delivered"` and a non-empty `deliveredGuardianIds` list.
- Active journey locations are reused for SOS live tracking; no second GPS poller is opened.
- All Person 3 Kotlin unit tests pass (87/87 in the full app unit-test task).
- New Person 3 rules and tracker-contract tests pass (5/5 and 7/7).
- No commits, branch switches, resets, stashes, cherry-picks, or merges were performed. Person 2's concurrent work was not discarded.

## 2. Git and concurrency handling

`git status --porcelain=v1 -uall` was rechecked before each further edit. Person 2's untracked audit artifacts (for example `NIGHTAGENT_MASTER_6_PERSON_AUDIT.md`, `NIGHTAGENT_CROSS_MODULE_INTEGRATION_AUDIT.md`, and the person-specific audit files) were left untouched.

Shared files that Person 2 also touches (`SOSManager.kt`, `GuardianRepositoryImpl.kt`) were inspected at every step. Person 3 edits were reapplied only where the files had regressed to the original TODO/broken form, and the final diffs still contain the intended Person 3 changes.

All changes remain uncommitted in the working tree so they can be reviewed independently.

## 3. What was implemented or completed

### Safe Journey lifecycle
- `JourneyEngine.kt`
  - Restores an active journey on cold start using the stored owner UID when Firebase Auth is still anonymous.
  - Validates owner and `ACTIVE` status before restoring.
  - Falls back to the stored journey ID when the canonical active-journey query is unavailable.
  - Requires a signed-in user before starting a journey.
  - Stops location monitoring, foreground service, and check-in watchdog work when a journey completes, is cancelled, or enters emergency state.
  - Preserves single-escalation deduplication for missed check-ins, route deviations, delayed arrival, and user-requested help.
- `JourneyRepository.kt`
  - Adds local-first/offline recovery backed by `JourneyLocalStore`.
  - Clears the recovery cache when a journey becomes inactive.
  - Continues to write the legacy `safe_walks/{userId}` mirror for old readers, while treating `journeys` as canonical.
- `JourneyLocalStore.kt` (new)
  - Device-local recovery cache keyed by canonical journey ID; not a second Firestore schema.
- `SafeWalkManager.kt`
  - Now a no-loop legacy facade delegating to `JourneyEngine`.
  - Refuses to start an invalid journey when no destination is provided.
- `JourneyViewModel.kt`
  - Uses the local-store-backed repository.
  - Passes journey context into manual SOS help requests.
  - No longer duplicates foreground-service stop calls owned by the engine.
- `JourneyScreen.kt`
  - Shows status, route point count, and last location/time/accuracy in addition to existing distance and check-in UI.

### SOS integration and honest guardian delivery
- `SOSManager.kt`
  - Resolves the duplicate `triggerSOS` overload through a single internal entry point and preserves the escalation reason.
  - Adds primary duplicate-SOS session guard plus the existing state-machine guard.
  - Requests guardian push delivery through `GuardianRepositoryImpl`.
  - Reuses `JourneyEngine` location state when a journey is active; otherwise checks location permission before opening the SOS location loop.
  - Cleans up journey-location jobs and notified session IDs.
- `GuardianRepositoryImpl.kt`
  - Calls the canonical `sendEmergencyNotification` Cloud Function via `getHttpsCallable`.
  - Adds a per-session idempotency guard in `SOS_NOTIFICATION_PREFS`.
  - Persists local notification-attempt records.
- `GuardianNotificationResponse.kt` (new)
  - Parses only explicit backend delivery confirmation; `queued`, `not_delivered`, missing IDs, and empty ID lists are failures.

### Backend and security rules
- `functions/src/index.ts`
  - `sendEmergencyNotification` verifies caller identity and SOS ownership.
  - Requires `guardianUids`, rejects unlinked guardians, validates optional coordinates, sends FCM only to confirmed linked guardians, writes `emergency_notifications` with `action: "delivered"`, and returns `status`, `deliveredGuardianIds`, and `failedGuardianIds`.
  - Legacy callers that omit `guardianUids` receive `status: "emergency_notification_requested"` and are never treated as confirmed delivery.
- `firestore.rules`
  - Adds `isLinkedGuardian`, `canReadOwnerData`, and `canReadJourney`.
  - Owner or explicitly linked guardian may read canonical journeys and locations; writes remain owner-only.
  - Legacy `safe_walks` reads follow the same owner/guardian rule; writes remain owner-only.
  - Adds bounded validation for descriptive legacy location writes.
  - Replaces unsupported `is double` checks with `is number` so the rules compile in the Firestore emulator.
- `firestore.indexes.json`
  - Adds the composite index for `journeys`: `userId ASC`, `status ASC`, `createdAt DESC`.
- `firebase.json`
  - Registers the Firestore indexes and Hosting `public: "live-tracker"` with SPA rewrites.
- `storage.rules`
  - Replaces unsupported top-level `const` declarations with rule functions so the Storage rules compile. This was required to run the emulator harness and is outside Person 3's feature scope but is a compatibility fix, not a behavior change.

### Web Live Tracker
- `live-tracker/index.html`
  - Removes all placeholder/embedded Firebase credentials.
  - Loads canonical config at runtime from Firebase Hosting `/__/firebase/init.json`.
  - Adds Firebase Auth sign-in/out.
  - Uses real-time `onSnapshot` listeners on `journeys` and `journeys/{journeyId}/locations`.
  - Uses `safe_walks/{userId}/locations` only as a legacy fallback.
  - Displays status, destination, ETA, route points, latest coordinate, timestamp, accuracy, stale/offline state, route polyline, and markers.
  - Escapes user-controlled strings before rendering.
- `live-tracker/contract.js` (new)
  - Pure shared browser/Node helpers for the canonical query contract, timestamp normalization, stale detection, coordinate validation, and `/track/{uid}` path handling.

### Test infrastructure and tests
- `jest.config.js`
  - Includes `tests/live-tracker/*.test.js` in discovery and coverage.
- `tests/rules/test-env.js` (new)
  - v4-compatible shared emulator harness using `initializeTestEnvironment`.
- `tests/rules/firestore.rules.test.js`, `tests/rules/storage.rules.test.js`
  - Migrated from removed `@firebase/rules-unit-testing` v2/v3 APIs (`loadFirestoreRules`, `initializeTestApp`, `initializeAdminApp`, `clearFirestoreData`) to the v4 public API so they can run at all.
- `tests/live-tracker/contract.test.js` (new)
  - 7 tests for canonical Firestore query shape, latest-only location reads, legacy fallback, timestamps, stale detection, coordinate validation, and target UID parsing.
- `tests/rules/journey.guardian.rules.test.js` (new)
  - 5 emulator tests for owner/guardian journey reads, guardian write denial, owner location writes, legacy mirror access, and invalid-location rejection.
- `JourneyLocalStoreTest.kt` (new)
  - Offline restart recovery, owner isolation, and cache clearing.
- `GuardianNotificationResponseTest.kt` (new)
  - Rejects malformed, queued, not-delivered, and empty-confirmation responses; normalizes confirmed IDs.
- `GuardianViewModelTest.kt`
  - Replaced instrumentation-only `ApplicationProvider` usage with a relaxed mocked Context and installed a test Main dispatcher so pre-existing guardian tests can run as local JVM tests.
- `VoiceMessageFirebase.kt`
  - Added the missing `.await()` on an HTTPS callable call; this pre-existing compile error was exposed once the SDK path was set correctly.

## 4. Verification actually run

Environment: `ANDROID_HOME=/Users/shantanu/Library/Android/sdk`.

### Android compile and unit tests
Command:

```bash
ANDROID_HOME=/Users/shantanu/Library/Android/sdk ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain
```

Result: **BUILD SUCCESSFUL**.  
XML tally: **87 tests, 0 failures, 0 errors, 0 skipped**.

This includes the existing Person 1/2/3/4/5 unit suites and the new Person 3 tests.

### Cloud Functions TypeScript
Command:

```bash
npm --prefix functions install --no-audit --no-fund
npm --prefix functions run build
```

Result: **`tsc` completed successfully.** Generated `functions/lib/*` artifacts were refreshed; they are tracked in this repository.

### Live Tracker contract tests
Command:

```bash
npx jest --config jest.config.js tests/live-tracker/contract.test.js --runInBand
```

Result: **1 suite passed, 7/7 tests passed**.

### Person 3 journey/guardian Firestore rules
Command:

```bash
npx firebase emulators:exec --only firestore --project nightagent-35d38 \
  "npx jest --config jest.config.js tests/rules/journey.guardian.rules.test.js --runInBand"
```

Result: **1 suite passed, 5/5 tests passed**.

### Full emulator Jest suite
Command:

```bash
npx firebase emulators:exec --only firestore,storage --project nightagent-35d38 \
  "npx jest --config jest.config.js --runInBand"
```

Result: **81 passed, 18 failed, 99 total**.

Passing suites:
- `tests/live-tracker/contract.test.js` — 7/7.
- `tests/rules/journey.guardian.rules.test.js` — 5/5.
- All Firestore rule tests for `users`, `sos_alerts`, `sos_logs` (most), `safe_walks`, and their location subcollections relevant to the legacy mirror.

The 18 failures are pre-existing, non-Person-3 rule/test mismatches in:
- `chats/{chatId}` and `chats/{chatId}/messages/{msgId}` (5).
- `fcm_requests/{requestId}` (2).
- `sos_alerts/{uid}/sessions/{sessionId}` and `sos_logs/{logId}` (5).
- `voice_messages/{chatId}/{senderId}/{messageId}.m4a` Storage tests (6).

They are now observable because the test harness was upgraded to the installed v4 API and the rules files were made compilable; they are not caused by the canonical `journeys` implementation.

### Credential check
Command:

```bash
grep -RInE 'apiKey|private_key|AIza|your-api-key|your-project' live-tracker/
```

Result: **no matches**. The web tracker contains no embedded Firebase credentials or placeholders.

## 5. Honest limitations / not verified end-to-end

- No real Firebase project deployment was performed. The Hosting runtime-config assumption (`/__/firebase/init.json`) is standard Firebase Hosting behavior, but a live deployed URL was not produced here.
- Real FCM delivery to physical guardian devices was not verified. Verification covered TypeScript compilation, rule-level authorization, and the client response parser only.
- WorkManager reboot/process-death recovery was tested through injected fakes in unit tests, not on an actual device.
- The full rules suite still has 18 unrelated failures listed above. Person 3's journey/guardian rules are independently green.
- No commit was created; the working tree intentionally contains all changes for review.

## 6. Review guide

Key Person 3 files:

- `app/src/main/java/com/example/nightagent/journey/JourneyEngine.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyRepository.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyLocalStore.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyViewModel.kt`
- `app/src/main/java/com/example/nightagent/sos/SafeWalkManager.kt`
- `app/src/main/java/com/example/nightagent/sos/SOSManager.kt`
- `app/src/main/java/com/example/nightagent/guardian/repository/GuardianRepositoryImpl.kt`
- `app/src/main/java/com/example/nightagent/guardian/repository/GuardianNotificationResponse.kt`
- `app/src/main/java/com/example/nightagent/ui/screens/JourneyScreen.kt`
- `live-tracker/index.html`
- `live-tracker/contract.js`
- `functions/src/index.ts`
- `firestore.rules`
- `firestore.indexes.json`
- `firebase.json`
- `tests/live-tracker/contract.test.js`
- `tests/rules/journey.guardian.rules.test.js`
- `app/src/test/java/com/example/nightagent/journey/JourneyLocalStoreTest.kt`
- `app/src/test/java/com/example/nightagent/guardian/repository/GuardianNotificationResponseTest.kt`

The final state is suitable for review and continued integration without disturbing Person 2's concurrent work.
