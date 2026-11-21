# NightAgent — Architecture Analysis

---

## Current architecture

**Single-module, monolithic app (`:app`).** All layers live in one Gradle
module under `com.example.nightagent`.

| Layer | What exists | Notes |
|---|---|---|
| Presentation | Jetpack Compose screens + components + `Navigation Compose` NavGraph | `MainActivity` is the single Compose host; no separate navigation module despite a root `navigation/` dir |
| State | `ViewModel` (SafetyViewModel, CallViewModel, VoiceMessageViewModel, StealthViewModel) + `StateFlow`/`MutableStateFlow` | Mixed with plain `object` managers (SOSManager, SafeWalkManager, ContactManager, FirebaseConfig) |
| Domain | `*Repository` classes (SafeWalkRepository, SOSRepository, SafetyRepository, EvidenceRepository, VoiceMessageRepository, SignalingRepository) | Thin Firestore wrappers; often bypassed — UI/managers call Firebase directly |
| Data | Firebase (Auth, Firestore, Storage, FCM), SharedPreferences, EncryptedSharedPreferences, files in `getExternalFilesDir` | No local database (Room); no offline queue beyond Firestore cache |
| Background | Foreground services (EvidenceCaptureService, AudioStreamingService, StealthDetectionService, AudioRecordingService), WorkManager workers | Services declared in manifest; some started from UI, some from receivers |
| Sensors/Hardware | CameraX, MediaRecorder, ExoPlayer, WebRTC, Accelerometer (ShakeDetector), SpeechRecognizer (VoiceSOS) | — |

## Module dependencies

There is exactly one module (`:app`), so there are no inter-module
dependencies. Internal package coupling is high:

- `MainActivity` imports from `sos.*`, `stealth.*`, `firebase.*`,
  `voicemessage.firebase`, `navigation` — it is a god class that wires
  permissions, auth, FCM, sensors, and the NavGraph.
- `SOSManager` (object) is called from `MainActivity`, `SafeWalkManager`,
  `StealthDetectionService`, and `PowerButtonReceiver` — a global
  singleton with mutable static state (`sosPending`, `mediaPlayer`).
- `SafetySettings` (object with `mutableStateOf`) is read/written across
  `MainActivity`, `SafetyScreen`, `SettingsScreen`, `ShakeDetector`,
  `VoiceSOSManager` — in-memory global state, not persisted.

## Execution flows

### UI → result (typical)
```
User tap (Compose onClick)
  → lambda passed to NavGraph / screen
  → Manager/ViewModel call (e.g. SOSManager.triggerSOS)
  → LocationProvider.getLocation (FusedLocation, one-shot)
  → FirestoreManager / Repository write to Firestore
  → SmsManager.sendTextMessage / MediaPlayer alarm
  → UI state update (Toast / navigation)
```

### SOS flow
```
SOS button long-press
  → MainActivity.NavGraph onSOSClick
  → EvidenceCaptureService.start(context, sosId)   [foreground service]
  → SOSManager.triggerSOS(context) {
        → LocationProvider.getLocation → callback
        → FirestoreManager.startSOSSession(message, lat, lng)
        → startLiveTracking(context)               [5 s MainLooper polling loop]
        → sendEmergencySMS(context, message)        [SmsManager to all contacts]
        → MediaPlayer alarm (15 s)
    }
  → 30 s: sosPending=false, onSOSComplete() → EvidenceCaptureService.stop(context)
```

### Safe Walk flow
```
Start Safe Walk
  → SafeWalkManager.startSafeWalk(context)
  → Firestore: safe_walks/{uid} set + start SMS
  → 5 s MainLooper loop:
      getLocation → checkMovement / checkDeviation / saveLocation / checkTimeout
      → any violation → SOSManager.triggerSOS(context, reason)
  → Stop → Firestore update isActive=false
```

### Voice message send flow
```
Hold-to-record release
  → VoiceMessageViewModel.stopAndSend()
  → VoiceRecorder.stop() → File
  → VoiceMessageRepository.sendVoiceMessage(file, ...)
      → VoiceMessageFirebase.ensureChatRoom
      → uploadAudio → Firebase Storage
      → saveMessage → Firestore chats/{chatId}/messages
      → queuePushNotification → fcm_requests doc [needs Cloud Function]
  → VoiceUploadWorker (alternative background path)
```

## Architecture weaknesses

1. **No Clean Architecture / layering.** Repositories are thin and frequently
   bypassed; UI and managers call Firebase/Sdk APIs directly. No use-case or
   domain-interactor layer.
2. **No dependency injection.** Every ViewModel/Repository instantiates its own
   dependencies (`SafetyViewModel(repository = SafetyRepository())`,
   `VoiceMessageRepository(application)`, `new SignalingRepository()` etc.).
   Hard to test, hard to swap implementations.
3. **God Activity.** `MainActivity` holds permission launchers, auth, FCM,
   shake detector, voice SOS manager, power receiver, and composes the entire
   NavGraph. Violates single responsibility.
4. **Global mutable singletons.** `SOSManager`, `SafetySettings`,
   `ContactManager`, `FirebaseConfig` hold static mutable state. Race conditions
   likely when multiple entry points (UI, service, receiver) touch `SOSManager`
   concurrently (`sosPending` flag is not atomic/thread-safe across the
   MainLooper handler and binder threads).
5. **Business logic in services and receivers.** `StealthDetectionService`,
   `SafeWalkManager` run polling loops on `MainLooper` handlers; location and
   tracking logic is scattered across services rather than a domain layer.
6. **No local database.** All state is Firestore + SharedPreferences. No
   offline-first queue for SOS/evidence beyond Firestore's 100 MB cache.
7. **Duplicate navigation code.** Root `navigation/NavGraph.kt` (package
   `com.example.womensafetyapp`) is a dead duplicate; app uses its own.
8. **Placeholder backend.** `network/SafetyApiService` points to
   `your-backend-domain.com()` and is unused.

## Proposed architecture

Retain what works; introduce light structure only where justified.

**Keep:**
- Compose UI + Navigation Compose (single Activity is fine for this app size)
- Firebase as backend (Auth, Firestore, Storage, FCM)
- ViewModels + StateFlow for UI state
- Foreground services for audio/location evidence (required by Android)
- WorkManager for resilient uploads

**Improve:**
- Add a thin DI (Hilt or Koin) — provides ViewModels, repositories, Firebase
  instances, location client. Low complexity, high testability.
- Introduce a Repository layer as the single data gateway; managers/UI call
  repositories, never Firebase directly. Move `SOSManager` logic into an
  `SosUseCase`/`SosRepository` backed by repository.
- Persist `SafetySettings` to EncryptedSharedPreferences (toggle state survives
  restart).
- Extract a `LocationRepository` (one source for `getCurrentLocation` and
  location updates) used by SafeWalk, SOS, and Map.
- Add a foreground `LocationTrackingService` so Safe Walk and SOS tracking
  survive backgrounding.

**Replace:**
- Dead `network/SafetyApiService` + placeholder Retrofit — remove or point to
  a real backend; currently unused.
- Duplicate root `navigation/NavGraph.kt` — delete.
- In-memory `SafetySettings` — replace with persisted prefs.

**Remove:**
- `sos/EvidenceUploader.kt` (dead, wrong file path, never called).
- `network/SafetyApiService.kt` (unused placeholder).
- Root `navigation/NavGraph.kt` (dead duplicate).

## Recommended module boundary (future, optional)

If the product grows, split into feature modules:
- `:feature:core` (DI, navigation, base UI)
- `:feature:sos` (SOS, safe walk, tracking)
- `:feature:stealth` (calculator disguise, PIN, background triggers)
- `:feature:streaming` (WebRTC audio)
- `:feature:evidence` (capture + upload)
- `:feature:voice` (messaging)
- `:feature:map` (safety score map)
- `:data:firebase`, `:data:local`

Do not introduce this until the single-module app is hardened; premature
modularization adds build complexity without benefit at current scale.
