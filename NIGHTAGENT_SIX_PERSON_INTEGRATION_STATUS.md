# NightAgent Six-Person Integration Status

Date: 2026-11-25  
Repository state: branch `main`, HEAD `0afca43`, working tree dirty and preserved.

## 1. Status summary

| Contributor / module | Integrated status | Evidence | Outstanding |
|---|---|---|---|
| Person 1 — SOS | Partial / integration-ready in code | `functions/src/index.ts`, `SOSManager.kt`, `EmergencySessionRepository.kt`, rule tests, static callable tests | Real guardian FCM fanout not executed; duplicate/dead SOS writers remain |
| Person 2 — Guardian | Partial | Consent callables, `GuardianRepositoryImpl.kt`, `GuardianViewModel.kt`, `GuardianScreen.kt`, consent emulator tests | `acceptInvitation`/`rejectInvitation` legacy local paths still exist; no real invite push receipt |
| Person 3 — Safe Journey / tracker | Integrated locally | `JourneyEngine.kt`, `JourneyRepository.kt`, `JourneyLocalStore.kt`, `live-tracker/*`, JVM + browser tests | No device process-restart test; legacy `safe_walks` mirror retained for compatibility |
| Person 4 — Evidence | Partial | `EvidenceManager.kt`, `EvidenceUploadWorker.kt`, `EvidenceCaptureService.kt`, `EvidenceVaultScreen.kt`, storage rules and unit tests | `person4-evidence/` is not shipped as an imported module; duplicate evidence writer paths remain |
| Person 5 — UI | Partial | `GuardianScreen.kt`, `JourneyScreen.kt`, `EvidenceVaultScreen.kt`, `MainActivity.kt` permission/token flow | No Compose UI tests or instrumentation tests; several settings remain unused |
| Person 6 — Firebase / backend | Partial / core contracts hardened | `functions/src/index.ts`, `firestore.rules`, `storage.rules`, `firebase.json`, `firestore.indexes.json`, emulator suites | No `.firebaserc`; no deployment; no callable runtime suite with Auth; no real FCM receipt |

## 2. Cross-module contracts now enforced

1. **Guardian consent**
   - Server owns invitations and link arrays.
   - No guardian link exists until `respondGuardianInvite` succeeds.
   - Firestore rules deny client writes to `guardianUids`, `guardianInvites`, and `emergency_notification_requests`.

2. **SOS delivery**
   - Server resolves owner-linked guardians.
   - `emergency_notification_requests/{sosSessionId}` provides per-session idempotency.
   - Client only reports delivery when the trusted response contains confirmed recipients.

3. **Journey/offline**
   - `Result.success` means local durability only.
   - `hasPendingSync()` / `pendingSync` distinguish unconfirmed remote writes.
   - Local cache is owner-scoped and cleared on sign-out/account switch.

4. **Tracker**
   - Canonical `journeys` + latest `locations` read path is primary.
   - `safe_walks` is fallback only.
   - Null island/out-of-range coordinates are rejected before rendering.

5. **Android API 35**
   - Manifest declares FGS types for microphone/location services.
   - `AudioStreamingService` and `StealthDetectionService` now pass matching FGS types on Android 14+ and stop cleanly on permission/security failure.

## 3. Known integration gaps still blocking full sign-off

- **Anonymous startup auth:** app can still begin flows before a real signed-in UID is guaranteed. Journey retry explicitly rejects `anonymous_user`, but broader startup ordering needs a product decision.
- **Duplicate/dead writers:** multiple SOS/evidence writers remain; canonical path needs a single owner to avoid conflicting writes.
- **Person 4 module not shipped:** `person4-evidence/` is present in the tree but not part of the Gradle module graph; current evidence code lives under `app`.
- **Dead routes:** `contacts`, `sosactive`, and streaming navigation paths are not reachable from the shipped UI flow.
- **Unused settings:** settings screens exist but some toggles are not consumed by runtime engines.
- **Test coverage holes:** no Compose UI, instrumentation, device-process-restart, real push receipt, or production-equivalent E2E tests.
- **Functions runtime:** callables are compile/contract tested and rules are emulator tested, but authenticated callable invocations were not run.

## 4. What passed together locally/emulator

- Android compiles, lints, assembles, and runs 93 JVM unit tests with zero failures.
- Functions compile and run 9 contract tests with zero failures.
- Firebase rules run 105 emulator tests with zero failures.
- Live tracker browser smoke runs 12/12 checks in headless Chrome.
- `git diff --check` reports no whitespace errors.

These results prove source-level and emulator-level coherence across the six modules. They do not prove real device or production interoperability.

## 5. Integration gate

**Status: NO-GO for commit/merge/push.**

Reason: physical-device/runtime FCM delivery, API 35 runtime FGS behavior, callable runtime Auth invocation, and production-equivalent deployment verification remain unperformed. Code-level integration is substantially improved, but six-person application-level sign-off requires the missing runtime evidence and resolution of the dead/duplicate paths listed above.
