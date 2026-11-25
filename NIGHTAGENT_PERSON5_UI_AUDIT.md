# Person 5 Audit — UI/UX & Android Application

**Auditor mode:** Read-only. Evidence from direct source inspection and completed subagent audit.  
**Status legend:** COMPLETE / PARTIAL / NOT INTEGRATED / BROKEN / MISSING / BLOCKED.

## Scope Inspected
- `MainActivity.kt`, `navigation/NavGraph.kt`, root duplicate `navigation/NavGraph.kt`
- `ui/screens/**`, `ui/components/**`, `ui/demo/DemoData.kt`, `ui/activities/FakeCallActivity.kt`, `ui/stealth/**`, `ui/theme/**`
- `viewmodel/SafetyViewModel.kt`, `streaming/**`, `voicemessage/ui/**`
- `AndroidManifest.xml`, `app/build.gradle.kts`, `settings.gradle.kts`, `app/src/main/res/**`
- Root duplicate `ui/**` directory

## Build Boundary
- `settings.gradle.kts:24-26` includes only `:app`.
- `app/build.gradle.kts` has no custom source sets; compiled UI is `app/src/main/java/**` and `app/src/main/res/**`.
- Root `ui/` and root `navigation/NavGraph.kt` are tracked but **not compiled** into `:app`.

## Screen / Route Assessment

| Screen/Route | Status | Evidence (file:line) | Wiring | Gaps / Risks |
|---|---|---|---|---|
| Splash (`splash`) | **PARTIAL** | `SplashScreen.kt:11-23`; route start destination (`NavGraph.kt:84-95`) | Delays 2s, navigates home | Minimal branding; no auth gate. |
| Login / registration | **MISSING/PARTIAL** | No login screen; `MainActivity.kt:70-81` anonymous auth; `UserRegistrationScreen.kt:31-125` for voice phone registration | Registration route reachable from Settings (`NavGraph.kt:191,209-220`) | No real user auth/profile UX; anonymous auth only. |
| Home / Dashboard (`home`) | **PARTIAL** | `HomeScreen.kt:32-237`; SOS state observed (`:41-46`) | SOS, fake call, safe walk, share location, manual record wired | "All Systems Active" and Recent Activity hardcoded (`:89-110,219-233`); "See All" no-op (`:211-213`). |
| SOS interface | **PARTIAL** | `SOSButton.kt:75-101`; Home shows cancel when active (`HomeScreen.kt:119-146`); `SOSActivatedScreen` exists (`NavGraph.kt:236-257`) | Manual SOS flow in NavGraph (`:99-142`) | `sosactive` route dead; no active SOS global banner/screen; accessibility semantics limited. |
| Guardian management (`guardians`) | **PARTIAL** | `GuardianScreen.kt:37-171` uses ViewModel/repo | Reachable via bottom nav and settings (`NavGraph.kt:160,192`) | Bottom nav label "Contacts" routes to guardians (`BottomNavBar.kt:30`); no invite/ack/escalation status UI. |
| Contacts (`contacts`) | **NOT INTEGRATED / DEAD ROUTE** | `ContactsScreen.kt:29-315`; route registered (`NavGraph.kt:158`) | No navigate callers found | Unreachable from normal UI; separate legacy contact model. |
| Safe Journey (`journey`) | **PARTIAL** | `JourneyScreen.kt:45-280` | Settings route (`NavGraph.kt:202-204`) | Not runtime-verified; form state not rememberSaveable. |
| Safe Walk monitor (`safewalk`) | **PARTIAL** | `SafeWalkScreen.kt:38-166` | Home and Journey link to it | Depends on Journey engine/location permissions. |
| Evidence capture (`evidence`) | **PARTIAL** | `EvidenceScreen.kt:32-359` | Bottom nav and SOS flow (`NavGraph.kt:170,136-138`) | Static timeline; upload/photo bugs in backend pipeline. |
| Evidence vault (`vault`) | **UI ONLY / NOT INTEGRATED** | `EvidenceVaultScreen.kt:15-176` uses `DemoData` | Settings route (`NavGraph.kt:205`) | Demo records, no real data source. |
| Voice messaging (`voicechat/{otherUserId}`) | **PARTIAL** | `VoiceMessageScreen.kt:44-615` rich UI; ViewModel wiring | Route exists (`NavGraph.kt:223-229`); opened from ContactsScreen, but ContactsScreen unreachable | Effective reachability broken from normal UI. |
| Live audio/video (`victimstream`, `guardianstream`) | **PARTIAL / UNREACHABLE** | `StreamingScreens.kt`, `CallViewModel.kt`, `SignalingRepository.kt` | Routes registered (`NavGraph.kt:172-186`) | No in-app navigate callers found; signaling rules missing. |
| Fake call | **PARTIAL** | `FakeCallActivity.kt:55-388`, manifest declares activity (`AndroidManifest.xml:100-106`) | Home and Safety launch it | Hardcoded "MOM" caller; limited config. |
| Stealth calculator/PIN/lockout | **PARTIAL** | `CalculatorActivity.kt:36-304` launcher; `StealthSettingsScreen.kt`/`StealthViewModel.kt`; manifest launcher (`AndroidManifest.xml:60-70`) | Stealth settings route; service start/stop | PIN/lockout behavior depends on StealthRepository; not fully audited E2E. |
| Settings (`settings`) | **PARTIAL** | `SettingsScreen.kt:21-344` | Bottom nav | Some toggles lack clear enforcement consumers; hardcoded strings. |
| Permissions (`permissions`) | **PARTIAL/GOOD** | `PermissionsScreen.kt:39-234` real permission checks/request launchers | Settings route (`NavGraph.kt:206`) | Not first-run gating; broad startup permission requests in MainActivity. |
| Map (`map`) | **PARTIAL** | `MapScreen.kt:64-418` OSMDroid, permissions, overlay, Overpass POIs | Bottom nav | Hardcoded third-party endpoint; runtime behavior not verified. |

## Cross-cutting UI Findings

| Area | Status | Evidence | Notes |
|---|---|---|---|
| Navigation | **PARTIAL** | `NavGraph.kt:34-258` | Dead routes: `contacts`, `sosactive`, possibly streaming routes. |
| ViewModel integration | **PARTIAL** | Guardian/Journey/Voice/Streaming/Stealth/Safety use ViewModels | Some screens still use local/demo state. |
| Demo data/placeholders | **PRESENT** | `DemoData.kt`, EvidenceVault, Home recent activity, static Evidence timeline, hardcoded statuses | Can mislead stakeholders/users. |
| Empty/no-op handlers | **PRESENT** | Home "See All" `HomeScreen.kt:211-213` | Minor but indicates incomplete UI. |
| State restoration | **MISSING/PARTIAL** | Many forms use `remember` not `rememberSaveable`; good lifecycle handling in map/streaming some places | Rotation/process death can lose form state. |
| Accessibility | **PARTIAL** | Many icons `contentDescription=null`; SOS button lacks semantic long-press hint; ToggleRow has unused icon param | Needs a11y pass. |
| Dead/duplicate UI trees | **PRESENT** | Root `ui/**`, root `navigation/NavGraph.kt`, `ui/screens/ContactsScreen.kt` unreachable | Keep compiled app tree as canonical. |

## Completion

| Dimension | % | Basis |
|---|---:|---|
| Code implementation | 68% | Most screens and components exist. |
| Application integration | 55% | Many screens reachable but several demo/dead/unwired flows remain. |
| Runtime verification | 10% | No UI tests/E2E executed. |
| **Overall** | **49%** | UI shell is broad; product-complete integration is not. |
