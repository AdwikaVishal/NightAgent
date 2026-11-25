# NightAgent Duplicate & Dead Code Audit

**Mode:** Read-only. No deletions performed. Recommendations only.

## Summary
The repo contains multiple parallel implementations for SOS, contacts/guardians, evidence, journey/safe walk, Firebase helpers, and UI trees. The shipping `:app` is the only compiled application module; `person4-evidence` and root `ui/`/`navigation/` trees are outside the shipping build.

## Findings

| # | Area | Files / Paths | Actually used? | Better / Canonical candidate | Delete/merge risk | Recommendation |
|---|---|---|---|---|---|---|
| 1 | SOS Firestore write path | `repository/EmergencySessionRepository.kt` vs `firebase/FirestoreManager.kt` vs `repository/SOSRepository.kt` | EmergencySessionRepository used by SOSManager; FirestoreManager legacy; SOSRepository appears unused | **EmergencySessionRepository** | Medium: confirm no hidden callers | Migrate any legacy callers to EmergencySessionRepository, then remove SOSRepository/FirestoreManager. |
| 2 | SOS model | `model/EmergencySession.kt` vs `model/SOSModel.kt` | EmergencySession used; SOSModel used only by SOSRepository | **EmergencySession** | Low-medium | Remove SOSModel with SOSRepository if unused. |
| 3 | Evidence pipeline (shipping) | `evidence/EvidenceCaptureService+EvidenceManager+CameraCaptureManager+EvidenceUploadWorker` vs `sos/EvidenceRecorder.kt` vs `sos/EvidenceUploader.kt` vs `repository/EvidenceRepository.kt` | EvidenceCaptureService/Worker/Screen used; EvidenceRecorder used by HomeScreen manual record; EvidenceUploader/Repository unused | **Consolidate into one evidence service/repo**. For shipping, evolve `evidence/*`; keep manual recorder only if renamed as note recorder | Medium-high | Define single evidence domain model/storage contract; remove dead uploader/repo or merge. |
| 4 | Evidence module (major duplicate) | `app/src/main/java/com/example/nightagent/evidence/*` vs `person4-evidence/**` | person4-evidence not included in root settings; shipping uses app evidence | **person4-evidence has better security design**, but **not integrated** | High | Either port person4-evidence as library module into root build or cherry-pick its crypto/metadata/integrity patterns into `:app`. Do not keep both divergent long-term. |
| 5 | Evidence vault | `ui/screens/EvidenceVaultScreen.kt` (demo) vs `evidence/EvidenceScreen.kt` (capture) vs person4-evidence vault | EvidenceScreen and demo VaultScreen reachable; person4-evidence not compiled | **One real vault backed by real records** | Medium | Replace DemoData vault with real repository-backed vault; keep capture screen separate. |
| 6 | Guardians vs Contacts | `guardian/**` + `ui/screens/GuardianScreen.kt` vs `contacts/ContactManager.kt` + `ui/screens/ContactsScreen.kt` | GuardianScreen reachable; ContactsScreen route dead; ContactManager used by SOSManager SMS | **Need product decision**; Guardian domain richer, ContactManager currently functional for SMS | High | Migrate SOS SMS to guardian contacts or explicitly define two concepts. Remove dead ContactsScreen route or wire it. |
| 7 | Phone normalization/validation | `guardian/utils/normalizePhoneNumber.kt`, `GuardianLocalDataSource.normalizePhoneNumber`, `model/Guardian.isValidPhone` | All present; inconsistent behavior | **Single validator/normalizer utility** | Low | Centralize E.164 normalization/validation and reuse in repo, UI, and SOS. |
| 8 | Journey vs SafeWalk | `journey/**` + `JourneyScreen` vs `sos/SafeWalkManager.kt` + `repository/SafeWalkRepository.kt` + `model/SafeWalkModel.kt` + `SafeWalkScreen` | Journey stack primary; SafeWalkManager is facade; SafeWalkScreen monitors JourneyViewModel | **JourneyEngine/Repository/ViewModel** | Medium | Keep SafeWalkScreen as view over Journey; remove/retire SafeWalkRepository/SafeWalkModel if unused. |
| 9 | NavGraph/UI tree duplicates | `app/src/main/java/com/example/nightagent/navigation/NavGraph.kt` vs root `navigation/NavGraph.kt`; `app/src/main/java/com/example/nightagent/ui/**` vs root `ui/**` | Root files not compiled (`settings.gradle.kts` includes only `:app`) | **App module files** | Low if confirmed unreferenced | Archive/remove root legacy UI/nav files after confirming no tooling depends on them. |
| 10 | Firebase config duplicates | `app/google-services.json` vs `app/src/google-services.json` | Both present; app module may package one depending on build; duplication risky | **Single canonical google-services.json at app/** | Medium | Remove duplicate and document environment/project ownership. |
| 11 | Generated/backup artifacts | `functions/lib/**`, `original_repositoryimpl.kt`, `assemble_out.txt`, `.kotlin/errors/*`, `ps_compile.ps1`, `run_*.bat/ps1`, `build_temp.bat` | Not runtime app features | N/A | Low-medium | Add to .gitignore or move to tooling artifacts if not needed; keep source-of-truth only. |
| 12 | Multiple SOS trigger entry points | `TriggerManager` vs direct `SOSManager.triggerSOS` calls from JourneyEngine/JourneyViewModel | Both used | **TriggerManager as single policy gate** | Medium | Route all SOS triggers through TriggerManager with reason/trigger metadata. |
| 13 | Streaming/calls rules gap | `streaming/SignalingRepository.kt` expects `calls/*` but rules absent | Code exists, backend blocked | **Add Firestore rules + auth model** | Medium | Either implement rules and tests or disable feature. |
| 14 | Voice notification paths | `VoiceMessageFirebase.queuePushNotification` (fcm_requests) vs `sendVoiceNotification` callable vs `MyFirebaseMessagingService` | Client queues fcm_requests; no trigger; callable unused; receiver handles voice_message type | **One backend-owned FCM path** | Medium | Pick trigger or callable; remove dead path. |

## Dead / Unreachable / Placeholder Items

| Item | Evidence | Status | Recommendation |
|---|---|---|---|
| `SOSActivatedScreen` route | `NavGraph.kt:231-257`, no navigate callers | Dead | Wire or remove. |
| `ContactsScreen` route | `NavGraph.kt:158`, no navigate callers | Dead | Wire or remove after product decision. |
| `victimstream`/`guardianstream` nav callers | Routes exist, no in-app navigate callers found | Likely unreachable | Wire from SOS/guardian notifications or hide. |
| `EvidenceVaultScreen` demo data | `EvidenceVaultScreen.kt:15-176` | Placeholder | Replace with real data. |
| Home "All Systems Active"/Recent Activity/See All | `HomeScreen.kt:89-110,211-233` | Placeholder | Wire to real state or remove. |
| `sos/EvidenceUploader.kt` | No callers found | Dead | Remove or integrate. |
| `repository/SOSRepository.kt`, `model/SOSModel.kt` | No callers found | Dead/legacy | Remove after confirmation. |
| Root duplicate UI/nav | Root `ui/`, `navigation/NavGraph.kt` | Not compiled | Remove/archive. |

## No-Deletion Safety Note
Per instructions, nothing was deleted. Several candidates are high-risk because similarly named code is currently used by production flows (e.g., ContactManager used by SOS SMS).
