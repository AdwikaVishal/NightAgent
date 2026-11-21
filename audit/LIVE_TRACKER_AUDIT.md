# NightAgent — Live Tracker Deep Audit

There are **two distinct tracking mechanisms** in the codebase. They are
implemented independently, write to different Firestore paths, and are not
unified.

---

## Tracking Mechanism A — SOS Live Tracking (in-app)

### What is being tracked
During an SOS session, the user's latitude/longitude/timestamp are written to
Firestore every 5 seconds.

### Entry point / initiation
- `SOSManager.triggerSOS()` → `startLiveTracking(context)` (`sos/SOSManager.kt:90-125`)
- Triggered from: SOS button long-press, StealthDetectionService (3-toggle),
  ShakeDetector, VoiceSOSManager, SafeWalkManager auto-SOS

### How updates are collected
`startLiveTracking` posts a `Runnable` on `Handler(Looper.getMainLooper())`
every 5 s. Each iteration calls `LocationProvider.getLocation()` — a one-shot
`FusedLocationProviderClient.getCurrentLocation` (high accuracy, max age
15 s) — and on success calls `FirestoreManager.updateLocation(lat, lng)`.

### GPS / location
Uses Google Play Services FusedLocationProvider (`PRIORITY_HIGH_ACCURACY`).
`LocationProvider` checks Play Services availability and `ACCESS_FINE_LOCATION`
permission before requesting.

### Location permissions
Manifest declares `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
`FOREGROUND_SERVICE_LOCATION`. `LocationProvider` checks permission at request
time and callbacks `null` if missing. No rationale/rationale-explanation flow;
`MainActivity` requests `ACCESS_FINE_LOCATION` on launch.

### Foreground service
**No dedicated foreground service for SOS tracking.** `EvidenceCaptureService`
(MICROPHONE|LOCATION) is started/stopped around the SOS in `NavGraph`
(onSOSClick), but `startLiveTracking` itself is not bound to a foreground
service. On Android 10+, background location access is heavily restricted;
tracking will likely stop when the app is backgrounded unless
`EvidenceCaptureService` remains alive.

### Background behavior
Tracking loop runs on `MainLooper` handler inside the app process. When the
app is backgrounded or killed, the handler stops. `EvidenceCaptureService` is
`START_NOT_STICKY` and stopped via `SOSManager.onSOSComplete()` after 30 s —
so SOS tracking does NOT persist beyond ~30 s unless the service is kept alive
externally.

### App killed
Tracking stops. No `RECEIVE_BOOT_COMPLETED` handler restarts it. No resumes.

### Battery consumption
Not considered. Polling `getCurrentLocation` every 5 s on high accuracy while
the app is alive is a significant drain; no adaptive interval, no
`LocationRequest` with `setInterval`/`setFastestInterval`, no
`requestLocationUpdates` callback model.

### Persistence
Updates are written only to Firestore (`sos_alerts/{uid}/sessions/{sessionId}/location_updates`).
No local persistence (Room/DB) for offline resumption.

### Map / visualization
**None in-app.** There is no live-tracking map screen inside the Android app.
The only visualization is the detached `live-tracker/index.html` web page.

### Privacy and consent
- Location is sent to Firestore and included in SOS SMS messages.
- No explicit consent dialog beyond the runtime location permission.
- `locationSharing` toggle in Settings (`SafetySettings.locationSharing`) is
  in-memory and not wired to tracking behavior.

### Connectivity loss
Firestore writes fail silently (only `Log.e`). `updateLocation` uses
`add(...)` with `addOnSuccessListener`/`addOnFailureListener` — no retry,
no offline queue (though Firestore persistence is enabled globally with 100 MB
cache). Writes may be cached locally and sync on reconnect if the client is
still alive.

### Duplicate / stale / inaccurate handling
None. Every 5 s write creates a new document; no dedup, no staleness check, no
accuracy filtering (location with poor accuracy is accepted).

### Status
**Partially implemented.** Tracking code exists and writes to Firestore, but
it is not background-resilient, not foreground-serviced, stops on process
death, and has no in-app visualization.

---

## Tracking Mechanism B — Safe Walk (in-app + web)

### What is being tracked
During a Safe Walk, the user's location is written every 5 s to
`safe_walks/{userId}/locations` as `LocationModel(latitude, longitude,
timestamp)`.

### Entry point
`SafeWalkScreen` → `SafeWalkManager.startSafeWalk(context)`.

### Collection
5 s `MainLooper` polling loop (`startMonitoring`) calling
`LocationProvider.getLocation()`, then `SafeWalkRepository.updateLocation(...)`.

### Foreground service
None. Same background limitations as Mechanism A.

### Web viewer
`live-tracker/index.html` (Leaflet map) reads
`safe_walks/{userId}/locations`, ordered by timestamp desc, limit 1, via
`onSnapshot`. Default view NYC; userId parsed from URL path
(`/track/test_user` → `test_user`). Firebase config placeholder is empty — the
viewer will not initialize without real config. Manual refresh every 5 s is an
empty `setInterval` callback.

### Status
**Partially implemented.** Client writes are implemented; the web viewer is a
standalone, unconfigured HTML file not part of the Android build and not
linked from any in-app screen.

---

## Path mismatch (critical gap)

- SOS tracking writes to: `sos_alerts/{uid}/sessions/{sessionId}/location_updates`
- Safe Walk writes to: `safe_walks/{userId}/locations`
- `live-tracker/index.html` reads: `safe_walks/{userId}/locations`

The web viewer reads the **Safe Walk** collection, but there is no in-app
mechanism that lets a guardian view either collection in real time from their
own phone. The viewer and the app are not integrated.

---

## Summary table

| Concern | SOS tracking | Safe Walk tracking | live-tracker/index.html |
|---|---|---|---|
| Data collected | lat/lng/time every 5 s | lat/lng/time every 5 s | reads safe_walks locations |
| Real-time? | Polling (not streaming) | Polling (not streaming) | Firestore onSnapshot (real-time) |
| Initiated by | SOS trigger | Safe Walk start | URL path userId |
| Foreground service | No | No | N/A (web) |
| Survives background | No | No | N/A |
| Survives process death | No | No | N/A |
| Battery considered | No | No | N/A |
| Local persistence | No | No | N/A |
| In-app map | No | Yes (OsmDroid, self only) | Yes (Leaflet, web) |
| Privacy/consent | Permission only | Permission only | None |
| Connectivity loss | Silent fail | Silent fail | Console error |
| Stale/dup handling | None | None | None |
| Status | Partial | Partial | Not integrated |
