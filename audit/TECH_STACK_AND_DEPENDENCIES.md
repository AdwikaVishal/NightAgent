# NightAgent — Tech Stack & Dependencies

All versions taken from `app/build.gradle.kts` and `settings.gradle.kts`.

---

## Build tooling

| Tool | Version | Source |
|---|---|---|
| Gradle wrapper | 9.3.1 | `gradle/wrapper/` |
| Android Gradle Plugin | via `libs.plugins.android.application` (version catalog) | `app/build.gradle.kts:2` |
| Kotlin | via `libs.plugins.kotlin.android` / `kotlin.compose` | `app/build.gradle.kts:3-4` |
| JVM target | 11 | `app/build.gradle.kts:31-34` |
| Compile SDK | 35 | `app/build.gradle.kts:11` |
| Min SDK | 24 | `app/build.gradle.kts:15` |
| Target SDK | 35 | `app/build.gradle.kts:16` |
| Version code / name | 1 / 1.0 | `app/build.gradle.kts:17-18` |

Note: `kotlinOptions` is deprecated (warning at `app/build.gradle.kts:38`); should migrate to `compilerOptions`.

---

## Dependency categories

### Maps
| Library | Version | Purpose |
|---|---|---|
| osmdroid-android | 6.1.16 | Offline/OpenStreetMap map view (MapScreen, SafeWalkScreen) |
| maps-compose | 2.11.4 | Google Maps Compose (declared; not clearly used — OsmDroid is used instead) |

### Live audio streaming (WebRTC)
| Library | Version | Purpose |
|---|---|---|
| webrtc-sdk.android | 125.6422.06.1 | Audio-only WebRTC peer connection (victim→guardian streaming) |

### Camera / media
| Library | Version | Purpose |
|---|---|---|
| camera-core | 1.3.4 | CameraX photo capture (EvidenceScreen) |
| camera-camera2 | 1.3.4 | CameraX backend |
| camera-lifecycle | 1.3.4 | CameraX lifecycle integration |
| camera-video | 1.3.4 | CameraX video recording |
| camera-extensions | 1.3.4 | CameraX extensions |
| camera-view | 1.3.4 | PreviewView |
| media3-exoplayer | 1.3.1 | Voice message playback (VoicePlayer) |
| media3-ui | 1.3.1 | Media UI |
| media3-common | 1.3.1 | Media3 common |

### Network
| Library | Version | Purpose |
|---|---|---|
| retrofit | 2.11.0 | HTTP client (declared; SafetyApiService is unused placeholder) |
| converter-gson | 2.11.0 | Retrofit JSON |
| okhttp logging-interceptor | 4.12.0 | HTTP logging (NetworkModule) |

### Firebase (BOM 32.7.0)
| Library | Purpose |
|---|---|
| firebase-auth-ktx | Anonymous + email auth |
| firebase-firestore-ktx | Firestore DB + offline persistence |
| firebase-storage-ktx | Evidence/voice audio storage |
| firebase-messaging-ktx | FCM push + token |
| google-services plugin | 4.4.2 (root build.gradle.kts) |

### Google Play Services
| Library | Purpose |
|---|---|
| play.services.location | FusedLocationProvider (LocationProvider) |

### AndroidX
| Library | Purpose |
|---|---|
| androidx.core.ktx | Core extensions |
| androidx.lifecycle.runtime.ktx | Lifecycle |
| androidx.activity.compose | Compose activity |
| androidx.fragment:fragment-ktx | 1.6.2 |
| androidx.activity:activity-ktx | 1.8.2 |
| androidx.security:security-crypto | 1.1.0-alpha06 | EncryptedSharedPreferences (StealthRepository) |
| androidx.compose.ui / graphics / tooling.preview / material3 | Compose UI (BOM) |
| androidx.navigation.compose | Navigation graph |
| compose.material.icons.extended | Icons |
| lifecycle.viewmodel.compose | ViewModel in Compose |
| lifecycle-runtime-compose | 2.7.0 |
| lifecycle-service | 2.7.0 | LifecycleService (EvidenceCaptureService) |

### Background work
| Library | Version | Purpose |
|---|---|---|
| work-runtime-ktx | 2.9.0 | EvidenceUploadWorker, VoiceUploadWorker |

### Coroutines / time
| Library | Version | Purpose |
|---|---|---|
| kotlinx-coroutines-android | 1.8.1 | Async / flows |
| kotlinx-datetime | 0.6.0 | Date/time |

### Permissions
| Library | Version | Purpose |
|---|---|---|
| accompanist-permissions | 0.34.0 | Runtime permissions (VoiceMessageScreen) |

### Test
| Library | Purpose |
|---|---|
| junit | Unit tests (ExampleUnitTest only) |
| androidx.test.ext:junit / espresso / compose.ui.test | Instrumented tests (placeholder only) |

---

## Observations

1. **OsmDroid AND Google Maps Compose both declared.** Only OsmDroid is used
   (`MapScreen`, `SafeWalkScreen`). `maps-compose:2.11.4` appears unused —
   candidate for removal.
2. **Retrofit/OkHttp declared but SafetyApiService points to a placeholder
   domain and is unused.** SafetyRepository calls Overpass API via raw
   `HttpURLConnection`. The Retrofit stack is dead code.
3. **No dependency injection library** (no Hilt/Koin/Dagger).
4. **No Room / local database** dependency. All persistence is Firestore +
   SharedPreferences / EncryptedSharedPreferences.
5. **No Mockito/MockK test dependency** — only JUnit.
6. **WebRTC SDK is large** (`webrtc-sdk.android:125.6422.06.1`) — significant
   APK size impact for audio-only streaming.

---

## Outdated / unnecessary dependencies

| Dependency | Issue | Recommendation |
|---|---|---|
| `maps-compose:2.11.4` | Declared, OsmDroid used instead | Remove if not used |
| `retrofit` + `converter-gson` + `logging-interceptor` | SafetyApiService unused placeholder | Remove or wire to real backend |
| `kotlinOptions` (deprecated) | Deprecated API | Migrate to `compilerOptions` |
| No MockK/Mockito | No mocking for tests | Add when writing tests (P4-1) |
