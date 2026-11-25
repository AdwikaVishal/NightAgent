# NightAgent MapTiler Integration Report

**Branch:** `integration/phase2-consolidation` (created locally from the current `main` HEAD so all pre-existing uncommitted work was preserved).  
**Status:** Implemented and validated locally. No commit, push, or merge was performed.

---

## 1. Files inspected

### Map implementation
- `app/src/main/java/com/example/nightagent/ui/screens/MapScreen.kt`
- `app/src/main/java/com/example/nightagent/ui/screens/SafeWalkScreen.kt`
- `app/src/main/java/com/example/nightagent/ui/screens/SafetyMapOverlay.kt`
- `app/src/main/java/com/example/nightagent/navigation/NavGraph.kt` (app navigation; the top-level `navigation/NavGraph.kt` is an unrelated stub)
- `app/src/main/java/com/example/nightagent/MainActivity.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/nightagent/sos/LocationProvider.kt`
- `app/src/main/java/com/example/nightagent/journey/LocationTracker.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyViewModel.kt`
- `app/src/main/java/com/example/nightagent/journey/JourneyEngine.kt`
- `app/src/main/java/com/example/nightagent/viewmodel/SafetyViewModel.kt`
- Existing unit tests under `app/src/test/java/com/example/nightagent/` (no map-UI tests exist)

### Build and configuration
- `app/build.gradle.kts`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/libs.versions.toml`
- `gradle.properties`
- `.gitignore`, `app/.gitignore`
- `local.properties` (inspected only for key presence/property name; value redacted and never printed)
- osmdroid `6.1.16` sources/AAR from the local Gradle cache, used to verify `XYTileSource`, `TileSourcePolicy`, `CopyrightOverlay`, `MapTileProviderBasic`, and `TileDownloader` behavior

### Existing implementation documented before changes
- Both screens created their own `MapView` with `TileSourceFactory.MAPNIK`, `setMultiTouchControls(true)`, and zoom 16 (`MapScreen`) or 17 (`SafeWalkScreen`).
- osmdroid `Configuration.load(...)` and `userAgentValue` were repeated three times across the two screens.
- `MapScreen` shows a current-location marker, a 400 m safety-zone `Polygon`, Overpass POI markers, danger/risk dialog, and two FABs.
- `SafeWalkScreen` renders a blue planned-route `Polyline`, red actual-path `Polyline`, current-location marker, and destination marker; it cleared all overlays on every update.
- Location comes from `LocationProvider` (single fix) in `MapScreen` and `JourneyEngine`/`LocationTracker` continuous updates in `SafeWalkScreen`.
- Navigation restores `NavBackStackEntry` state, but `remember`-based map state was lost on screen re-entry.
- Dependency in use: `org.osmdroid:osmdroid-android:6.1.16`; `com.google.maps.android:maps-compose:2.11.4` is declared but unused and was left untouched.

---

## 2. Files modified

| File | Change |
|---|---|
| `app/build.gradle.kts` | Enabled `buildConfig`, added `MAPTILER_API_KEY` BuildConfig field sourced only from `local.properties` or `MAPTILER_API_KEY` environment variable; added string escaping. |
| `app/src/main/java/com/example/nightagent/map/MapTileSources.kt` | **New.** Central osmdroid tile-source configuration, MapTiler source selection, reachability probe, offline fallback, attribution overlay, and log-safety controls. |
| `app/src/main/java/com/example/nightagent/ui/screens/MapScreen.kt` | Replaced direct MAPNIK setup with `MapTileSources`, removed duplicate `Configuration.load`, added saved camera/location state, added MapTiler verification and non-crashing fallback. |
| `app/src/main/java/com/example/nightagent/ui/screens/SafeWalkScreen.kt` | Same tile-source integration, preserved route overlays while keeping the attribution overlay, added saved camera/path state. |
| `local.properties` | **Local only, gitignored and untracked.** Contains the supplied MapTiler key and the local Android SDK path. No value is reproduced in this report or in Git. |
| `app/src/test/java/com/example/nightagent/map/MapTileSourcesTest.kt` | **New.** Two JVM tests verifying the locally supplied key is present and the tile URL uses the documented MapTiler endpoint without hardcoding the key. |
| `NIGHTAGENT_MAPTILER_INTEGRATION_REPORT.md` | This report. |

No other tracked files were changed by this work. All pre-existing uncommitted changes (AndroidManifest, services, repositories, tests, Firebase files, audit reports, etc.) were left untouched.

---

## 3. MapTiler configuration details

- **Credential source:** `MAPTILER_API_KEY` in `local.properties`, or `MAPTILER_API_KEY` environment variable for CI. Gradle reads it at configure time and injects it into `BuildConfig.MAPTILER_API_KEY`; it is not stored in any tracked source file.
- **Tile endpoint (OSM-compatible raster XYZ):** `https://api.maptiler.com/maps/streets-v4/256/{z}/{x}/{y}.png?key=<BuildConfig key>`. The key is appended by `XYTileSource` at runtime; no literal key appears in source.
- **Zoom range / tile size:** 0–19, 256 px PNG, matching the existing OSMdroid zoom behavior.
- **Attribution:** `© MapTiler © OpenStreetMap contributors`, displayed with osmdroid's `CopyrightOverlay` on both maps.
- **User agent:** `com.example.nightagent/NightAgentMapTiles/1.0`, used with a `TileSourcePolicy` that disallows bulk/preventive downloads and requires a meaningful user agent. Recommended MapTiler Cloud rule: restrict the key to the `NightAgentMapTiles/1.0` User-Agent substring.
- **Fallback:** `TileSourceFactory.MAPNIK` (the original permitted OSM source). osmdroid's `setOfflineFirst(true)` and disk cache continue to serve already-cached tiles when the device is offline.
- **Failure handling:** A single lightweight tile probe (5 s connect/read timeouts) validates HTTP 200 + image content type. Failures fall back to OSM without throwing. Connectivity is checked on map creation and on lifecycle resume. Probe results are cached for 60 seconds to avoid excess traffic.
- **Log safety:** osmdroid `isDebugMode` and `isDebugMapTileDownloader` are explicitly disabled after `Configuration.load(...)` because osmdroid can log full tile URLs. Application logs contain only tile-source names and coarse status codes; no URL or key is logged.

---

## 4. Security verification

- `git grep` for the supplied key across all tracked files: **not found**.
- `git check-ignore -v local.properties`: ignored by `.gitignore` rule; `git ls-files` confirms it is **not tracked**.
- No hardcoded API key in Kotlin, XML, Gradle (other than the BuildConfig field name), or documentation.
- Generated `BuildConfig.java` lives under `app/build/...` and is not tracked; its value was verified only in redacted form.
- No log statement in the changed code includes `BuildConfig.MAPTILER_API_KEY`, a tile URL, or an exception message that could contain the URL. Probe failures log only the exception class name and HTTP status.
- OSMdroid debug flags that could print tile URLs are forced off after configuration load.
- No commit, push, merge, or branch switch away from the local working tree was performed after preserving the existing uncommitted changes.

---

## 5. Existing functionality preserved

- OSMdroid remains the only map SDK in use; no Google Maps SDK or UI redesign was introduced.
- `MapView` creation, multi-touch zoom/scroll, lifecycle hooks, and zoom levels remain unchanged except for restoring the previous camera state.
- Current-location marker, 400 m safety-zone polygon, POI markers, risk dialog, and both FABs remain intact.
- SafeWalk planned route, actual path, current-location marker, and destination marker remain intact; overlay rebuild now preserves the attribution overlay.
- Location tracking via `LocationProvider` and `JourneyEngine`/`LocationTracker` was not modified.
- Map location, camera zoom/center, and SafeWalk actual path now survive screen navigation using `rememberSaveable` instead of being lost.
- No duplicate map initialization was introduced: `Configuration.load(...)` occurs only in `MapTileSources.initializeOsmdroid(...)`, and `setTileSource(...)` occurs only in `MapTileSources.applyTileSource(...)`. Each screen still creates exactly one `MapView`, as before.

---

## 6. Build and test results

Commands run from the repository root with `--offline --no-daemon`:

| Command | Result |
|---|---|
| `./gradlew :app:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL** — 95 tests, 0 failures (includes the 2 new MapTiler configuration tests) |
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** |

Live endpoint verification (credential supplied only via `local.properties`, value redacted in all output):

- `https://api.maptiler.com/maps/streets-v4/256/0/0/0.png?key=...` → **HTTP 200**, `image/png`, 13,329 bytes, valid 256×256 PNG.

One pre-existing deprecation warning remains in `MapScreen.kt` for `LocalLifecycleOwner` (unrelated to this change). No new errors or warnings were introduced by the MapTiler integration.

---

## 7. Remaining blockers / follow-ups

1. **On-device validation:** The MapTiler tile endpoint and key were verified live over HTTP, and all builds/tests pass. No emulator/device (`adb` was unavailable), so on-screen tile rendering, pinch-zoom, panning, and offline-cache behavior were not exercised visually.
2. **Key restriction:** The MapTiler key should be restricted in MapTiler Cloud to the `NightAgentMapTiles/1.0` User-Agent substring before production release. This is an account-side setting and cannot be verified from the repository.
3. **Attribution links:** osmdroid's `CopyrightOverlay` renders attribution text but not clickable HTML links. Text attribution is displayed; clickable MapTiler/OSM links would require a small custom overlay if strictly required by policy.
4. **Caching policy:** MapTiler's terms may restrict bulk/offline tile caching on certain plans. The fallback uses OSM cached tiles; confirm the account plan permits the app's caching behavior.
5. **Pre-existing issues intentionally untouched:** fine/coarse location permission mismatch, duplicated `onResume`/`onDetach` lifecycle calls, and unused `maps-compose` dependency remain as previously documented; changing them was outside this task's scope.
6. **No Git history changes:** Nothing was committed, pushed, or merged. Review and commit the four tracked source changes plus the local `local.properties` setup according to your normal release process.
