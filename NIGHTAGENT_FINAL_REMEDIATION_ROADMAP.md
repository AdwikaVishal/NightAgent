# NightAgent Final Remediation Roadmap

**Mode:** Read-only audit output. Prioritized by safety and integration impact.  
**Constraint:** No code changes were made during audit.

## P0 — Safety-Critical Broken Functionality

| ID | Task | Responsible | Files | Dependencies | Broken now | Implementation needed | Acceptance criteria | Test plan | Merge blocker |
|---|---|---|---|---|---|---|---|---:|---:|
| P0-1 | Fix Cloud Functions callable auth bug | Person 6 | `functions/src/index.ts`, `functions/lib/*`, tests | Node/tsc toolchain | `verifyCaller(request.auth)` checks `context.auth`, likely rejecting all authenticated callables | Accept v2 `CallableRequest`/`request.auth.uid`; use `HttpsError`; add auth unit tests | All callables accept valid Firebase auth and reject unauthenticated with proper code | Functions unit tests + emulator callable smoke tests | Yes |
| P0-2 | Wire SOS guardian notification end-to-end | Person 1/2/6 | `sos/SOSManager.kt`, `guardian/repository/GuardianRepositoryImpl.kt`, `guardian/domain/repository/GuardianRepository.kt`, `functions/src/index.ts`, `firestore.rules` | P0-1, guardian identity model | Guardian push never sent; repo TODO stub; backend records only | Define guardian link model (guardian user UID), call backend callable from SOS, fanout FCM to linked guardians, persist delivery/ack status | SOS trigger creates backend-confirmed guardian notifications; guardian receives FCM; status visible in app/logs | Emulator test + two-device/manual FCM test | Yes |
| P0-3 | Fix guardian acknowledgment/escalation contract | Person 1/2/6 | `SOSManager.kt`, `GuardianRepositoryImpl.kt`, `functions/src/index.ts`, `firestore.rules` | P0-2 | Ack callable writes separate collection; SOSManager listens on session doc; guardians blocked by rules | Standardize ack/escalation schema; update session state via trusted backend; allow authorized guardian reads/acks | Guardian ack moves session to ACKNOWLEDGED; escalation triggers on timeout; UI reflects state | Emulator + integration test | Yes |
| P0-4 | Make evidence capture reliable for SOS | Person 1/4 | `navigation/NavGraph.kt`, `sos/SOSManager.kt`, `sos/TriggerManager.kt`, `evidence/EvidenceCaptureService.kt`, `EvidenceUploadWorker.kt`, `EvidenceManager.kt` | Android FGS rules | Capture started only on manual home SOS; audio temp path mismatch; JPG skipped by upload allowlist | Start/stop evidence through single SOS lifecycle hook; fix storage paths; support jpg/png/jpeg; link evidence to EmergencySession.id | Every SOS trigger produces local evidence records and successful uploads when online | Unit tests for worker path/content type + device SOS smoke test | Yes |
| P0-5 | Add SOS background reliability strategy | Person 1 | `SOSManager.kt`, `AndroidManifest.xml`, new/updated service | P0-4 | Tracking/auto-resolve tied to process/Handler | Introduce SOS foreground service or bind to existing FGS responsibly; persist/restore timers; handle process death | Active SOS survives app background/kill (within OS limits) and continues tracking/notification | Device kill/background test | Yes |

## P1 — Major Missing Integrations

| ID | Task | Responsible | Files | Dependencies | Broken now | Implementation needed | Acceptance criteria | Test plan | Merge blocker |
|---|---|---|---|---|---|---|---|---:|---:|
| P1-1 | Align voice messaging backend contracts | Person 6/5 | `VoiceMessageFirebase.kt`, `VoiceUploadWorker.kt`, `storage.rules`, `functions/src/index.ts`, `MyFirebaseMessagingService.kt` | P0-1 | Storage metadata mismatch, no fcm_requests consumer, signed URL broken, cross-user token read blocked | Redesign participant metadata/rules or use backend-issued upload/download; implement one FCM path; fix receiver access | Sender uploads, receiver gets push, receiver plays via authorized URL | Rules tests + emulator + two-device voice test | Yes |
| P1-2 | Fix Firestore rules/index gaps | Person 6 | `firestore.rules`, `firestore.indexes.json`, `firebase.json`, `.firebaserc` | P0-1 | Missing `calls` rules, missing indexes ref, broad legacy rules, safe_walks location shape mismatch | Add calls rules, journey indexes, remove/lock legacy collections, align safe_walks location schema | Rules tests pass; signaling/journey queries work | `tests/rules` emulator run | Yes |
| P1-3 | Consolidate guardian/contact model | Person 2/1/5 | `guardian/*`, `contacts/ContactManager.kt`, `ContactsScreen.kt`, `SOSManager.kt` | P0-2 | Two contact systems; SOS SMS uses ContactManager, Guardian UI separate | Pick canonical model; migrate SMS and UI; support linked guardian accounts | One emergency contact/guardian source used by UI and SOS | Unit + UI tests | Yes |
| P1-4 | Replace Evidence Vault demo with real data | Person 4/5 | `ui/screens/EvidenceVaultScreen.kt`, `evidence/*`, `repository/*` | P0-4 | Vault uses DemoData only | Build EvidenceViewModel/repository querying Firestore/Storage/local metadata | Vault lists real SOS evidence with upload/integrity state | UI + integration tests | Yes |
| P1-5 | Integrate or retire `person4-evidence` module | Person 4 | `person4-evidence/**`, root `settings.gradle.kts`, app build | P0-4 | Strong module not shipped | Decide: port as library module or extract crypto/integrity components into `:app` | Single canonical evidence implementation in shipping app | Build + unit/integration tests | Yes |

## P2 — Functional Improvements

| ID | Task | Responsible | Files | Dependencies | Implementation needed | Acceptance criteria | Test plan | Merge blocker |
|---|---|---|---|---|---|---|---|---:|
| P2-1 | Active SOS UX and navigation | Person 5/1 | `NavGraph.kt`, `HomeScreen.kt`, `SOSActivatedScreen` or new screen | P0-2 | Add active SOS screen/banner with state, cancel/resolve, location/evidence status | User can always see and control active SOS | UI/E2E test | No |
| P2-2 | Permission and onboarding UX | Person 5 | `MainActivity.kt`, `PermissionsScreen.kt`, journey/sos screens | P1-2 | Request permissions contextually; background location rationale; notification denial handling | No surprise permission wall; clear recovery paths | Manual device test | No |
| P2-3 | Auth/user identity | Person 6/5 | `AuthManager.kt`, `MainActivity.kt`, new auth screens | P0-2 | Replace anonymous-only startup with real sign-in/profile and account linking | Guardians/FCM/ownership stable across devices | Auth emulator tests | No |
| P2-4 | Journey E2E hardening | Person 3 | `JourneyEngine.kt`, `JourneyRepository.kt`, rules, watchdog/service | P1-2 | Fix safe_walks schema, verify boot recovery, deviation/check-in behavior | Start/check-in/deviation/missed check-in/SOS escalation pass | Device + WorkManager tests | No |
| P2-5 | Streaming/call signaling rules and wiring | Person 6/5 | `SignalingRepository.kt`, `firestore.rules`, `NavGraph.kt` | P1-2 | Add secure calls rules or disable feature; wire navigation from notifications | Streaming only available when authorized and reachable | Emulator rules + call smoke test | No |

## P3 — Cleanup & Optimization

| ID | Task | Responsible | Files | Dependencies | Implementation needed | Acceptance criteria | Test plan | Merge blocker |
|---|---|---|---|---|---|---|---|---:|
| P3-1 | Remove dead/legacy duplicates | All | `SOSRepository.kt`, `SOSModel.kt`, `sos/EvidenceUploader.kt`, root `ui/`, root `navigation/NavGraph.kt`, legacy scripts/artifacts | P1-3/P1-5 decisions | Delete/archive after migration and confirmation | No dead duplicate code paths | Build/tests green | No |
| P3-2 | Hardcoded strings/a11y/i18n pass | Person 5 | Compose screens/components, `strings.xml` | none | Move strings to resources, add content descriptions/semantics, fix dark mode hardcodes | Accessibility baseline improved; lint warnings reduced | Accessibility scanner/lint | No |
| P3-3 | Repo hygiene and secrets/config | Person 6 | `google-services.json` duplicates, `.gitignore`, docs, generated files | none | Single config, ignore generated artifacts, document envs/project IDs | Clean repo, no config drift | CI checks | No |
| P3-4 | Test suite expansion | All | `app/src/test`, `app/src/androidTest`, `tests/rules`, `functions` tests | P0/P1 fixes | Add SOS/Journey/Evidence/Guardian integration tests, rules emulator CI, UI smoke tests | Critical workflows covered by automated tests | CI green | No |

## Parallelizable Workstreams (non-overlapping files where possible)

| Stream | Owner(s) | Can run in parallel with | Caution |
|---|---|---|---|
| Functions auth + backend contract tests | Person 6 | UI a11y cleanup, evidence worker unit tests, journey detector tests | Coordinate Firestore/rules schema changes |
| Evidence worker/path/content-type fix | Person 4 | Guardian UI cleanup, settings/a11y, functions tests | Avoid SOSManager/NavGraph until SOS lifecycle hook design agreed |
| Guardian data model/rules design doc | Person 2+6 | Evidence vault UI scaffolding, map/settings polish | Final wiring touches SOSManager/GuardianRepositoryImpl later |
| UI dead-route cleanup and strings extraction | Person 5 | Backend tests, evidence module analysis | Avoid NavGraph same-time edits with SOS/Journey wiring |

## Key Blockers Before "Complete"

1. SOS guardian delivery/ack/escalation must be real and tested.
2. Evidence capture/upload must work for all SOS triggers and supported media types.
3. Firebase callable auth/schema mismatches must be fixed and covered by emulator tests.
4. Duplicate/disconnected modules (person4-evidence, contact/guardian split, legacy SOS writers) must be resolved.
5. Build, unit, rules, and device E2E verification must pass.
