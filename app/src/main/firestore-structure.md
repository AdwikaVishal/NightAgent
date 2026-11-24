# NightAgent Firestore Data Structure

## Collection: `users`
General user document storing profile and preferences.

**Document ID**: `{userId}`
```json
{
  "displayName": "string",
  "phoneNumber": "string",
  "email": "string",
  "createdAt": "timestamp",
  "fcmToken": "string"
}
```

## Collection: `guardians`
Guardian management data. Each user has their own guardian list.

**Document ID**: `{userId}`
```json
{
  "guardians": [
    {
      "guardianId": "string",         // UUID
      "name": "string",
      "phoneNumber": "string",
      "relationship": "string",       // Mother/Father/Spouse/Child/Sibling/Friend/Other
      "isPrimary": "boolean",
      "status": "string",             // Active/Invited/Removed/Blocked
      "invitationStatus": "string",   // Pending/Accepted/Rejected/Expired
      "invitationSentAt": "timestamp",
      "acknowledgementStatus": "string",  // sent/delivered/acknowledged/responding/resolved/failed
      "lastAcknowledgedAt": "timestamp"
    }
  ],
  "primaryGuardianId": "string",
  "guardianCount": "number"
}
```

## Collection: `sos_alerts`
SOS alert tracking with sessions.

**Document ID**: `{userId}`
```json
{
  "sosAlerts": [
    {
      "alertId": "string",
      "message": "string",
      "startLat": "number",
      "startLng": "number",
      "startTime": "timestamp",
      "status": "string",      // active/ended
      "sessions": [
        {
          "sessionId": "string",
          "userId": "string",
          "primaryGuardianId": "string",
          "allGuardianIds": ["string"],
          "escalationStartedAt": "timestamp",
          "currentStep": "number",
          "totalSteps": "number",
          "acknowledgedGuardianIds": ["string"],
          "smsFallbackTriggered": "boolean",
          "resolvedAt": "timestamp"
        }
      ]
    }
  ]
}
```

## Collection: `sos_sessions`
Individual SOS session data.

**Document ID**: `{sessionId}` (auto-generated under sos_alerts/{alertId}/sessions)
```json
{
  "sessionId": "string",
  "userId": "string",
  "message": "string",
  "startLat": "number",
  "startLng": "number",
  "startTime": "timestamp",
  "currentGuardianId": "string",
  "currentStep": "number",
  "totalSteps": "number",
  "escalationTimeoutMs": "number",
  "status": "string",  // active/acknowledged/responding/resolved/failed
  "guardianAcknowledgements": {
    "guardianId1": "string",  // InvitationStatus value
    "guardianId2": "string"
  },
  "smsFallbackTriggeredAt": "timestamp",
  "resolvedAt": "timestamp"
}
```

## Collection: `invitations`
Guardian invitation tracking.

**Document ID**: `{invitationId}` (auto-generated)
```json
{
  "invitationId": "string",
  "senderUserId": "string",
  "receiverUserId": "string",  // null for public invitations
  "guardianName": "string",
  "phoneNumber": "string",
  "status": "string",        // Pending/Accepted/Rejected/Expired/Sent/Delivered/Acknowledged/Resolved/Failed
  "createdAt": "timestamp",
  "expiresAt": "timestamp",
  "acknowledgementStatus": "string",  // sent/delivered/acknowledged/responding/resolved/failed
  "acknowledgedAt": "timestamp"
}
```

## Collection: `escalation_timers`
Active escalation timers (for real-time tracking).

**Document ID**: `{timerId}` (auto-generated)
```json
{
  "timerId": "string",
  "sessionId": "string",
  "primaryGuardianId": "string",
  "currentGuardianIndex": "number",
  "startTime": "timestamp",
  "timeoutMs": "number",
  "status": "string",  // active/completed/failed
  "smsFallbackTriggered": "boolean"
}
```

---

# Security Rules

## Firebase Firestore Security Rules

```javascript
service cloud.firestore {
  match /databases/{database}/documents {
    
    // ===== Users =====
    match /users/{userId} {
      allow read: if request.auth != null && request.auth.uid == userId;
      allow create, update: if request.auth != null && request.auth.uid == userId;
    }
    
    // ===== Guardians =====
    match /users/{userId}/guardians/{guardianId} {
      // Users can read their own guardians
      allow read: if request.auth != null && request.auth.uid == userId;
      
      // Users can create/update their own guardians
      allow create: if request.auth != null && request.auth.uid == userId;
      allow update: if request.auth != null && request.auth.uid == userId;
      
      // Only the owner can delete guardians (with primary guardian protection)
      allow delete: if request.auth != null && request.auth.uid == userId 
                    && get(/users/$(userId)).data.primaryGuardianId != resource.data.guardianId;
    }
    
    // ===== Invitations =====
    match /invitations/{invitationId} {
      // Anyone can create invitations (sender)
      allow create: if request.auth != null;
      
      // Invitee can read/update their own invitations
      allow read, update: if request.auth != null && 
                          (request.auth.uid == request.resource.data.senderUserId || 
                           request.auth.uid == resource.data.receiverUserId);
      
      // Invitations expire after a certain time (enforced via validation)
      // allow delete: if false;  // Prevent manual deletion, use expiration instead
    }
    
    // ===== SOS Sessions =====
    match /sos_alerts/{alertId}/sessions/{sessionId} {
      // Users can read their own SOS sessions
      allow read: if request.auth != null && request.auth.uid == request.resource.data.userId;
      
      // Users can create SOS sessions
      allow create: if request.auth != null;
      
      // Only user can update their own sessions
      allow update: if request.auth != null && request.auth.uid == request.resource.data.userId;
    }
    
    // ===== Escalation Timers =====
    match /escalation_timers/{timerId} {
      // Only system/backend can write escalation timers
      allow write: if false;
      
      // Users can read active timers related to their sessions
      allow read: if request.auth != null && 
                    request.auth.uid == get(/users/$(request.auth.uid)).data.role != "guardian" 
                    ? request.auth.uid : get(/sos_alerts/$(request.auth.uid)/sessions/$(timerId)).data.userId == request.auth.uid;
    }
  }
}
```

---

# Offline Persistence & Sync

## Local Storage Strategy

1. **SharedPreferences** (Current - Working)
   - Guardian list with primary indicator
   - Invitation statuses (local only)
   - Primary guardian ID

2. **Firestore Offline Capabilities** (Requires Backend)
   - Enable: `FirebaseFirestore.getInstance().setPersistenceEnabled(true)`
   - Caches data for offline use
   - Automatic sync when connectivity restores

3. **Room Database** (Future Enhancement)
   - Cache frequently accessed guardian data
   - Fast local reads without network dependency
   - Sync state with Firestore on connectivity restore

---

# Backend API Endpoints (Proposed)

## 1. POST /api/fcm-token
Register FCM token for a device.

```http
POST /api/fcm-token
Authorization: Bearer {jwt}
{
  "fcmToken": "string"
}
```

## 2. POST /api/sos/trigger
Trigger an SOS alert with guardian notification.

```http
POST /api/sos/trigger
Authorization: Bearer {jwt}
{
  "message": "string",
  "latitude": "number",
  "longitude": "number",
  "primaryGuardianId": "string",
  "allGuardianIds": ["string"]
}
```

Response:
```json
{
  "sessionId": "string",
  "status": "started",
  "escalationTimer": "30000ms"
}
```

## 3. POST /api/sos/{sessionId}/acknowledge
Guardian acknowledges SOS notification.

```http
POST /api/sos/{sessionId}/acknowledge
Authorization: Bearer {jwt}
{
  "acknowledged": "boolean",
  "responseType": "string"  // responding/resolved/cancelled
}
```

## 4. POST /api/sms/fallback
Trigger SMS fallback for a session.

```http
POST /api/sms/fallback
Authorization: Bearer {jwt}
{
  "sessionId": "string",
  "userPhone": "string",
  "guardianIds": ["string"]
}
```

## 5. GET /api/invitations/{userId}
Get pending invitations for a user.

```http
GET /api/invitations/{userId}
Authorization: Bearer {jwt}
```

---

# Unit Tests (Mock-Based)

## Test: GuardianRepositoryImpl Invitation Functions

```kotlin
// Unit test for inviteGuardian
@Test
fun `inviteGuardian should add invitation to preferences`() = runTest {
    // Given
    val repository = mock(GuardianRepository::class.java)
    // ... mock setup
    
    // When
    // repository.inviteGuardian(...)
    
    // Then
    // verify invitation was saved to SharedPreferences
}
```

## Test: InvitationStatus enum

```kotlin
@Test
fun `InvitationStatus fromString should handle all values`() {
    assert(InvitationStatus.fromString("pending") == InvitationStatus.Pending)
    assert(InvitationStatus.fromString("accepted") == InvitationStatus.Accepted)
    assert(InvitationStatus.fromString("rejected") == InvitationStatus.Rejected)
    assert(InvitationStatus.fromString("expired") == InvitationStatus.Expired)
    assert(InvitationStatus.fromString("sent") == InvitationStatus.Sent)
    assert(InvitationStatus.fromString("delivered") == InvitationStatus.Delivered)
    assert(InvitationStatus.fromString("acknowledged") == InvitationStatus.Acknowledged)
    assert(InvitationStatus.fromString("responding") == InvitationStatus.Responding)
    assert(InvitationStatus.fromString("resolved") == InvitationStatus.Resolved)
    assert(InvitationStatus.fromString("failed") == InvitationStatus.Failed)
}
```

## Test: Phone Number Normalization

```kotlin
@Test
fun `normalizePhoneNumber strips prefixes and formats consistently`() {
    assert(normalizePhoneNumber("+91 98765 12345") == "9876512345")
    assert(normalizePhoneNumber("98765 12345") == "9876512345")
    assert(normalizePhoneNumber("(987) 654-3210") == "9876543210")
    assert(normalizePhoneNumber("987-654-3210") == "9876543210")
}
```