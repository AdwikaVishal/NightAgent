# NightAgent — Phase 1A Critical Remediation Report

**Repository:** `NightAgent-Final-sms-feature-safe/`
**Firebase project:** `nightagent-35d38` (NOT deployed to)
**Phase:** 1A — Firebase Security Hardening (Critical Findings Remediation)
**Status:** Remediation implemented, NOT deployed, emulator tests NOT executed

---

## 1. Executive Verdict

**NOT APPROVED** for production deployment.

Four critical findings from `PHASE_1A_FINAL_CODE_REVIEW.md` have been addressed:

| Finding | Priority | Status | Resolution |
|---------|----------|--------|------------|
| C-1 Legacy voice-path denied | Critical | Fixed | Narrow sender-only read rule added to `storage.rules` |
| C-2 Evidence MIME mismatch | Critical | Fixed | Content type resolved from file extension in `EvidenceUploadWorker.kt` |
| C-3 Untrusted metadata | Critical | Documented | Architectural analysis in Section 4; no code change possible at Storage layer |
| C-4 Missing phone index | High | Fixed | `firestore.indexes.json` created with `users.phoneNumber` index |

**Production readiness verdict: NOT READY.** Emulator tests have not been executed (no Firebase CLI available on this machine). The C-3 architectural risk remains unresolved and requires a production decision before deployment.

---

## 2. Finding-by-Finding Status

### C-1 — Legacy voice-message compatibility

**Status:** Fixed (with documented limitation)

**Problem:** Existing voice messages on `voice_messages/{senderId}/{messageId}.m4a` (old path, no Storage metadata, no chatId in path) fall under the catch-all deny rule in corrected `storage.rules`.

**Resolution:** Added a narrowly scoped legacy read rule to `storage.rules`:

```
match /voice_messages/{senderId}/{messageId} {
  allow read: if isAuthenticated() && request.auth.uid == senderId;
  allow create: if false;
  allow update: if false;
  allow delete: if false;
}
```

**Security analysis:**
- Only the original sender (uid matching path variable `senderId`) can read their own legacy messages
- No cross-user access — receivers cannot read legacy messages through Storage
- Create/update/delete prohibited on legacy path
- No broad authenticated-user access
- Legacy messages have no Storage metadata, so `validVoiceMetadata()` cannot be applied; the path-variable sender match is the only authorization available

**Limitation documented:** Receivers cannot access legacy messages through Storage. They must be re-uploaded via the canonical path (see migration plan below). Storage rules cannot query Firestore to verify chat membership for legacy objects, so receiver-side legacy access is not possible without re-upload.

**Migration plan:**
- **Option A (immediate):** Deploy corrected rules with legacy sender-only read rule. Senders can access their own legacy messages. Receivers must re-upload or obtain messages through alternative means.
- **Option B (cleanup):** Android app lists messages from Firestore `chats/{chatId}/messages`, re-uploads legacy audio files to the new canonical path with metadata, updates `audioUrl` in Firestore, deletes old-path objects.
- **Rollback:** Revert `storage.rules` to remove the legacy match block; legacy messages become fully inaccessible.

### C-2 — Evidence content type mismatch

**Status:** Fixed

**Problem:** `EvidenceUploadWorker.kt` set `application/octet-stream` for all evidence files, but `storage.rules` `EVIDENCE_CONTENT_TYPES` does not include that type. Evidence uploads would be denied.

**Resolution:** Modified `EvidenceUploadWorker.kt` to resolve content type from file extension:

| Extension | Content Type | In EVIDENCE_CONTENT_TYPES |
|-----------|-------------|--------------------------|
| `.m4a` | `audio/m4a` | Yes |
| `.aac` | `audio/aac` | Yes |
| `.mp4` | `video/mp4` | Yes |
| `.mov` | `video/quicktime` | Yes |
| `.avi` | `video/x-msvideo` | Yes |
| Other | null (upload skipped with warning) | N/A |

**Files changed:**
- `app/src/main/java/com/example/nightagent/evidence/EvidenceUploadWorker.kt` — Added `contentTypeForFile()` helper; replaced hardcoded `application/octet-stream` with extension-based resolution; unrecognized extensions are skipped with `Log.w` warning

**Security:** No arbitrary content types allowed. Files with unrecognized extensions are not uploaded. Owner-only access preserved. File size limits (50 MB) unchanged.

### C-3 — Trusted voice-download authorization

**Status:** Documented (architectural decision, no code change possible)

**Problem:** Storage rules cannot query Firestore to verify that client-supplied `participants` metadata matches `chats/{chatId}/participants`. A malicious uploader could set arbitrary `participants` values. Tokenized Firebase download URLs are bearer tokens — anyone with the URL can download the file if Storage read rules permit.

**Security architecture analysis:**

```
Authorization layers for voice-message download:

Layer 1: Firestore (TRUSTED)
  └─ chats/{chatId}/messages/{msgId} read
     └─ Only chat participants can read the message document
     └─ Only participants obtain the audioUrl download URL
     └─ Server-enforced, cannot be bypassed by client

Layer 2: Storage (PARTIALLY TRUSTED)
  └─ voice_messages/{chatId}/{senderId}/{messageId}.m4a read
     └─ Checks resource.metadata.participants contains request.auth.uid
     └─ Metadata is client-supplied at upload time
     └─ CANNOT verify against Firestore chat membership
     └─ A malicious uploader could set false participants

Layer 3: Download URL (NOT TRUSTED)
  └─ Tokenized URL is a bearer credential
  └─ Anyone with the URL can download (if Storage read permits)
  └─ URL can be shared, leaked, or exfiltrated
  └─ Bypasses Firestore authorization entirely
```

**Production-safe authorization design options:**

| Option | Description | Trade-offs |
|--------|-------------|------------|
| **A: Firestore-as-gate (current)** | Storage read relies on client metadata; Firestore controls URL distribution | Simple, no backend needed. Residual risk: bearer URL leakage, metadata spoofing |
| **B: Signed URLs (Cloud Function)** | Cloud Function generates time-limited signed download URLs after verifying Firestore chat membership | Strong authorization, no bearer URL persistence. Requires Cloud Functions (Blaze plan), adds latency |
| **C: Proxy download (Cloud Function)** | Cloud Function proxies Storage download after verifying Firestore membership | Strongest authorization, no public Storage read. Highest complexity, highest cost |

**Recommendation:** Accept Option A for MVP with documented residual risk. Implement Option B or C in Phase 1C when Cloud Functions are deployed.

**Effect on Android playback:**
- `VoicePlayer.playOrPause()` receives `audioUrl` from Firestore and passes it to `ExoPlayer`
- No Storage-level authorization check on the Android side
- Playback succeeds if Storage rules permit the download (metadata participant check)
- Non-participants cannot obtain the URL (Firestore blocks message read)
- But once obtained, the URL is a bearer token

**Residual risks:**
1. **Metadata spoofing:** A malicious sender could set `participants: [victimUid]` on their own upload, causing the victim to be able to read it (not a privilege escalation, but misleading)
2. **URL leakage:** A compromised participant device exposes all message URLs
3. **URL sharing:** A participant can share the download URL with non-participants

**Mitigation:** Documented. No code change at this phase. Revisit in Phase 1C with Cloud Functions.

### C-4 — Firestore phone index

**Status:** Fixed (index definition created, not deployed)

**Problem:** `VoiceMessageFirebase.findUidByPhone()` queries `users` collection with `whereEqualTo("phoneNumber", ...)`. Firestore requires a single-field index on `phoneNumber` (ascending) for this query. Without it, the query fails at runtime.

**Resolution:** Created `firestore.indexes.json`:

```json
{
  "indexes": [
    {
      "collectionGroup": "users",
      "queryScope": "COLLECTION",
      "fields": [
        {
          "fieldPath": "phoneNumber",
          "order": "ASCENDING"
        }
      ]
    }
  ],
  "fieldOverrides": []
}
```

**Index details:**
- Collection: `users` (single-collection scope, not collection group)
- Field: `phoneNumber`
- Order: ASCENDING
- Query pattern: `whereEqualTo("phoneNumber", value).limit(1)`

**Deployment:** Must be created in Firebase Console or via `firebase deploy --only firestore:indexes`. NOT deployed to production. Verification: Run `findUidByPhone()` query in Firestore emulator or Firebase Console and confirm it returns results without "index not found" error.

---

## 3. Files Changed

| File | Change | Finding Addressed |
|------|--------|-------------------|
| `storage.rules` | Added legacy voice-message read rule (sender-only) | C-1 |
| `storage.rules` | No changes needed for C-2 (content types already in rules) | C-2 |
| `EvidenceUploadWorker.kt` | Added `contentTypeForFile()` helper; replaced `application/octet-stream` with extension-based resolution | C-2 |
| `firestore.indexes.json` | New file — `users.phoneNumber` single-field index | C-4 |
| (No code change) | C-3 documented in this report (architectural decision) | C-3 |

**No existing source files were deleted.** `FirebaseConfig.kt`, `AuthManager.kt`, `FirestoreManager.kt`, `MyFirebaseMessagingService.kt`, `MainActivity.kt`, `NavGraph.kt` were not touched.

---

## 4. Final Authorization Architecture Recommendation

**Voice-message download authorization model:**

```
┌─────────────────────────────────────────────────────────────┐
│  CLIENT (Android app)                                       │
│  └─ VoicePlayer plays audioUrl from Firestore               │
│     └─ No Storage-level authorization check on client       │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼ (bearer URL)
┌─────────────────────────────────────────────────────────────┐
│  FIREBASE STORAGE                                           │
│  └─ voice_messages/{chatId}/{senderId}/{messageId}.m4a      │
│     └─ Read rule: request.auth.uid in resource.metadata     │
│        .participants (client-supplied, NOT trusted)         │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼ (URL obtained from Firestore)
┌─────────────────────────────────────────────────────────────┐
│  CLOUD FIRESTORE (TRUSTED AUTHORIZATION GATE)               │
│  └─ chats/{chatId}/messages/{msgId} read                    │
│     └─ Only chat participants can read                      │
│     └─ Only participants obtain audioUrl                    │
│     └─ Server-enforced rules                                │
└─────────────────────────────────────────────────────────────┘
```

**Key principle:** Firestore is the authorization gate. Storage metadata is a best-effort signal, not a trust boundary. Download URLs are bearer tokens — treat them as sensitive.

**Production decision required:** Accept bearer-URL risk (Option A) or implement signed URLs via Cloud Functions (Option B/C in Phase 1C).

---

## 5. Legacy Migration and Rollback Plan

### Migration Options

**Option A: Dual-path read (deployed in storage.rules)**

Legacy read rule allows senders to read their own legacy messages. Receivers cannot read legacy messages through Storage.

**Steps:**
1. Deploy corrected `storage.rules` with legacy sender-only read block
2. Android app uses new canonical path for all new uploads
3. Senders can access legacy messages via sender-only read
4. Receivers must obtain messages through re-upload (Option B)

**Option B: One-time re-upload migration (not implemented)**

1. Deploy corrected `storage.rules` (new path only)
2. Android app lists messages from Firestore `chats/{chatId}/messages`
3. For each message with `audioUrl` on old path, re-upload audio to new path
4. Update Firestore `audioUrl` to new download URL
5. Delete old-path objects

**Rollback Plan:**
1. Remove legacy match block from `storage.rules`
2. Revert Android code to old path and no metadata (if needed)
3. Legacy messages become fully inaccessible (acceptable — migration should be complete)

---

## 6. Tests Executed

**Tests NOT executed.** This machine has no Firebase CLI, emulator, or Android SDK installed.

| Test Category | Status | Required Environment |
|--------------|--------|---------------------|
| Firestore rules unit tests | Not executed | Firebase CLI + emulator |
| Storage rules unit tests | Not executed | Firebase CLI + emulator |
| Voice upload with canonical path + metadata | Not executed | Android SDK + emulator |
| Voice download/playback compatibility | Not executed | Android SDK + emulator |
| Legacy voice-message read (sender-only) | Not executed | Firebase CLI + emulator |
| Non-participant access denial | Not executed | Firebase CLI + emulator |
| Evidence upload with valid content type | Not executed | Android SDK + emulator |
| Evidence upload with invalid content type | Not executed | Android SDK + emulator |
| Evidence owner-only access | Not executed | Android SDK + emulator |
| Phone lookup query compatibility | Not executed | Firebase emulator + index |

**To execute:** Run on a machine with Node.js and Firebase CLI:

```bash
cd NightAgent-Final
npm install

npx firebase emulators:exec \
  --project demo-nightagent \
  --only auth,firestore,storage \
  "npm test"
```

Use `demo-nightagent` (demo project ID) — not the production project.

---

## 7. Remaining Risks

| ID | Risk | Severity | Status | Required Action |
|----|------|----------|--------|-----------------|
| R-01 | Storage metadata is client-supplied and untrusted | HIGH | Documented (C-3) | Accept bearer-URL risk or implement signed URLs (Phase 1C) |
| R-02 | Download URL is a bearer token | MEDIUM | Documented (C-3) | Review download-token handling; consider signed URLs |
| R-03 | `google-services.json` committed to Git | CRITICAL | Open | Add to `.gitignore`, inject at build time, rotate key |
| R-04 | `users.phoneNumber` index created but not deployed | High | Fixed (file created) | Deploy index to Firebase project |
| R-05 | Legacy messages: receivers cannot read via Storage | Medium | Fixed (documented) | Implement Option B re-upload migration |
| R-06 | `allowBackup="true"` | HIGH | Open | Set `allowBackup="false"` for release (Person 5) |
| R-07 | PII/location in Logcat | HIGH | Open | Guard logs behind `Build.DEBUG` (Person 5) |
| R-08 | FCM `chatId` vs `conversationId` mismatch | MEDIUM | Open | Phase 1B fix |
| R-09 | Cloud Function not deployed | HIGH | Open | Phase 1C (requires Blaze plan) |

---

## 8. Updated Production-Readiness Verdict

**NOT READY** for production deployment.

**Blocking items:**
1. Emulator tests not executed (no Firebase CLI available)
2. C-3 bearer-URL risk not resolved (architectural decision pending)
3. `users.phoneNumber` index not deployed to Firebase project
4. `google-services.json` still committed to Git
5. Legacy receiver-side access not available (requires re-upload migration)

**Ready for:** Code review of remediation changes. **Not ready for:** Production deployment or emulator rule verification without resolving the blocking items above.

---

## 9. Git Status

```
git diff --stat:
 app/src/main/java/com/example/nightagent/evidence/EvidenceUploadWorker.kt |  13 ++-
 storage.rules                                                           |  14 ++++
 firestore.indexes.json                                                  |   8 ++++

git status --short:
 M app/src/main/java/com/example/nightagent/evidence/EvidenceUploadWorker.kt
 M storage.rules
 ?? firestore.indexes.json
```

**3 files changed.** 2 existing source files modified, 1 new index definition file created. No privileged credentials introduced. No existing source files deleted.
