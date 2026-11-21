# NightAgent — Repository Structure

## Top-level tree (source only, excluding build outputs / .idea)

```
NightAgent-Final-sms-feature-safe/
├── build.gradle.kts                 # Root build config (plugins, google-services 4.4.2)
├── settings.gradle.kts              # Includes ONLY ":app"
├── gradle.properties                # Gradle/JVM settings
├── gradlew / gradlew.bat
├── gradle/                          # Wrapper
├── TODO.md                          # Scoped SMS-feature task list
├── app/                             # THE ONLY Gradle module
│   ├── build.gradle.kts             # App module config (see TECH_STACK)
│   ├── proguard-rules.pro
│   ├── google-services.json         # Firebase config (contains live API key — see SECURITY_AUDIT)
│   └── src/
│       ├── main/
│       │   ├── java/com/example/nightagent/
│       │   │   ├── MainActivity.kt
│       │   │   ├── navigation/NavGraph.kt
│       │   │   ├── viewmodel/SafetyViewModel.kt
│       │   │   ├── sos/
│       │   │   │   ├── SOSManager.kt
│       │   │   │   ├── SafeWalkManager.kt
│       │   │   │   ├── LocationProvider.kt
│       │   │   │   ├── PowerButtonReceiver.kt      (broken trigger)
│       │   │   │   ├── ShakeDetector.kt
│       │   │   │   ├── VoiceSOSManager.kt
│       │   │   │   ├── SafetySettings.kt           (in-memory, not persisted)
│       │   │   │   ├── EvidenceRecorder.kt
│       │   │   │   └── EvidenceUploader.kt         (dead/wrong path)
│       │   │   ├── stealth/
│       │   │   │   ├── StealthDetectionService.kt
│       │   │   │   ├── AudioRecordingService.kt
│       │   │   │   └── StealthRepository.kt
│       │   │   ├── streaming/
│       │   │   │   ├── WebRTCManager.kt
│       │   │   │   ├── AudioStreamingService.kt
│       │   │   │   ├── SignalingRepository.kt
│       │   │   │   ├── CallViewModel.kt
│       │   │   │   └── StreamingScreens.kt
│       │   │   ├── evidence/
│       │   │   │   ├── EvidenceScreen.kt
│       │   │   │   ├── EvidenceCaptureService.kt
│       │   │   │   ├── EvidenceManager.kt
│       │   │   │   ├── CameraCaptureManager.kt
│       │   │   │   ├── EvidenceUploadWorker.kt
│       │   │   │   └── EvidenceRepository.kt
│       │   │   ├── voicemessage/
│       │   │   │   ├── ui/{VoiceMessageScreen,UserRegistrationScreen}.kt
│       │   │   │   ├── viewmodel/VoiceMessageViewModel.kt
│       │   │   │   ├── repository/VoiceMessageRepository.kt
│       │   │   │   ├── firebase/VoiceMessageFirebase.kt
│       │   │   │   ├── audio/{VoiceRecorder,VoicePlayer}.kt
│       │   │   │   ├── upload/VoiceUploadWorker.kt
│       │   │   │   └── model/VoiceMessage.kt
│       │   │   ├── contacts/ContactManager.kt
│       │   │   ├── firebase/{FirebaseConfig,AuthManager,FirestoreManager,MyFirebaseMessagingService}.kt
│       │   │   ├── network/{NetworkModule,SafetyApiService}.kt   (placeholder backend)
│       │   │   ├── repository/{SafeWalkRepository,SOSRepository,SafetyRepository,EvidenceRepository}.kt
│       │   │   ├── model/{SOSModel,SafeWalkModel,LocationModel,SafetyResponse}.kt
│       │   │   ├── ui/stealth/{CalculatorActivity,StealthViewModel,StealthSettingsScreen}.kt
│       │   │   ├── ui/activities/FakeCallActivity.kt
│       │   │   ├── ui/components/{SOSButton,BottomNavBar,QuickActionCard,ActivityItem,ToggleRow}.kt
│       │   │   ├── ui/screens/{Home,Map,Contacts,Safety,Settings,Splash,SafeWalk,Evidence,SafetyMapOverlay}.kt
│       │   │   └── ui/theme/{Theme,Color,Type}.kt
│       │   ├── res/ (layouts, values, drawable, mipmap, xml, raw/alarm_sos.mp3)
│       │   └── AndroidManifest.xml
│       ├── test/
│       │   └── ExampleUnitTest.kt              (2 + 2 == 4)
│       └── androidTest/
│           └── ExampleInstrumentedTest.kt    (package-name assertion)
├── live-tracker/
│   └── index.html                         # Detached Leaflet web viewer (NOT part of build)
└── navigation/
    └── NavGraph.kt                        # Duplicate/obsolete NavGraph (package com.example.womensafetyapp)
```

> Note: `live-tracker/` and `navigation/` exist at the repo root but are **not**
> Gradle modules. `settings.gradle.kts` includes only `:app`. The root
> `navigation/NavGraph.kt` uses package `com.example.womensafetyapp` and is
> not referenced by the app (the app has its own
> `app/src/main/.../navigation/NavGraph.kt`).

## Module responsibility table

| Module | Gradle? | Responsibility |
|---|---|---|
| `:app` | Yes | Entire application: UI, navigation, ViewModels, SOS, stealth, streaming, evidence, voice messaging, Firebase, network, local storage |
| `live-tracker/` | No | Standalone HTML/JS Leaflet map viewer (not compiled, not wired) |
| `navigation/` | No | Obsolete duplicate NavGraph (different package, unused) |

## Important files

| File | Role |
|---|---|
| `settings.gradle.kts` | Declares only `:app`; repo root name `nightagent` |
| `app/build.gradle.kts` | compileSdk 35, minSdk 24, targetSdk 35, Compose, all dependencies |
| `app/src/main/AndroidManifest.xml` | Permissions, activities (CalculatorActivity = LAUNCHER), services, FCM receiver, providers |
| `app/src/main/java/.../MainActivity.kt` | Entry point; auth, FCM token, shake detector, voice SOS, power receiver, NavGraph host |
| `app/src/main/java/.../navigation/NavGraph.kt` | Compose navigation graph (all routes) |
| `app/src/main/java/.../sos/SOSManager.kt` | Core SOS orchestration (alarm, location, Firestore, tracking, SMS) |
| `app/src/main/java/.../stealth/StealthDetectionService.kt` | Silent 3-toggle power-button background SOS |
| `app/src/main/java/.../streaming/` | Live audio WebRTC streaming |
| `app/src/main/java/.../evidence/` | Photo/video/audio evidence capture + WorkManager upload |
| `app/src/main/java/.../voicemessage/` | App-to-app voice messaging |
| `app/src/main/java/.../firebase/` | Firebase configuration, auth, Firestore, FCM |
| `app/google-services.json` | Firebase project config (contains API key) |

## Build identifiers

- namespace / applicationId: `com.example.nightagent`
- minSdk: 24 (Android 7.0)
- targetSdk / compileSdk: 35 (Android 15)
- versionCode: 1, versionName: 1.0
