# NightAgent — Data Models

**Source:** Existing Kotlin data classes under `app/src/main/java/com/example/nightagent/model/` and `.../voicemessage/model/`. All models listed below are EXISTING unless marked `[PROPOSED]`.

**Legend:** `[EXISTING]` = class present in repo. `[PROPOSED]` = new model needed for Person 6 roadmap. `[UNUSED]` = present but not invoked by active code.

---

## 1. Voice messaging models (canonical, most complete)

### `VoiceMessage` — `voicemessage/model/VoiceMessage.kt:51`

Firestore document for `chats/{chatId}/messages/{messageId}`.

```kotlin
data class VoiceMessage(
    val messageId: String = "",
    val chatId: String = "",
    val senderId: String = "",      // Firebase Auth UID
    val receiverId: String = "",    // Firebase Auth UID
    val audioUrl: String = "",      // Firebase Storage download URL
    val localPath: String = "",     // device-local only, never written to Firestore
    val durationMs: Long = 0L,
    val fileSize: Long = 0L,        // bytes
    val timestamp: Long = 0L,       // epoch ms, client-set for ordering
    val uploadStatus: UploadStatus = UploadStatus.DONE,
    val messageStatus: MessageStatus = MessageStatus.SENT
)
```

- `toFirestoreMap()` — single source of truth for Firestore field names.
- `localPath` is always written as `""` to Firestore (device-local only).
- Status: EXISTING, fully wired to Firestore + Storage.

### `UploadStatus` enum — `VoiceMessage.kt:80`

`PENDING, UPLOADING, DONE, FAILED`. Stored as enum name string in Firestore.

### `MessageStatus` enum — `VoiceMessage.kt:81`

`SENT, DELIVERED, SEEN`.

### `UserProfile` — `VoiceMessage.kt:85`

Firestore document for `users/{uid}`.

```kotlin
data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val phoneNumber: String = "",   // E.164
    val fcmToken: String = ""
)
```

- `toMap()` helper in `VoiceMessageFirebase.kt`.
- Status: EXISTING.

### `ChatRoom` — `VoiceMessage.kt:94`

Firestore document for `chats/{chatId}`.

```kotlin
data class ChatRoom(
    val chatId: String = "",
    val participants: List<String> = emptyList(),
    val lastMessage: String = "",
    val lastTimestamp: Long = 0L
)
```

- Status: EXISTING.

### `VoiceMessageUiModel` — `VoiceMessage.kt:103`

UI-layer model (not persisted).

```kotlin
data class VoiceMessageUiModel(
    val message: VoiceMessage,
    val isOwnMessage: Boolean,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val playbackPositionMs: Long = 0L,
    val uploadProgressPercent: Int = 0
)
```

### `PlaybackState` enum — `VoiceMessage.kt:111`

`IDLE, LOADING, PLAYING, PAUSED`.

---

## 2. Location models

### `LocationModel` — `model/LocationModel.kt:7`

```kotlin
@Parcelize
data class LocationModel(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
) : Parcelable
```

- Used by Safe Walk tracking (`safe_walks/{userId}/locations`) and `EvidenceManager`.
- Status: EXISTING.

---

## 3. SOS models

### `SOSModel` — `model/SOSModel.kt:7`

```kotlin
@Parcelize
data class SOSModel(
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
) : Parcelable
```

- Used by `SOSRepository.sendSOS()` → `sos_logs`.
- **Gap:** Does NOT include `message` field. The separate `FirestoreManager.startSOSSession()` stores `message`, `startLat`, `startLng`, `startTime` in `sos_alerts`. Two parallel SOS schemas with no shared model — **unify (PROPOSED).**
- Status: EXISTING but schema-incomplete.

---

## 4. Safe Walk models

### `SafeWalkModel` — `model/SafeWalkModel.kt:7`

```kotlin
@Parcelize
data class SafeWalkModel(
    val userId: String,
    val startTime: Long,
    val isActive: Boolean
) : Parcelable
```

- Used by `SafeWalkRepository.startSafeWalk()` → `safe_walks/{userId}`.
- Status: EXISTING.

---

## 5. Safety score models (non-Firebase; Overpass API)

### `SafetyResponse` — `model/SafetyResponse.kt:5`

```kotlin
data class SafetyResponse(
    val safetyScore: Double,
    val riskLevel: String,
    val factors: SafetyFactors
)
```

- Gson-serialized (`@SerializedName`). Used by MapScreen safety score; cached in SharedPreferences.
- Status: EXISTING.

### `SafetyFactors` — `model/SafetyResponse.kt:13`

```kotlin
data class SafetyFactors(
    val poiDensity: String,
    val nearestPoliceDistance: String
)
```

- Status: EXISTING.

### `CachedSafetyMetadata` — `SafetyRepository.kt:20`

Local cache metadata class (not a Firestore model). Defined inside `SafetyRepository.kt`.

---

## 6. Models NOT in repo (PROPOSED for Person 6)

These do not exist yet and are needed to complete the backend architecture.

| Proposed model | Purpose | File path (suggested) | Firestore/Storage target |
|----------------|---------|----------------------|--------------------------|
| `AuthResult` | Wraps auth outcome (user + isNewUser) | `data/model/AuthResult.kt` | `users/{uid}` (profile creation) |
| `FcmTokenRequest` | FCM token registration payload | `data/model/FcmTokenRequest.kt` | `users/{uid}/fcmToken` |
| `SosSession` | Unified SOS session (replaces parallel `sos_alerts` + `sos_logs`) | `data/model/SosSession.kt` | `sos_sessions/{uid}` |
| `SosLocationUpdate` | Location update within an SOS session | `data/model/SosLocationUpdate.kt` | `sos_sessions/{uid}/locations/{auto-id}` |
| `EvidenceFile` | Evidence metadata (hash, storage path, upload status) | `data/model/EvidenceFile.kt` | `evidence/{sosId}/files/{fileId}` |
| `FcmRequest` | FCM push request document | `data/model/FcmRequest.kt` | `fcm_requests/{auto-id}` |
| `Contact` | Emergency contact (currently SharedPreferences only) | `data/model/Contact.kt` | `users/{uid}/contacts/{contactId}` (PROPOSED — sync to backend) |

---

## 7. Model-to-collection traceability

| Model | Firestore path | Writes via | Reads via | Status |
|-------|---------------|------------|-----------|--------|
| `UserProfile` | `users/{uid}` | `VoiceMessageFirebase.registerUser()` | `findUidByPhone`, `getUserProfile`, `queuePushNotification` | EXISTING |
| `ChatRoom` | `chats/{chatId}` | `ensureChatRoom()`, `saveMessage()` (merge) | — | EXISTING |
| `VoiceMessage` | `chats/{chatId}/messages/{messageId}` | `saveMessage()` | `messagesFlow()` | EXISTING |
| `FcmRequest` (no class) | `fcm_requests/{auto-id}` | `queuePushNotification()` | Cloud Function (missing) | EXISTING-schema, no model class |
| `SOSModel` | `sos_logs/{auto-id}` | `SOSRepository.sendSOS()` | — | EXISTING |
| (session data, no class) | `sos_alerts/{uid}/sessions/{sessionId}` | `FirestoreManager.startSOSSession()` | — | EXISTING |
| (location, no class) | `.../sessions/{sessionId}/location_updates/{auto-id}` | `FirestoreManager.updateLocation()` | — | EXISTING |
| `SafeWalkModel` | `safe_walks/{userId}` | `SafeWalkRepository.startSafeWalk()` | — | EXISTING |
| `LocationModel` | `safe_walks/{userId}/locations/{auto-id}` | `SafeWalkRepository.updateLocation()` | `live-tracker/index.html` | EXISTING |
| (evidence, no class) | Storage `evidence/{userId}/{ts}.mp4` | `EvidenceRepository.uploadFile()` (unused) | — | EXISTING-code, UNUSED |
| `VoiceMessage` audio | Storage `voice_messages/{senderId}/{messageId}.m4a` | `VoiceMessageFirebase.uploadAudio()` | download URL in message doc | EXISTING |

---

## 8. Serialization / persistence notes

- Firestore writes use plain `mapOf(...)` via `toFirestoreMap()` (VoiceMessage) or ad-hoc `hashMapOf` (FirestoreManager, SOSRepository). **No typed Firestore converter / `FirestoreDataConverter` is used.**
- `@Parcelize` models (`LocationModel`, `SOSModel`, `SafeWalkModel`) are for Android Parcel, not Firestore.
- `SafetyResponse` uses Gson `@SerializedName` (Overpass API JSON), not Firestore.
- No `kotlinx.serialization` or Moshi declared.
- **Proposed:** Add `toFirestoreMap()`/`fromFirestoreSnapshot()` factory pairs to every Firestore model for consistency (currently only VoiceMessage has this).

---

## 9. Missing model confirmations (team)

| Item | Why | Confirmation needed |
|------|-----|---------------------|
| Emergency contact model | Contacts currently in SharedPreferences only (`ContactManager`); no Firestore sync | Should contacts sync to `users/{uid}/contacts`? |
| SOS message field | `SOSModel` lacks `message`; parallel `sos_alerts` path stores it | Unify SOS schema under one model/collection? |
| Evidence collection | No `evidence` Firestore collection; metadata split across `sos_logs` | Create dedicated `evidence/{sosId}/files/` collection? |
| Contact phone E.164 | `+91` hardcoded for 10-digit (India) | Is app India-only or global? |
