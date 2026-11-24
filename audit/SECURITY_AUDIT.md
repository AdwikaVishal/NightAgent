# NightAgent — Security Audit (Person 6: Firebase Security Focus)

**Scope:** Firebase Auth, Firestore, Storage, FCM, Cloud Functions security rules and client-side security. Builds on the existing `audit/SECURITY_AUDIT.md` (findings S-01 to S-10) which remains valid.

**Severity:** CRITICAL / HIGH / MEDIUM / LOW / INFORMATIONAL.

**Method:** Source inspection only. No security-rules files exist in the repo, so Firestore/Storage are presumed in test/open mode (unverified at runtime — no SDK to confirm).

---

## Summary of existing findings (carried forward)

| ID | Severity | Finding | Source |
|----|----------|---------|--------|
| S-01 | CRITICAL | Firebase API key checked into git (`app/google-services.json`) | `app/google-services.json:18` |
| S-02 | HIGH | `android:allowBackup="true"` with no restrictive backup rules | `AndroidManifest.xml:29` |
| S-03 | HIGH | PII/location/UIDs logged to Logcat | `sos/SOSManager.kt`, `sos/LocationProvider.kt`, `streaming/CallViewModel.kt` |
| S-04 | MEDIUM | No network security config; cleartext risk | `AndroidManifest.xml` (no `networkSecurityConfig`) |
| S-05 | MEDIUM | Exported `MainActivity` with unvalidated deep-link `navigate_to`; duplicate `PowerButtonReceiver` | `AndroidManifest.xml:72-76`, `MainActivity.kt` |
| S-06 | LOW | PendingIntent immutability (correctly mitigated) | `MyFirebaseMessagingService.kt:58-60` |
| S-07 | MEDIUM | Location in SMS + Firestore, no retention, no owner-only rules | `FirestoreManager.kt`, `SOSManager.kt` |
| S-08 | LOW | PIN plaintext comparison (acceptable given encrypted-at-rest) | `stealth/StealthRepository.kt` |
| S-09 | LOW | Dead `EvidenceUploader` with `Uri.fromFile` risk if invoked | `sos/EvidenceUploader.kt` |
| S-10 | INFORMATIONAL | Dependency versions need scanning (Firebase BOM 32.7.0, etc.) | `app/build.gradle.kts` |

---

## NEW: Firebase-specific security gaps (Person 6 scope)

### F-01: No Firestore security rules — CRITICAL

**Evidence:** No `firestore.rules` file anywhere in the repo. `FirebaseConfig.firestore` is obtained via `FirebaseFirestore.getInstance()` with no rules enforcement visible. All collections (`users`, `chats`, `chats/{chatId}/messages`, `sos_alerts`, `safe_walks`, `fcm_requests`) are readable/writable by anyone if the project is in test mode.

**Risk:** Any user (or unauthenticated client with the API key) can read all user profiles, phone numbers, FCM tokens, SOS locations, and voice messages. SOS location data (written every 5 s) is exposed.

**Required rules (proposed):**
- `users/{uid}`: read/write only by `auth.uid == uid`.
- `chats/{chatId}`: read only if `auth.uid in resource.data.participants`; write only by participants.
- `chats/{chatId}/messages/{msgId}`: create only by `auth.uid == request.resource.data.senderId`; read by participants; update `messageStatus` only by `receiverId`.
- `sos_alerts/{uid}/...`: read/write only by `auth.uid == uid`.
- `safe_walks/{userId}/...`: read/write only by `auth.uid == userId`.
- `fcm_requests/{reqId}`: create only by authenticated users; read/delete only by service account (Cloud Function).

**Acceptance criteria:** `firestore.rules` file exists; unit-tested against Firebase Emulator; no `allow read, write: if true` rules remain.

---

### F-02: No Storage security rules — CRITICAL

**Evidence:** No `storage.rules` file. Uploads to `voice_messages/{senderId}/{messageId}.m4a` and `evidence/{userId}/...` are not rules-protected.

**Risk:** Anyone can upload arbitrary files to Firebase Storage (quota abuse, malicious content) or download voice messages/evidence by guessing paths.

**Required rules (proposed):**
- `voice_messages/{senderId}/{messageId}.m4a`: write only if `request.auth.uid == senderId`; read only if sender or receiver (needs chat doc check — Storage rules can't query Firestore; consider signed URLs or a read proxy).
- `evidence/{userId}/...`: write only if `request.auth.uid == userId`; max file size limit (e.g. 50 MB); content-type validation (audio/video only).

**Acceptance criteria:** `storage.rules` exists; emulator-tested; file size/type limits enforced.

---

### F-03: FCM token exposed to any chat participant — HIGH

**Evidence:** `users/{uid}/fcmToken` is written by `AuthManager.saveFCMToken()` and `VoiceMessageFirebase.updateFcmToken()`. `VoiceMessageFirebase.queuePushNotification()` reads the receiver's token from `users/{receiverUid}` and writes it to `fcm_requests/{auto-id}` — a collection any authenticated user can read (no rules).

**Risk:** A malicious user can harvest FCM tokens of other users by reading `fcm_requests` or `users` docs, enabling targeted push spam or token-based attacks.

**Mitigation:** Restrict `users/{uid}/fcmToken` read to the owner only; `fcm_requests` write-only for clients, read/delete only by the Cloud Function service account.

---

### F-04: No data retention / TTL for SOS locations — MEDIUM

**Evidence:** `FirestoreManager.updateLocation()` writes lat/lng/time every 5 s to `sos_alerts/{uid}/sessions/{sessionId}/location_updates`. `SafeWalkRepository` writes every 5 s to `safe_walks/{userId}/locations`. No TTL, no cleanup, no deletion option.

**Risk:** Indefinite storage of precise location history; privacy exposure if account is compromised; storage cost growth.

**Required:** Cloud Function scheduled cleanup (e.g. delete docs older than 7–30 days) or client-side TTL. **Confirm retention window with product.**

---

### F-05: Phone-number enumeration via `whereEqualTo` — MEDIUM

**Evidence:** `VoiceMessageFirebase.findUidByPhone()` queries `users` where `phoneNumber == ...`. Without rules restricting this, an attacker can enumerate registered phone numbers.

**Mitigation:** Owner-only read on `users`; phone lookup should be rate-limited or require auth. Consider whether phone→UID lookup needs to be public at all.

---

### F-06: Deep-link route injection — MEDIUM (carried from S-05)

**Evidence:** `MyFirebaseMessagingService` builds an Intent with `putExtra("navigate_to", "voicechat/$senderId")`; `MainActivity` is `android:exported="true"` and accepts the extra; `NavGraph` navigates to `voicechat/{otherUserId}` without validating the route string.

**Risk:** Any app can send a broadcast/intent with a crafted `navigate_to` to open internal routes. While `voicechat/{id}` is relatively benign, the pattern is unsafe.

**Mitigation:** Validate `navigate_to` against an allowlist of route prefixes in `MainActivity` before navigating.

---

### F-07: `google-services.json` API key in version control — CRITICAL (carried from S-01)

**Evidence:** `app/google-services.json:18` contains `current_key: "AIzaSyBMo2a3e5Pb3xKU2c-4WZwLym6LazFLiuQ"` committed to git.

**Risk:** Quota abuse, project fingerprinting, targeted Firebase Auth/FCM attacks.

**Mitigation:** Add `google-services.json` to `.gitignore`; inject at build time via CI secrets or Gradle; rotate the exposed key in Firebase Console.

---

### F-08: No certificate pinning / network security config — MEDIUM (carried from S-04)

**Evidence:** No `android:networkSecurityConfig`. Retrofit `NetworkModule` points to placeholder `https://your-backend-domain.com/` (unused). Overpass API called over raw HTTPS with no pinning.

**Risk:** If placeholder URL is activated without TLS enforcement, cleartext traffic.

**Mitigation:** Add network security config with `cleartextTrafficPermitted="false"`; consider pinning for any custom backend.

---

## Security rules template (proposed — to be created)

### `firestore.rules` (new file)

```rules
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Users: owner-only
    match /users/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    // Chats: participants only
    match /chats/{chatId} {
      allow read: if request.auth != null && request.auth.uid in resource.data.participants;
      allow create: if request.auth != null && request.auth.uid in request.resource.data.participants;
      allow update: if request.auth != null && request.auth.uid in resource.data.participants;
    }
    // Messages: sender creates, participants read, receiver marks seen
    match /chats/{chatId}/messages/{messageId} {
      allow read: if request.auth != null && request.auth.uid in get(/databases/$(database)/documents/chats/$(chatId)).data.participants;
      allow create: if request.auth != null && request.auth.uid == request.resource.data.senderId;
      allow update: if request.auth != null && request.auth.uid == resource.data.receiverId
                    && request.resource.data.messageStatus == "SEEN";
    }
    // SOS alerts: owner only
    match /sos_alerts/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    // Safe walks: owner only
    match /safe_walks/{userId}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
    // FCM requests: clients write only; function reads/deletes
    match /fcm_requests/{reqId} {
      allow create: if request.auth != null;
      allow read, delete: if false; // service account bypasses via Admin SDK
    }
  }
}
```

### `storage.rules` (new file)

```rules
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /voice_messages/{senderId}/{messageId}.m4a {
      allow write: if request.auth != null && request.auth.uid == senderId;
      allow read: if request.auth != null; // tighten after confirming receiver access pattern
    }
    match /evidence/{userId}/{allPaths=**} {
      allow write: if request.auth != null && request.auth.uid == userId
                   && request.resource.size <= 50 * 1024 * 1024; // 50 MB cap
    }
  }
}
```

---

## Client-side security checklist

| Item | Status | File | Action |
|------|--------|------|--------|
| `google-services.json` not in git | ❌ Missing | `app/google-services.json`, `.gitignore` | Add to gitignore; inject via CI |
| `allowBackup="false"` | ❌ Missing | `AndroidManifest.xml:29` | Set false for release |
| No PII in Logcat | ❌ Missing | `sos/SOSManager.kt`, `sos/LocationProvider.kt` | Guard logs behind `Build.DEBUG` |
| Network security config | ❌ Missing | `AndroidManifest.xml` | Add `networkSecurityConfig` |
| Deep-link validation | ❌ Missing | `MainActivity.kt` | Allowlist `navigate_to` routes |
| PendingIntent immutable | ✅ OK | `MyFirebaseMessagingService.kt:58-60` | No change |
| EncryptedSharedPreferences (PIN) | ✅ OK | `stealth/StealthRepository.kt` | No change |
| Storage size/content limits | ❌ Missing | — | Add via `storage.rules` |
| Firestore owner-only rules | ❌ Missing | — | Create `firestore.rules` |
| FCM token access control | ❌ Missing | — | Restrict `users/{uid}/fcmToken` read |

---

## Prioritized security remediation (Person 6)

| ID | Task | Severity | File paths | Acceptance criteria |
|----|------|----------|------------|---------------------|
| SEC-1 | Rotate Firebase API key; remove `google-services.json` from git; inject at build time | CRITICAL | `.gitignore`, `app/google-services.json`, CI | Key not tracked; new key in Console |
| SEC-2 | Create `firestore.rules` (owner-only for users/chats/messages/sos/safe_walks) | CRITICAL | `firestore.rules` (new) | Emulator tests pass; no open rules |
| SEC-3 | Create `storage.rules` (authenticated upload, path-scoped, size limits) | CRITICAL | `storage.rules` (new) | Emulator tests pass; size cap enforced |
| SEC-4 | Restrict `fcm_requests` read/delete to service account; clients write-only | HIGH | `firestore.rules`, `functions/src/index.ts` | Only function can read/delete |
| SEC-5 | Set `allowBackup="false"` + backup rules exclusions | HIGH | `AndroidManifest.xml`, `res/xml/backup_rules.xml` | Release manifest has `allowBackup="false"` |
| SEC-6 | Remove PII/location logging in release | HIGH | `sos/SOSManager.kt`, `sos/LocationProvider.kt` | No phone/location/UID in release Logcat |
| SEC-7 | Add network security config (no cleartext) | MEDIUM | `res/xml/network_security_config.xml`, `AndroidManifest.xml` | `cleartextTrafficPermitted="false"` |
| SEC-8 | Validate deep-link `navigate_to` against allowlist | MEDIUM | `MainActivity.kt` | Unknown routes rejected |
| SEC-9 | Add data retention/TTL for SOS + safe-walk locations | MEDIUM | `functions/src/` (scheduled function) or client cleanup | Old docs auto-delete after retention |
| SEC-10 | Dependency vulnerability scan | INFORMATIONAL | `app/build.gradle.kts` | OWASP Dependency-Check run |

---

## Missing confirmations (team)

| Item | Why | Confirmation needed |
|------|-----|---------------------|
| Firestore project mode | Are rules currently disabled (test mode)? | Confirm Firestore/Storage are in locked mode before deploying rules |
| Retention window | SOS/safe-walk location TTL | How many days to keep location history? |
| GCM/FCM token access | Who reads `users/{uid}/fcmToken` besides owner? | Can guardians look up tokens, or only the Cloud Function? |
| API key rotation | `google-services.json` has live key | Who owns Firebase project `nightagent-35d38` to rotate? |
| Cloud Function service account | `fcm_requests` read/delete by Admin SDK | Which service account deploys/runs the function? |
