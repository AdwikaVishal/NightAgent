# NightAgent — Feature Audit

Every finding below is traced to actual source. Statuses:

- **Implemented** — code path exists and is wired end-to-end (unverified at runtime; no SDK to build/test).
- **Partially implemented** — code exists but a link in the flow is broken or incomplete.
- **UI only / demo** — screen exists but has no real business logic behind it.
- **Broken / dead** — code is present but never invoked, references wrong paths, or is a placeholder.
- **Not implemented** — referenced/planned but absent.

Package root: `app/src/main/java/com/example/nightagent/`.

---

## 1. SOS Trigger (panic button)

**Files:**
- `ui/screens/HomeScreen.kt` — SOS button long-press → `onSOSClick`
- `ui/components/SOSButton.kt` — long-press gesture
- `sos/SOSManager.kt` — `triggerSOS()`: alarm (15 s), location fetch, Firestore session, live tracking (5 s loop), SMS to contacts
- `sos/LocationProvider.kt` — one-shot `getCurrentLocation` via FusedLocationProvider
- `firebase/FirestoreManager.kt` — `startSOSSession` / `updateLocation`
- `sos/ContactManager.kt` — emergency contacts from SharedPreferences

**Flow:** long-press SOS → `EvidenceCaptureService.start` (foreground audio+location) → `SOSManager.triggerSOS` → location → `FirestoreManager.startSOSSession` → `startLiveTracking` (5 s polling loop writing `lat/lng/time` to Firestore) → `sendEmergencySMS` to all contacts → alarm stops after 15 s, session ends after 30 s.

**Status: Implemented** (end-to-end code path present; runtime unverified).

**Gaps:** tracking loop runs on `MainLooper` handler (battery risk); no foreground service dedicated to SOS tracking (EvidenceCaptureService is started/stopped around it); 30 s hard stop; SMS message format not standardized (see TODO.md).

---

## 2. Stealth Mode (calculator disguise)

**Files:**
- `AndroidManifest.xml:59-69` — `CalculatorActivity` is LAUNCHER, exported
- `ui/stealth/CalculatorActivity.kt` — working calculator; PIN entered via digits, `=` unlocks → `MainActivity`
- `ui/stealth/StealthSettingsScreen.kt` — toggle, set/change PIN, how-it-works
- `ui/stealth/StealthViewModel.kt` — PIN validation, lockout (5 attempts / 60 s)
- `stealth/StealthRepository.kt` — `EncryptedSharedPreferences` (AES256-GCM master key) for PIN + stealth state
- `stealth/StealthDetectionService.kt` — foreground service; 3 screen toggles within 3 s → `SOSManager.triggerSOS()` + `AudioRecordingService`
- `stealth/AudioRecordingService.kt` — silent mic foreground service, auto-stops after 5 min

**Status: Implemented.**

**Gaps:** `StealthDetectionService` uses `START_STICKY` and a persistent foreground notification (PRIORITY_MIN) — may be killed aggressively on some OEMs; screen-toggle detection is a proxy for power-button, not the actual power key.

---

## 3. Power-Button SOS (advertised 2-press)

**Files:**
- `AndroidManifest.xml:79-86` — `PowerButtonReceiver` (SCREEN_ON/SCREEN_OFF, exported=false)
- `sos/PowerButtonReceiver.kt` — counts presses; on 2nd press within 2 s shows **only** a Toast "Power SOS triggered"; **never calls `SOSManager.triggerSOS()`**
- `MainActivity.kt:123-128` — also dynamically registers a `PowerButtonReceiver` for the same actions (duplicate registration)

**Status: Broken.** The receiver is wired for detection but the trigger action is missing. Duplicate static + dynamic registration is also problematic on Android 12+ (SCREEN_ON/OFF broadcasts cannot be received from the manifest unless the app is visible / a foreground service is running).

---

## 4. Shake Detection SOS

**Files:**
- `sos/ShakeDetector.kt` — accelerometer delta threshold (>18), requires 2 shakes within 1 s, cooldown 2 s, gated by `SafetySettings.shakeDetection.value`
- `MainActivity.kt:101-103,160` — started in `onResume`, stopped in `onPause`

**Status: Partially implemented.** Wired into MainActivity lifecycle and calls `SOSManager.triggerSOS`, but `SafetySettings.shakeDetection` is in-memory only (resets on restart), so the feature is unreliable across sessions.

---

## 5. Voice-Keyword SOS

**Files:**
- `sos/VoiceSOSManager.kt` — Android `SpeechRecognizer`, keywords {help, danger, sos, emergency, save me, ...}, 5 s cooldown, gated by `SafetySettings.voiceSOS.value`
- `MainActivity.kt:105-121` — enable/disable + `snapshotFlow` observer on toggle

**Status: Partially implemented.** SpeechRecognizer requires network + Google services; continuous restart loop (500 ms) after every result/error can drain battery; toggle state not persisted.

---

## 6. Emergency Contacts

**Files:**
- `ui/screens/ContactsScreen.kt` — add/edit/delete contacts, dial, voice-chat entry
- `sos/ContactManager.kt` — `saveContacts` / `getContacts` via SharedPreferences (`emergency_contacts` / `numbers`)
- `ui/components/ContactCard.kt` — inline in ContactsScreen

**Status: Implemented** (CRUD + dial). No sync to backend; contacts are device-local only. Voice-chat entry passes `contact.phone` as `otherUserId` to `voicechat/{phone}` route.

---

## 7. Safe Walk (location trail + auto-SOS)

**Files:**
- `ui/screens/SafeWalkScreen.kt` — OsmDroid map, start/stop, 3 s location polling loop drawing polyline + marker
- `sos/SafeWalkManager.kt` — 5 s monitoring loop: movement (>5 m resets timer), no-movement >120 s → SOS, deviation >60° & >10 m → auto-SOS, distance from start >300 m → SOS, max duration 5 min → SOS; writes `safe_walks/{uid}/locations` to Firestore; sends start SMS
- `repository/SafeWalkRepository.kt` — Firestore writes

**Status: Implemented** (end-to-end code path). Runs on `MainLooper` handler (5 s polling); stops when app is backgrounded/killed (no foreground service); no resume-on-reboot.

---

## 8. Live Location Tracking (during SOS)

**Files:**
- `sos/SOSManager.kt:startLiveTracking` — 5 s `getCurrentLocation` loop writing to `FirestoreManager.updateLocation`
- `firebase/FirestoreManager.kt:updateLocation` — writes `lat/lng/time` under `sos_alerts/{uid}/sessions/{sessionId}/location_updates`
- `live-tracker/index.html` — standalone Leaflet map reading `safe_walks/{userId}/locations` (note: different collection path than SOS tracking!)

See `LIVE_TRACKER_AUDIT.md` for detail.

**Status: Partially implemented.** Tracking writes to Firestore but path mismatch with the web viewer; no foreground service; stops on process death.

---

## 9. Map + Safety Score

**Files:**
- `ui/screens/MapScreen.kt` — OsmDroid MapView, user marker, safety-zone polygon, POI markers (police/hospital/etc.), "Directions" + "Locate Me" FABs, risk dialog
- `repository/SafetyRepository.kt` — Overpass API query, poi/police/hospital scoring, SharedPreferences cache (30 s min refresh, 0.001° delta)
- `ui/screens/SafetyMapOverlay.kt` — `SafetyScoreCard` + `updateSafetyOverlay`
- `viewmodel/SafetyViewModel.kt` — load/fetch/cache safety score

**Status: Implemented.** Calls public `overpass-api.de` directly (no API key). Network on `Dispatchers.IO`. Risk dialog shows for "Risky" areas.

**Gaps:** Third-party API (no key, rate-limited, can be blocked); safety score is a rough heuristic, not authoritative.

---

## 10. Evidence Capture (photo / video / audio)

**Files:**
- `ui/screens/EvidenceScreen.kt` — CameraX PreviewView, photo/video buttons, Start/Stop Capture, timeline (static demo items), WorkManager upload state
- `evidence/CameraCaptureManager.kt` — CameraX imageCapture + videoCapture, bindToLifecycle
- `evidence/EvidenceCaptureService.kt` — foreground service (MICROPHONE|LOCATION), chunked audio via EvidenceManager, enqueues EvidenceUploadWorker on stop
- `evidence/EvidenceManager.kt` — MediaRecorder chunked audio (30 s chunks), SHA-256, metadata
- `evidence/EvidenceUploadWorker.kt` — WorkManager upload of Evidence/ dir to Firebase Storage + Firestore `evidence/{sosId}`, retries ×3, network constraint
- `repository/EvidenceRepository.kt` — uploadFile helper (unused by worker; duplicates logic)

**Status: Implemented.** Photo/video via CameraX in UI layer (correctly avoids Android 15 camera-FGS restriction); audio via foreground service; upload via WorkManager with retry.

**Gaps:** `EvidenceScreen` timeline items are hardcoded strings (demo); `EvidenceRepository.uploadFile` is unused/dead.

---

## 11. Live Audio Streaming (victim → guardian)

**Files:**
- `streaming/WebRTCManager.kt` — audio-only PeerConnection, STUN Google servers, offer/answer/ICE via callbacks
- `streaming/AudioStreamingService.kt` — foreground service hosting caller WebRTC session
- `streaming/CallViewModel.kt` — `startSOSStream` / `joinAsGuardian` / `leaveCall`, Firestore signaling via `SignalingRepository`
- `streaming/SignalingRepository.kt` — Firestore call doc, offer/answer/candidate/status flows
- `streaming/StreamingScreens.kt` — `VictimStreamScreen` (pulsing circle, stop button), `GuardianListenScreen` (mute, leave, audio level indicator — cosmetic)
- `streaming/StreamingScreens.kt` — `PulsingCircle` / `AudioLevelIndicator` are animated placeholders (not real audio levels)

**Status: Implemented** (signaling + WebRTC code path). Guardian-side audio level and mute are cosmetic (guardian is listen-only; no actual audio playback of the stream is implemented in `WebRTCManager` — only remote track handling stubs `onAddTrack` empty).

**Gaps:** No TURN server (STUN only — fails behind symmetric NAT); guardian cannot speak back; audio-level UI is fake animation.

---

## 12. Voice Messaging (app-to-app async voice)

**Files:**
- `voicemessage/ui/VoiceMessageScreen.kt` — hold-to-record mic bar, message list, bubbles, playback slider, waveform, upload progress, seen status
- `voicemessage/viewmodel/VoiceMessageViewModel.kt` — auth, receiver UID resolution (phone → UID), chatId (sorted UIDs), Firestore listener, send pipeline, playback
- `voicemessage/repository/VoiceMessageRepository.kt` — register user, find receiver by phone, observe messages, send pipeline (upload → Firestore → FCM request)
- `voicemessage/firebase/VoiceMessageFirebase.kt` — chatId, registerUser, findUidByPhone, ensureChatRoom, uploadAudio (putStream), saveMessage, messagesFlow (callbackFlow), queuePushNotification (fcm_requests)
- `voicemessage/ui/UserRegistrationScreen.kt` — phone + name registration
- `voicemessage/audio/VoiceRecorder.kt` — MediaRecorder, amplitude polling, min 500 ms
- `voicemessage/audio/VoicePlayer.kt` — ExoPlayer, single-message playback, seek
- `voicemessage/upload/VoiceUploadWorker.kt` — WorkManager background upload with retry
- `firebase/MyFirebaseMessagingService.kt` — data-message handling, deep link `voicechat/{senderId}`, FCM token refresh
- `voicemessage/model/VoiceMessage.kt` — Firestore schema docs in comments

**Status: Implemented** (most complete feature). Real-time listener, push via Cloud Function (function code documented as comment but not deployed in repo), phone→UID lookup.

**Gaps:** Cloud Function for FCM is not part of the repo (must be deployed separately); registration is optional (skip-able), so many users won't be discoverable.

---

## 13. Fake Call

**Files:**
- `AndroidManifest.xml:98-104` — `FakeCallActivity` (fullscreen, showOnLockScreen, excludeFromRecents)
- `ui/activities/FakeCallActivity.kt` — (not read in full; declared, launched from HomeScreen/SafetyScreen)

**Status: UI / partially implemented.** Declared and launched; actual call simulation logic not fully traced.

---

## 14. Settings & toggles

**Files:**
- `ui/screens/SettingsScreen.kt` — notifications, dark mode, location sharing, auto recording, consent, stealth entry, voice registration entry
- `sos/SafetySettings.kt` — all toggles are `mutableStateOf` (in-memory, NOT persisted)

**Status: UI implemented; persistence broken.** All safety toggles reset on process death.

---

## 15. Splash / Navigation

**Files:**
- `ui/screens/SplashScreen.kt` — 2 s delay → home
- `navigation/NavGraph.kt` — NavHost with routes: splash, home, safewalk, map, contacts, safety, evidence, victimstream/{guardianUid}, guardianstream/{callId}, settings, stealth, register, voicechat/{otherUserId}, sosactive
- `ui/components/BottomNavBar.kt` — home/map/contacts/evidence/safety/settings

**Status: Implemented.** Deep-link from FCM (`navigate_to` extra) supported. `sosactive` is a static "SOS ACTIVATED" card (demo).

---

## Summary table

| Feature | Status | Key file |
|---|---|---|
| SOS panic button | Implemented | `sos/SOSManager.kt` |
| Stealth / calculator disguise | Implemented | `ui/stealth/CalculatorActivity.kt` |
| Power-button SOS (2-press) | **Broken** | `sos/PowerButtonReceiver.kt` |
| Shake-detection SOS | Partial | `sos/ShakeDetector.kt` |
| Voice-keyword SOS | Partial | `sos/VoiceSOSManager.kt` |
| Emergency contacts | Implemented | `sos/ContactManager.kt` |
| Safe Walk | Implemented | `sos/SafeWalkManager.kt` |
| Live location tracking | Partial | `sos/SOSManager.kt` + `firebase/FirestoreManager.kt` |
| Map + safety score | Implemented | `ui/screens/MapScreen.kt` |
| Evidence capture + upload | Implemented | `evidence/` |
| Live audio streaming | Implemented (guardian listen-only) | `streaming/` |
| Voice messaging | Implemented | `voicemessage/` |
| Fake call | Partial | `ui/activities/FakeCallActivity.kt` |
| Settings persistence | **Broken** | `sos/SafetySettings.kt` |
| live-tracker web viewer | Not integrated | `live-tracker/index.html` |
