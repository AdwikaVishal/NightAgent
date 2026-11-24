# NightAgent — API Contracts

**Purpose:** Define every interface/contract in the backend layer, distinguishing EXISTING (code present) from PROPOSED (needed for Person 6 roadmap). No source files modified.

**Legend:** `[EXISTING]` = code present. `[PROPOSED]` = new contract to add. `[INFORMAL]` = no formal interface; ad-hoc usage.

---

## 1. Firebase service contracts (existing)

These are the current Firebase SDK access points. `FirebaseConfig.kt` is the single access point — all services should be obtained through it.

### `FirebaseConfig` — `app/src/main/java/com/example/nightagent/firebase/FirebaseConfig.kt:11`

| Member | Type | Lazy? | Return | Use |
|--------|------|-------|--------|-----|
| `firestore` | `FirebaseFirestore` | yes (`by lazy`) | Firestore instance with 100 MB persistent cache | All Firestore reads/writes |
| `storage` | `FirebaseStorage` | yes (`by lazy`) | Storage instance | All uploads/downloads |
| `auth` | `FirebaseAuth` | yes (`by lazy`) | Auth instance | Sign-in/sign-out/token |
| `messaging` | `FirebaseMessaging` | yes (`by lazy`) | FCM instance | Token management |

**Contract:** Single-object singleton. Consumers call `FirebaseConfig.firestore`, `.storage`, `.auth`, `.messaging`. **No DI; no interface.** Every class that needs Firebase reaches into `FirebaseConfig` directly (or calls `FirebaseFirestore.getInstance()` directly in `FirestoreManager`/`AuthManager`).

**Proposed:** Wrap behind an interface (`FirebaseGateway`) so the data layer can be tested/mocked.

---

### `AuthManager` — `.../firebase/AuthManager.kt:7`

Object (singleton). Contract:

| Method | Signature | Returns | Notes |
|--------|-----------|---------|-------|
| `getCurrentUserId()` | `fun` | `String?` | Current auth UID |
| `loginAnonymous()` | `suspend fun` | `Result<String>` | Anonymous sign-in |
| `loginWithEmail(email, password)` | `suspend fun` | `Result<String>` | Email/password sign-in |
| `createUser(email, password)` | `suspend fun` | `Result<String>` | Email/password sign-up |
| `logout()` | `fun` | `Unit` | Sign out |
| `addAuthStateListener(listener)` | `fun` | `Unit` | Callback `(String?) -> Unit` |
| `saveFCMToken(token)` | `fun` | `Unit` | Writes `fcmToken` to `users/{uid}` |

**Gaps (no contract exists for):** profile auto-creation on sign-up, Google sign-in, phone auth, password reset, email verification, custom claims, token refresh listener (FCM token saved in `MyFirebaseMessagingService.onNewToken` instead).

---

### `FirestoreManager` — `.../firebase/FirestoreManager.kt:7`

Object (singleton). Contract:

| Method | Signature | Writes to |
|--------|-----------|-----------|
| `startSOSSession(message, latitude, longitude)` | `fun` | `sos_alerts/{uid}/sessions/{sessionId}` |
| `updateLocation(latitude, longitude)` | `fun` | `.../sessions/{sessionId}/location_updates/{auto-id}` |

**Gaps:** Uses callbacks (`addOnSuccessListener`/`addOnFailureListener`), not coroutines; no retry; no return value; no suspend functions. `currentSessionId` is in-memory mutable state (not thread-safe across callers).

---

## 2. Repository contracts

No formal repository interfaces exist. All repositories are concrete classes. The contracts below are **informal** (implied by method signatures) unless marked `[PROPOSED]`.

### `SOSRepository` — `.../repository/SOSRepository.kt:10`

| Method | Signature | Returns | Firestore target |
|--------|-----------|---------|------------------|
| `sendSOS(data)` | `suspend fun` | `Result<String>` | `sos_logs/{auto-id}` (+ returns doc ID) |
| `getCurrentUserId()` | `suspend fun` | `String` | — (delegates to AuthManager) |

**Informal contract:** `sendSOS` writes an `SOSModel` and returns the generated document ID. Caller must catch `Result.failure`.

### `SafeWalkRepository` — `.../repository/SafeWalkRepository.kt:10`

| Method | Signature | Returns | Firestore target |
|--------|-----------|---------|------------------|
| `startSafeWalk(userId, startTime)` | `suspend fun` | `Result<Unit>` | `safe_walks/{userId}` |
| `updateLocation(userId, location)` | `suspend fun` | `Result<Unit>` | `safe_walks/{userId}/locations/{auto-id}` |
| `endSafeWalk(userId)` | `suspend fun` | `Result<Unit>` | `safe_walks/{userId}` (update `isActive=false`) |

### `EvidenceRepository` — `.../repository/EvidenceRepository.kt:12`

| Method | Signature | Returns | Target |
|--------|-----------|---------|--------|
| `uploadFile(fileUri, userId, sosId)` | `suspend fun` | `Result<String>` | Storage `evidence/{userId}/{ts}.mp4` + `sos_logs/{sosId}/evidenceUrl` |

**Status:** UNUSED — never invoked by active code. Dead contract.

### `VoiceMessageRepository` — `.../voicemessage/repository/VoiceMessageRepository.kt:28`

Concrete class, constructor-injected with `Context`. Contract:

| Method | Signature | Returns |
|--------|-----------|---------|
| `registerCurrentUser(phoneNumber, displayName)` | `suspend fun` | `Unit` |
| `findReceiverUid(phoneNumber)` | `suspend fun` | `String?` |
| `observeMessages(chatId)` | `fun` | `Flow<List<VoiceMessage>>` |
| `sendVoiceMessage(file, durationMs, senderId, receiverId, onProgress)` | `suspend fun` | `Result<VoiceMessage>` |
| `markMessageSeen(chatId, messageId)` | `suspend fun` | `Unit` |

**Informal contract:** Full send pipeline = ensure chat room → upload audio → save message → queue FCM push → delete local file. Push failure is non-fatal.

### `VoiceMessageFirebase` — `.../voicemessage/firebase/VoiceMessageFirebase.kt:20`

Object. The actual Firebase gateway for voice messaging. Contract:

| Method | Signature | Returns | Target |
|--------|-----------|---------|--------|
| `chatId(uid1, uid2)` | `fun` | `String` | deterministic sorted UID key |
| `registerUser(profile)` | `suspend fun` | `Unit` | `users/{uid}` (set+merge) |
| `findUidByPhone(phoneNumber)` | `suspend fun` | `String?` | `users` where `phoneNumber==...` |
| `getUserProfile(uid)` | `suspend fun` | `UserProfile?` | `users/{uid}` get |
| `ensureChatRoom(uid1, uid2)` | `suspend fun` | `Unit` | `chats/{chatId}` (set+merge) |
| `uploadAudio(file, senderId, messageId, onProgress)` | `suspend fun` | `Result<String>` | Storage `voice_messages/{senderId}/{messageId}.m4a`; returns download URL |
| `saveMessage(message)` | `suspend fun` | `Result<String>` | `chats/{chatId}/messages/{messageId}` |
| `messagesFlow(chatId)` | `fun` | `Flow<List<VoiceMessage>>` | real-time snapshot listener |
| `markMessageSeen(chatId, messageId)` | `suspend fun` | `Unit` | update `messageStatus=SEEN` |
| `updateFcmToken(uid, token)` | `suspend fun` | `Unit` | `users/{uid}/fcmToken` |
| `queuePushNotification(receiverUid, senderUid, senderName, chatId)` | `suspend fun` | `Unit` | `fcm_requests/{auto-id}` |

---

## 3. Proposed repository interfaces (contracts to add)

Person 6 should introduce formal interfaces so the data layer is testable and implementations can be swapped. Suggested file: `app/src/main/java/com/example/nightagent/data/repository/`.

### `AuthRepository` [PROPOSED]

```kotlin
interface AuthRepository {
    suspend fun signInAnonymously(): Result<String>
    suspend fun signInWithEmail(email: String, password: String): Result<String>
    suspend fun createAccount(email: String, password: String): Result<String>
    suspend fun signOut()
    val currentUserId: String?
    fun observeAuthState(): Flow<String?>
}
```

### `UserRepository` [PROPOSED]

```kotlin
interface UserRepository {
    suspend fun registerProfile(profile: UserProfile)
    suspend fun findUidByPhone(phoneNumber: String): String?
    suspend fun getProfile(uid: String): UserProfile?
    suspend fun updateFcmToken(uid: String, token: String)
}
```

### `ChatRepository` [PROPOSED]

```kotlin
interface ChatRepository {
    suspend fun ensureChatRoom(uid1: String, uid2: String): String
    suspend fun sendMessage(message: VoiceMessage): Result<String>
    fun observeMessages(chatId: String): Flow<List<VoiceMessage>>
    suspend fun markSeen(chatId: String, messageId: String)
}
```

### `SosRepository` [PROPOSED] (unifies parallel SOS paths)

```kotlin
interface SosRepository {
    suspend fun startSession(message: String, lat: Double?, lng: Double?): Result<String>
    suspend fun updateLocation(sessionId: String, lat: Double, lng: Double): Result<Unit>
    fun observeSession(sessionId: String): Flow<...>
}
```

### `StorageRepository` [PROPOSED]

```kotlin
interface StorageRepository {
    suspend fun uploadAudio(file: File, senderId: String, messageId: String): Result<String>
    suspend fun uploadEvidence(fileUri: Uri, userId: String, sosId: String): Result<String>
}
```

### `FcmRepository` [PROPOSED]

```kotlin
interface FcmRepository {
    fun observeToken(): Flow<String>
    suspend fun queuePush(receiverUid: String, senderUid: String, senderName: String, chatId: String)
}
```

---

## 4. FCM data-message contract

Sender payload (written to `fcm_requests`, then sent by Cloud Function — function NOT in repo):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | String | yes | `"voice_message"` |
| `chatId` | String | yes | chatId |
| `senderId` | String | yes | sender UID |
| `senderName` | String | yes | sender display name or UID prefix |

FCM message structure (per `MyFirebaseMessagingService`):

```
data: {
  type: "voice_message"
  senderId: "<uid>"
  senderName: "<name or phone>"
  conversationId: "<chatId>"   // note: key is "conversationId" in comment, "chatId" in code
}
```

**Inconsistency (flag):** `MyFirebaseMessagingService.kt:50` reads `data["conversationId"]` but `VoiceMessageFirebase.queuePushNotification()` writes `"chatId"` to `fcm_requests`, and the documented Cloud Function sends `data: { chatId: ... }`. The receiver reads `conversationId` but the sender writes `chatId` — **mismatched key will break deep-link navigation.** Unify on one key.

### Deep-link contract (FCM → app)

- Intent action: `MainActivity` opened with extra `navigate_to = "voicechat/$senderId"`
- NavGraph route: `voicechat/{otherUserId}`
- `MainActivity` is `android:exported="true"` to accept FCM deep links (security concern — see SECURITY_AUDIT S-05).

---

## 5. Storage upload contract

### Voice audio (existing)

- Path: `voice_messages/{senderId}/{messageId}.m4a`
- Method: `putStream(file.inputStream())` (no `Uri.fromFile` — avoids FileUriExposedException)
- Progress: `addOnProgressListener` → `onProgress(Int)` 0–100
- Result: download URL string stored in `audioUrl` field

### Evidence (existing code, unused)

- Path: `evidence/{userId}/{timestamp}.mp4`
- Method: `putFile(fileUri)` (uses `Uri.fromFile` — risky on Android 10+)
- Status: dead code path.

---

## 6. Contracts between feature teams (integration seams)

These are the integration points Person 6 must own. None are formalized today.

| Seam | Producer | Consumer | Contract (informal) | Formalize? |
|------|----------|----------|---------------------|------------|
| Auth → User profile | AuthManager | VoiceMessageRepository.registerCurrentUser | After sign-in, caller must register phone+displayName to `users/{uid}` | YES — UserRepository |
| FCM token → Firestore | MyFirebaseMessagingService.onNewToken | AuthManager.saveFCMToken / VoiceMessageFirebase.updateFcmToken | Token written to `users/{uid}/fcmToken` on refresh | YES — FcmRepository |
| Voice send → push | VoiceMessageRepository.sendVoiceMessage | Cloud Function (missing) | Writes `fcm_requests` doc; function sends push + deletes doc | YES — functions/src/index.ts |
| SOS session → tracking | FirestoreManager.startSOSSession | FirestoreManager.updateLocation | `currentSessionId` in-memory links session to location writes | YES — SosRepository |
| Safe Walk → SOS | SafeWalkManager | SOSManager.triggerSOS | Auto-SOS on movement/deviation/timeout violation | Existing (no contract change) |
| Voice deep link | FCM notification | NavGraph route `voicechat/{senderId}` | `navigate_to` extra parsed in MainActivity | YES — fix `conversationId` vs `chatId` key |

---

## 7. Missing confirmations (team)

| Item | Why | Confirmation needed |
|------|-----|---------------------|
| Repository interfaces | No DI; repositories are concrete classes | Adopt Hilt/Koin or hand-rolled DI? |
| `conversationId` vs `chatId` key | Receiver reads `conversationId`; sender writes `chatId` | Which key is canonical? |
| Cloud Function runtime | FCM push requires deployed function | Node.js version, region, Blaze plan? |
| Contact sync | Contacts local-only (SharedPreferences) | Sync to Firestore `users/{uid}/contacts`? |
| Deep-link allowlist | MainActivity exported; any app can pass `navigate_to` | Allowlist of valid routes before navigation? |
