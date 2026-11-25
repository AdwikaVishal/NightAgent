# NightAgent Cross-Module Integration Audit

**Auditor mode:** Read-only. Traced from actual source, manifest, rules, and functions. No assumptions from docs.

## Workflow A — SOS

| Step | Component | Actual call/path | Status | Evidence |
|---|---|---|---|---|
| User activates SOS | `HomeScreen` long-press -> NavGraph `onSOSClick` | `TriggerManager.onTrigger(context, TriggerType.MANUAL)` | **PARTIAL** | `HomeScreen.kt:116`, `NavGraph.kt:99-142` |
| SOSManager receives | `TriggerManager.onTrigger` -> `SOSManager.triggerSOS` | Trigger gates settings/dedupe/active session | **PARTIAL** | `TriggerManager.kt:24-62`, `SOSManager.kt:69-197` |
| EmergencySession created | `SOSEngine.initiateSession`, `EmergencySessionRepository.saveSession` | Local SharedPreferences + Firestore `sos_alerts/{userId}/sessions/{sessionId}` | **PARTIAL** | `SOSEngine.kt:75-87`, `EmergencySessionRepository.kt:59-146`, `firestore.rules:144-169` |
| Location tracking | `LocationProvider.getLocation`, `SOSManager.startLiveTracking` | Firestore `.../location_updates` every 5s | **PARTIAL** | `LocationProvider.kt:14-70`, `SOSManager.kt:488-521`, `EmergencySessionRepository.kt:152-184` |
| Evidence capture | NavGraph starts `EvidenceCaptureService` before trigger | Local files + `EvidenceUploadWorker` on endCapture | **PARTIAL** | `NavGraph.kt:100-133`, `EvidenceCaptureService.kt:112-129`, `EvidenceUploadWorker.kt:27-131` |
| Guardian notification | `SOSManager.notifyGuardians` -> `GuardianRepositoryImpl.requestSOSNotification` | TODO stub; no callable; no FCM fanout | **BROKEN** | `SOSManager.kt:497-542`, `GuardianRepositoryImpl.kt:417-438`, no Android call to `sendEmergencyNotification` |
| FCM delivery | Cloud Function `sendEmergencyNotification` exists | Records `emergency_notifications`; does not send to guardians | **BROKEN** | `functions/src/index.ts:237-261` |
| Guardian device | `MyFirebaseMessagingService` | Handles `voice_message` only | **BROKEN** | `MyFirebaseMessagingService.kt:39-87` |
| Acknowledgment | SOSManager listens for `acknowledgedBy`/ACKNOWLEDGED on session doc | Backend `acknowledgeEmergency` writes `emergency_acknowledgements` only | **BROKEN** | `SOSManager.kt:450-481`, `functions/src/index.ts:263-282` |
| Escalation | `GuardianRepositoryImpl.initiateEscalation` local stub; backend `escalateEmergency` records only | No timer/worker/FCM | **MISSING** | `GuardianRepositoryImpl.kt:498-607`, `functions/src/index.ts:284-301` |
| Resolution | `SOSManager.resolve/cancel` updates engine+repo; notification action | Local/session state updated; side effects stopped | **PARTIAL** | `SOSManager.kt:202-256`, `SOSActionReceiver.kt:20-27` |

**Verdict:** **BROKEN end-to-end.** Local SOS trigger/persistence/SMS are partial; guardian delivery, ack, escalation, and all-trigger evidence capture are not complete.

## Workflow B — Safe Journey

| Step | Component | Actual call/path | Status | Evidence |
|---|---|---|---|---|
| User starts journey | `JourneyScreen` -> `JourneyViewModel.startJourney` -> `JourneyEngine.startJourney` | Firestore `journeys/{journeyId}` create, legacy `safe_walks/{userId}` | **PARTIAL** | `JourneyScreen.kt:144`, `JourneyViewModel.kt:27-40`, `JourneyEngine.kt:81+`, `JourneyRepository.kt:36-58` |
| GPS tracking | `JourneyTrackingService` FGS location | `LocationTracker`, repo saves locations to `journeys/{id}/locations` and legacy safe_walks | **PARTIAL** | `AndroidManifest.xml:137-142`, `JourneyTrackingService.kt:15-20`, `JourneyRepository.kt:82-113` |
| Firestore location writes | `FirestoreJourneyRepository.saveLocation` | Journeys subcollection ok; legacy safe_walks location shape mismatches rule | **PARTIAL/RISK** | `JourneyRepository.kt:91-99`, `firestore.rules:28-33,228-234` |
| Check-ins | Engine schedules WorkManager watchdog; notifications/actions | `CheckInWatchdogWorker.kt`, `JourneyNotificationHelper.kt`, `JourneyActionReceiver.kt` | **PARTIAL** | `JourneyEngine.kt:383-427`, `CheckInWatchdogWorker.kt:9-19`, manifest receivers |
| Deviation detection | `RouteDeviationDetector` + engine | `deviations` subcollection | **PARTIAL** | `RouteDeviationDetector.kt`, `JourneyRepository.kt:140-150` |
| Missed check-in escalation | Watchdog -> engine -> `SOSManager.triggerSOS` | Direct SOSManager call bypasses TriggerManager | **PARTIAL** | `CheckInWatchdogWorker.kt:13`, `JourneyEngine.kt:24` |
| Guardian notification | SOSManager/Guardian repo | Broken as in Workflow A | **BROKEN** | `GuardianRepositoryImpl.kt:417-438` |

**Verdict:** **PARTIAL.** Core journey stack exists; E2E safety escalation and rule/schema alignment not verified.

## Workflow C — Evidence

| Step | Component | Actual call/path | Status | Evidence |
|---|---|---|---|---|
| SOS activation | NavGraph manual SOS | Starts `EvidenceCaptureService` with timestamp sosId | **PARTIAL** | `NavGraph.kt:100-133` |
| Audio/video/photo capture | `EvidenceCaptureService` + `EvidenceManager` + `CameraCaptureManager`/EvidenceScreen | Audio chunks to `Evidence/temp`; photo jpg and video mp4 to `Evidence/{sosId}/...` | **PARTIAL** | `EvidenceCaptureService.kt:112-121`, `EvidenceManager.kt:163-171`, `CameraCaptureManager.kt:89-174` |
| Local storage | App external files dir | Path mismatch/audio temp; photos jpg | **BROKEN for full upload** | `EvidenceUploadWorker.kt:36-47,101-111` |
| WorkManager upload | `EvidenceUploadWorker.enqueue` on service endCapture | Uploads allowed files to `evidence/{userId}/{sosId}/...` | **PARTIAL** | `EvidenceCaptureService.kt:126`, `EvidenceUploadWorker.kt:27-131` |
| Firebase Storage | Storage rules owner-only evidence | Images not allowed by rules; jpg skipped by worker | **BROKEN/PARTIAL** | `storage.rules:101-113`, `EvidenceUploadWorker.kt:101-111` |
| Firestore metadata | `evidence/{sosId}` doc with fileUrls/hashes | Written on successful worker run | **PARTIAL** | `EvidenceUploadWorker.kt:69-83`, `firestore.rules:231-233` |
| Evidence Vault UI | `EvidenceVaultScreen` | Reads `DemoData.evidenceRecords` only | **NOT INTEGRATED** | `EvidenceVaultScreen.kt:15-176` |

**Verdict:** **BROKEN/PARTIAL.** Capture pipeline exists but has path/content-type/linkage gaps; vault is demo-only.

## Workflow D — Guardians

| Step | Component | Actual call/path | Status | Evidence |
|---|---|---|---|---|
| Add guardian | GuardianScreen -> GuardianViewModel -> GuardianRepositoryImpl | Local SharedPreferences only | **PARTIAL** | `GuardianScreen.kt:113-125`, `GuardianViewModel.kt:52-69`, `GuardianRepositoryImpl.kt:30-69` |
| Validate phone | LocalDataSource/utils/model validators | Duplicate normalization implementations | **PARTIAL** | `GuardianLocalDataSource.kt:53-71`, `normalizePhoneNumber.kt:3-13` |
| Persist locally | `guardian_prefs/guardians_list` | SharedPreferences Gson | **PARTIAL** | `GuardianLocalDataSource.kt:14-51` |
| Sync Firestore | None | No guardian collections/rules | **MISSING** | `GuardianRepositoryImpl.kt` no Firestore; `firestore.rules` default deny |
| Register FCM token | `AuthManager.saveFCMToken` -> `users/{uid}.fcmToken` | Device token saved | **PARTIAL** | `AuthManager.kt:45-52`, `MainActivity.kt:83-102` |
| Trigger SOS | SOSManager notifyGuardians | Filters guardians with `userId` Firebase UID; usually none | **BROKEN** | `SOSManager.kt:497-542` |
| Notify guardian | `requestSOSNotification` TODO stub / backend callable unused | No real delivery | **BROKEN** | `GuardianRepositoryImpl.kt:417-438`, `functions/src/index.ts:237-261` |
| Acknowledge SOS | Local invitation status update / backend ack record | No SOS session state update; rules block guardian access | **BROKEN** | `GuardianRepositoryImpl.kt:451-484`, `functions/src/index.ts:263-282`, `SOSManager.kt:450-481` |

**Verdict:** **BROKEN.** Guardian management is local UI; emergency guardian workflow is not end-to-end.

## Workflow E — Voice Messaging

| Step | Component | Actual call/path | Status | Evidence |
|---|---|---|---|---|
| Record voice | `VoiceMessageScreen`/`VoiceRecorder` | Local audio file | **PARTIAL** | `voicemessage/ui/VoiceMessageScreen.kt`, `voicemessage/audio/VoiceRecorder.kt` |
| Upload to Storage | `VoiceMessageFirebase.uploadAudio` / `VoiceUploadWorker` | `voice_messages/{chatId}/{senderId}/{messageId}.m4a` | **BROKEN/RISK** | `VoiceMessageFirebase.kt:159-171`, `VoiceUploadWorker.kt:58-66`, `storage.rules:56-79` require participants list; Android writes string |
| Save Firestore message | `VoiceMessageFirebase.saveMessage` | `chats/{chatId}/messages/{messageId}` | **PARTIAL** | `VoiceMessageFirebase.kt:176+`, `VoiceMessage.kt:66-78`, `firestore.rules:88-130` |
| Generate notification | `queuePushNotification` writes `fcm_requests` | No backend trigger consumer; callable exists unused | **BROKEN** | `VoiceMessageFirebase.kt:347-394`, `functions/src/index.ts:166-221` |
| FCM delivery | `MyFirebaseMessagingService` handles `voice_message` data | Deep-link to `voicechat/{senderId}` | **PARTIAL** | `MyFirebaseMessagingService.kt:39-87`, `MainActivity.kt:166` |
| Receiver deep link / playback | NavGraph `initialRoute` + `voicechat/{otherUserId}` | Signed URL callable intended, but auth bug | **BROKEN/PARTIAL** | `NavGraph.kt:45-51,223-229`, `VoiceMessageViewModel.kt:293-314`, `functions/src/index.ts:106-162` |

**Verdict:** **BROKEN/PARTIAL.** Significant client code exists, but storage/rules/backend contracts prevent reliable E2E.

## Workflow F — Complete Application

| Step | Status | Evidence | Gaps |
|---|---|---|---|
| Launch | **PARTIAL** | Calculator launcher disguise in manifest; MainActivity anonymous auth, permission requests | No onboarding/auth UX. |
| Authentication | **PARTIAL** | `MainActivity.kt:70-81` anonymous Firebase auth | No real login/registration; user identity weak. |
| Dashboard | **PARTIAL** | `HomeScreen.kt` with SOS/fake call/safe walk/share/record | Hardcoded status/activity. |
| Navigate every feature | **PARTIAL** | NavGraph has most routes | Dead/unreachable routes: contacts, sosactive, streaming possibly. |
| Perform operations | **PARTIAL/BROKEN** | Some local operations work in code; many E2E flows broken | Guardian, evidence vault, voice push/playback, SOS guardian delivery. |
| Return/restart/persist state | **PARTIAL** | SOS/Journey have local stores; some Compose states not rememberSaveable | Runtime persistence not verified. |

**Verdict:** **NOT COMPLETE.** The app is an integrated prototype with major broken cross-module workflows.
