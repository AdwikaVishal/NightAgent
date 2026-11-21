# NightAgent — Open Questions

Items that cannot be established from the codebase alone and require owner
input, external confirmation, or an Android SDK to verify.

---

## Product decisions (owner input)

1. **What is the intended primary use case?** The codebase mixes SOS panic,
   Safe Walk, stealth disguise, live audio streaming, evidence capture, and
   voice messaging. No product doc or README clarifies which is the core
   value proposition. (See PRODUCT_OPPORTUNITIES.md for a recommended
   direction: "Silent Guardian" stealth safety.)

2. **Who is the target audience and region?** Phone normalization in
   `VoiceMessageRepository.normalizePhone` hardcodes `+91` (India) for
   10-digit numbers. Is the app India-specific or global? This affects phone
   formatting, emergency number expectations, and SMS cost.

3. **Is the stealth/calculator disguise meant to be permanent or opt-in?**
   `CalculatorActivity` is the manifest LAUNCHER, so the app always opens as
   a calculator. Is that the desired default for all users, or should there
   be a normal launcher option?

4. **Should voice-message registration be mandatory at onboarding?** The
   `UserRegistrationScreen` has a "Skip for now" path, and many features
   (voice chat from Contacts) require a registered phone. Is incomplete
   registration an acceptable state?

5. **Who deploys and operates the FCM Cloud Function?** `VoiceMessageFirebase.kt`
   documents an `index.js` function that watches `fcm_requests` and sends
   push notifications, but no function code exists in the repo. Who owns
   Firebase project `nightagent-35d38` and will deploy the function?

6. **Is there a real custom backend?** `network/SafetyApiService` points to
   `https://your-backend-domain.com()` and is unused. Is a custom backend
   planned, or is Firebase the entire backend?

7. **What is the retention / deletion policy for SOS location data?**
   Locations are written to Firestore every 5 s during SOS/Safe Walk with no
   retention rules. How long should this data be kept, and who can read it?

---

## External services requiring confirmation

8. **Firebase project `nightagent-35d38`**: Is this a real, active project?
   The `google-services.json` is committed with a live API key (security
   issue S-01). Confirm project ownership and plan to rotate/inject the key.

9. **Overpass API (`overpass-api.de`)**: Used raw (no key) for safety-score
   POI queries. Is reliance on a third-party, rate-limited, unauthenticated
   API acceptable for a safety product? What is the fallback when it is
   unreachable?

10. **TURN server**: WebRTC uses only Google STUN servers. Is a TURN server
    planned for relay behind symmetric NAT? Who will operate/bill it?

11. **Google Play Services dependency**: `LocationProvider` requires
    `GooglePlayServicesAvailable == SUCCESS`. What happens on devices without
    GMS (e.g. some custom ROMs, emulators without Play Store)?

---

## Technical unknowns (require an Android SDK / device to verify)

12. **Does the project compile?** Build fails on this machine due to missing
    Android SDK (`local.properties` / `ANDROID_HOME`). Run
    `./gradlew :app:assembleDebug` once an SDK is configured.

13. **Do tests pass?** Only placeholder tests exist
    (`ExampleUnitTest`: 2+2=4; `ExampleInstrumentedTest`: package name).
    No real feature tests. Cannot execute without SDK + device/emulator.

14. **Runtime behavior of foreground services:** Does
    `EvidenceCaptureService` (MICROPHONE|LOCATION) and
    `AudioStreamingService` (MICROPHONE) actually start on Android 15
    (targetSdk 35) given foreground-service type restrictions? Does
    `StealthDetectionService` (START_STICKY, PRIORITY_MIN notification)
    survive Doze/Standby?

15. **WebRTC connectivity**: Does the audio stream connect across NATs with
    only STUN? Likely not on symmetric NAT — needs TURN verification on real
    devices/networks.

16. **SpeechRecognizer reliability**: `VoiceSOSManager` uses
    `SpeechRecognizer` with continuous restart. Does it work offline? On
    devices without Google speech services? Battery impact of the 500 ms
    restart loop?

17. **Power-button detection on modern Android**: `SCREEN_ON`/`SCREEN_OFF`
    broadcasts cannot be received from the manifest on Android 12+ unless the
    app is visible. Does the dynamic registration in `MainActivity` (plus the
    manifest registration) actually capture power-button presses when the
    screen is off and the app is backgrounded?

---

## Missing documentation

- No README describing architecture, setup, Firebase configuration, or how
  to run the project.
- No API documentation for Firestore collections beyond inline comments in
  `voicemessage/model/VoiceMessage.kt`.
- No security model / threat model document.
- No runbook for what happens when an SOS is triggered (guardian response
  process, escalation, false-positive handling).
