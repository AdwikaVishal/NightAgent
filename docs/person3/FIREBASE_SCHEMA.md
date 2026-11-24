# Person 3 — Firebase Schema

**Status labels used:** `IMPLEMENTED` · `AUTOMATED TEST VERIFIED` · `DEVICE VERIFIED` · `CODE INSPECTED` · `NOT VERIFIED` · `BLOCKED` · `OPTIONAL ENHANCEMENT`. Firestore field paths below are `CODE INSPECTED`; live reads/writes and rules enforcement are `NOT VERIFIED`; deployment is `BLOCKED`.

## Collections

### `journeys/{journeyId}`
| Field | Type | Notes |
|---|---|---|
| journeyId | string | UUID |
| userId | string | Firebase Auth UID owner |
| startLatitude/startLongitude | number | Journey start |
| destinationLatitude/destinationLongitude | number | Destination |
| destinationName | string | User label |
| expectedArrivalTime | number (ms) | ETA deadline |
| actualArrivalTime | number/null | Set on COMPLETED |
| startedAt/completedAt/createdAt/updatedAt | number (ms) | Lifecycle timestamps |
| status | string | IDLE/PREPARING/ACTIVE/COMPLETED/CANCELLED/EMERGENCY |
| isActive | boolean | true only while ACTIVE |
| checkInIntervalMinutes | number | Config |
| checkInResponseTimeoutSeconds | number | Config |
| deviationThresholdMeters | number | Config |
| arrivalGraceMinutes | number | Config |
| routePoints | array<{latitude,longitude}> | Planned route polyline |

### `journeys/{journeyId}/locations/{locationId}`
| Field | Type |
|---|---|
| latitude/longitude | number |
| accuracy | number |
| speedMps/bearing/altitude | number/null |
| timestamp | number (ms) |
| provider | string/null |

### `journeys/{journeyId}/checkins/{checkInId}`
| Field | Type |
|---|---|
| checkInId/journeyId | string |
| scheduledAt/dueAt/respondedAt | number/null |
| status | PENDING/ACKNOWLEDGED/MISSED/HELP_REQUESTED/CANCELLED |
| response | SAFE/HELP/null |
| escalated | boolean |

### `journeys/{journeyId}/deviations/{eventId}`
| Field | Type |
|---|---|
| eventId/journeyId | string |
| detectedAt | number |
| latitude/longitude | number |
| distanceFromRouteMeters | number |
| reason/status | string |
| escalated | boolean |

## Legacy compatibility

- `safe_walks/{userId}` - maintained with `isActive`, `journeyId`, `startTime`, `destinationName`, `expectedArrivalTime`, `status` for existing readers.
- `safe_walks/{userId}/locations/{id}` - dual-written location records with `latitude, longitude, timestamp(ms), accuracy, journeyId` for existing live-tracker.

## Security rules — `IMPLEMENTED` in repo, `BLOCKED` on deployment

The earlier note saying rules were absent from the repo was outdated. `firestore.rules` exists at the repository root and contains owner-only Person 3 rules. It has **not** been deployed in this work; emulator/staging validation is `NOT VERIFIED`.

Key rules (abridged; see `firestore.rules` for the complete file):

```rules
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function signedIn() { return request.auth != null; }
    function isOwner(userId) { return signedIn() && request.auth.uid == userId; }
    function journeyOwner(journeyId) {
      return signedIn()
        && get(/databases/$(database)/documents/journeys/$(journeyId)).data.userId == request.auth.uid;
    }

    match /journeys/{journeyId} {
      allow create: if signedIn()
        && request.resource.data.userId == request.auth.uid
        && request.resource.data.journeyId == journeyId;
      allow read: if signedIn() && resource.data.userId == request.auth.uid;
      allow update: if signedIn()
        && resource.data.userId == request.auth.uid
        && request.resource.data.userId == request.auth.uid;
      allow delete: if signedIn() && resource.data.userId == request.auth.uid;

      match /locations/{locationId} { allow read, create, update, delete: if journeyOwner(journeyId); }
      match /checkins/{checkInId} { allow read, create, update, delete: if journeyOwner(journeyId); }
      match /deviations/{eventId} { allow read, create, update, delete: if journeyOwner(journeyId); }
      match /{subcollection}/{docId} { allow read, write: if journeyOwner(journeyId); }
    }

    match /safe_walks/{userId} {
      allow read, write: if isOwner(userId);
      match /{subcollection}/{docId} { allow read, write: if isOwner(userId); }
    }

    match /{document=**} { allow read, write: if false; }
  }
}
```

### Findings and limits

| Item | Status | Evidence / limit |
|---|---|---|
| Owner-only `journeys` and subcollections | `IMPLEMENTED`, `CODE INSPECTED` | `userId` immutable on update; `journeyId` must match document ID on create |
| Legacy `safe_walks` owner-only | `IMPLEMENTED`, `CODE INSPECTED` | Includes all subcollections |
| Default deny / unauthenticated access | `IMPLEMENTED`, `CODE INSPECTED` | Catch-all rule is `false` |
| Non-Person-3 baseline collections (`sos_alerts`, `sessions`, `location_updates`, `chats`, `evidence`, etc.) | `CODE INSPECTED`, `BLOCKED` | Rules currently allow any signed-in user; must be tightened by owning teams before production |
| Rules emulator / staging validation | `NOT VERIFIED` | No emulator or staging project was used |
| Rules deployment | `BLOCKED` | Requires explicit authorization and confirmation of the target project |

**Exact deployment command (do not run without explicit authorization):**

```
firebase projects:list
firebase use <your-project-id>      # confirm it matches google-services.json / app environment
firebase use                        # confirm active project
firebase deploy --only firestore:rules
```
