# VOD Overlay and Chapter Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the existing VOD playback control card with the approved cinematic, video-first overlay and add Media3 1.11 chapter navigation while preserving all current VOD capabilities and leaving live TV controls unchanged.

**Architecture:** Keep Media3 extraction behind the `:player` `PlayerEngine` boundary. Normalize Media3 `Chapter` metadata into an app-owned `PlayerChapter` state, derive current/previous/next behavior with pure feature-layer policies, and render the new VOD overlay through focused Compose components and exclusive side-sheet state. The existing live overlay remains in place; only the non-live VOD branch is replaced after parity tests pass.

**Tech Stack:** Kotlin, Coroutines `StateFlow`, Media3 1.11.0, Jetpack Compose, Compose for TV, Android instrumentation golden tests, Robolectric/JUnit unit tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-11-vod-overlay-chapters-design.md`

## Global Constraints

- Media3 remains at `1.11.0`; do not introduce a second Media3 version or a new playback stack.
- `:feature:playback` must not import `androidx.media3.*`; Media3 types stay in `:player`.
- Live TV and live-timeshift controls must retain their current behavior and existing goldens.
- VOD controls use semantic app theme colors and existing localization patterns; no parallel hard-coded palette.
- Every new production behavior is developed test-first: observe a failing test, implement the smallest change, then refactor only while green.
- Chapters are source metadata only; do not add provider APIs, chapter editing, chapter downloads, or Cast chapter support.
- Existing VOD capabilities remain reachable: tracks, episodes, external player, speed, A/V sync, timers, Cast, picture-in-picture, mute, aspect ratio, and quality.

---

### Task 1: Add the app-owned chapter model and pure Media3 metadata normalization

**Files:**
- Create: `player/src/main/java/com/streamvault/player/PlayerChapter.kt`
- Create: `player/src/main/java/com/streamvault/player/ChapterMetadataMapper.kt`
- Test: `player/src/test/java/com/streamvault/player/ChapterMetadataMapperTest.kt`

**Interfaces:**
- Produces `data class PlayerChapter(index: Int, title: String, startTimeMs: Long, endTimeMs: Long?)`.
- Produces `internal fun mapChapterMetadata(metadata: Metadata?, windowOffsetMs: Long, durationMs: Long): List<PlayerChapter>`.
- Media3 chapter input is `androidx.media3.extractor.metadata.Chapter`; feature code will consume only `PlayerChapter`.

- [x] **Step 1: Write failing mapper tests.**

  Cover unordered entries, period-to-window offset subtraction, hidden entries, unset/negative starts, missing titles (`Chapter N`), missing ends derived from the next entry or duration, duplicate entries, and duration clamping. Build test entries with `Chapter.Builder` and `Label`/`Cue` values available in Media3 1.11.

- [x] **Step 2: Run the mapper tests and verify the expected missing-symbol failure.**

  Run:

  ```powershell
  .\gradlew.bat :player:testDebugUnitTest --tests com.streamvault.player.ChapterMetadataMapperTest --no-daemon
  ```

  Expected: compilation fails because `PlayerChapter` and `mapChapterMetadata` do not exist.

- [x] **Step 3: Implement the immutable model and mapper.**

  Flatten `Metadata` entries, filter hidden/unset data, subtract `windowOffsetMs`, sort by start, deduplicate by normalized start/title, derive missing ends, clamp to `durationMs` when positive, and assign one-based indexes after filtering. Return an empty list for null metadata or malformed input.

- [x] **Step 4: Run the mapper tests and verify they pass.**

  Re-run the focused command. Expected: all mapper tests pass with no playback-side effects.

- [x] **Step 5: Refactor only while green and commit the isolated task.**

  ```powershell
  git add player/src/main/java/com/streamvault/player/PlayerChapter.kt player/src/main/java/com/streamvault/player/ChapterMetadataMapper.kt player/src/test/java/com/streamvault/player/ChapterMetadataMapperTest.kt
  git commit -m "feat: normalize Media3 VOD chapters"
  ```

### Task 2: Publish chapter state from the player engine

**Files:**
- Modify: `player/src/main/java/com/streamvault/player/PlayerEngine.kt`
- Modify: `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt`
- Modify: player fake/test engines identified by compilation after the interface change
- Test: `player/src/test/java/com/streamvault/player/Media3PlayerEngineChapterTest.kt` or the existing engine test fixture location

**Interfaces:**
- Adds `val chapters: StateFlow<List<PlayerChapter>>` to `PlayerEngine`.
- `Media3PlayerEngine` owns a `MutableStateFlow<List<PlayerChapter>>`, clears it at prepare/stop/release, and updates it from `Tracks` metadata and current timeline.

- [x] **Step 1: Add a failing contract test for initial and reset state.**

  Assert a fake engine exposes an empty chapter list before preparation and after `stop`/`release`. Add a test fixture that supplies a `Tracks` object containing a `Format` with `Metadata` chapter entries and expects normalized chapters.

- [x] **Step 2: Run the focused player tests to verify the missing contract failure.**

  Run:

  ```powershell
  .\gradlew.bat :player:testDebugUnitTest --tests '*Chapter*' --no-daemon
  ```

  Expected: compile failures for the missing `PlayerEngine.chapters` contract and production publication.

- [x] **Step 3: Add the state contract and lifecycle publication.**

  Add the `StateFlow` to the interface and all fakes. In `Media3PlayerEngine`, invoke the mapper from `onTracksChanged` using the selected media-period metadata and `Timeline.Window.positionInFirstPeriodUs`. Refresh after timeline changes, clear stale state before a new media item, and never throw when metadata is absent.

- [x] **Step 4: Run player chapter tests and the existing player suite.**

  ```powershell
  .\gradlew.bat :player:testDebugUnitTest --tests '*Chapter*' --no-daemon
  .\gradlew.bat :player:testDebugUnitTest --no-daemon
  ```

  Expected: chapter tests and all existing player tests pass.

- [x] **Step 5: Commit only the engine contract/publication changes.**

  ```powershell
  git add player/src/main/java/com/streamvault/player/PlayerEngine.kt player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt player/src/test
  git commit -m "feat: expose VOD chapters from player engine"
  ```

### Task 3: Add pure chapter navigation and VOD action policies

**Files:**
- Create: `feature/playback/src/main/java/com/streamvault/feature/playback/player/VodChapterNavigation.kt`
- Create: `feature/playback/src/main/java/com/streamvault/feature/playback/player/VodOverlayState.kt`
- Test: `feature/playback/src/test/java/com/streamvault/feature/playback/player/VodChapterNavigationTest.kt`
- Test: `feature/playback/src/test/java/com/streamvault/feature/playback/player/VodOverlayStateTest.kt`

**Interfaces:**
- Produces `const val CHAPTER_RESTART_THRESHOLD_MS = 3_000L`.
- Produces `fun currentChapter(chapters: List<PlayerChapter>, positionMs: Long): PlayerChapter?`.
- Produces `fun previousChapterTarget(chapters: List<PlayerChapter>, positionMs: Long): Long?`.
- Produces `fun nextChapterTarget(chapters: List<PlayerChapter>, positionMs: Long): Long?`.
- Produces `data class VodOverlayState(... chapters: List<PlayerChapter>, currentChapter: PlayerChapter?, canShowChapterAction: Boolean, canSeekPreviousChapter: Boolean, canSeekNextChapter: Boolean, ...)`.

- [x] **Step 1: Write failing policy tests.**

  Assert current chapter at boundaries, previous-chapter restart when more than three seconds into the current chapter, previous-chapter navigation when within three seconds, first/last chapter disabling, gaps, and empty chapters. Add overlay-action tests for movie versus series episode, missing tracks, cast-connected state, and hidden chapter action.

- [x] **Step 2: Run focused feature tests and verify they fail for missing policies.**

  ```powershell
  .\gradlew.bat :feature:playback:testDebugUnitTest --tests '*VodChapterNavigationTest' --tests '*VodOverlayStateTest' --no-daemon
  ```

- [x] **Step 3: Implement the smallest pure policies.**

  Keep chapter math independent of Compose and player implementations. Use window-relative `startTimeMs`, preserve the three-second threshold, and make action visibility depend on content type, chapter availability, track counts, and Cast state.

- [x] **Step 4: Run focused tests and the existing playback unit suite.**

  Expected: all new policy tests pass and no existing playback tests regress.

- [x] **Step 5: Commit the pure policies.**

  ```powershell
  git add feature/playback/src/main/java/com/streamvault/feature/playback/player/VodChapterNavigation.kt feature/playback/src/main/java/com/streamvault/feature/playback/player/VodOverlayState.kt feature/playback/src/test
  git commit -m "feat: add VOD chapter navigation policy"
  ```

### Task 4: Build the new standalone VOD overlay composition

**Files:**
- Create: `feature/playback/src/main/java/com/streamvault/feature/playback/player/VodPlayerControlsOverlay.kt`
- Create: `feature/playback/src/main/java/com/streamvault/feature/playback/player/VodTimeline.kt`
- Create: `feature/playback/src/main/java/com/streamvault/feature/playback/player/VodTransportControls.kt`
- Create: `feature/playback/src/main/java/com/streamvault/feature/playback/player/VodChapterSheet.kt`
- Create: `feature/playback/src/main/java/com/streamvault/feature/playback/player/VodPlaybackSettingsSheet.kt`
- Test: `feature/playback/src/androidTest/java/com/streamvault/feature/playback/player/VodPlayerControlsOverlayGoldenTest.kt`
- Test fixture/goldens: `feature/playback/src/androidTest/assets/ui-goldens/player_controls_overlay_vod_*.png`

**Interfaces:**
- `VodPlayerControlsOverlay` consumes `VodOverlayState`, playback position/duration, `SeekPreviewState`, focus requesters, and callbacks for every preserved VOD action.
- `VodTimeline` consumes chapters/current chapter and emits position changes plus scrubbing lifecycle.
- `VodChapterSheet` consumes `List<PlayerChapter>` and a selected index and emits seek/dismiss events.
- `VodPlaybackSettingsSheet` consumes action availability/state and emits existing action callbacks.

- [x] **Step 1: Add failing Compose/golden fixtures for the approved visual states.**

  Create deterministic preview state and assertions for no-chapter controls, chapter controls, seeking preview, chapter sheet, settings sheet, and focus on the primary action. Replace the old VOD golden expectation only in the new test fixture; retain live goldens.

- [x] **Step 2: Run the new golden tests and verify missing-composable failures.**

  Run:

  ```powershell
  .\gradlew.bat :feature:playback:connectedDebugAndroidTest --tests '*VodPlayerControlsOverlayGoldenTest' --no-daemon
  ```

  Expected: compilation fails because the new overlay composables do not exist.

- [x] **Step 3: Implement the visual hierarchy.**

  Use a bottom `Brush.verticalGradient` over the video, title/current chapter metadata, elapsed/remaining time, full-width chapter-aware timeline, centered transport, and compact contextual actions. Use semantic theme colors, localized strings, minimum 48 dp targets, explicit focus properties, and no floating VOD card.

- [x] **Step 4: Implement the chapter sheet and settings sheet.**

  Use the existing exclusive modal pattern, dim the video, focus the current chapter/settings row, restore focus to the originating action on dismissal, and delegate detail actions to existing track/speed/timer dialogs.

- [x] **Step 5: Run and review the new golden states at TV and compact widths.**

  Expected: the approved visual direction is visible, no controls clip, focus rings are high contrast, and the chapter list is readable from TV distance.

- [x] **Step 6: Commit the standalone UI slice.**

  ```powershell
  git add feature/playback/src/main/java/com/streamvault/feature/playback/player/Vod*.kt feature/playback/src/androidTest
  git commit -m "feat: add cinematic VOD controls and chapter sheets"
  ```

### Task 5: Wire the new overlay into the player screen and preserve capability parity

**Files:**
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerControlsOverlayHost.kt`
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerScreen.kt`
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerModalState.kt`
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerModalHosts.kt`
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerBackNavigationPolicy.kt`
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerViewModel.kt` or the existing action-support file for chapter seeks
- Test: existing and new `PlayerModalStateTest`, `PlayerBackNavigationPolicyTest`, `PlayerOverlayItemKeysTest`, and chapter wiring tests

**Interfaces:**
- `PlayerControlsOverlayHost` collects `playerEngine.chapters`, derives `VodOverlayState`, and passes callbacks into `VodPlayerControlsOverlay`.
- Add `PlayerModal.ChapterSelection` and `PlayerModal.PlaybackSettings` only if the existing modal state needs explicit ownership beyond the sheet composables.
- Add `PlayerViewModel.seekToPreviousChapter()` and `seekToNextChapter()` wrappers that call the pure targets and existing `seekTo`.

- [x] **Step 1: Write failing wiring tests.**

  Assert chapter modal/back transitions, focus restoration state, previous/next chapter callbacks, and VOD-only gating. Assert live content continues to select the existing live overlay path.

- [x] **Step 2: Run focused feature tests and verify the missing state/callback failures.**

- [x] **Step 3: Wire chapter state and all existing VOD callbacks.**

  Keep `contentType == LIVE` on the existing live branch. For VOD/movie/series episode, replace `PlayerVodInfo` with `VodPlayerControlsOverlay`, pass the existing track, timer, Cast, PiP, aspect ratio, external-player, and episode callbacks, and hide chapter actions while Cast is connected.

- [x] **Step 4: Implement back/focus/auto-hide integration.**

  Ensure opening a sheet suspends auto-hide, Back closes detail then sheet then controls, and changing media clears chapters without leaving stale focus or current-chapter text.

- [x] **Step 5: Run feature playback tests and screenshot tests.**

  ```powershell
  .\gradlew.bat :feature:playback:testDebugUnitTest --no-daemon
  .\gradlew.bat :feature:playback:connectedDebugAndroidTest --tests '*VodPlayerControlsOverlayGoldenTest' --no-daemon
  ```

- [x] **Step 6: Commit the wiring slice.**

  ```powershell
  git add feature/playback/src/main/java/com/streamvault/feature/playback/player feature/playback/src/test feature/playback/src/androidTest
  git commit -m "feat: wire VOD overlay chapters into playback"
  ```

### Task 6: Remove the obsolete VOD overlay path and finish strings/documentation

**Files:**
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/player/overlay/PlayerControlsChrome.kt`
- Modify: `feature/playback/src/main/res/values/playback_player_strings.xml`
- Modify: localized playback string resources where the existing localization workflow requires regenerated entries
- Modify: `docs/upgrade.txt`
- Delete only after parity: the old private `PlayerVodInfo` and VOD-only helper code inside `PlayerControlsChrome.kt`

- [ ] **Step 1: Add failing guardrail coverage for the obsolete VOD path.**

  Assert the live branch still resolves to the existing live controls and that no production VOD composition references `PlayerVodInfo` after the new path is wired.

- [ ] **Step 2: Remove the old VOD branch and add localized copy.**

  Add strings for chapters, chapter counts, previous/next chapter, current chapter, settings groups, and accessibility labels. Remove dead VOD-only layout helpers while preserving shared live helpers.

- [ ] **Step 3: Replace the old VOD golden and run UI verification.**

  Review controls, seeking, chapters, settings, compact, RTL, and live goldens. The old card must no longer be rendered.

- [x] **Step 4: Update `docs/upgrade.txt`.**

  Mark the VOD chapter navigation item `DONE`, describe the new UI entry points, and leave sliding-window preloading and telemetry-driven follow-ups pending.

- [ ] **Step 5: Commit the cleanup/documentation slice.**

  ```powershell
  git add feature/playback/src/main feature/playback/src/test feature/playback/src/androidTest docs/upgrade.txt
  git commit -m "feat: complete VOD overlay and chapter navigation"
  ```

### Task 7: Run graph and full verification

**Files:**
- Modify generated graph output through the `graphify update .` command only.

- [ ] **Step 1: Run targeted verification.**

  ```powershell
  .\gradlew.bat :player:testDebugUnitTest :feature:playback:testDebugUnitTest :app:assembleDebug --no-daemon
  ```

- [ ] **Step 2: Update the project graph after code changes.**

  ```powershell
  graphify update .
  ```

- [ ] **Step 3: Run repository-wide unit tests and inspect the result.**

  ```powershell
  .\gradlew.bat testDebugUnitTest --no-daemon
  ```

  Expected: all repository unit tests pass; configuration-cache warnings are acceptable only if Gradle exits successfully.

- [ ] **Step 4: Run `git diff --check` and verify no unmerged paths.**

  ```powershell
  git diff --check
  git diff --name-only --diff-filter=U
  ```

- [ ] **Step 5: Report the worktree state without merging it.**

  Confirm the implementation remains in `chore/media3-1.11-upgrade`, the original `develop` checkout is untouched, and provide the user the test commands/results and any device-validation gap.
