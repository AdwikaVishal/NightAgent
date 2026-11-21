# NightAgent — Product Analysis

---

## Original product concept

NightAgent is positioned as a **discreet personal-safety application**. The
concept: a user in danger should be able to trigger an emergency response
without drawing attention. The app disguises itself as a calculator, supports
silent triggers (power button, shake, voice keyword), notifies emergency
contacts via SMS, streams live audio to a guardian, captures photo/video/audio
evidence, tracks location during a safe walk or SOS, and shows a safety score
for the surrounding area.

## Target audience

- Individuals (particularly women and vulnerable populations) concerned about
  personal safety when walking alone, commuting at night, or in situations
  where opening an obvious "SOS" app would escalate risk.
- Users who want a covert way to alert guardians/contacts and record evidence.

## User journeys

### Journey 1: Emergency SOS (screen accessible)
1. User opens the app (or calculator disguise → PIN unlock → real app).
2. Long-presses the SOS button on HomeScreen.
3. EvidenceCaptureService starts (audio + location foreground service).
4. SOSManager fetches location, starts Firestore SOS session, starts 5 s live
   location tracking, sends SMS to all emergency contacts, plays alarm.
5. After 30 s the SOS session ends; evidence is queued for upload via
   WorkManager.

### Journey 2: Silent SOS (screen NOT accessible — stealth)
1. Stealth Mode enabled + PIN set; StealthDetectionService running.
2. User presses power button 3× within 3 s (detected via screen on/off
   toggles).
3. StealthDetectionService fires SOSManager.triggerSOS() and starts
   AudioRecordingService (silent mic).
4. SMS + location + Firestore + alarm proceed as in Journey 1.

### Journey 3: Safe Walk
1. User taps "Safe Walk" → SafeWalkScreen.
2. Starts SafeWalkManager: 5 s location loop, writes to Firestore,
   sends start SMS to contacts.
3. Auto-SOS triggers on no-movement (>120 s), sharp deviation (>60°/>10 m),
   distance from start (>300 m), or max duration (5 min).

### Journey 4: Voice message to guardian
1. User registers phone number (UserRegistrationScreen).
2. Opens Contacts → taps voice-message icon → `voicechat/{phone}`.
3. VoiceMessageViewModel resolves receiver UID by phone, computes chatId,
   starts Firestore real-time listener.
4. Holds mic to record → VoiceUploadWorker uploads to Firebase Storage,
   writes message to Firestore, queues FCM push (via Cloud Function).
5. Receiver gets push, taps notification → deep link `voicechat/{senderId}`
   → plays message with ExoPlayer.

### Journey 5: Check area safety
1. Opens MapScreen → OsmDroid map with user location.
2. SafetyViewModel fetches safety score from Overpass API (police/hospital/
   POI density within 2 km).
3. Risk dialog shows if area is "Risky"; POI markers can be tapped for
   directions.

## Implemented functionality

(See FEATURE_AUDIT.md for the full status table.)

Fully implemented end-to-end code paths:
- SOS panic button (SMS, alarm, Firestore session, live tracking)
- Stealth disguise (calculator launcher, PIN, unlock)
- Stealth background SOS (3-toggle detection)
- Emergency contacts (local CRUD + dial)
- Safe Walk with auto-SOS conditions
- Evidence capture (CameraX photo/video + chunked audio) + WorkManager upload
- Live audio streaming (WebRTC victim→guardian, Firestore signaling)
- Voice messaging (registration, phone→UID, real-time Firestore, FCM push, ExoPlayer playback)
- Map + safety score (OsmDroid + Overpass API)

Partially implemented / fragile:
- Power-button SOS (trigger action missing)
- Shake / voice SOS (toggles not persisted)
- Live tracking (no foreground service, stops on process death)
- Settings (in-memory only)

UI-only / demo:
- `live-tracker/index.html` (detached, empty Firebase config)
- EvidenceScreen timeline items (hardcoded strings)
- `sosactive` screen (static card)
- Guardian audio-level / mute UI (cosmetic, listen-only)
- `network/SafetyApiService` (placeholder backend URL, unused)

## Product gaps

| Gap | Why it matters |
|---|---|
| Power-button SOS broken | Advertised trigger does nothing |
| Toggles not persisted | Stealth/shake/voice settings vanish on restart |
| No foreground location tracking | Safe-walk/SOS tracking dies when app backgrounds |
| No guardian mobile live-view | `live-tracker/index.html` is detached and unconfigured |
| No TURN server | WebRTC streaming fails behind symmetric NAT |
| FCM Cloud Function not deployed | Voice-message push notifications won't fire |
| Voice registration optional | Many users won't be discoverable by phone |
| No tests | No safety net for refactoring |
| Firebase API key in git | Credential exposure |
