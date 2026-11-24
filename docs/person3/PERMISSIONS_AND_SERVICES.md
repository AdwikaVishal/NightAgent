# Person 3 — Permissions & Services

**Status labels used:** `IMPLEMENTED` · `AUTOMATED TEST VERIFIED` · `DEVICE VERIFIED` · `CODE INSPECTED` · `NOT VERIFIED` · `BLOCKED` · `OPTIONAL ENHANCEMENT`. Manifest/service declarations are `CODE INSPECTED`; no Android 10–15 runtime behaviour is `DEVICE VERIFIED`.

## Manifest permissions

- `ACCESS_FINE_LOCATION` - precise GPS tracking.
- `ACCESS_COARSE_LOCATION` - fallback location.
- `ACCESS_BACKGROUND_LOCATION` - declared for background journey tracking on Android 10+; runtime request must be separate and consent-driven.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` - required for journey tracking FGS.
- `POST_NOTIFICATIONS` - check-in/alert/foreground notification visibility on Android 13+.
- `RECEIVE_BOOT_COMPLETED` - active journey recovery after reboot.
- `WAKE_LOCK`, `INTERNET`, `ACCESS_NETWORK_STATE` - already present; used by networking/OSM/Firestore.

## Foreground service

`JourneyTrackingService` (`.journey.JourneyTrackingService`)
- `exported=false`, `foregroundServiceType="location"`
- Starts as foreground with persistent notification (channel `journey_tracking`, low importance).
- START_STICKY; on restart restores active journey from Firestore/runtime store.
- Stops itself when journey no longer ACTIVE.

## Broadcast receivers

- `JourneyActionReceiver` - handles `CHECKIN_SAFE`, `CHECKIN_HELP`, `END_JOURNEY` notification actions.
- `JourneyBootReceiver` - receives `BOOT_COMPLETED`, restarts tracking service if runtime store has active journey.

## Runtime handling

- UI checks/requests fine/coarse location and POST_NOTIFICATIONS before starting.
- Background location is declared but not aggressively requested at startup; Android requires separate user flow/grant for background location. If denied, foreground tracking still works while app/service is active under normal FGS constraints.
- Location permission revocation: `LocationTracker.locationUpdates()` closes with `SecurityException`; the engine logs and surfaces the error but does not auto-cancel the journey or stop the FGS. `CODE INSPECTED`, `NOT VERIFIED`.
- Notification permission denial (Android 13+): notification helpers catch `SecurityException`, so starting a journey is non-fatal, but check-in prompts become invisible. `CODE INSPECTED`, `NOT VERIFIED`.
- Background location may be declined; tracking then relies on the location FGS for while-in-use access and is subject to OEM battery restrictions. `CODE INSPECTED`, `NOT VERIFIED`.

## WorkManager watchdog

`DefaultCheckInWorkScheduler` enqueues a unique one-time `CheckInWatchdogWorker` request per check-in with `timeoutMs + 5 s` initial delay, tagged `journey_checkin_<journeyId>` so all watchdogs for a journey can be cancelled. WorkManager is deferrable rather than an exact alarm, so Doze/OEM policies may delay missed-check-in detection. `IMPLEMENTED`, `CODE INSPECTED`, `NOT VERIFIED`.

## Android compatibility notes

- minSdk 24, target/compile 35.
- Android 8+: notification channels created (`CODE INSPECTED`).
- Android 10+: `ACCESS_BACKGROUND_LOCATION` declared and a separate consent flow exists; user can decline and still start a journey (`CODE INSPECTED`, `NOT VERIFIED`).
- Android 12+: `JourneyBootReceiver` starts the location FGS from `BOOT_COMPLETED`; background FGS start restrictions likely block this. The exception is caught/logged, so boot recovery may fail. `BLOCKED`, `NOT VERIFIED`.
- Android 13+: `POST_NOTIFICATIONS` runtime permission requested; denial is non-fatal but hides check-ins (`CODE INSPECTED`, `NOT VERIFIED`).
- Android 14/15: `foregroundServiceType="location"` declared and `startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)` is called on API 29+ from the user-initiated UI start path. Runtime compatibility is `NOT VERIFIED`; OEM battery optimisation behaviour is also `NOT VERIFIED`.
- Process recreation: singleton engine + `SharedPreferences` runtime store + Firestore restore + `START_STICKY` + boot receiver. Restore paths are `CODE INSPECTED` only.

## Verification status

| Area | Status | Evidence / limit |
|---|---|---|
| Manifest permissions, service/receiver declarations | `IMPLEMENTED`, `CODE INSPECTED` | `app/src/main/AndroidManifest.xml` |
| Foreground service + notification actions | `IMPLEMENTED`, `CODE INSPECTED` | `JourneyTrackingService`, `JourneyActionReceiver`, `JourneyNotificationHelper` |
| Runtime permission request flows | `IMPLEMENTED`, `CODE INSPECTED` | `JourneyScreen`/`SafeWalkScreen`; `NOT VERIFIED` on device |
| WorkManager watchdog | `IMPLEMENTED`, `CODE INSPECTED` | Deferrable timing; `NOT VERIFIED` |
| Android 12+ boot FGS restart | `BLOCKED`, `NOT VERIFIED` | Background-start restrictions; no safe minimal fix chosen |
| Background/location FGS on Android 10–15 | `NOT VERIFIED` | Device unauthorized; no emulator. All 16 workflow steps unexecuted |
