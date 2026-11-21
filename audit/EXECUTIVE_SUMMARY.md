# NightAgent — Executive Summary

**Source:** Complete source-code audit of the NightAgent Android project
(root: `NightAgent-Final-sms-feature-safe/`)
**Audit method:** Evidence-based inspection of all Kotlin sources, Gradle
files, manifests, resources, and the standalone `live-tracker/index.html`.
No source files were modified. No build/tests could be executed because no
Android SDK is present on this machine (see `TESTING_REPORT.md`).

---

## What NightAgent is

NightAgent is a personal-safety Android application (package
`com.example.nightagent`, minSdk 24 / targetSdk 35 / compileSdk 35) built as a
single Gradle module (`:app`) using Jetpack Compose, Firebase (Auth, Firestore,
Storage, FCM), OsmDroid maps, WebRTC, CameraX, Media3 ExoPlayer, and WorkManager.

Its positioning is a discreet, panic-button safety tool with stealth disguise
(calculator launcher), silent background triggers (shake / power-button /
voice keyword), live audio streaming to a guardian, evidence capture (photo /
video / audio), emergency SMS, safe-walk tracking, and an overpass-API safety
score map.

---

## Current state

| Dimension | Status |
|---|---|
| Build | Does not compile on this machine (no Android SDK / `local.properties`) |
| Runtime | Unverified — no device/emulator available |
| Tests | Only placeholder unit + instrumented tests (no real coverage) |
| Single module | Yes (`:app` only); `live-tracker/` and `navigation/` at root are NOT Gradle modules (`settings.gradle.kts` includes only `:app`) |
| Persistence | Firestore + Firebase Storage + EncryptedSharedPreferences + SharedPreferences |
| Backend | Firebase project `nightagent-35d38` (google-services.json present, includes an API key) |

## Biggest strengths

1. **Stealth/disguise UX** — `CalculatorActivity` is the declared LAUNCHER
   (`AndroidManifest.xml:59-69`), fully functional calculator with PIN-gated
   unlock to the real app (`ui/stealth/CalculatorActivity.kt`). This is a
   genuine differentiator for at-risk users.
2. **Background-triggered SOS** — `StealthDetectionService` counts 3 screen
   toggles within 3 s and fires `SOSManager.triggerSOS()` without the user
   looking at the screen (`stealth/StealthDetectionService.kt`).
3. **Live audio streaming** — WebRTC caller/callee with Firestore signaling,
   foreground `AudioStreamingService`, victim + guardian Compose screens
   (`streaming/`).
4. **Evidence pipeline** — chunked audio recording (`EvidenceManager`),
   CameraX photo/video (`CameraCaptureManager`), WorkManager upload with
   SHA-256 hashes (`EvidenceUploadWorker`).
5. **Voice messaging** — end-to-end app-to-app voice chat with phone-number
   registration, Firestore real-time listener, FCM push, ExoPlayer playback,
   waveform UI (`voicemessage/`).
6. **Offline-first Firestore** — 100 MB persistent cache enabled
   (`firebase/FirebaseConfig.kt:20-32`).
7. **Safety score map** — OsmDroid map + Overpass API POI/police/hospital
   density scoring with risk color coding (`ui/screens/MapScreen.kt`,
   `repository/SafetyRepository.kt`).

## Biggest weaknesses

1. **Broken power-button SOS link** — `PowerButtonReceiver` (manifest +
   dynamic registration in `MainActivity`) counts 2 presses but never calls
   `SOSManager.triggerSOS()`; it only shows a Toast
   (`sos/PowerButtonReceiver.kt:13-38`). The stealth 3-toggle path in
   `StealthDetectionService` works, but the advertised 2-press power-button
   SOS is dead.
2. **Dead/placeholder code** — `network/SafetyApiService.kt` points to
   `https://your-backend-domain.com/` and is unused (SafetyRepository calls
   Overpass directly). `sos/EvidenceUploader.kt` references a file path
   (`Environment.DIRECTORY_MUSIC/evidence_recording.3gp`) that never matches
   where evidence is actually saved; it is never invoked.
3. **No dependency injection** — ViewModels/repositories instantiated directly
   throughout; no Hilt/Koin.
4. **Settings not persisted** — `sos/SafetySettings.kt` uses in-memory
   `mutableStateOf`, so toggles (Voice SOS, Shake, Power Button, Silent) reset
   on every process death/restart.
5. **Security** — `google-services.json` checked into git with a live Firebase
   API key (`AIzaSyBMo2a3e5Pb3xKU2c-4WZwLym6LazFLiuQ`);
   `allowBackup="true"`; SOS message bodies and phone numbers logged via
   `Log.d` (`sos/SOSManager.kt`).
6. **No real test coverage** — only `ExampleUnitTest` (2+2=4) and
   `ExampleInstrumentedTest` (package-name assertion).
7. **Live-tracker is a detached web file** — `live-tracker/index.html` is not
   part of the Android build, has an empty Firebase config placeholder, and is
   not wired to any in-app screen.

## Overall assessment

NightAgent is a **feature-rich but fragile prototype**. It demonstrates
ambitious, genuine safety-product ideas (stealth disguise, background triggers,
live streaming, evidence chain, voice messaging) but several core paths are
broken or dead, there is no test safety net, settings are volatile, and a
credential is checked into source control. It is best treated as a
proof-of-concept to be hardened and re-scoped around a single clear
differentiator before any production release.
