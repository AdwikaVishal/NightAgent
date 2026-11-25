# Person 2 Audit — Guardians & Emergency Contacts

**Auditor mode:** Read-only. Evidence from direct source inspection and completed subagent audit.  
**Status legend:** COMPLETE / PARTIAL / NOT INTEGRATED / BROKEN / MISSING / BLOCKED.

## Scope Inspected
- `guardian/**` (domain, data/local, repository impl, presentation, utils)
- `model/Guardian.kt`, `GuardianInvitation.kt`, `GuardianRelationship.kt`
- `ui/screens/GuardianScreen.kt`, `ui/screens/ContactsScreen.kt`, `contacts/ContactManager.kt`
- `repository/EmergencySessionRepository.kt`, `sos/SOSManager.kt`, `notifications/NotificationHelper.kt`
- `firebase/MyFirebaseMessagingService.kt`, `firebase/FirestoreManager.kt`, `MainActivity.kt`, `navigation/NavGraph.kt`
- `AndroidManifest.xml`, `app/build.gradle.kts`, guardian tests, `functions/src/index.ts`, `firestore.rules`, `storage.rules`

## Sub-feature Assessment

| Sub-feature | Status | Evidence (file:line) | Callers / Execution Path | Gaps / Risks |
|---|---|---|---|---|
| Guardian CRUD | **PARTIAL (local-only)** | `GuardianRepositoryImpl.kt:30-69` add validates/max/dedupe/saves local; `:71-109` update; `:111-139` delete; `:141-175` get/set primary; `GuardianLocalDataSource.kt:14-51` SharedPreferences/Gson | `GuardianScreen.kt:40-59,113-170` wires ViewModel; `GuardianViewModel.kt:52-147` CRUD methods | Add returns original input not persisted entity (`GuardianRepositoryImpl.kt:68`); update lacks revalidation; delete-last returns error and ViewModel doesn't clear stale list (`:124-138`, `GuardianViewModel.kt:110-128`). No Firestore sync. |
| Phone validation / normalization | **PARTIAL / inconsistent** | `GuardianLocalDataSource.kt:53-71` normalize/validate; `guardian/utils/normalizePhoneNumber.kt:3-13` duplicate util; `model/Guardian.kt:15-21` duplicate validation extensions | Repo uses utils normalization for dedupe (`GuardianRepositoryImpl.kt:51-56,81-87`) but stores raw trimmed input | Normalization strips only `+91`; canonicalization inconsistent; persisted value may differ from compared value. |
| Local persistence | **PARTIAL** | `GuardianLocalDataSource.kt:14-51` `guardian_prefs/guardians_list`; invitations `guardian_invitations` (`GuardianRepositoryImpl.kt:213-230`); escalation `escalation_state` (`:548-556`) | All guardian repo operations local | No encryption/migration/versioning; SharedPreferences only. |
| Firestore sync | **MISSING** | No Firestore/Firebase imports in `GuardianRepositoryImpl.kt`; no guardian collections/rules in `firestore.rules` (default deny `:299-301`) | None | Guardians/invitations/relationships are device-local only. |
| GuardianViewModel / Repository wiring | **PARTIAL** | `GuardianScreen.kt:40-44` creates repo+VM; `GuardianViewModel.kt:25-342` exposes state/operations | GuardianScreen uses load/add/update/delete/set primary/invite; SOS/ack/escalation/notification methods mostly uncalled by UI | UI doesn't render loading/error/success consistently; add dialog success flow brittle (string match at `GuardianScreen.kt:127-141`). |
| FCM token registration | **PARTIAL** | `AuthManager.kt:45-52` saves `users/{uid}.fcmToken`; `MainActivity.kt:83-102` fetches and saves token; `MyFirebaseMessagingService.kt:91-95` refresh | Device token registered for current user | No guardian account linking (`guardianUids` graph absent); cannot target guardian app user by guardian contact record. |
| SOS notification delivery | **BROKEN / NOT INTEGRATED** | `SOSManager.kt:497-542` filters guardians with linked Firebase UID and calls `requestSOSNotification`; `GuardianRepositoryImpl.kt:417-438` TODO stub returns empty success; backend `sendEmergencyNotification` exists (`functions/src/index.ts:237-261`) | SOSManager.notifyGuardians called on activation/restore (`SOSManager.kt:180,350`) | Android never calls Cloud Function; repo stub cannot deliver; backend expects ownership/auth and only records notification; FCM service has no `sos_alert` handler (`MyFirebaseMessagingService.kt:39-87`). |
| Guardian acknowledgment | **BROKEN** | Local invitation status update `GuardianRepositoryImpl.kt:451-484`; backend `acknowledgeEmergency` (`functions/src/index.ts:263-282`) writes `emergency_acknowledgements`; SOSManager listens on session doc for `acknowledgedBy`/ACKNOWLEDGED (`SOSManager.kt:450-481`) | ViewModel method exists, no UI caller found | Contract mismatch: backend does not update SOS session doc; guardian cannot access owner SOS doc by rules. No end-to-end ack. |
| Escalation logic | **STUBBED / MISSING** | Local escalation steps stored in prefs (`GuardianRepositoryImpl.kt:498-560`); `getEscalationStatus` not real (`:563-581`); backend `escalateEmergency` only records audit (`functions/src/index.ts:284-301`) | ViewModel exposes methods, no UI callers found | No timer/worker/FCM escalation, no guardian progression, no backend orchestration. |
| SMS fallback | **PARTIAL** | SOSManager sends SMS to `ContactManager` contacts (`SOSManager.kt:529-604`); Guardian repo `emergencySMSFallback` TODO (`GuardianRepositoryImpl.kt:583-601`); SEND_SMS permission in manifest/MainActivity (`AndroidManifest.xml:8`, `MainActivity.kt:196-215`) | Actual SOS flow uses ContactManager, not Guardian repo | Guardian-based SMS fallback not implemented. Two contact systems diverge. |
| Notification permissions | **PARTIAL** | POST_NOTIFICATIONS declared (`AndroidManifest.xml:19`); MainActivity requests on T+ (`MainActivity.kt:196-212`) | App launch | No dedicated rationale/denial UX for guardian/SOS notifications. |
| Duplicate implementations | **PRESENT** | Guardian system: `guardian/*`, `GuardianScreen`; legacy contacts: `ContactsScreen.kt`, `contacts/ContactManager.kt`; duplicate normalization/validation/model | SOS SMS uses ContactManager; UI bottom nav "Contacts" routes to Guardians (`BottomNavBar.kt:30`) | Ambiguous user model and split data sources. |
| UI integration | **PARTIAL** | `NavGraph.kt:160` route `guardians`; settings navigates to guardians (`:192`); bottom nav to guardians | Reachable | No invitation inbox/accept/reject/ack/escalation/delivery status UI. ContactsScreen unreachable. |

## Duplicate / Canonical Candidates

| Area | Files | Canonical candidate | Why | Delete risk |
|---|---|---|---|---|
| Emergency contacts | `guardian/*` + `model/Guardian*` vs `contacts/ContactManager.kt` + `ui/screens/ContactsScreen.kt` | **Needs product decision**; Guardian is newer/richer, ContactManager is what SOS SMS actually uses | SOSManager currently sends SMS via `ContactManager.getContacts` (`SOSManager.kt:640`) | High if removing ContactManager before migrating SMS path. |
| Phone normalization | `guardian/utils/normalizePhoneNumber.kt`, `GuardianLocalDataSource.normalizePhoneNumber` | `guardian/utils/normalizePhoneNumber.kt` | Single utility, but currently insufficient | Low-medium; update imports/tests. |

## Completion

| Dimension | % | Basis |
|---|---:|---|
| Code implementation | 58% | Local CRUD/UI/models exist; backend delivery/ack/escalation stubs. |
| Application integration | 25% | Screen reachable but not connected to real guardian delivery/sync. |
| Runtime verification | 15% | Tests shallow/not executed in audit. |
| **Overall** | **36%** | Guardian feature is not end-to-end functional. |
