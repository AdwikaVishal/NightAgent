# Person 4 Audit — Evidence Capture & Evidence Vault

**Auditor mode:** Read-only. Evidence from direct source inspection and completed subagent audit.  
**Status legend:** COMPLETE / PARTIAL / NOT INTEGRATED / BROKEN / MISSING / BLOCKED.

## Scope Inspected
- Shipping `:app` evidence: `evidence/**`, `sos/EvidenceRecorder.kt`, `sos/EvidenceUploader.kt`, `repository/EvidenceRepository.kt`, `ui/screens/EvidenceVaultScreen.kt`, `MainActivity.kt`, `NavGraph.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`
- Standalone `person4-evidence/**` module: Gradle, manifest, source, tests
- Firebase rules/storage rules for evidence paths

## Executive Finding
Two evidence systems exist. **System A (shipping `:app`) is the only compiled/integrated system** and is incomplete/buggy. **System B (`person4-evidence`) is a stronger security prototype but is NOT included in the shipping build** (`settings.gradle.kts` includes only `:app`).

## Sub-feature Assessment (Shipping :app / System A)

| Sub-feature | Status | Evidence (file:line) | Callers / Execution Path | Gaps / Risks |
|---|---|---|---|---|
| Audio recording | **PARTIAL / BROKEN for upload** | `EvidenceCaptureService.kt:112-121` starts chunked audio via `EvidenceManager`; `EvidenceManager.kt:48-111,163-171` records AAC/M4A to `Evidence/temp/audio_*.m4a` | Manual SOS in `NavGraph.kt:99-133` starts service; service FGS declared `AndroidManifest.xml:129-134` | `EvidenceUploadWorker.kt:36-47` scans only `Evidence/{sosId}`; temp audio path mismatch means chunks likely never uploaded. |
| Video recording | **PARTIAL** | `CameraCaptureManager.kt:119-174` CameraX video to `Evidence/{sosId}/video/*.mp4`; `EvidenceScreen.kt:180-224` UI controls | EvidenceScreen route (`NavGraph.kt:170`) | UI/manual; not automatically tied to all SOS triggers; camera not in FGS. |
| Photo capture | **PARTIAL / BROKEN for upload** | `CameraCaptureManager.kt:89-107,200-204` saves JPG to `Evidence/{sosId}/photo/*.jpg`; `EvidenceScreen.kt:159-170` button | EvidenceScreen | `EvidenceUploadWorker.kt:101-111,51-54` allowlist excludes jpg; photos skipped. |
| EvidenceCaptureService | **PARTIAL** | LifecycleService FGS mic|location (`EvidenceCaptureService.kt:24-35,75-110`, manifest `:129-134`); `start/stop` helpers (`:161-200`); `endCapture` enqueues upload (`:123-129`) | NavGraph starts/stops on manual SOS; SOSManager.stopSideEffects stops (`SOSManager.kt:388-401`) | Not started for non-manual triggers; onDestroy stops audio but does not enqueue upload (`EvidenceCaptureService.kt:67-71`). |
| EvidenceRecorder (sos) | **PARTIAL / DISCONNECTED** | `sos/EvidenceRecorder.kt:40-159` manual M4A recording to `Evidence/evidence_ts.m4a` | `HomeScreen.kt:243-289` manual Record/Stop | Separate from SOS evidence pipeline; no upload/metadata/sosId linkage. |
| EvidenceRepository (app/repository) | **NOT INTEGRATED / DEAD** | `repository/EvidenceRepository.kt:14-41` uploads to `evidence/{userId}/{timestamp}.mp4` and updates `sos_logs/{sosId}.evidenceUrl` | No callers found | Schema diverges from EvidenceUploadWorker and evidence rules/paths. |
| EvidenceUploadWorker | **PARTIAL / BUGGY** | `EvidenceUploadWorker.kt:27-131` WorkManager, CONNECTED, exponential backoff, retry <3, unique work per sosId, SHA-256 hashes, writes Firestore `evidence/{sosId}` | `EvidenceCaptureService.kt:126` enqueues on endCapture | JPG excluded; scans wrong dir for audio; unique REPLACE can overwrite; no per-file idempotent manifest. |
| Storage paths | **BROKEN / INCONSISTENT** | Audio temp `Evidence/temp`, photos/videos `Evidence/{sosId}/...`, worker scans `Evidence/{sosId}` | Capture and upload code | Mismatch causes missing uploads. |
| Upload metadata / Firestore evidence record | **PARTIAL** | Worker writes `evidence/{sosId}` with `userId,sosId,timestamp,fileUrls,hashes` (`EvidenceUploadWorker.kt:69-83`); `EvidenceManager.buildMetadata()` exists but unused (`EvidenceManager.kt:115-142`) | Worker only | No rich metadata schema; no verification/read model. |
| Encryption | **MISSING (System A)** | No evidence-file encryption in app evidence code; files are plain M4A/JPG/MP4 | N/A | Security gap; `security-crypto` dep exists but not used for evidence. |
| File integrity | **PARTIAL** | SHA-256 computed and stored in Firestore by worker (`EvidenceUploadWorker.kt:63,74`, `EvidenceManager.kt:146-156`) | Upload worker | No verify-on-read/signature/tamper manifest. |
| SOS-to-evidence linkage | **PARTIAL** | Manual NavGraph SOS generates sosId, starts EvidenceCaptureService, stops on completion (`NavGraph.kt:100-133`); SOSManager stops on side effects (`SOSManager.kt:388-401`) | Manual SOS | sosId not EmergencySession.id; other SOS triggers don't start capture. |
| Firestore evidence records | **PARTIAL** | Worker writes `evidence/{sosId}`; rules include `match /evidence/{evidenceId}` (`firestore.rules:231-233`) | Worker | End-to-end upload/read not verified. |
| EvidenceVaultScreen | **NOT INTEGRATED (demo UI)** | `ui/screens/EvidenceVaultScreen.kt:15-176` explicitly UI-only, uses `DemoData.evidenceRecords`; share intent real | NavGraph route `vault` (`NavGraph.kt:205`), Settings link | No real Firestore/Storage/Room read, no ViewModel, no real record status. |
| EvidenceViewModel | **MISSING** | No EvidenceVault ViewModel; EvidenceScreen uses local Compose state | N/A | Needed for real vault. |
| Evidence retention | **MISSING** | No retention/expiry/delete policy in A evidence code | N/A | Storage growth/privacy risk. |
| Upload status | **PARTIAL** | `EvidenceScreen.kt:396-414` observes WorkManager by tag `evidence_upload` | EvidenceScreen | Tag-based, not sosId-specific; vault has no real status. |
| Background recording restrictions | **PARTIAL** | FGS mic|location declared; camera in UI; Android 14+ caveats partially noted | Service start | Runtime compliance not verified; background camera capture not supported. |
| Permissions | **PARTIAL** | Manifest declares camera/mic/location/FGS/media/read media/audio (`AndroidManifest.xml:5-27`); MainActivity requests some runtime perms (`MainActivity.kt:196-212`) | App startup | Background location/post-notification denial UX incomplete. |
| Person4-evidence integration | **NOT INTEGRATED** | `person4-evidence/` is separate Gradle root/module; root `settings.gradle.kts:24` includes only `:app` | None from shipping app | Cannot be reached by shipping `:app`. |

## System B (`person4-evidence`) Summary

| Area | Status | Evidence | Integration |
|---|---|---|---|
| Secure local evidence module prototype | **PARTIAL (standalone)** | Room metadata, NGE1/Keystore encryption, integrity verifier, access controller, retention controller, consent/permission abstractions, cloud upload controller with `NotConfiguredEvidenceUploader`, ~40 unit tests | **Not integrated**. Different appId/package, own Gradle root, no root include. |
| Cloud upload | **NOT INTEGRATED** | `sync/NotConfiguredEvidenceUploader.kt` explicit not-configured default | No Firebase backend wiring. |
| SOS background capture | **BOUNDARY ONLY** | `MockEvidenceCaptureTrigger` refuses background capture; host foreground UI required | Not connected to NightAgent SOS. |

## Duplicate / Canonical Candidates

| System | Files | Used by shipping app | Better for | Canonical now |
|---|---|---:|---|---|
| System A `:app` evidence | `app/src/main/java/com/example/nightagent/evidence/*`, `sos/EvidenceRecorder*`, `repository/EvidenceRepository.kt`, `ui/screens/EvidenceVaultScreen.kt` | Yes | Current shipping wiring | **Yes, by default** (but flawed) |
| System B `person4-evidence` | `person4-evidence/**` | No | Security/integrity/retention design | **No** (not integrated) |

## Completion

| Dimension | % | Basis |
|---|---:|---|
| Code implementation | 62% | A has capture/upload/vault stubs; B has strong prototype but separate. |
| Application integration | 35% | Only partial manual SOS capture path in A; vault demo; B disconnected. |
| Runtime verification | 10% | No build/runtime/evidence tests executed in audit. |
| **Overall** | **39%** | Evidence is not reliably end-to-end in shipping app. |
