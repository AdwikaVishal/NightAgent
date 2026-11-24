# Person 3 — Architecture

**Status labels used:** `IMPLEMENTED` · `AUTOMATED TEST VERIFIED` · `DEVICE VERIFIED` · `CODE INSPECTED` · `NOT VERIFIED` · `BLOCKED` · `OPTIONAL ENHANCEMENT`. No runtime claim in this document is `DEVICE VERIFIED`.

## Components

- **JourneyViewModel** (`journey/JourneyViewModel.kt`) - AndroidViewModel exposing `JourneyUiState`, history, and actions (start/acknowledge/help/complete/cancel). Starts/stops `JourneyTrackingService`.
- **JourneyEngine** (`journey/JourneyEngine.kt`) - singleton coordinator. Owns active journey state, location monitoring, check-in scheduling, deviation/ETA escalation, and SOS integration. Restores active journey from Firestore/runtime store. Accepts an injectable `CoroutineScope` (tests) and `CoroutineDispatcher` (production default `Dispatchers.Default`); public lifecycle methods use `withContext(dispatcher)`. Persistence `Result` failures are logged rather than silently discarded. (`IMPLEMENTED`, `AUTOMATED TEST VERIFIED`, `CODE INSPECTED`)
- **JourneyRepository / FirestoreJourneyRepository** (`journey/JourneyRepository.kt`) - Firestore persistence for journeys, locations, check-ins, deviation events. Dual-writes legacy `safe_walks/{uid}` for live-tracker compatibility.
- **LocationTracker** (`journey/LocationTracker.kt`) - wraps FusedLocationProviderClient. Provides current location and continuous Flow updates with filtering (invalid, stale-by-age, future clock skew, inaccurate, out-of-order, duplicate, implausible jump). One-shot fixes use `CurrentLocationRequest.setMaxUpdateAgeMillis(JourneyConfig.maxLocationAgeMs, default 30 s)`. (`IMPLEMENTED`, `AUTOMATED TEST VERIFIED`)
- **RouteDeviationDetector** (`journey/RouteDeviationDetector.kt`) - equirectangular point-to-segment distance against planned route polyline; requires sustained deviation count before escalating.
- **RouteProvider** (`journey/RouteProvider.kt`) - fetches walking route via OSRM public API and geocodes via Nominatim. Failures degrade gracefully (journey still tracks; deviation detection disabled when no route).
- **JourneyTrackingService** (`journey/JourneyTrackingService.kt`) - foreground service (`location` type) hosting active journey monitoring; shows persistent notification; START_STICKY.
- **JourneyActionReceiver** (`journey/JourneyActionReceiver.kt`) - handles notification actions: I'm Safe, I Need Help, End Journey.
- **CheckInWatchdogWorker** (`journey/CheckInWatchdogWorker.kt`) - WorkManager one-time watchdog for missed check-in recovery after process death.
- **JourneyBootReceiver** (`journey/JourneyBootReceiver.kt`) - attempts to restart tracking service on boot if runtime store has active journey. On Android 12+ background FGS start restrictions likely block this; the exception is caught/logged. (`IMPLEMENTED`, `BLOCKED` on Android 12+, `NOT VERIFIED`)
- **JourneyRuntimeStore** (`journey/JourneyRuntimeStore.kt`) - SharedPreferences persistence for active journey/check-in recovery.
- **JourneyNotificationHelper** (`journey/JourneyNotificationHelper.kt`) - tracking/check-in/alert notification channels and builders.

## State model

`JourneyStatus`: IDLE, PREPARING, ACTIVE, COMPLETED, CANCELLED, EMERGENCY.  
`CheckInStatus`: PENDING, ACKNOWLEDGED, MISSED, HELP_REQUESTED, CANCELLED.  
`JourneyEventType`: MISSED_CHECKIN, ROUTE_DEVIATION, ARRIVAL_DELAY, USER_REQUESTED_HELP.

## Lifecycle

1. User submits journey form in `JourneyScreen`.
2. ViewModel validates permissions, gets current location, fetches route, calls `JourneyEngine.startJourney`.
3. Engine creates Firestore journey, saves start location, stores runtime state, starts location Flow, schedules check-ins, starts arrival monitor.
4. `JourneyTrackingService` started as FGS; displays ongoing notification.
5. Location updates filtered by `LocationTracker`, persisted by repository, evaluated by deviation detector and arrival monitor.
6. Check-ins scheduled by engine coroutine + WorkManager watchdog; notification actions handled by `JourneyActionReceiver`.
7. Escalations call the existing `SOSManager.triggerSOS(context, reason = ...)` through an injected escalator with engine dedupe keys; `SOSManager` retains a backward-compatible signature for all other callers. Reason appears in logs/SMS/notification but not as structured Firestore SOS metadata. (`IMPLEMENTED`, `AUTOMATED TEST VERIFIED` for escalator calls)
8. Completion/cancel/emergency updates Firestore, stops monitoring, cancels watchdogs, clears runtime store, stops service.

## Integration boundaries

- **SOS (Person 1)**: existing `SOSManager` reused; no new SOS implementation. A backward-compatible `reason: String? = null` parameter was added earlier so escalation context is included in SMS/notification/logs. Structured SOS Firestore reason metadata is still a Person 1 coordination item. `JourneyEngine.markEmergency()` only sets status EMERGENCY and is not wired to UI/SOS; do not treat it as an SOS feature. Duplicate GPS acquisition during an SOS + journey overlap is `CODE INSPECTED` and documented as an `OPTIONAL ENHANCEMENT` (bounded 30 s window).
- **Contacts/SMS**: existing ContactManager/SOSManager flows reused through SOS.
- **Maps**: OsmDroid used in SafeWalkScreen and existing MapScreen; route polyline rendered blue, actual path red.
- **Firebase Auth**: `AuthManager.getCurrentUserId()` used for ownership.

## Threading, monitoring and data-flow notes

- `JourneyEngine` launches tracking, arrival and check-in jobs on an injected scope. Production uses `SupervisorJob() + Dispatchers.Default`; tests inject a `TestScope` and cancel monitoring inside `runTest` to avoid the infinite-loop finalization hang found during verification.
- All public lifecycle operations (`startJourney`, `completeJourney`, `cancelJourney`, `markEmergency`) switch to the injected dispatcher, so unit tests exercise the same dispatch path as production.
- Location writes, check-in writes and deviation-event writes use `Result.onFailure { Log.w(...) }`; there is no separate retry queue. Offline writes depend on Firestore's persistent cache.
- `CheckInWatchdogWorker` is a one-time WorkManager request with a `+5 s` delay relative to the check-in timeout. WorkManager is deferrable, not an exact alarm; OEM Doze may delay missed-check-in detection (`CODE INSPECTED`, `NOT VERIFIED`).
- `RouteProvider` uses OSRM `foot` routing and Nominatim geocoding. The OSRM endpoint returned HTTP 200 with a route during inspection; if route fetch fails the journey still tracks, but deviation detection is disabled. No device tile/network verification was performed (`CODE INSPECTED`, `NOT VERIFIED`).

## Verification status summary

| Area | Status | Evidence / limit |
|---|---|---|
| Engine, models, filters, detector, repository contract | `IMPLEMENTED`, `AUTOMATED TEST VERIFIED` | 34/34 unit tests pass after final code changes |
| FGS, receivers, notifications, manifest declarations | `IMPLEMENTED`, `CODE INSPECTED` | No device/emulator run |
| Firestore implementation and owner-only rules file | `IMPLEMENTED`, `CODE INSPECTED` | Rules in repo but `BLOCKED` on deployment; live reads/writes `NOT VERIFIED` |
| Maps / OSRM / Nominatim | `IMPLEMENTED`, `CODE INSPECTED` | HTTP evidence only for OSRM; map/tiles `NOT VERIFIED` on device |
| Android 12+ boot FGS restart | `BLOCKED`, `NOT VERIFIED` | Background FGS start restrictions; no safe minimal fix chosen |
| Runtime permission revocation during active journey | `CODE INSPECTED`, `NOT VERIFIED` | Partial behaviour: flow errors are surfaced; journey is not auto-cancelled |
| Historical journey replay screen | `OPTIONAL ENHANCEMENT` | History APIs exist; live map shows only in-session path |
