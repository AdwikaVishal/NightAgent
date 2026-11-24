# Person 4 — Evidence Module Integration Guide (Phase 6 readiness)

This document is written against the **actual APIs in this repository**. The
standalone `person4-evidence/app` is a development harness only. The reusable
component is the package `com.nightagent.evidence.*` (Compose-free business
logic) plus `EvidenceModuleFactory` for host assembly. No harness Activity,
screen, or `devtest/` class is part of the host contract.

---

## 1. Module architecture

| Layer | Package / classes | Responsibility |
| --- | --- | --- |
| Host facade | `integration.EvidenceModule`, `integration.EvidenceModuleFactory` | Single public entry point; wires all controllers once |
| Authorization | `integration.EvidenceAuthorization`, `AuthorizationResult`, `CombinedEvidenceAuthorization`, `consent.ConsentGate`, `permission.*` | Independent consent + Android permission checks; always enforced inside the module |
| Consent storage | `consent.PersistentConsentRepository`, `model.ConsentKey`, `model.ConsentRecord` | Durable, versioned per-decision consent in Preferences DataStore |
| Capture boundary | `integration.EvidenceCaptureTrigger`, `CaptureTriggerRequest`, `CaptureTriggerResult`, `TriggerSource`, `MockEvidenceCaptureTrigger` | Host requests capture; module authorizes; host performs foreground capture |
| Publication | `integration.EvidencePublicationController`, `PublicationResult`, `storage.Encrypted*Store`, `storage.EncryptedEvidenceStore` | Turns captured plaintext into NGE1 `.ng1` + SHA-256 + Room metadata; returns stable `evidenceId` |
| Metadata | `metadata.EvidenceMetadataRepository`, `RoomEvidenceMetadataRepository`, `EvidenceMetadataDatabase` | Local evidence index and status; no plaintext paths |
| Integrity | `integrity.EvidenceIntegrityVerifier`, `IntegrityVerificationResult`, `IntegrityStatus` | Explicit statuses: missing file, digest/size/sha mismatch, invalid NGE1 auth, metadata problems |
| Access | `access.EvidenceAccessController`, `EvidenceAccessToken`, `EvidenceAccessResult` | Scoped plaintext access only after `VERIFIED`; explicit cleanup |
| Retention | `retention.EvidenceRetentionController`, `DeletionResult` | Explicit delete of encrypted file + metadata row |
| Upload | `sync.CloudUploadController`, `EvidenceUploader`, `EncryptedUploadRequest`, `UploadResult`, `UploadState`, `NotConfiguredEvidenceUploader` | Encrypted-only upload boundary; consent rechecked at upload time |
| Crypto | `crypto.EvidenceEncryptionEngine`, `AndroidEvidenceKeyManager`, `KeystoreCipherFactory`, `AndroidAtomicFilePublisher` | NGE1 AES-GCM, non-exportable Android Keystore keys, atomic publication |
| Diagnostics | `crypto.EvidenceDiagnosticLogger`, `RecordingEvidenceDiagnosticLogger`, `EvidenceFailureDiagnostic` | Sanitized failure stage/class; no keys, plaintext or private paths |

The NGE1 container format, key version `1`, and existing evidence files are
unchanged. All encryption, publication, metadata and integrity code is reused.

---

## 2. Public API entry points

Create once (e.g. in `Application.onCreate()` or an Activity-scoped singleton):

```kotlin
val evidence = EvidenceModuleFactory.create(
    context = applicationContext,
    captureTrigger = myForegroundCaptureTrigger,   // required for real SOS capture
    uploader = myFirebaseUploader                  // omit => NotConfiguredEvidenceUploader
)
```

`EvidenceModule` public surface (all suspend calls are main-safe; file work runs
on `Dispatchers.IO` inside the controllers):

- Consent: `observeConsent()`, `observeConsent(key)`, `consentRecord(key)`,
  `grantConsent(key)`, `revokeConsent(key)`, `authorizeCapture(type)`,
  `isAuthorized(operation)`.
- Capture/publication: `triggerCapture(request)`, `publish(plaintext, type)`.
- Evidence: `observeEvidence()`, `observeEvidence(type)`, `evidenceDetails(id)`,
  `verify(id)`.
- Access: `open(id)`, `activeAccessTokenCount()`, `releaseEvidenceAccess()`.
- Deletion: `delete(id)`.
- Upload: `upload(id)`, `uploadState: StateFlow<UploadState>`, `resetUploadState()`.

Result types are sealed interfaces/enums in the same packages:
`AuthorizationResult`, `CaptureTriggerResult`, `PublicationResult`,
`IntegrityVerificationResult`, `EvidenceAccessResult`, `DeletionResult`,
`UploadResult`, `UploadState`.

---

## 3. Host initialization

`EvidenceModuleFactory.create(context, ...)` does the following internally:

1. `PersistentConsentRepository.create(appContext)` — durable consent.
2. `EvidenceAuthorization(ConsentGate(...), AndroidPermissionStateProvider(...))`.
3. `RoomEvidenceMetadataRepository(EvidenceMetadataDatabase.getInstance(...).metadataDao())`.
4. `EvidenceEncryptionEngine(AndroidEvidenceKeyManager(...), AndroidAtomicFilePublisher(...), ...)`.
5. `EvidenceIntegrityVerifier`, `EvidenceAccessController`, `EvidenceRetentionController`,
   `CloudUploadController`, `EvidencePublicationController`.
6. Returns one `EvidenceModule`.

The host must **not** construct the internal engine, key manager, stores or
repositories itself; doing so risks a second Keystore/Room instance and bypasses
the facade's authorization.

Optional injection points:

- `captureTrigger: EvidenceCaptureTrigger` — host implementation that launches
  foreground capture UI. Default `MockEvidenceCaptureTrigger` only records the
  request and returns `CaptureTriggerResult.Unsupported`.
- `uploader: EvidenceUploader` — host implementation for the chosen backend.
  Default `NotConfiguredEvidenceUploader` returns `UploadResult.NotConfigured`.
- `logger: EvidenceDiagnosticLogger` — defaults to
  `RecordingEvidenceDiagnosticLogger(AndroidEvidenceDiagnosticLogger())`.

---

## 4. How SOS requests reach Person 4

```kotlin
val request = CaptureTriggerRequest(
    type = EvidenceType.PHOTO,              // PHOTO, AUDIO or VIDEO
    source = TriggerSource.SOS,              // SOS, HOST_APP or SOS_MOCK
    externalRequestId = sosId                // optional host correlation id
)
when (val result = evidence.triggerCapture(request)) {
    CaptureTriggerResult.Accepted -> { /* host foreground capture UI is now active */ }
    is CaptureTriggerResult.Denied -> { /* result.reason: consent/permission problem */ }
    is CaptureTriggerResult.Unsupported -> { /* no background capture; show UI */ }
}
```

`triggerCapture()` calls `authorizeCapture(request.type)` first: consent is
checked via `ConsentGate`, then the Android permission required for that media
type (`CAMERA`; `RECORD_AUDIO`; both for video) via `PermissionStateProvider`.
`externalRequestId` is only a correlation token; it is not used for security
decisions and never written into evidence metadata.

The main app currently starts its SOS flow in
`com.example.nightagent.navigation.NavGraph` (`"home"` route long-press →
`EvidenceCaptureService.start(context, sosId)` → `SOSManager.triggerSOS(...)` →
navigate `"evidence"`). That is the natural place to also call
`evidence.triggerCapture(...)`. `VoiceSOSManager`, `ShakeDetector`,
`StealthDetectionService` and `SafeWalkManager` call `SOSManager.triggerSOS(...)`
without a Person 4 request; they need a host-side bridge that forwards the same
contract before Person 4 will capture anything.

Person 4 never starts a service, camera or microphone itself through this API.

---

## 5. Host retrieval and verification

```kotlin
evidence.observeEvidence().collect { result ->
    when (result) {
        is EvidenceListResult.Success -> render(result.records) // EvidenceMetadata
        is EvidenceListResult.Error -> showError(result.cause)
    }
}

val record = evidence.evidenceDetails(evidenceId)
val verification = evidence.verify(evidenceId) // IntegrityVerificationResult
if (verification.status == IntegrityStatus.VERIFIED) { /* safe to access/upload */ }
```

`EvidenceMetadata` exposes `evidenceId`, `mediaType`, `originalExtension`,
`createdAtMillis`, `containerRelativePath`, `containerSizeBytes`, `sha256`,
`schemaVersion`, `registrationStatus`. The container path is internal app
storage and must not be shown to end users or logged.

---

## 6. Decryption / access sessions

```kotlin
when (val result = evidence.open(evidenceId)) {
    is EvidenceAccessResult.Ready -> {
        val token = result.token
        // token.mediaType, token.originalExtension
        // token.plaintextFile is app-private and only valid until token.close()
        viewer.show(token.plaintextFile)
        // ...on viewer dismiss:
        token.close()
    }
    is EvidenceAccessResult.Denied -> showIntegrityDenied(result.status)
    is EvidenceAccessResult.Failed -> showFailure(result.stage)
}
```

`EvidenceAccessController.open()` re-verifies integrity itself; a non-`VERIFIED`
result is denied before any decryption. Plaintext is written to a unique
app-private `.evidence-access/<uuid>/` directory with owner-only permissions.
`EvidenceAccessToken.close()` is idempotent and deletes the temporary file and
its directory; `toString()` deliberately omits the path. On Activity/Service
teardown call `evidence.releaseEvidenceAccess()` (bulk `releaseAll()`).

---

## 7. Cloud upload

```kotlin
// Consent must be granted and can be revoked at any time:
evidence.grantConsent(ConsentKey.CLOUD_UPLOAD)
evidence.revokeConsent(ConsentKey.CLOUD_UPLOAD)

val result = evidence.upload(evidenceId) // rechecks consent every call
// observe with evidence.uploadState.collect { state -> ... }
```

Only a verified `.ng1` container is passed to `EvidenceUploader` via
`EncryptedUploadRequest(evidenceId, container, containerSizeBytes, sha256, mediaType)`.
No plaintext path, data key or master key is ever passed. With no configured
backend the result is `UploadResult.NotConfigured` — success is never simulated.
Retry by calling `upload()` again; coroutine cancellation yields
`UploadResult.Cancelled` and `UploadState.CANCELLED`.

---

## 8. Android manifest / service requirements

The standalone harness manifest declares only `CAMERA` and `RECORD_AUDIO` and
has no services. Person 4's reusable code adds **no services, receivers or
providers**. The host manifest therefore needs:

- `CAMERA` and `RECORD_AUDIO` (already present in root `app/src/main/AndroidManifest.xml`).
- `FOREGROUND_SERVICE` plus `FOREGROUND_SERVICE_CAMERA` and
  `FOREGROUND_SERVICE_MICROPHONE` (already present in root manifest) whenever
  the host performs foreground camera/microphone capture.
- `POST_NOTIFICATIONS` for the visible foreground-service notification (already present).
- `INTERNET` only if a real uploader is supplied (already present in root manifest).
- A host-owned foreground service / Activity for capture. The root app already
  has `com.example.nightagent.evidence.EvidenceCaptureService` and CameraX UI;
  those remain host-owned.

No Person 4 permission is requested automatically. `AndroidPermissionStateProvider`
only observes grant state; the host must request runtime permissions as it does
today, and `EvidenceModule` denies capture while they are missing or permanently denied.

---

## 9. Gradle / integration requirements

`person4-evidence/` is a separate Gradle build (`rootProject.name =
"person4-evidence"`, only `:app`) and is **not** included in the root
`settings.gradle.kts`, which currently includes only `:app`.

For host integration, either copy `app/src/main/java/com/nightagent/evidence/**`
into the host source set or convert the harness `app` into an Android library
module and add it to root settings. Required dependencies already used by the
reusable code:

- `androidx.datastore:datastore-preferences` (consent)
- `androidx.room:room-runtime`, `androidx.room:room-ktx`, KSP `androidx.room:room-compiler`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`, `kotlinx-coroutines-guava`
- `androidx.core:core-ktx`, `androidx.lifecycle:lifecycle-runtime-ktx`
- CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`,
  `camera-video`) **only if** the host uses the harness capture screens; the
  facade itself is Compose-free.
- Compose/Material3 **only for harness screens**, not for the facade.

No Firebase dependency is added by Person 4.

---

## 10. Firebase configuration decision and requirements

Facts observed in the root app:

- Root `applicationId` / namespace: `com.example.nightagent`.
- Root `app/google-services.json`: project `nightagent-35d38`,
  storage bucket `nightagent-35d38.firebasestorage.app`.
- Root has `firebase-auth-ktx`, `firebase-firestore-ktx`,
  `firebase-storage-ktx`, `firebase-messaging-ktx` and the Google Services plugin.
- Root `AuthManager.loginAnonymous()` supplies a Firebase UID; root
  `EvidenceUploadWorker` currently uploads plaintext files to
  `evidence/{userId}/{sosId}/{relativePath}` and writes `evidence/{sosId}`.
- Standalone Person 4 app: applicationId `com.nightagent.evidence`, no
  `INTERNET`, no Firebase plugin/dependencies, no `google-services.json`.

**Decision:** the `nightagent-35d38` Firebase project can be used by Person 4
**only when Person 4 code is compiled into the root `com.example.nightagent`
app**, because FirebaseApp identity is tied to the host applicationId and the
root `google-services.json` is for that package. The standalone harness must
not register that project or add those credentials. No Firebase dependency or
initialization was added in this phase.

Exact requirements before a real `EvidenceUploader` is implemented:

1. Host supplies an `EvidenceUploader` implementation using the root app's
   already-initialized `FirebaseStorage`; `isConfigured = true` only after
   successful initialization. No second `FirebaseApp.initializeApp(...)`.
2. Consent: `ConsentKey.CLOUD_UPLOAD` must be granted; `CloudUploadController`
   rechecks it on every `upload()` call. Revocation must prevent the next upload.
3. Upload only `EncryptedUploadRequest.container` (a verified `.ng1` file) with
   `application/octet-stream`. Never transmit `token.plaintextFile`, keys, or
   the NGE1 data key. Attach `evidenceId`, `sha256`, `containerSizeBytes`,
   `mediaType` as custom metadata/Firestore fields.
4. Suggested Storage path: `evidence/{uid}/{evidenceId}/{encryptedName}`.
   Firestore: `evidence/{evidenceId}` with `ownerUid` and the same digest.
   Do **not** reuse the root worker's plaintext path for NGE1 evidence.
5. Security rules (must be deployed in Firebase console, not in this repo):
   Storage `allow read, write: if request.auth != null &&
   request.auth.uid == uid` for the owner path; deny all other reads/writes;
   validate file name suffix `.ng1` and size/digest metadata where possible.
   Firestore `allow read, write: if request.auth != null &&
   request.auth.uid == resource.data.ownerUid` (and equivalent on create).
6. The root app's existing plaintext `EvidenceUploadWorker` path must be
   disabled or replaced for evidence produced by Person 4, otherwise plaintext
   from the legacy pipeline can still be uploaded.
7. Add `INTERNET` only in the host (already present in root manifest). No
   network permission was added to the harness.

Until those are satisfied, `NotConfiguredEvidenceUploader` remains the default
and no upload success is claimed.

---

## 11. Known lifecycle and background restrictions

- `TriggerSource.SOS` is a **request** only. Android 10+ background camera and
  microphone access is restricted, and Android 14+ requires valid foreground
  service types. Person 4 does not and cannot guarantee silent background
  capture; the host must surface foreground capture UI and the visible
  notification. `MockEvidenceCaptureTrigger` returns `Unsupported` by design.
- Runtime permissions must be granted before `triggerCapture()`/`publish()`;
  permanently denied permissions are reported as
  `AuthorizationResult.Reason.PERMISSION_PERMANENTLY_DENIED`.
- Temporary plaintext exists only for an open `EvidenceAccessToken`. Close it
  on viewer/player dismissal and call `releaseEvidenceAccess()` on Activity or
  Service destruction. Never persist or share `token.plaintextFile`.
- Consent is versioned (`ConsentTerms.CURRENT_VERSION`); an outdated grant is
  reported as `CONSENT_OUTDATED` and must be re-accepted by the host UI.
- Room deletion is explicit and only via `EvidenceRetentionController.delete(id)`;
  there is no auto-expiry policy.
- The harness `devtest/` controllers are test-only and must not be shipped in
  the host integration.

---

## 12. Example Kotlin host integration

```kotlin
class NightAgentEvidenceHost(private val context: Context) {
    private val evidence = EvidenceModuleFactory.create(
        context = context.applicationContext,
        captureTrigger = EvidenceCaptureTrigger { request ->
            // Host launches its OWN foreground capture UI here.
            // Return Accepted only when that UI has actually started.
            CaptureTriggerResult.Accepted
        },
        uploader = NotConfiguredEvidenceUploader() // replace when Firebase rules are ready
    )

    suspend fun onSosPhotoRequested(sosId: String) {
        evidence.grantConsent(ConsentKey.capture(EvidenceType.PHOTO)) // normally host UI does this
        val trigger = evidence.triggerCapture(
            CaptureTriggerRequest(EvidenceType.PHOTO, TriggerSource.SOS, sosId)
        )
        if (trigger !is CaptureTriggerResult.Accepted) return
        // host foreground capture produces photoFile, then:
        when (val published = evidence.publish(photoFile, EvidenceType.PHOTO)) {
            is PublicationResult.Published -> {
                val evidenceId = published.evidenceId
                val verification = evidence.verify(evidenceId)
                check(verification.status == IntegrityStatus.VERIFIED)
                // report evidenceId back to the SOS workflow
            }
            is PublicationResult.Denied -> { /* consent/permission was revoked */ }
            is PublicationResult.PendingMetadata -> { /* keep plaintext for retry */ }
            is PublicationResult.Failed -> { /* keep plaintext; no deletion */ }
            is PublicationResult.MetadataLookupFailed -> { /* inconsistent state */ }
        }
    }

    fun onDestroy() {
        evidence.releaseEvidenceAccess()
    }
}
```

The snippet is illustrative of the real API surface; the host owns the actual
capture UI, runtime permission requests, Firebase wiring and SOS session model.
