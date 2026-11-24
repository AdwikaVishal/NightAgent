# NightAgent — Security Review Corrections (Phase 1A Revised)

**Repository:** `NightAgent-Final-sms-feature-safe/`
**Firebase project:** `nightagent-35d38`
**Phase:** 1A — Firebase Security Hardening (REVISED)
**Status:** Corrected rules and tests delivered. Tests NOT yet executed (no Firebase emulator available on this machine).

---

## 1. Issues Found and Fixed

### Issue 1 — Broad Firestore `allow read, write` permissions

**Problem:** Firebase security rules are additive. Any matching `allow` expression that evaluates to `true` grants access. The original rules used broad `allow read, write: if isOwner(uid)` which overrode the more restrictive `create`/`update`/`delete` rules beneath it. For example, `allow delete: if false` was meaningless because `allow write` already granted deletion.

**Fix:** Removed all broad `allow read, write` permissions. Every operation (read, create, update, delete) is now independently defined with its own authorization expression.

**Affected collections:**
- `sos_alerts/{uid}/sessions/{sessionId}`
- `sos_alerts/{uid}/sessions/{sessionId}/location_updates/{locationId}`
- `sos_logs/{logId}`
- `safe_walks/{userId}`
- `safe_walks/{userId}/locations/{locationId}`
- `users/{uid}`
- `chats/{chatId}`
- `chats/{chatId}/messages/{msgId}`
- `fcm_requests/{requestId}`

### Issue 2 — `safe_walks/{userId}` variable mismatch

**Problem:** The rule used `isOwner(uid)` but the path variable was `userId`, so `uid` was undefined and ownership checks always failed.

**Fix:** Changed to `isOwner(userId)` to match the path variable.

### Issue 3 — Voice messages readable by all authenticated users

**Problem:** `allow read: if isAuthenticated()` on `voice_messages/{senderId}/{messageId}` meant any authenticated user could download any voice message if they obtained the storage path. A UUID path is not an authorization mechanism.

**Fix:** 
- Changed storage path to `voice_messages/{chatId}/{senderId}/{messageId}` so chatId is in the path.
- Required Storage metadata on upload: `chatId`, `senderId`, `participants` ([uid1, uid2]).
- Read is now allowed only if `request.auth.uid` is in `resource.metadata.participants`.
- Delete is allowed only if `resource.metadata.senderId == request.auth.uid`.
- Upload and delete are separate operations; `request.resource` is never accessed during deletion.

**Dependency:** Android upload code must set Storage metadata with `chatId`, `senderId`, and `participants` for these rules to work end-to-end.

### Issue 4 — Missing validations

**Fixes applied:**
- **Chat creation:** Validates participants list (size == 2, all strings, auth.uid is a participant), restricts allowed fields to `[chatId, participants, createdAt]`.
- **Messages:** Validates both senderId and receiverId are chat participants before allowing creation. ReceiverId spoofing to non-participants is blocked.
- **Location updates:** Added geographic bounds (latitude -90 to 90, longitude -180 to 180), strict field set `["lat", "lng", "time"]`, extra-field rejection.
- **FCM requests:** Field size limits (token ≤200 chars, senderName ≤100 chars), exact field set validation, chat existence check, sender chat-membership check.
- **SOS sessions/logs:** Coordinate bounds, strict field sets, no broad permissions.

---

## 2. Files Created or Modified

| File | Action | Purpose |
|------|--------|---------|
| `firestore.rules` | Rewritten | Corrected security rules with independent per-operation permissions |
| `storage.rules` | Rewritten | Participant-based voice read, separate upload/delete, metadata validation |
| `tests/rules/firestore.rules.test.js` | Updated | Added regression tests for spoofed receiverId, unauthorized participant, SOS log deletion, invalid GPS bounds, extra fields |
| `tests/rules/storage.rules.test.js` | Rewritten | Updated for metadata requirements, added non-participant read/delete tests, invalid content type tests |
| `SECURITY_REVIEW_CORRECTIONS.md` | New | This report |
| `SECURITY_REMEDIATION_REPORT.md` | Existing | Updated (from Phase 1A initial delivery) |

---

## 3. Regression Tests Added

| Test | Collection | Purpose |
|------|------------|---------|
| SOS session cannot be deleted | `sos_alerts/{uid}/sessions/{sessionId}` | Prevent emergency record deletion |
| SOS log cannot be deleted | `sos_logs/{logId}` | Prevent legacy log deletion |
| Sender cannot set receiverId to non-participant | `chats/{chatId}/messages/{msgId}` | Prevent message spoofing |
| User cannot add unauthorized participant via chat update | `chats/{chatId}` | Prevent participant tampering |
| Chat creation with invalid participants is denied | `chats/{chatId}` | Prevent invalid chat structures |
| Latitude out of bounds is denied | `sos_alerts/.../location_updates/{locationId}` | Prevent invalid geo data |
| Longitude out of bounds is denied | `sos_alerts/.../location_updates/{locationId}` | Prevent invalid geo data |
| Location update with extra field is denied | `sos_alerts/.../location_updates/{locationId}` | Prevent field injection |
| Non-participant cannot read voice message | `voice_messages/...` | Prevent unauthorized audio access |
| Owner can delete their own voice message | `voice_messages/...` | Verify sender deletion works |
| Cross-user cannot delete voice message | `voice_messages/...` | Verify cross-user delete blocked |
| Cross-user cannot read evidence | `evidence/...` | Verify evidence isolation |
| Upload without metadata is denied | `voice_messages/...` | Enforce metadata requirement |
| Uploading a file with invalid content type is denied | `voice_messages/...` | Enforce content type validation |

---

## 4. Test Execution Status

**Tests NOT executed.** This machine has no Firebase CLI or emulator installed. The following command must be run in an environment with Node.js and Firebase CLI:

```bash
cd NightAgent-Final
npm install

npx firebase emulators:exec \
  --project nightagent-35d38 \
  --only auth,firestore,storage \
  "npm test"
```

**Actual test results are not available** until the emulator suite is run. No pass/fail claims are made.

---

## 5. Git Verification

| Check | Result |
|-------|--------|
| `git status` | Only new deliverable files untracked; no existing source modified |
| `git diff --stat` | No modifications to tracked files (all new files are untracked) |
| `git ls-files \| grep -Ei 'serviceAccount\|private.key'` | **No privileged credential files tracked** |
| Existing Android source files | **None modified** |

---

## 6. Remaining Vulnerabilities

| ID | Vulnerability | Severity | Status |
|----|--------------|----------|--------|
| R-01 | `google-services.json` committed to Git with live API key | CRITICAL | Open — requires project owner approval to rotate |
| R-02 | `android:allowBackup="true"` | HIGH | Open — Android-side change (coordinate with Person 5) |
| R-03 | PII/location logged to Logcat | HIGH | Open — Android-side change (coordinate with Person 5) |
| R-04 | No network security config | MEDIUM | Open |
| R-05 | Exported `MainActivity` with unvalidated deep-link | MEDIUM | Open |
| R-06 | FCM Cloud Function not yet deployed | HIGH | Open — Phase 1C |
| R-07 | SOS/safe-walk location data has no retention/TTL | MEDIUM | Open — P1-6 |
| R-08 | `conversationId` vs `chatId` FCM payload mismatch | MEDIUM | Open — Phase 1B |
| R-09 | No Blaze plan confirmed for Cloud Functions | HIGH | Open — M-2 |

---

## 7. Phase 1A Revised Acceptance Criteria

| Criterion | Status |
|-----------|--------|
| No broad SOS write permissions | ✅ Fixed — independent read/create/update/delete |
| No broad SOS log write permissions | ✅ Fixed — independent read/create/update/delete |
| Correct safe-walk ownership checks | ✅ Fixed — `isOwner(userId)` |
| Participant-based voice-message access | ✅ Fixed — metadata.participants check |
| Separate upload and delete permissions | ✅ Fixed — create/delete are separate operations |
| All regression tests pass in Firebase Emulator | ⏳ Pending — emulator not available |
| Security report updated | ✅ This document |

**Phase 1A is NOT yet fully accepted** until emulator tests are executed and pass.

---

## 8. Next Step: Phase 1B — FCM Payload Compatibility Fix

Do not start Phase 1B until Phase 1A emulator tests pass. Phase 1B will fix the `chatId` vs `conversationId` mismatch in `VoiceMessageFirebase.kt` and `MyFirebaseMessagingService.kt`.
