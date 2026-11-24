# NightAgent — Firebase Schema

**Source of truth:** Inline schema docs in `app/src/main/java/com/example/nightagent/voicemessage/model/VoiceMessage.kt` plus collection paths referenced across `FirestoreManager.kt`, `SOSRepository.kt`, `SafeWalkRepository.kt`, `EvidenceRepository.kt`, `VoiceMessageFirebase.kt`, `AuthManager.kt`.

**Legend:** `[EXISTING]` = code path writes/reads this collection today. `[PROPOSED]` = not yet implemented; recommended for Person 6 roadmap. `[MISSING-RULES]` = no security rules exist.

---

## Firestore project: `nightagent-35d38`

### Collection: `users`  [EXISTING]  [MISSING-RULES]

**Document:** `{uid}` (Firebase Auth UID)

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `uid` | String | `UserProfile.uid` | Auth UID |
| `displayName` | String | `UserProfile.displayName` | Set at registration |
| `phoneNumber` | String | `UserProfile.phoneNumber` | E.164, e.g. `+919876543210` |
| `fcmToken` | String | `AuthManager.saveFCMToken()`, `VoiceMessageFirebase.updateFcmToken()` | Updated on FCM token refresh |

**Writes:** `VoiceMessageFirebase.registerUser()` (set+merge), `AuthManager.saveFCMToken()` (set+merge `fcmToken`), `VoiceMessageFirebase.updateFcmToken()` (update/fallback set+merge).

**Reads:** `VoiceMessageFirebase.findUidByPhone()` (`whereEqualTo("phoneNumber", ...)`), `VoiceMessageFirebase.getUserProfile()` (doc get), `VoiceMessageFirebase.queuePushNotification()` (read token).

**Security rules needed:** Owner can read/write own doc; phone-number lookup index required (`whereEqualTo` needs a composite index — **confirm index created in Firebase Console**).

---

### Collection: `chats`  [EXISTING]  [MISSING-RULES]

**Document:** `{chatId}` where `chatId = sorted(uid1, uid2).join("_")` (deterministic, same on both devices).

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `chatId` | String | `VoiceMessage.chatId()` | Redundant with doc ID |
| `participants` | `[String]` | `[uid1, uid2]` | Two UIDs |
| `lastMessage` | String | `"🎙 Voice message"` | Set on saveMessage (merge) |
| `lastTimestamp` | Long | `message.timestamp` | Epoch ms |
| `createdAt` | Timestamp | `FieldValue.serverTimestamp()` | Set on ensureChatRoom |

**Writes:** `VoiceMessageFirebase.ensureChatRoom()` (set+merge), `VoiceMessageFirebase.saveMessage()` (update lastMessage/lastTimestamp via merge).

**Reads:** VoiceMessageScreen listens to `chats/{chatId}/messages`, not `chats` directly.

**Security rules needed:** Only participants can read/write the chat doc.

---

### Sub-collection: `chats/{chatId}/messages`  [EXISTING]  [MISSING-RULES]

**Document:** `{messageId}` (UUID or provided messageId).

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `messageId` | String | UUID.randomUUID() | Redundant with doc ID |
| `chatId` | String | computed chatId | |
| `senderId` | String | Firebase Auth UID | |
| `receiverId` | String | Firebase Auth UID | |
| `audioUrl` | String | Firebase Storage download URL | |
| `localPath` | String | `""` (never persisted) | Device-local only |
| `durationMs` | Long | recorder duration | |
| `fileSize` | Long | `file.length()` bytes | |
| `timestamp` | Long | `System.currentTimeMillis()` | Client-set for ordering |
| `uploadStatus` | String | `PENDING\|UPLOADING\|DONE\|FAILED` | Enum name |
| `messageStatus` | String | `SENT\|DELIVERED\|SEEN` | Enum name |

**Writes:** `VoiceMessageFirebase.saveMessage()` (`docRef.set(toFirestoreMap())`).

**Reads:** `VoiceMessageFirebase.messagesFlow(chatId)` — `orderBy("timestamp", ASCENDING)` + `addSnapshotListener` (real-time).

**Security rules needed:** Participants can read; sender can create; receiver can update `messageStatus` to SEEN.

---

### Collection: `fcm_requests`  [EXISTING-write only]  [MISSING-RULES]  [DEPENDS-ON-FUNCTION]

**Document:** `{auto-id}`

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `token` | String | receiver's `fcmToken` from `users/{uid}` | |
| `chatId` | String | chatId | |
| `senderId` | String | sender UID | |
| `senderName` | String | sender displayName or UID prefix | |
| `timestamp` | Long | `System.currentTimeMillis()` | |

**Writes:** `VoiceMessageFirebase.queuePushNotification()` (`collection.add(...)`).

**Reads:** Consumed by a Cloud Function (not in repo) that sends the push via Firebase Admin SDK, then deletes the doc.

**Security rules needed:** Client can create; only the Cloud Function (service account) can read/delete.

---

### Collection: `sos_alerts`  [EXISTING]  [MISSING-RULES]

**Document:** `{uid}` (one doc per user; sessions as sub-collection).

Used by `FirestoreManager.startSOSSession()`.

**Path:** `sos_alerts/{uid}/sessions/{sessionId}`

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `message` | String | SOS message text | |
| `startLat` | Double? | Location latitude | Nullable |
| `startLng` | Double? | Location longitude | Nullable |
| `startTime` | Long | `System.currentTimeMillis()` | Epoch ms |

**Writes:** `FirestoreManager.startSOSSession()` (`docRef.set(sessionData)`).

**Path:** `sos_alerts/{uid}/sessions/{sessionId}/location_updates/{auto-id}`

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `lat` | Double | Location latitude | Written every 5 s during SOS |
| `lng` | Double | Location longitude | |
| `time` | Long | `System.currentTimeMillis()` | |

**Writes:** `FirestoreManager.updateLocation()` (`collection.add(data)`).

**Security rules needed:** Owner UID read/write; guardian read access TBD (confirm who can view live SOS location).

---

### Collection: `sos_logs`  [EXISTING]  [MISSING-RULES]  [PARTIALLY-UNUSED]

**Document:** `{auto-id}`

Used by `SOSRepository.sendSOS(SOSModel)` and `EvidenceRepository.uploadFile()`.

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `userId` | String | `SOSModel.userId` | |
| `latitude` | Double | `SOSModel.latitude` | |
| `longitude` | Double | `SOSModel.longitude` | |
| `timestamp` | Long | `SOSModel.timestamp` | |
| `evidenceUrl` | String | Set by `EvidenceRepository.uploadFile()` via `update()` | Added post-upload |

**Notes:** `SOSModel` does NOT include `message` field — the FirestoreManager session (separate path) stores `message`, but `SOSRepository.sendSOS` does not. These are two parallel SOS logging paths with different schemas — **unify or deprecate one.**

**Security rules needed:** Owner read/write.

---

### Collection: `safe_walks`  [EXISTING]  [MISSING-RULES]

**Document:** `{userId}`

Used by `SafeWalkRepository.startSafeWalk()`.

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `userId` | String | | |
| `startTime` | Long | | |
| `isActive` | Boolean | Set `false` on `endSafeWalk()` | |

**Sub-collection:** `safe_walks/{userId}/locations/{auto-id}`

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `latitude` | Double | `LocationModel.latitude` | |
| `longitude` | Double | `LocationModel.longitude` | |
| `timestamp` | Long | `LocationModel.timestamp` | |

**Reads:** `live-tracker/index.html` reads this path (web viewer, detached, empty Firebase config — will not initialize).

**Security rules needed:** Owner read/write; guardian read TBD.

---

### Collection: `evidence`  [REFERENCED-in-comment-only]

`EvidenceRepository.uploadFile()` uploads to Storage path `evidence/$userId/$timestamp.mp4` and writes `evidenceUrl` to `sos_logs/{sosId}`. There is **no** top-level `evidence` collection in Firestore — evidence metadata lives only in `sos_logs`.

---

## Firebase Storage  [MISSING-RULES]

### Path: `voice_messages/{senderId}/{messageId}.m4a`  [EXISTING]

**Source:** `VoiceMessageFirebase.uploadAudio()` via `putStream(file.inputStream())`.

**Content:** M4A/AAC audio recorded by `VoiceRecorder`.

**Download URL:** Retrieved via `ref.downloadUrl.await()` and stored in `chats/{chatId}/messages/{messageId}/audioUrl`.

### Path: `evidence/{userId}/{timestamp}.mp4`  [EXISTING-code, UNUSED]

**Source:** `EvidenceRepository.uploadFile()` (`storageRef.child("evidence/$userId/$timestamp.mp4")`).

**Status:** Code exists but is not invoked by any active worker (dead). `EvidenceUploadWorker` and `VoiceUploadWorker` handle actual uploads.

**Security rules needed:** Authenticated users only; upload path must match request.auth.uid; size limits recommended.

---

## Firebase Realtime Database / Cloud Functions schema

### `fcm_requests` trigger  [PROPOSED — function not in repo]

Documented as a JavaScript comment in `VoiceMessageFirebase.kt` (`queuePushNotification`). Expected Cloud Function:

- Trigger: `firestore.document('fcm_requests/{reqId}').onCreate`
- Reads `token`, `chatId`, `senderId`, `senderName`
- Sends via `admin.messaging().send({token, data:{type:"voice_message",...}, notification:{...}, android:{priority:"high"}})`
- Deletes the doc after send

**Not deployed; not in repo.** Person 6 must create `functions/src/index.ts` (TypeScript) and deploy.

---

## Cross-collection inconsistencies (to resolve)

| Issue | Detail | Resolution |
|-------|--------|------------|
| Two SOS log paths | `FirestoreManager` → `sos_alerts/{uid}/sessions/...`; `SOSRepository` → `sos_logs/{auto-id}` | Unify into one canonical SOS collection |
| Tracking path mismatch | SOS writes `sos_alerts/{uid}/sessions/{sessionId}/location_updates`; Safe Walk writes `safe_walks/{userId}/locations`; web viewer reads `safe_walks/{userId}/locations` | Unify tracking under one collection (roadmap P1-3) |
| `sos_logs` missing `message` | `SOSModel` lacks the SOS message text that `FirestoreManager` stores separately | Add `message` to `SOSModel` or deprecate `sos_logs` |
| Evidence metadata split | Evidence upload writes Storage + `sos_logs.evidenceUrl`; no dedicated `evidence` collection | Consider `evidence/{sosId}/files/{fileId}` collection |

---

## Indexes required (confirm in Firebase Console)

| Collection | Query | Index type |
|------------|-------|------------|
| `users` | `whereEqualTo("phoneNumber", ...)` + `limit(1)` | Single-field index on `phoneNumber` (asc) |
| `chats/{chatId}/messages` | `orderBy("timestamp", ASCENDING)` | Single-field index on `timestamp` (asc) — may be auto-created |
| `fcm_requests` | (function reads by doc ID only) | None needed |

---

## Security rules status

| Resource | Rules file | Status |
|----------|------------|--------|
| Firestore | None in repo | **Missing** — open/test mode by default |
| Storage | None in repo | **Missing** — open/test mode by default |

Person 6 must create `firestore.rules` and `storage.rules` (see BACKEND_AUDIT P0-1/P0-2).
