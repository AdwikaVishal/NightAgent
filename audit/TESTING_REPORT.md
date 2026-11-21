# NightAgent — Testing Report

---

## Test environment limitation

**No Android SDK is installed on this machine** (`ANDROID_HOME` and
`ANDROID_SDK_ROOT` are unset; no `~/Library/Android/sdk`). Attempting
`./gradlew :app:assembleDebug` fails at configuration:

```
Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'.
> SDK location not found. Define a valid SDK location with an ANDROID_HOME
environment variable or by setting the sdk.dir path in your project's local
properties file at '/Users/admin/.../local.properties'.
```

Therefore **no Gradle build, compile, or test task could be executed.**
No test results are claimed. The findings below are based on source inspection
only.

---

## Tests discovered

### Unit tests (`app/src/test/`)

| File | Content | Status |
|---|---|---|
| `ExampleUnitTest.kt` | `addition_isCorrect`: `assertEquals(4, 2 + 2)` | Placeholder only |

### Instrumented tests (`app/src/androidTest/`)

| File | Content | Status |
|---|---|---|
| `ExampleInstrumentedTest.kt` | `useAppContext`: asserts package name `com.example.nightagent` | Placeholder only |

There are **no unit tests, integration tests, or UI tests** for any real
feature: SOS, Safe Walk, stealth, WebRTC streaming, evidence capture, voice
messaging, location, permissions, or repositories.

## Build/test commands that would be run (with an SDK)

| Command | Purpose | Executed? |
|---|---|---|
| `./gradlew :app:assembleDebug` | Build debug APK | No (no SDK) |
| `./gradlew :app:testDebugUnitTest` | Run local unit tests | No |
| `./gradlew :app:connectedAndroidTest` | Run instrumented tests (requires device/emulator) | No |
| `./gradlew :app:lint` | Run lint checks | No |

## Missing test coverage

Every major feature area has zero real test coverage:

- **SOS** (`sos/SOSManager.kt`) — alarm, SMS, Firestore session, live tracking
- **Power-button SOS** (`sos/PowerButtonReceiver.kt`) — broken trigger, untested
- **Shake detection** (`sos/ShakeDetector.kt`) — sensor logic, untested
- **Voice-keyword SOS** (`sos/VoiceSOSManager.kt`) — SpeechRecognizer, untested
- **Safe Walk** (`sos/SafeWalkManager.kt`) — movement/deviation/timeout auto-SOS, untested
- **Location** (`sos/LocationProvider.kt`) — permission/Play Services handling, untested
- **Stealth** (`stealth/StealthRepository.kt`, `StealthDetectionService.kt`) — PIN, lockout, encryption, untested
- **Evidence** (`evidence/EvidenceManager.kt`, `CameraCaptureManager.kt`, `EvidenceUploadWorker.kt`) — MediaRecorder, CameraX, upload retry, untested
- **Streaming** (`streaming/WebRTCManager.kt`, `CallViewModel.kt`, `SignalingRepository.kt`) — WebRTC signaling, untested
- **Voice messaging** (`voicemessage/...`) — Firestore listener, phone→UID resolution, send pipeline, playback, untested
- **Map / safety score** (`repository/SafetyRepository.kt`) — Overpass parsing/scoring, untested
- **Repositories** — all Firestore wrappers, untested

## Mocking strategy

None. No Mockito, MockK, or other mocking dependency is declared in
`app/build.gradle.kts`. `testImplementation(libs.junit)` is the only test
dependency (JUnit). No `androidx.test.ext:junit` extensions beyond the
instrumented runner, no `compose.ui.test` beyond the manifest dependency.

## Crash risks & reliability concerns (from code inspection)

- `SOSManager.sosPending` is a plain `Boolean` mutated across the UI thread,
  `MainLooper` handler callbacks, and potentially binder/service threads — not
  atomic, potential race between concurrent SOS triggers.
- `SafeWalkManager.isActive` and handlers are plain mutable state on `MainLooper`;
  calling `startSafeWalk` twice rapidly could double-post the monitoring
  runnable (guarded by `isActive` but not thread-safe).
- `VoiceSOSManager.restartListening()` posts a 500 ms delayed restart after
  every result/error — if `isEnabled` toggles off during the delay, the guard
  checks `isEnabled` but the `stopListening()`/`startListening()` pair still
  executes when the delay fires (it checks before stopping, but there is a
  small window).
- `PowerButtonReceiver` and `StealthDetectionService` both listen for
  `SCREEN_ON`/`SCREEN_OFF`; duplicate counting could cause double triggers.
- `EvidenceCaptureService.onStartCommand` returns `START_NOT_STICKY`; if killed,
  evidence capture ends and `stopSelf()` runs — upload worker is enqueued in
  `endCapture()` before `stopSelf()`, so upload survives if the worker is
  enqueued before process death.
- `WebRTCManager` creates a `PeerConnectionFactory` per instance with no
  reference counting; multiple simultaneous streams could exhaust native
  resources.
- `VoicePlayer` starts a position-polling `while(true)` loop on `Dispatchers.Main`
  that is cancelled on `STATE_ENDED`/`release`, but a rapid sequence of
  `playOrPause` calls could leave a stray job.

## Testing recommendation

Add JUnit 5 + MockK (or Mockito) for repository/ViewModel logic; add
Espresso/Compose UI tests for critical paths (SOS trigger, Safe Walk start/stop,
stealth unlock); add a hermetic Robolectric test for `LocationProvider`
permission handling; add instrumentation tests for `EvidenceCaptureService`
foreground-service behavior. Enable `testOptions.unitTests.includeAndroidResources`
if tests need Android context.
