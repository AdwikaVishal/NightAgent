# NightAgent — Security Remediation Report (Phase 1A)

**Repository:** `NightAgent-Final-sms-feature-safe/`
**Firebase project:** `nightagent-35d38` (project number `480993110584`)
**Phase:** 1A — Firebase Security Hardening
**Status:** Rules and tests created. Tests NOT yet executed (no Firebase SDK/emulator available on this machine).

---

## 1. Files Created

| File | Purpose |
|------|---------|
| `firestore.rules` | Firestore security rules for all existing collections |
| `storage.rules` | Storage security rules for voice messages and evidence |
| `firebase.json` | Firebase Emulator Suite configuration |
| `package.json` | Test dependencies (`@firebase/rules-unit-testing`, `jest`, `firebase-tools`) |
| `jest.config.js` | Jest test runner configuration |
| `tests/rules/firestore.rules.test.js` | 10 Firestore rules test suites |
| `tests/rules/storage.rules.test.js` | 10 Storage rules test suites |

**No existing source files were modified.** `FirebaseConfig.kt`, `AuthManager.kt`, `FirestoreManager.kt`, `MyFirebaseMessagingService.kt`, `MainActivity.kt`, and `NavGraph.kt` were all left untouched.

---

## 2. Firebase Credential Audit (Objective 1)

### Finding: `google-services.json` committed in first commit

- **File:** `app/google-services.json`
- **Commit:** `29bbb71` (first commit)
- **Contents:** Firebase project config including `project_number`, `project_id` (`nightagent-35d38`), `storage_bucket`, `mobilesdk_app_id`, `android_client_info.package_name`, and `api_key.current_key`.

### Assessment

The `api_key.current_key` is a **Firebase client API key** — not a service-account private key. Client API keys are intended to be embedded in mobile apps and are not, by themselves, privileged credentials for server access. However:

1. The key is committed to Git history, making it permanently exposed even if removed from the current tree.
2. Anyone with repo access can extract the key and use it for quota abuse, project fingerprinting, or targeted requests against Firebase Auth/FCM endpoints.
3. The key should be reviewed for API restrictions in the Firebase Console.

### Recommended remediation (not yet applied)

| Action | Status | Notes |
|--------|--------|-------|
| Review API key restrictions in Firebase Console | Pending | Confirm key is restricted to Android app package `com.example.nightagent` |
| Add `google-services.json` to `.gitignore` | Pending | Prevent future commits |
| Inject `google-services.json` at build time via CI/local.properties | Pending | Do not commit the file |
| Rotate the exposed key | Pending | Requires project owner approval (M-1) |
| Verify no service-account private keys are committed | Complete | None found in repo |

### No privileged credentials found

A search of the repository confirmed no service-account private keys, no FCM server keys, no signing keys, and no other privileged secrets are committed.

---

## 3. Firestore Security Rules (Objective 2)

### Rules file: `firestore.rules`

Covers all existing collections:

| Collection | Access control |
|------------|---------------|
| `users/{uid}` | Owner-only read/write/create |
| `chats/{chatId}` | Participant read; participant create (with 2-participant validation); participant update (participants/chatId immutable); delete denied |
| `chats/{chatId}/messages/{msgId}` | Participant read; sender-only create (with field validation); receiver-only update to `messageStatus=SEEN`; delete denied |
| `fcm_requests/{requestId}` | Authenticated client create (with field validation, senderId must match auth.uid); client read/delete/update denied |
| `sos_alerts/{uid}/sessions/{sessionId}` | Owner-only read/write/create; update restricted; delete denied |
| `sos_alerts/{uid}/sessions/{sessionId}/location_updates/{locationId}` | Owner read; owner create (validated lat/lng/time); update/delete denied |
| `sos_logs/{logId}` | Owner read/write/create (userId must match auth.uid); update restricted to `evidenceUrl` field only; delete denied |
| `safe_walks/{userId}` | Owner-only read/write/create/update; delete denied |
| `safe_walks/{userId}/locations/{locationId}` | Owner read; owner create (validated location); update/delete denied |

### Key security properties enforced

- **Authentication required** for all operations (`request.auth != null`)
- **User ownership** enforced (`request.auth.uid == uid`)
- **Chat participant membership** validated for reads and writes
- **Message creation** restricted to sender (`senderId == request.auth.uid`)
- **Message status updates** restricted to receiver, only `SENT` → `SEEN`
- **FCM requests** create-only for clients; read/delete denied to clients
- **SOS sessions** cannot be modified by arbitrary users
- **Location updates** validated (lat/lng as doubles, time as positive number)
- **Ownership/participant fields** immutable on update
- **No wildcard `allow read, write: if true`** rules

---

## 4. Storage Security Rules (Objective 3)

### Rules file: `storage.rules`

Covers:

| Path | Access control |
|------|---------------|
| `voice_messages/{senderId}/{messageId}.m4a` | Authenticated owner write (size ≤ 25 MB, audio content types only); owner delete; authenticated read (app-layer participant enforcement) |
| `evidence/{userId}/{timestamp}.mp4` | Authenticated owner write (size ≤ 50 MB, audio/video content types only); owner read/delete; no public access |
| Catch-all `/**` | Deny all |

### Key security properties enforced

- **Authentication required** for all operations
- **Owner upload** enforced (`request.auth.uid == senderId`/`userId`)
- **Content type validation** (audio/m4a, audio/aac, audio/mp4 for voice; video/mp4, audio/m4a, etc. for evidence)
- **Size limits** (25 MB voice, 50 MB evidence)
- **No public downloads** of evidence
- **Owner-only delete**

---

## 5. Emulator Test Configuration

### `firebase.json`

Configures Auth, Firestore, and Storage emulators on ports 9099, 8080, 9199 respectively, with UI on port 4000. Points to `firestore.rules` and `storage.rules`.

### Test infrastructure

- `package.json` — `@firebase/rules-unit-testing`, `jest`, `firebase-tools`
- `jest.config.js` — test match `tests/rules/*.test.js`, node environment, 30s timeout

### Test suites

**`tests/rules/firestore.rules.test.js`** (10 test groups):

1. `users/{uid}` — owner read/write, cross-user deny, unauthenticated deny
2. `chats/{chatId}` — participant read, non-participant deny, create with validation, participant-field immutability
3. `chats/{chatId}/messages/{msgId}` — participant read, sender create, receiver mark-seen, sender-mark-seen deny, delete deny
4. `fcm_requests/{requestId}` — client create, senderId spoofing deny, client read/delete deny
5. `sos_alerts/{uid}/sessions/{sessionId}` — owner create/read, cross-user deny, delete deny
6. `sos_alerts/{uid}/sessions/{sessionId}/location_updates/{locationId}` — owner write, cross-user deny, invalid location deny
7. `sos_logs/{logId}` — owner create, cross-user read deny, spoofed userId deny, evidenceUrl-only update
8. `safe_walks/{userId}` — owner create/read, cross-user deny, end-walk update
9. `safe_walks/{userId}/locations/{locationId}` — owner write/read, cross-user deny

**`tests/rules/storage.rules.test.js`** (10 test groups):

1. `voice_messages` — owner upload (m4a/aac), cross-user upload deny, oversized deny, invalid content type deny, owner read, cross-user read, owner delete, cross-user delete deny
2. `evidence` — owner upload (video/audio), cross-user deny, oversized deny, invalid content type deny, owner read, cross-user read deny, owner delete, cross-user delete deny
3. Catch-all — unmatched path deny

---

## 6. Test Execution

### How to run tests

```bash
# Install dependencies
npm install

# Start emulators (separate terminal)
npx firebase emulators:start --project nightagent-35d38

# Run Firestore rules tests
npm run test:firestore

# Run Storage rules tests
npm run test:storage

# Run all rules tests
npm test
```

### Expected results

All 20+ test cases should **pass** (assertSucceeds for valid operations, assertFails for unauthorized operations). No tests should be weakened to make them pass.

### Actual results

**Not available** — no Firebase SDK or emulator is installed on this machine. Tests must be executed in an environment with Node.js and the Firebase CLI/emulator available.

---

## 7. Remaining Vulnerabilities

| ID | Vulnerability | Severity | Mitigation |
|----|--------------|----------|------------|
| R-01 | `google-services.json` committed to Git with live API key | CRITICAL | Add to `.gitignore`, inject at build time, rotate key (requires owner approval) |
| R-02 | `android:allowBackup="true"` in manifest | HIGH | Set `allowBackup="false"` for release builds |
| R-03 | PII/location logged to Logcat | HIGH | Guard logs behind `Build.DEBUG` |
| R-04 | No network security config | MEDIUM | Add `network_security_config.xml` with cleartext disabled |
| R-05 | Exported `MainActivity` with unvalidated deep-link | MEDIUM | Validate `navigate_to` against allowlist |
| R-06 | `fcm_requests` read by clients blocked by rules, but function not yet deployed | HIGH | Deploy FCM Cloud Function (Phase 1C) |
| R-07 | SOS/safe-walk location data has no retention/TTL | MEDIUM | Implement scheduled cleanup function (P1-6) |
| R-08 | `conversationId` vs `chatId` FCM payload mismatch | MEDIUM | Unify payload key (Phase 1B) |
| R-09 | No Blaze plan confirmed for Cloud Functions | HIGH | Confirm billing plan before deploying functions (M-2) |

---

## 8. Schema Ambiguities

| Item | Description | Required decision |
|------|-------------|-------------------|
| A-01 | Two SOS log paths: `sos_alerts/{uid}/sessions/` and `sos_logs/{auto-id}` | Consolidate into one canonical path (P1-3) |
| A-02 | `SOSModel` missing `message` field | Add `message` to `SOSModel` or deprecate `sos_logs` |
| A-03 | Tracking path mismatch: SOS writes `sos_alerts/.../location_updates`, Safe Walk writes `safe_walks/.../locations` | Unify under `live_tracking/{userId}/locations` (P3-1) |
| A-04 | FCM payload key: sender writes `chatId`, receiver reads `conversationId` | Unify on one key (Phase 1B) |
| A-05 | `sos_logs` evidence metadata split across Storage and Firestore | Consider dedicated `evidence/{sosId}/files/` collection |
| A-06 | Guardian read access to SOS/safe-walk locations TBD | Define guardian read policy (M-4/M-5) |

---

## 9. Changes Requiring Team Approval

| # | Change | Why approval needed | Owner |
|---|--------|--------------------|-------|
| T-1 | Rotate Firebase API key in `google-services.json` | Live key in Git history; rotation affects all clients | Project owner (M-1) |
| T-2 | Add `google-services.json` to `.gitignore` and inject at build time | Changes build configuration | DevOps (M-8) |
| T-3 | Deploy Firestore/Storage security rules | Rules affect data access for all users | Person 6 / project owner |
| T-4 | Deploy FCM Cloud Function | Requires Blaze billing plan | Billing owner (M-2) |
| T-5 | Consolidate SOS collection paths (`sos_alerts` + `sos_logs`) | Schema change affects Persons 1, 3, 4 | Architecture lead (M-10) |
| T-6 | Unify FCM payload key (`chatId` vs `conversationId`) | Affects voice-message feature (Persons 4, 5) | Voice team |
| T-7 | Define retention window for location data | Privacy/compliance decision | Product lead (M-4) |

---

## 10. Next Steps (Phase 1B–1D)

1. **Phase 1B — FCM payload compatibility:** Fix `chatId` vs `conversationId` mismatch in `VoiceMessageFirebase.kt` and `MyFirebaseMessagingService.kt`.
2. **Phase 1C — Cloud Functions:** Implement `sendEmergencyNotification`, `acknowledgeEmergency`, `escalateEmergency`, `resolveEmergency` (requires Blaze plan confirmation).
3. **Phase 1D — Authentication and profile creation:** Auto-create `users/{uid}` on sign-up in `AuthManager.kt`.

**Stop after Phase 1A.** Review these rules and test results before proceeding to Cloud Functions or unrelated feature development.
