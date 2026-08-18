# Player Modal Hosts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints.

**Goal:** Extract the nine player dialog call sites into placement-preserving internal hosts without changing player behavior.

**Architecture:** Add `PlayerModalHosts.kt` with `PlayerTopLevelModalHost` for the two dialogs currently composed before the root player `Box`, and `PlayerControlsModalHost` for the seven dialogs currently composed inside the `Box`. Keep all state, effects, Back/input policy, navigation callbacks, and ViewModel ownership in `PlayerScreen.kt`; move only dialog invocation composition and its direct arguments/callbacks.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt Compose ViewModels, Android Gradle Plugin, existing app unit/instrumentation tests.

## Global Constraints

- Preserve every existing visibility predicate, composition location, source order, callback order, state owner, effect key, Back/focus/input rule, and ViewModel scope.
- MultiView remains visible under its existing `showSplitDialog && currentChannel != null` predicate; do not add a PiP restriction.
- Audio/video offset remains gated by `showAudioVideoOffsetDialog && audioVideoSyncEnabled && castConnectionState != CastConnectionState.CONNECTED`, in addition to the non-PiP host guard.
- Do not introduce Phase 2 modal exclusivity, sealed modal state, state grouping, route contracts, feature modules, or player behavior changes.
- Keep `PlayerModalHosts.kt` internal to `com.streamvault.app.ui.screens.player`.
- Run `graphify update .` only if Kotlin source changes are made.

---

### Task 1: Establish a clean implementation baseline

**Files:**
- Read: `docs/superpowers/specs/2026-08-17-player-modal-host-design.md`
- Read: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt`
- Read: `app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerSystemOverlays.kt`
- Read: `app/src/main/java/com/streamvault/app/ui/screens/multiview/MultiViewPlannerDialog.kt`

**Interfaces:**
- Consumes: the approved modal-host design and current dialog call sites.
- Produces: a verified baseline and a line-by-line call-site map for Tasks 2–3.

- [x] **Step 1: Verify the current working tree and source inventory**

Run:

```powershell
git status --short
rg -n "ProgramHistoryDialog\(|MultiViewPlannerDialog\(|PlayerTrackSelectionDialog\(|ChannelVariantSelectionDialog\(|PlayerSpeedSelectionDialog\(|PlayerSleepTimerDialog\(|PlayerAudioVideoOffsetDialog\(|PlayerEpisodeSelectionDialog\(" app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
```

Expected: only the approved documentation edits are present; exactly nine dialog invocations are found.

- [x] **Step 2: Run the existing app unit-test baseline**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: exit code 0. If the baseline fails, record the existing failure before changing Kotlin and do not attribute it to this extraction.

### Task 2: Create the placement-preserving modal hosts

**Files:**
- Create: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerModalHosts.kt`
- Test: no new production test seam; this is a mechanical composition extraction verified by source mapping and existing Compose/instrumentation coverage.

**Interfaces:**
- Consumes: existing dialog argument types and callbacks from `PlayerScreen.kt`.
- Produces: `internal @Composable fun PlayerTopLevelModalHost(...)` and `internal @Composable fun PlayerControlsModalHost(...)` in the player package.

- [x] **Step 1: Write the failing structural check**

Before creating the file, run:

```powershell
if (Test-Path 'app/src/main/java/com/streamvault/app/ui/screens/player/PlayerModalHosts.kt') { throw 'PlayerModalHosts.kt already exists; inspect before proceeding.' }
```

Expected: the check succeeds because the new host file does not yet exist.

- [x] **Step 2: Add the top-level host with the exact existing predicates and order**

Implement `PlayerTopLevelModalHost` with read-only values and callbacks for:

```kotlin
@Composable
internal fun PlayerTopLevelModalHost(
    isInPictureInPictureMode: Boolean,
    showProgramHistory: Boolean,
    programHistory: List<Program>,
    onDismissProgramHistory: () -> Unit,
    onSelectProgramHistory: (Program) -> Unit,
    showSplitDialog: Boolean,
    currentChannel: Channel?,
    onDismissSplitDialog: () -> Unit,
    onLaunchMultiView: () -> Unit,
)
```

Inside the host, preserve this order and no other wrapper:

1. Compose `ProgramHistoryDialog` only when `!isInPictureInPictureMode && showProgramHistory`.
2. Compose `MultiViewPlannerDialog` only when `showSplitDialog && currentChannel != null`.
3. Obtain `MultiViewViewModel` with `hiltViewModel()` inside that second branch only.

Do not add `!isInPictureInPictureMode` to the MultiView branch.

- [x] **Step 3: Add the in-Box host with the exact existing order**

Implement `PlayerControlsModalHost` with parameters corresponding directly to the current seven invocations: PiP flag, track state/lists, variant state/channel, speed state, both timer states/titles, precomputed audio/video-offset visibility and state, channel-save capability, episode presentation data, and narrow callbacks.

Inside the host, preserve this order and no other wrapper:

1. `PlayerTrackSelectionDialog`
2. `ChannelVariantSelectionDialog`
3. `PlayerSpeedSelectionDialog`
4. stop-playback `PlayerSleepTimerDialog`
5. idle-standby `PlayerSleepTimerDialog`
6. `PlayerAudioVideoOffsetDialog`
7. `PlayerEpisodeSelectionDialog`

Guard all seven with the existing non-PiP condition. Pass audio/video offset `visible` as the complete existing predicate, not merely `showAudioVideoOffsetDialog`.

- [x] **Step 4: Compile the new file before wiring it**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: exit code 0. Fix only imports, parameter types, and syntax needed for the standalone host file; do not alter dialog implementations.

### Task 3: Replace the two contiguous call-site blocks in `PlayerScreen.kt`

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt:346-370`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt:1137-1220`

**Interfaces:**
- Consumes: `PlayerTopLevelModalHost` and `PlayerControlsModalHost` from Task 2.
- Produces: the same player screen behavior with only dialog invocation composition moved.

- [x] **Step 1: Wire `PlayerTopLevelModalHost` at the old top-level position**

Replace only the current program-history/MultiView contiguous block with one call at the same location. Keep callback bodies equivalent:

```kotlin
PlayerTopLevelModalHost(
    isInPictureInPictureMode = isInPictureInPictureMode,
    showProgramHistory = showProgramHistory,
    programHistory = programHistory,
    onDismissProgramHistory = { showProgramHistory = false },
    onSelectProgramHistory = { program ->
        viewModel.playCatchUp(program)
        showProgramHistory = false
    },
    showSplitDialog = showSplitDialog,
    currentChannel = currentChannel,
    onDismissSplitDialog = { showSplitDialog = false },
    onLaunchMultiView = {
        showSplitDialog = false
        viewModel.handOffPlaybackToMultiView()
        onNavigate?.invoke(Routes.MULTI_VIEW)
    }
)
```

Use the actual parameter names from the host implementation; do not move `currentChannel`, state declarations, or navigation policy.

- [x] **Step 2: Wire `PlayerControlsModalHost` at the old in-Box position**

Replace only the current track-through-episode contiguous block with one call after `PlayerResumePrompt` and before diagnostics/live overlays. Preserve the exact callback bodies from the current call sites, including:

- subtitle deactivation before subtitle selection;
- live translation clearing before activation;
- user activity before each timer mutation and flag clear;
- audio/video-offset flag clear before preview dismissal;
- episode picker flag clear before playback.

Compute and pass:

```kotlin
audioVideoOffsetVisible = showAudioVideoOffsetDialog &&
    audioVideoSyncEnabled &&
    castConnectionState != CastConnectionState.CONNECTED
```

Keep `PlayerResumePrompt`, diagnostics, live overlays, `anyOverlayVisible`, Back handling, effects, and focus/input code in `PlayerScreen.kt`.

- [x] **Step 3: Remove only imports made unused by the move**

Retain imports still used elsewhere in `PlayerScreen.kt`; remove only dialog imports no longer referenced there. Do not remove `Routes`, `MultiViewViewModel`, or other imports until compilation proves they are unused and the host owns the corresponding reference.

- [x] **Step 4: Run the focused compile check**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: exit code 0 with no new Kotlin errors.

### Task 4: Verify the mechanical extraction and regression gates

**Files:**
- Inspect: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt`
- Inspect: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerModalHosts.kt`
- Inspect: `docs/superpowers/specs/2026-08-17-player-modal-host-design.md`

**Interfaces:**
- Consumes: the wired hosts from Task 3.
- Produces: evidence that the Phase 1 slice is behavior-preserving and ready for manual/live validation.

- [x] **Step 1: Verify all nine call sites moved exactly once**

Run:

```powershell
rg -n "ProgramHistoryDialog\(|MultiViewPlannerDialog\(|PlayerTrackSelectionDialog\(|ChannelVariantSelectionDialog\(|PlayerSpeedSelectionDialog\(|PlayerSleepTimerDialog\(|PlayerAudioVideoOffsetDialog\(|PlayerEpisodeSelectionDialog\(" app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt app/src/main/java/com/streamvault/app/ui/screens/player/PlayerModalHosts.kt
```

Expected: every dialog invocation appears in `PlayerModalHosts.kt` and no dialog invocation remains in `PlayerScreen.kt`.

- [x] **Step 2: Verify source constraints**

Run:

```powershell
rg -n "PlayerViewModel|NavController|BackHandler|LaunchedEffect|remember\(|PlayerResumePrompt|DiagnosticsOverlay|ChannelInfoOverlay|EpgOverlay" app/src/main/java/com/streamvault/app/ui/screens/player/PlayerModalHosts.kt
```

Expected: no forbidden host-owned state/effect/input/navigation references; only the dialog-specific implementation references are outside the host.

- [x] **Step 3: Run scoped automated checks**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:lintDebug --no-daemon --console=plain
git diff --check
```

Result: `compileDebugKotlin`, `testDebugUnitTest`, and `git diff --check` passed. `lintDebug` originally failed on 57 unfiltered repository findings: dependency/version freshness checks, missing translations, Compose resource-access checks, unused resources, KTX/recycle suggestions, and one `ModifierParameter` finding in the earlier `PlayerControlsOverlayHost.kt` extraction. The `ModifierParameter` issue was corrected by moving `modifier` before the first optional parameter; a fresh `compileDebugKotlin` passed and a fresh `lintDebug` now fails only on the remaining 56 unrelated repository findings. No finding names the new `PlayerModalHosts.kt` or the modified `PlayerScreen.kt`; this lint baseline remains a repository gate, not a finding in the modal-host slice.

- [x] **Step 4: Refresh the graph after Kotlin changes**

Run:

```powershell
graphify update .
```

Expected: graphify reports the changed Kotlin files and updates `graphify-out/` without modifying unrelated source.

- [ ] **Step 5: Run required manual and live validation before declaring Phase 1 slice complete**

Use the repository protocol from the design spec: TV smoke coverage for all applicable dialogs, PiP, Back/focus, controls auto-hide, and cast gating; then validate at least two live channels with 2-second screenshots for at least 45 captures (prefer 61), changing screenshot hashes, media-session `PLAYING`/`error=null`, and sanitized logs showing no fatal/stuck/fallback regressions.

If an emulator, provider, or stream prevents a gate from running, record the exact limitation and leave the gate incomplete.

Validation evidence (2026-08-17, `emulator-5554`, `com.streamvault.app.debug`, APK installed from the current workspace):

- TV smoke path reached seeded Live TV data and full playback using keyboard focus; no provider credentials were required. CBSN New York and F1 Channel both opened through the seeded preview-to-full-playback flow.
- CBSN New York: 61 screenshots at a 2-second cadence (`C:\Temp\streamvault_live_validation_cbsn`), 61 unique SHA-256 hashes, visible frame progression, media session `PLAYING` with `error=null`, metadata `US CBSN New York (D)`. Logs showed HLS prepare and first-frame success; no player fatal/stuck/recovery/fallback diagnostics. Two unrelated historical Bluetooth service `state=ERROR` lines were excluded from player findings.
- F1 Channel: 61 screenshots at a 2-second cadence (`C:\Temp\streamvault_live_validation_f1`), 61 unique SHA-256 hashes, visible frame progression, media session `PLAYING` with `error=null`, metadata `F1 Channel`. Logs showed HLS prepare and first-frame success; no player fatal/stuck/recovery/fallback diagnostics.
- Additional smoke evidence: the player controls opened on the TV emulator, Back dismissed the variant dialog, and the extracted subtitle and video-quality dialogs rendered (`Select Subtitles` and `Video Quality`). `PlayerOverlayGoldenTest` passed all 6 tests on the same emulator. `PlayerSmokeTest` executed but failed 3 existing overlay/focus assertions (play-button focus lookup, category-rail search focus lookup, and audio-track selection); no failure names `PlayerScreen` or `PlayerModalHosts`, and the class does not compose either host. Full manual coverage of every timer/speed/episode/PiP/cast scenario remains incomplete, so Step 5 stays open and Phase 1 is not being marked fully accepted.
