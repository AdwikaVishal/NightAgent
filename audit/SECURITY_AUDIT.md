# NightAgent — Security & Privacy Audit

Severity classes: CRITICAL / HIGH / MEDIUM / LOW / INFORMATIONAL.
No actual secret values are reproduced; the `google-services.json` API key is
referenced only by presence, not value.

---

## S-01: Firebase API key checked into version control — CRITICAL

**Evidence:** `app/google-services.json` contains an `api_key.current_key`
value (a live Firebase/Android API key) and is committed to the repo. The file
is part of the Android build and is read at runtime by Firebase SDKs.

**Risk:** Anyone with repo access can extract the key. While Firebase API keys
are not, by themselves, secrets for server access, they can be used for
quota abuse, fingerprinting the Firebase project, and targeted attacks
against Firebase Auth/FCM endpoints.

**Remediation:** Add `google-services.json` to `.gitignore`; inject it at
build time via CI secrets or a gradle plugin; rotate the exposed key in the
Firebase Console.

---

## S-02: `allowBackup="true"` — HIGH

**Evidence:** `app/src/main/AndroidManifest.xml:29`
`android:allowBackup="true"` with no restrictive `backup_rules.xml` includes
(the file is empty/commented out).

**Risk:** ADB backup can extract app private data (SharedPreferences,
including potentially sensitive PIN-related stealth state and location
history) from a rooted/debug-enabled device.

**Remediation:** Set `android:allowBackup="false"` for release builds; define
explicit `android:fullBackupContent` exclusions for sensitive preferences.

---

## S-03: Sensitive data logged to Logcat — HIGH

**Evidence:**
- `sos/SOSManager.kt` logs emergency SMS message body and per-contact phone:
  `Log.d("TRACKING", "Attempting SMS to phone=$phone messageLen=...")` and
  `"SMS FAILED to $phone ... msg=${e.message}"` (lines 159, 202).
- `sos/SOSManager.kt` logs `"LOCATION CALLBACK HIT"` and coordinates
  indirectly via `LocationProvider.getLocation` logs (`Log.d("LocationProvider",
  "Location: ${lat}, ${lng}")`).
- `streaming/CallViewModel.kt` and others log UIDs/callIds.

**Risk:** On a rooted device or via `adb logcat`, an attacker can read phone
numbers, message contents, approximate locations, and user UIDs. Logcat is
world-readable on many devices.

**Remediation:** Remove or guard PII/location logging behind `Build.DEBUG`;
use a proper logging framework that redacts sensitive fields in release.

---

## S-04: No network security config / cleartext risk — MEDIUM

**Evidence:** No `android:networkSecurityConfig` referenced in the manifest.
`network/NetworkModule` uses `https://your-backend-domain.com()` (placeholder,
unused). `SafetyRepository` calls `https://overpass-api.de/...` over HTTPS.
Firebase SDKs use TLS by default.

**Risk:** If the placeholder base URL is ever pointed at a real HTTP backend,
all traffic would be cleartext. No certificate pinning for the Overpass API
or any custom backend.

**Remediation:** Add a Network Security Config enforcing `cleartextTrafficPermitted="false"`;
consider certificate pinning for any custom backend.

---

## S-05: Exported activities / receivers — MEDIUM

**Evidence:**
- `CalculatorActivity` is `android:exported="true"` with `MAIN/LAUNCHER`
  intent filter (`AndroidManifest.xml:59-69`) — intentional disguise.
- `MainActivity` is `android:exported="true"` to accept FCM deep links
  (`AndroidManifest.xml:72-76`).
- `PowerButtonReceiver` is `exported="false"` but registered for
  `SCREEN_ON`/`SCREEN_OFF` both in manifest and dynamically in `MainActivity`
  (duplicate registration).

**Risk:** An exported `MainActivity` with a deep-link extra `navigate_to`
allows any app to trigger navigation into internal routes (e.g.
`voicechat/{senderId}`) without validation. Duplicate broadcast registration
can cause multiple `PowerButtonReceiver` instances.

**Remediation:** Validate deep-link route strings against an allowlist before
navigating. Remove one of the two `PowerButtonReceiver` registrations.

---

## S-06: PendingIntent mutability — LOW (already mitigated)

**Evidence:** `MyFirebaseMessagingService.kt:58-60` uses
`PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT`.

**Status:** Correct for Android 12+. No action needed.

---

## S-07: Location data in SMS and Firestore — MEDIUM (privacy)

**Evidence:** `SOSManager.sendEmergencySMS` embeds a Google Maps link with the
user's latitude/longitude in the SMS body (`SOSManager.kt:47-58`). `FirestoreManager.updateLocation`
writes lat/lng/time to Firestore on every 5 s poll.

**Risk:** Location is shared with emergency contacts (intended) but also
persisted long-term in Firestore with no retention policy, no access-control
rules visible in the client, and no option to delete history.

**Remediation:** Add Firestore security rules restricting read/write to the
owner UID; add a data-retention/cleanup policy; let users disable location
sharing (the `locationSharing` toggle exists but is not wired).

---

## S-08: Stealth PIN stored encrypted but verified by plaintext comparison — LOW

**Evidence:** `stealth/StealthRepository.kt` uses `EncryptedSharedPreferences`
(AES256-GCM master key via Android Keystore) — good. But `verifyPin` compares
`saved == inputPin` directly (`StealthRepository.kt:39`).

**Risk:** Plaintext PIN comparison in memory is standard for local PIN
verification; acceptable given encrypted at rest. Rate limiting (5 attempts /
60 s lockout) is present (`isLockedOut`, `lockoutRemainingSeconds`).

**Status:** Acceptable. No change required.

---

## S-09: External file URIs in EvidenceUploader — LOW (dead code)

**Evidence:** `sos/EvidenceUploader.kt:24` builds `Uri.fromFile(file)` and
references `Environment.DIRECTORY_MUSIC/evidence_recording.3gp`. This code is
dead (never invoked) and references a path that doesn't match where evidence
is actually saved.

**Risk:** If ever invoked on Android 7–9, `Uri.fromFile` could expose a
file:// URI; on Android 10+ it may throw FileUriExposedException under strict
mode. Because it's dead, risk is theoretical.

**Remediation:** Remove dead code (see roadmap).

---

## S-10: Dependencies — INFORMATIONAL

No dependency-vulnerability scan was possible (no network/ODC tooling and no
Android SDK to run `./gradlew dependencies:analyze`). Notable versions in
`app/build.gradle.kts`:
- Firebase BOM 32.7.0 (2023-era)
- WebRTC android 125.6422.06.1
- CameraX 1.3.4, Retrofit 2.11.0, OkHttp logging 4.12.0, WorkManager 2.9.0,
  Coroutines 1.8.1, Compose BOM (version managed by BOM)

**Recommendation:** Run `./gradlew :app:dependencies` and an OWASP
Dependency-Check scan once an SDK is available.

---

## Severity summary

| ID | Severity | Finding |
|---|---|---|
| S-01 | CRITICAL | Firebase API key in google-services.json (committed) |
| S-02 | HIGH | allowBackup="true" |
| S-03 | HIGH | PII/location logged to Logcat |
| S-04 | MEDIUM | No network security config |
| S-05 | MEDIUM | Exported MainActivity + deep link, duplicate receiver |
| S-07 | MEDIUM | Location in SMS/Firestore, no retention |
| S-06 | LOW | PendingIntent immutability (ok) |
| S-08 | LOW | PIN plaintext compare (acceptable) |
| S-09 | LOW | Dead EvidenceUploader (file URI risk if used) |
| S-10 | INFORMATIONAL | Dependency versions need scanning |
