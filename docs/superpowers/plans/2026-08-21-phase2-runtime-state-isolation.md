# Phase 2 Runtime State Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task with review checkpoints. Steps use checkbox syntax for tracking.

**Goal:** Reduce broad Compose invalidation in player and channel-card hot paths while preserving playback behavior and record evidence against Phase 0.

**Architecture:** Keep PlayerScreen as the coordinator. Move consumer-only flow collection into player hosts, replace modal flags with one nullable sealed modal, and move channel progress time to list owners. Finish with hot-list and compiler/build audits; device playback remains an explicit gate.

**Tech Stack:** Kotlin, Jetpack Compose, lifecycle-aware Flow, JUnit/Truth, Compose compiler reports, Macrobenchmark, graphify.

**Spec:** docs/superpowers/specs/2026-08-21-phase2-runtime-state-isolation-design.md

## Global Constraints

- Preserve preparation, recovery, timeshift, Media3, PiP, focus, Back priority, and event-time input behavior.
- Keep top-level modal calls before the player Box and controls modal calls after PlayerResumePrompt inside the Box.
- Modal state is one nullable PlayerModal; derived queries must not store duplicate flags.
- Cards receive one shared 30-second clock value; cards do not collect the clock.
- Hot lazy items use stable keys and contentType where required.
- Do not blanket-annotate domain/data models.
- Follow red-green-refactor for new pure production functions.
- Run graphify update . after Kotlin changes.
- Do not claim Phase 2 exit without multi-channel live-playback evidence; adb unavailability remains an incomplete gate.

---

### Task 1: Modal state and shared channel progress policy

**Files:**
- Modify: app/src/main/java/com/streamvault/app/ui/screens/player/PlayerModalState.kt
- Test: app/src/test/java/com/streamvault/app/ui/screens/player/PlayerModalStateTest.kt
- Create: app/src/main/java/com/streamvault/app/ui/components/ChannelProgressTicker.kt
- Test: app/src/test/java/com/streamvault/app/ui/components/ChannelProgressTickerTest.kt

**Interfaces:**
- PlayerModal is a sealed interface with TrackSelection(TrackType), VariantSelection, SpeedSelection, AudioVideoOffset, StopPlaybackTimer, IdleStandbyTimer, ProgramHistory, Split, and EpisodePicker.
- PlayerModalState(val active: PlayerModal? = null) exposes derived modal queries plus hasVisibleModal, open(modal), and dismiss().
- ChannelProgressTicker exposes read-only StateFlow<Long> nowMs.
- channelProgressFraction(nowMs, startTimeMs, endTimeMs) returns a clamped Float.

- [ ] Step 1: Add failing tests for modal replacement, track-type retention, derived visibility, dismissal, and empty state.

~~~kotlin
@Test
fun opening_a_modal_replaces_the_previous_modal() {
    val state = PlayerModalState()
        .open(PlayerModal.ProgramHistory)
        .open(PlayerModal.SpeedSelection)

    assertThat(state.showProgramHistory).isFalse()
    assertThat(state.showSpeedSelection).isTrue()
}
~~~

Run:
~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PlayerModalStateTest' --no-daemon --console=plain
~~~
Expected: FAIL because the new API is absent.

- [ ] Step 2: Implement the minimal sealed modal state and derived queries. Do not change PlayerScreen in this task.

- [ ] Step 3: Run the focused modal test again. Expected: PASS.

- [ ] Step 4: Add failing channel-progress tests for before-start, in-progress, after-end, and non-positive duration.

~~~kotlin
@Test
fun progress_is_clamped_to_the_program_interval() {
    assertThat(channelProgressFraction(90L, 100L, 200L)).isEqualTo(0f)
    assertThat(channelProgressFraction(150L, 100L, 200L)).isEqualTo(0.5f)
    assertThat(channelProgressFraction(250L, 100L, 200L)).isEqualTo(1f)
}
~~~

Run the focused ChannelProgressTickerTest. Expected: FAIL because the pure function is absent.

- [ ] Step 5: Implement one 30-second StateFlow ticker and the pure clamped progress function, returning 0f for non-positive duration.

- [ ] Step 6: Run:
~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PlayerModalStateTest' --tests '*ChannelProgressTickerTest' --no-daemon --console=plain
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
~~~
Expected: both commands exit 0.

- [ ] Step 7: Commit:
~~~powershell
git add app/src/main/java/com/streamvault/app/ui/screens/player/PlayerModalState.kt app/src/test/java/com/streamvault/app/ui/screens/player/PlayerModalStateTest.kt app/src/main/java/com/streamvault/app/ui/components/ChannelProgressTicker.kt app/src/test/java/com/streamvault/app/ui/components/ChannelProgressTickerTest.kt
git commit -m "refactor: model phase 2 modal and channel state"
~~~

### Task 2: Migrate PlayerScreen modal transitions

**Files:**
- Modify: app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
- Inspect: app/src/main/java/com/streamvault/app/ui/screens/player/PlayerModalHosts.kt

**Interfaces:** Consume Task 1 modal APIs and preserve all existing callback order, Back priority, focus behavior, auto-hide behavior, predicates, and composition placement.

- [ ] Step 1: Enumerate existing modalState.copy(...) call sites:
~~~powershell
rg -n "modalState\.copy\(" app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
~~~

- [ ] Step 2: Replace modal openings with modalState.open(...), using the matching sealed variant for track, variant, speed, both timers, audio/video offset, program history, split, and episode picker.

- [ ] Step 3: Replace modal-only flag clears with modalState.dismiss(). Preserve ViewModel side effects and ordering, especially audio/video preview dismissal and timer activity notification.

- [ ] Step 4: Run:
~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PlayerModalStateTest' --tests '*PlayerBackNavigationPolicyTest' --tests '*PlayerInputPolicyTest' --no-daemon --console=plain
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
~~~
Expected: exit 0.

- [ ] Step 5: Confirm PlayerTopLevelModalHost is still before preparation and PlayerControlsModalHost is still after PlayerResumePrompt and before diagnostics/live overlays:
~~~powershell
rg -n -C 3 "PlayerTopLevelModalHost|PlayerControlsModalHost|PlayerResumePrompt|DiagnosticsOverlay|EpgOverlay" app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
~~~

- [ ] Step 6: Commit:
~~~powershell
git add app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
git commit -m "refactor: use exclusive player modal state"
~~~

### Task 3: Isolate player state by consumer

**Files:**
- Create: app/src/main/java/com/streamvault/app/ui/screens/player/PlayerLiveOverlayHost.kt
- Create: app/src/main/java/com/streamvault/app/ui/screens/player/PlayerDiagnosticsHost.kt
- Modify: app/src/main/java/com/streamvault/app/ui/screens/player/PlayerControlsOverlayHost.kt
- Modify: app/src/main/java/com/streamvault/app/ui/screens/player/PlayerModalHosts.kt
- Modify: app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt

**Interfaces:**
- PlayerLiveOverlayHost owns live-only lists/categories/EPG data collection and renders the existing live overlay call sites.
- PlayerDiagnosticsHost collects playerStats only while the diagnostics overlay is visible.
- PlayerControlsOverlayHost retains position/duration collection and owns controls-only track, cast, timer, seek-preview, and media-control state.

- [ ] Step 1: Capture the current collection map in the ledger:
~~~powershell
rg -n "collectAsStateWithLifecycle\(\)|playerStats|availableCategories|currentChannelList|recentChannels|upcomingPrograms|availableAudioTracks|availableSubtitleTracks|availableVideoQualities|seekPreview|timeshiftUiState|sleepTimerUiState" app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt app/src/main/java/com/streamvault/app/ui/screens/player/PlayerControlsOverlayHost.kt
~~~

- [ ] Step 2: Move live-only collectors for availableCategories, parentalControlLevel, activeCategoryId, currentChannelList, recentChannels, lastVisitedCategory, upcomingPrograms, and numericChannelInput into PlayerLiveOverlayHost. Keep root visibility flags needed by input, Back, and focus policy; pass those flags into the host.

- [ ] Step 3: Move playerStats into PlayerDiagnosticsHost under the existing visible/non-PiP predicate. Keep root playback/surface/error state at the root.

- [ ] Step 4: Move controls-only track, translation, recording, mute, speed, media-title, cast, seek-preview, timeshift, and sleep-timer collection into the controls subtree where the root does not need the value for surface, lifecycle, or input decisions. Keep currentPosition and duration in PlayerControlsOverlayHost.

- [ ] Step 5: Run:
~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*Player*' --no-daemon --console=plain
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
~~~
Expected: exit 0.

- [ ] Step 6: Verify moved collectors are absent from PlayerScreen and present in consumer hosts; run git diff --check; commit:
~~~powershell
rg -n "val (availableCategories|parentalControlLevel|activeCategoryId|currentChannelList|recentChannels|lastVisitedCategory|upcomingPrograms|numericChannelInput|playerStats) by .*collectAsState" app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt
rg -n "collectAsStateWithLifecycle\(\)" app/src/main/java/com/streamvault/app/ui/screens/player/PlayerLiveOverlayHost.kt app/src/main/java/com/streamvault/app/ui/screens/player/PlayerDiagnosticsHost.kt app/src/main/java/com/streamvault/app/ui/screens/player/PlayerControlsOverlayHost.kt
git diff --check
git add app/src/main/java/com/streamvault/app/ui/screens/player
git commit -m "refactor: isolate player consumer state"
~~~

### Task 4: Consolidate channel clock and audit hot lazy lists

**Files:**
- Modify: app/src/main/java/com/streamvault/app/ui/components/Cards.kt
- Modify: app/src/main/java/com/streamvault/app/ui/components/shell/AppMediaCards.kt
- Modify: app/src/main/java/com/streamvault/app/ui/screens/dashboard/DashboardScreen.kt
- Modify: app/src/main/java/com/streamvault/app/ui/screens/home/HomeScreen.kt
- Modify: app/src/main/java/com/streamvault/app/ui/screens/search/SearchScreen.kt
- Modify: app/src/main/java/com/streamvault/app/ui/screens/epg/EpgGridComponents.kt
- Modify: app/src/main/java/com/streamvault/app/ui/components/CategoryRow.kt
- Modify: affected player/catalog/dialog files found by the audit
- Create: docs/COMPOSE_REDUCTION_PHASE2_LIST_AUDIT.md

**Interfaces:** ChannelCard and LiveChannelRowCard accept nowMs: Long; LiveChannelRowSurface forwards it. Dashboard, Home, and search owners collect ChannelProgressTicker.nowMs once per channel-progress list. The audit document records each reviewed list, key, contentType, status, and notes.

- [ ] Step 1: Prove the precondition:
~~~powershell
rg -n "ChannelProgressTicker|LiveChannelRowTicker|collectAsStateWithLifecycle" app/src/main/java/com/streamvault/app/ui/components/Cards.kt app/src/main/java/com/streamvault/app/ui/components/shell/AppMediaCards.kt
~~~
Expected: both card-owned tickers are found before the migration.

- [ ] Step 2: Remove card-owned ticker objects, add nowMs parameters, and use channelProgressFraction in progress indicators. Add owner-level collection outside items { } in dashboard/Home/search.

- [ ] Step 3: Add stable keys and contentType to the Home channel list, EPG rows, CategoryRow, dashboard shelf, player/dialog dynamic lists, and media shelf/grid lists found by the audit. Preserve existing stable ID keys.

- [ ] Step 4: Write docs/COMPOSE_REDUCTION_PHASE2_LIST_AUDIT.md with a table of every reviewed list and the reason unchanged lists are sufficient.

- [ ] Step 5: Verify:
~~~powershell
rg -n "ChannelProgressTicker|LiveChannelRowTicker|collectAsStateWithLifecycle" app/src/main/java/com/streamvault/app/ui/components/Cards.kt app/src/main/java/com/streamvault/app/ui/components/shell/AppMediaCards.kt
rg -n "items\(|itemsIndexed\(" app/src/main/java/com/streamvault/app/ui/screens/home app/src/main/java/com/streamvault/app/ui/screens/epg app/src/main/java/com/streamvault/app/ui/components app/src/main/java/com/streamvault/app/ui/screens/player -g '*.kt'
~~~
Expected: no ticker collection remains in card files; changed hot lists have stable keys and required contentType.

- [ ] Step 6: Run focused progress tests, compile, git diff --check, then commit:
~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ChannelProgressTickerTest' --no-daemon --console=plain
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
git diff --check
git add app/src/main/java/com/streamvault/app/ui docs/COMPOSE_REDUCTION_PHASE2_LIST_AUDIT.md
git commit -m "perf: consolidate channel progress and audit lazy lists"
~~~

### Task 5: Record evidence and refresh graphify

**Files:**
- Create: docs/COMPOSE_REDUCTION_PHASE2_REPORT.md
- Modify: graphify-out/ through graphify update .
- Inspect: docs/COMPOSE_REDUCTION_BASELINE.md and app/build/reports/compose-compiler/app_debug-composables.txt

**Interfaces:** Produce an evidence report separating implementation, compiler audit, build/test evidence, benchmark evidence, live playback gate, remaining gaps, and exit assessment.

- [ ] Step 1: Run fresh compile, unit-test, assemble, and compiler-report commands:
~~~powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
rg -n -i "unstable|PlayerScreen|PlayerControlsOverlay|ChannelCard|LiveChannelRowCard|MovieCard|SeriesCard|CategoryRow" app/build/reports/compose-compiler/app_debug-composables.txt
~~~
Record exit codes, test counts, and named hotspots against the Phase 0 baseline.

- [ ] Step 2: Run the existing benchmark task discovered from benchmark/build.gradle.kts. Record exact output; an unavailable emulator/provider is an incomplete gate.

- [ ] Step 3: Run graphify update . and verify graphify-out changed for Kotlin sources.

- [ ] Step 4: Write docs/COMPOSE_REDUCTION_PHASE2_REPORT.md with sections Implementation, Compiler audit, Build/test evidence, Benchmark evidence, Live playback gate, Remaining Phase 2 gaps, and Exit assessment. State that Phase 2 is not exit-ready if required live validation cannot run.

- [ ] Step 5: Run git diff --check, git status --short, and git log --oneline -8; review the full diff against the spec.

- [ ] Step 6: Commit:
~~~powershell
git add docs/COMPOSE_REDUCTION_PHASE2_REPORT.md graphify-out
git commit -m "docs: record phase 2 compose evidence"
~~~

## Final handoff

If adb and a stable provider stream become available, run the repository multi-channel protocol and report channel names, screenshot count, interval, unique hashes, media-session state, and sanitized logs. Otherwise report the exact blocker and keep the Phase 2 exit gate open.

