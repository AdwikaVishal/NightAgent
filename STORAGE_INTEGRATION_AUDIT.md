# NightAgent — Storage Integration Audit (Phase 1A Final)

**Repository:** `NightAgent-Final-sms-feature-safe/`
**Firebase project:** `nightagent-35d38`
**Phase:** 1A — Firebase Security Hardening (Final Integration Audit)
**Status:** READ-ONLY. No source files modified.

---

## 1. Executive Summary

The corrected `storage.rules` are **incompatible** with the existing Android voice-message upload/download implementation in three critical ways:

1. **Storage path mismatch:** Android uploads to `voice_messages/{senderId}/{messageId}.m4a`. The corrected rules expect `voice_messages/{chatId}/{senderId}/{messageId}`.
2. **Missing Storage metadata:** Android `uploadAudio()` does not set Storage metadata (`chatId`, `senderId`, `participants`). The corrected rules require this metadata for participant-based read authorization.
3. **Participants not propagated:** `VoiceUploadWorker.enqueue()`, `VoiceMessageFirebase.uploadAudio()`, and `VoiceMessageRepository.sendVoiceMessage()` do not accept or pass `participants`.

**Result:** If the corrected `storage.rules` are deployed without Android changes, voice-message uploads will fail (missing metadata) and downloads will fail (no participant match in metadata). Existing voice messages on the old path will be inaccessible under the new rules.

---

## 2. Current Android Implementation

### 2.1 VoiceMessageFirebase.uploadAudio() — `…/voicemessage/firebase/VoiceMessageFirebase.kt:126-164`

| Aspect | Current implementation |
|--------|----------------------|
| Storage path | `voice_messages/$senderId/$messageId.m4a` |
| Upload method | `putStream(file.inputStream())` |
| Storage metadata | **None set** |
| Parameters | `file`, `senderId`, `messageId`, `onProgress` |
| Returns | Download URL string |

**No `chatId` or `participants` are passed to Storage.** The `chatId` is computed later (line 70 of `VoiceUploadWorker.kt`) only for the Firestore message document.

### 2.2 VoiceUploadWorker — `…/voicemessage/upload/VoiceUploadWorker.kt:33-111`

| Aspect | Current implementation |
|--------|----------------------|
| Calls `uploadAudio()` with | `file`, `senderId`, `messageId`, `onProgress` |
| `participants` parameter | **Not accepted, not passed** |
| `chatId` parameter | **Not accepted** (computed after upload) |
| `enqueue()` parameters | `context, filePath, messageId, senderId, receiverId, durationMs, fileSize` |
| `enqueue()` participants | **Not accepted** |

### 2.3 VoiceMessageRepository.sendVoiceMessage() — `…/voicemessage/repository/VoiceMessageRepository.kt:82-145`

| Aspect | Current implementation |
|--------|----------------------|
| Calls `uploadAudio()` with | `file`, `senderId`, `messageId`, `onProgress` |
| `participants` | **Not passed** |
| `chatId` passed to upload | **No** (computed separately for Firestore) |

### 2.4 EvidenceUploadWorker — `…/evidence/EvidenceUploadWorker.kt:26-80`

| Aspect | Current implementation |
|--------|----------------------|
| Storage path | `evidence/$userId/$sosId/$relativePath` |
| Upload method | `putFile(android.net.Uri.fromFile(file))` |
| Storage metadata | **None set** |
| Path compatibility with rules | Partially compatible (owner-only `evidence/{userId}/{allPaths=**}`) |
| Deprecated API | `Uri.fromFile()` — may throw on Android 10+ strict mode |

### 2.5 VoicePlayer — `…/voicemessage/audio/VoicePlayer.kt:49-128`

| Aspect | Current implementation |
|--------|----------------------|
| Playback source | `audioUrl` (download URL string from Firestore) |
| Storage interaction | None — uses `MediaItem.fromUri(audioUrl)` directly |
| Authorization check | None at Storage level; relies on URL being unguessable |

### 2.6 VoiceMessageViewModel — `…/voicemessage/viewmodel/VoiceMessageViewModel.kt:251-278`

| Aspect | Current implementation |
|--------|----------------------|
| Calls `repository.sendVoiceMessage()` with | `file`, `durationMs`, `senderId`, `receiverId`, `onProgress` |
| `participants` | **Not available** at this layer |

---

## 3. Integration Verification (8 Questions)

| # | Question | Answer | Evidence |
|---|----------|--------|----------|
| 1 | Does Android upload code provide `chatId`, `senderId`, `participants` as Storage metadata? | **No.** `uploadAudio()` sets no Storage metadata at all. `senderId` is in the path only. | `VoiceMessageFirebase.kt:141-154` — `putStream(file.inputStream())` with no `.setMetadata()` call |
| 2 | Does Android code use `voice_messages/{chatId}/{senderId}/{messageId}` path? | **No.** Uses `voice_messages/{senderId}/{messageId}.m4a` (no chatId, has `.m4a` extension). | `VoiceMessageFirebase.kt:143` |
| 3 | Are all download URLs and storage references generated using the same path? | **Yes** within current code — `uploadAudio()` generates the URL from the same path it uploads to. But this path differs from the corrected rules. | `VoiceMessageFirebase.kt:143,157` |
| 4 | Are existing voice messages using the old path still accessible under corrected rules? | **No.** Corrected rules match `voice_messages/{chatId}/{senderId}/{messageId}`. Old path `voice_messages/{senderId}/{messageId}.m4a` does not match. Catch-all denies it. | `storage.rules` match block + catch-all deny |
| 5 | Can a non-participant obtain or use a download URL? | **Yes, currently.** The download URL is stored in Firestore `audioUrl` field. Any authenticated user who can read the chat document (participant) gets the URL. But Storage rules currently allow any authenticated user to read any voice message by path (in original rules) or deny all (in corrected rules, due to missing metadata). | `VoiceMessage.kt:61` (`audioUrl` in `toFirestoreMap()`) |
| 6 | Is authorization checked against trusted chat membership rather than trusting client-supplied metadata alone? | **No.** Storage rules cannot query Firestore. Client-supplied Storage metadata cannot be verified against `chats/{chatId}/participants`. | `storage.rules` — no cross-service validation possible |
| 7 | Are evidence uploads compatible with current owner-only Storage rules? | **Partially.** Path `evidence/{userId}/...` matches owner-only rule. But `Uri.fromFile()` is deprecated and may fail on Android 10+. No content-type validation. | `EvidenceUploadWorker.kt:50-51` |
| 8 | Are Firestore queries compatible with the new security rules? | **Partially.** `messagesFlow` uses `orderBy("timestamp")` — needs index. `findUidByPhone` uses `whereEqualTo("phoneNumber")` — needs index. Rules allow reads by participants, which is compatible, but indexes must exist. | `VoiceMessageFirebase.kt:219-222`, `VoiceMessageFirebase.kt:62-64` |

---

## 4. Required Android Changes

To make the corrected `storage.rules` work end-to-end, the following Android changes are required:

| # | Change | File | Details |
|---|--------|------|---------|
| A-1 | Change upload path to include `chatId` | `VoiceMessageFirebase.kt` | `voice_messages/$senderId/$messageId.m4a` → `voice_messages/$chatId/$senderId/$messageId` |
| A-2 | Add `chatId` parameter to `uploadAudio()` | `VoiceMessageFirebase.kt` | New parameter needed for path construction |
| A-3 | Set Storage metadata on upload | `VoiceMessageFirebase.kt` | Add `chatId`, `senderId`, `participants` to `StorageMetadata` |
| A-4 | Add `participants` parameter to `uploadAudio()` | `VoiceMessageFirebase.kt` | Needed to populate Storage metadata |
| A-5 | Propagate `participants` through `VoiceUploadWorker` | `VoiceUploadWorker.kt` | `enqueue()` and `doWork()` need `participants` |
| A-6 | Propagate `participants` through `VoiceMessageRepository` | `VoiceMessageRepository.kt` | `sendVoiceMessage()` needs `participants` |
| A-7 | Compute `participants` before upload | `VoiceMessageViewModel.kt` | Need both UIDs before calling `sendVoiceMessage` |
| A-8 | Add Storage metadata getter for download authorization | `VoiceMessageFirebase.kt` | Read path must match upload path exactly |
| A-9 | Replace `Uri.fromFile()` with `putStream()` in EvidenceUploadWorker | `EvidenceUploadWorker.kt` | Deprecated API, may fail on Android 10+ |
| A-10 | Add Firestore indexes | Firebase Console | `users.phoneNumber`, `chats/{chatId}/messages.timestamp` |

---

## 5. Backward Compatibility Migration Plan

Existing voice messages uploaded to the old path `voice_messages/{senderId}/{messageId}.m4a` will be inaccessible under corrected rules because:
- The corrected rules match `voice_messages/{chatId}/{senderId}/{messageId}` (no extension, chatId in path)
- The catch-all `match /{allPaths=**}` denies unmatched paths

### Migration Options

**Option A: Dual-path read (recommended for transition)**

Add a temporary second match block in `storage.rules` for the old path:

```
match /voice_messages/{senderId}/{messageId} {
  allow read: if isAuthenticated() && resource.metadata != null && ...;
  allow delete: if false;
  allow create, update: if false;
}
```

This allows existing files to be read while new uploads use the new path. Delete is disabled on old path to prevent orphaned files.

**Option B: One-time migration (cleaner)**

1. Deploy corrected rules with new path only
2. Android app lists all messages from Firestore `chats/{chatId}/messages`
3. For each message with `audioUrl` on old path, re-upload to new path with metadata
4. Update Firestore `audioUrl` to new download URL
5. Delete old-path objects

**Option C: Keep old path in rules (simplest, less secure)**

Keep `voice_messages/{senderId}/{messageId}` match block for backward compatibility, but restrict read to participants. This requires knowing which chat a senderId/messageId belongs to — not possible from path alone without metadata.

**Recommended approach:** Option A for immediate compatibility, then Option B for cleanup. Do not deploy corrected rules to production without a migration plan.

---

## 6. Remaining Security Risks

| ID | Risk | Severity | Description |
|----|------|----------|-------------|
| S-01 | Storage metadata is client-supplied and untrusted | HIGH | Storage rules cannot query Firestore to verify `participants`. A malicious client could set `participants: [victimUid]` on their own upload and read others' messages. |
| S-02 | Download URL is a bearer token | MEDIUM | Once a participant obtains the `audioUrl` from Firestore, they can share it. Storage read allows any authenticated user on the path (if metadata matches). URL leakage enables unauthorized access. |
| S-03 | No voice-message content validation beyond type | MEDIUM | Rules validate content type but not file integrity, malware, or duration limits. |
| S-04 | Evidence path uses deprecated `Uri.fromFile()` | MEDIUM | `EvidenceUploadWorker.kt:51` may fail on Android 10+ strict mode. No fallback. |
| S-05 | Evidence metadata not stored in Storage rules | LOW | Evidence rules don't validate that uploads correspond to legitimate SOS sessions. |
| S-06 | Firestore `get()` in rules may fail on missing documents | LOW | `senderIsChatParticipant()` and `chatDoc()` use `get()` which returns null if doc missing; rules handle null but may cause unexpected denies. |
| S-07 | List queries need indexes | MEDIUM | `orderBy("timestamp")` on messages and `whereEqualTo("phoneNumber")` on users require Firestore indexes. Without them, queries fail regardless of rules. |
| S-08 | Voice message download URL stored in plaintext in Firestore | MEDIUM | `audioUrl` field is readable by chat participants. If a participant's device is compromised, all message URLs are exposed. |

---

## 7. Storage Metadata Authorization Limitation (Critical)

**Storage rules cannot verify client-supplied metadata against Firestore.**

The corrected rules check `request.auth.uid in resource.metadata.participants`. But:
- `resource.metadata.participants` is set by the **client** during upload
- Storage rules have **no way to query** `chats/{chatId}/participants` to verify the list is accurate
- A malicious sender could upload with `participants: [victimUid]` and the victim would be able to read the sender's message (not a privilege escalation, but misleading)
- More concerning: a malicious sender could set `participants: [theirOwnUid]` on a message they upload to another user's chatId path, and then read it back — but they already can as the sender

**Mitigation options:**
1. **Accept the limitation:** Storage metadata is a best-effort signal. The real authorization is at the Firestore level — only chat participants can read the `audioUrl` from `chats/{chatId}/messages/{msgId}`. Without the URL, they can't download.
2. **Use signed URLs:** Generate download URLs server-side (Cloud Function) with expiration, bypassing public Storage read entirely.
3. **Hybrid:** Keep Storage read open for authenticated users on participant-scoped paths, but rely on Firestore to control URL distribution.

**Recommended:** Option 1 for now (Firestore controls URL access), with Option 2 as a future enhancement.

---

## 8. Emulator Test Execution Status

**Tests NOT executed.**

This machine has no Firebase CLI or emulator installed. The following command must be run in an environment with Node.js and Firebase CLI:

```bash
cd NightAgent-Final
npm install

npx firebase emulators:exec \
  --project demo-nightagent \
  --only auth,firestore,storage \
  "npm test"
```

**Use `demo-nightagent` (demo project ID)** for emulator-only testing — this avoids connecting to the production Firebase project `nightagent-35d38`.

**Actual test results are not available** until the emulator suite is run. No pass/fail claims are made.

---

## 9. Deliverables

| Deliverable | Status |
|-------------|--------|
| STORAGE_INTEGRATION_AUDIT.md | ✅ This document |
| List of required Android changes | ✅ Section 4 |
| Backward compatibility migration plan | ✅ Section 5 |
| Remaining security risks | ✅ Section 6 |
| Emulator test execution report | ❌ Pending — emulator not available |

---

## 10. Next Steps

1. **Run emulator tests** on a machine with Firebase CLI (use `demo-nightagent` project ID).
2. **Fix test failures** if any before proceeding.
3. **Implement Android changes** (A-1 through A-10) to align upload/download with corrected Storage rules.
4. **Choose migration strategy** (Option A or B) for existing voice messages on old path.
5. **Address Storage metadata trust limitation** (S-01) — decide whether Firestore-level URL gating is sufficient or if signed URLs are needed.
6. **Proceed to Phase 1B** (FCM `chatId` vs `conversationId` fix) only after Phase 1A emulator tests pass and Android integration is verified.
