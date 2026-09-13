# VOD Sliding-Window Preloading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded Media3 `DefaultPreloadManager` path for non-live VOD/series-episode and catch-up navigation, while preserving direct playback and the existing guarded single-item live preload path.

**Architecture:** Keep Media3 preload details inside `:player` and expose a small `PlayerEngine` preload-window contract to `:feature:playback`. The feature layer selects and resolves adjacent candidates; the player layer creates per-item media sources with the existing custom data-source provider, ranks them with `SimpleRankingDataComparator`, and returns a managed `MediaSource` when playback switches to an item that was preloaded. The manager is recreated together with the player so every returned source is consumed by an `ExoPlayer` created from the same `DefaultPreloadManager.Builder`.

**Tech Stack:** Kotlin, AndroidX Media3 1.11.0 `DefaultPreloadManager`, `SimpleRankingDataComparator`, `TargetPreloadStatusControl`, custom OkHttp-backed `DataSource.Factory`, Media3 `MediaSource`, coroutines, Hilt, JUnit/Truth/Mockito, Robolectric, Android TV device validation.

**Spec:** `docs/upgrade.txt`, the `ADD (P2) - VOD/episode sliding-window preloading` item and the remaining-backlog instruction to design and test VOD-only preloading before replacing the current single-source path.

## Global Constraints

- Scope is this one upgrade item only. Do not implement custom adaptive variant ordering, Media3 Cast migration, explicit playlist progression, or embedded-subtitle recovery.
- Work directly on the current `develop` worktree. Preserve unrelated existing changes, including the current `docs/CHANGELOG.md` edit and the completed FrameExtractor pilot changes.
- The new manager is opt-in by content policy: normal `LIVE` channel playback is never added to the sliding window. The existing `PlayerEngine.preload(StreamInfo?)` behavior remains available for the guarded adjacent-channel zap path and recovery code.
- The initial window is at most three managed items: the current item, one previous item, and one next item. At most two non-current items may cause preload I/O. Do not preload the entire series, category, or EPG history.
- The initial target status for each adjacent item is `DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(0L, 3_000L)`. The current item and all items outside the one-item distance are returned as `null` by the target status control. Do not configure persistent disk caching in this pilot.
- VOD/series-episode and catch-up candidates must be resolved through existing feature/provider code and must retain headers, user-agent, proxy, TLS policy, transport policy, container metadata, DRM metadata, and expiration information. Candidate resolution or preload failure only removes that candidate; it must never fail foreground playback.
- Do not preload a candidate with a blank/invalid URL, an expired `StreamInfo`, `PlayerPreloadContentType.LIVE`, an unknown stream type, or DRM during the initial pilot. DRM stays on the normal direct-playback path because early license acquisition has side effects and a separate cost profile.
- Use the existing `PlayerDataSourceFactoryProvider.createFactory(..., preload = true)` for every preload source. Preload transfers must not attach the foreground transfer listeners used for measured playback bitrate and statistics.
- Media3 preload APIs are `@UnstableApi`; keep the `@OptIn` localized to `:player`. `:feature:playback` must continue to depend only on `PlayerEngine` and player-domain preload value types, never on `androidx.media3` classes.
- All `DefaultPreloadManager` calls, source reconciliation, and manager release happen on the same engine/main thread. A released manager or builder must never be reused.
- Keep URLs and request metadata sanitized in logs. Log only candidate kind/key, resolved stream class, rank/distance, source count, skip/failure category, preload completion, and playback-source reuse.
- Keep direct playback as the fallback whenever a candidate is not managed, has expired, has changed identity, or the manager cannot return a source. The current `PreloadCoordinator` remains responsible for the legacy single-source path.

## Existing Code to Preserve

- `player/src/main/java/com/streamvault/player/playback/PreloadCoordinator.kt` remains the compatibility cache for the existing single-item `preload(StreamInfo?)` API, especially live zapping and recovery.
- `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerZapActions.kt` keeps its current connection-limit/cooldown checks and adjacent live-channel behavior. It must not be silently migrated to the VOD window.
- `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerRecoveryActions.kt` continues to clear preload state through `preload(null)`; the new implementation must make that clear both the legacy cache and the window manager.
- `player/src/main/java/com/streamvault/player/playback/PlayerDataSourceFactoryProvider.kt` remains the source of custom headers, user-agent handling, timeout profiles, proxy/TLS behavior, and foreground-transfer exclusion.
- `player/src/main/java/com/streamvault/player/playback/PlayerMediaSourceFactory.kt` remains the source of HLS, DASH, Smooth Streaming, progressive, MPEG-TS, RTSP, DRM, extractor, retry-policy, and subtitle-format decisions.
- `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerContentResolver.kt` remains the feature boundary for resolving episode/VOD and provider playback metadata.
- `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerEpgCoordinator.kt` and `PlayerGuideTimelineCoordinator.kt` remain responsible for fetching and projecting the EPG timeline used by catch-up selection.
- `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerAutoPlayActions.kt` keeps the existing ten-second next-episode countdown and auto-play preference behavior. Preloading must not auto-start or change that UI flow.

## Implementation Steps

- [x] **Step 1: Define the player-boundary preload-window contract and deterministic window policy.**

  **Files:**

  - Add `player/src/main/java/com/streamvault/player/PlayerPreload.kt`.
  - Modify `player/src/main/java/com/streamvault/player/PlayerEngine.kt`.
  - Add `player/src/test/java/com/streamvault/player/PlayerPreloadWindowPolicyTest.kt`.

  **Changes:**

  - Define the Media3-free value types:

    ```kotlin
    enum class PlayerPreloadContentType { VOD, CATCH_UP, LIVE }

    data class PlayerPreloadItem(
        val key: String,
        val streamInfo: StreamInfo,
        val contentType: PlayerPreloadContentType
    )

    data class PlayerPreloadWindow(
        val items: List<PlayerPreloadItem>,
        val currentIndex: Int
    )
    ```

  - Add default-safe methods to `PlayerEngine` so existing test doubles and auxiliary engines remain source-compatible:

    ```kotlin
    fun preloadWindow(window: PlayerPreloadWindow) {}
    fun clearPreloadWindow() = preload(null)
    ```

  - Add a pure `normalizePlayerPreloadWindow(window, maxManagedItems = 3)` helper. It must trim blank keys/URLs, remove duplicate keys while retaining the first occurrence, reject `LIVE` items, keep the current item when its index is valid, and retain only the closest previous/next entries around the current item.
  - Keep the current index meaningful after filtering. If the requested current item is removed, return an empty window instead of silently treating an adjacent candidate as current.
  - Do not make `PlayerPreloadItem` contain Media3 `MediaItem`, `MediaSource`, `Uri`, or `DataSource` values.

  **Tests:**

  - A three-item `[previous, current, next]` window remains unchanged and indexes current correctly.
  - Four or more candidates are reduced to current plus one item on each side.
  - Duplicate keys retain the first item and the current index is adjusted.
  - Live candidates, blank keys, blank URLs, and an invalid current index produce the documented filtered/empty result.
  - A VOD item and a catch-up item remain eligible even when their underlying Media3 stream class is HLS or DASH.

- [x] **Step 2: Add the Media3 manager session with ranking, target status, reconciliation, and source lookup.**

  **Files:**

  - Add `player/src/main/java/com/streamvault/player/playback/Media3PreloadWindowManager.kt`.
  - Add `player/src/test/java/com/streamvault/player/playback/Media3PreloadWindowManagerTest.kt`.

  **Required production surface:**

  ```kotlin
  @UnstableApi
  internal class Media3PreloadWindowManager(
      private val context: Context,
      private val mediaSourceFactory: PlayerMediaSourceFactory,
      private val dataSourceFactoryProvider: PlayerDataSourceFactoryProvider,
      private val bandwidthMeter: BandwidthMeter
  ) {
      fun createPlayer(
          renderersFactory: RenderersFactory,
          loadControl: LoadControl,
          preloadDataSourceFactory: DataSource.Factory,
          configure: (ExoPlayer.Builder) -> Unit
      ): ExoPlayer

      fun updateWindow(window: PlayerPreloadWindow)
      fun getMediaSource(streamInfo: StreamInfo): MediaSource?
      fun clearWindow()
      fun release()
  }
  ```

  **Changes:**

  - In `createPlayer`, construct one `DefaultPreloadManager.SimpleRankingDataComparator` and one `TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus>`.
  - Return `specifiedRangeLoaded(0L, 3_000L)` only when the ranking index is exactly one position away from the current index. Return `null` for the current item, unset current index, or any item farther away.
  - Build `DefaultPreloadManager.Builder(context, rankingComparator, targetStatusControl)`, call `setDataSourceFactory(preloadDataSourceFactory)`, set the same renderers factory/load control/bandwidth meter used by foreground playback, build the manager, then create the foreground `ExoPlayer` with `builder.buildExoPlayer(ExoPlayer.Builder(...).apply(configure))`.
  - Do not create an independent foreground `ExoPlayer` with `.build()` after a preload manager has been created. The manager-returned sources must be played only by the player created from this builder.
  - `updateWindow` must call the Step 1 normalization policy, set the manager’s current index through `setCurrentPlayingIndex`, and reconcile by key/media identity rather than blindly calling `reset()` on every update. Keep an existing current source alive when it remains in the new window; remove only stale/changed entries with `removeMediaItems` or `remove`.
  - For each new item, resolve its stream type with `buildPlaybackPreparationPlan(..., preload = true)`, create its source through `PlayerMediaSourceFactory.create(..., preload = true)`, and add it with `add(mediaSource, rankingIndex)` or `addMediaSources` using its list index as ranking data.
  - Use the per-item source path so each candidate retains its own custom request properties. Also pass the preload-scoped factory from `PlayerDataSourceFactoryProvider` into `DefaultPreloadManager.Builder.setDataSourceFactory` as the manager/player factory fallback. When no stream is known yet (for example, a render surface is bound before playback), use a non-foreground fallback factory; actual managed candidates must still be created with the per-item `preload = true` provider path.
  - Store a map from the stable `PlayerPreloadItem.key` and the factory’s media identity to the managed `MediaItem`. `getMediaSource(streamInfo)` must return the manager’s source for the exact matching media identity, or `null` when no managed source exists.
  - `clearWindow` must remove/release all managed sources without releasing the foreground player. `release` must release the `DefaultPreloadManager`, clear maps, and make all later methods no-ops or safe failures.
  - Attach a `PreloadManagerListener` for sanitized completion/error logs. A preload error removes only that candidate and never emits a foreground `PlayerError`.

  **Tests:**

  - Verify target status is `null` for current/far items and three seconds loaded for adjacent items.
  - Verify `SimpleRankingDataComparator` is updated when the current index moves and the nearer candidate is ranked first.
  - Verify reconciliation retains the current source, removes stale candidates, replaces changed stream identity, and never exceeds three managed items.
  - Verify live candidates are rejected before any Media3 source is created.
  - Verify sources are created with `preload = true`, the configured `setDataSourceFactory` receives the preload factory, and the foreground transfer listener is not attached to preload data sources.
  - Verify a missing manager source returns `null` so the caller can create a direct source, and verify release/clear calls do not throw.
  - Use an injectable/fake manager seam for deterministic JVM tests where real Media3 preload threads are not suitable; keep one Robolectric construction test for the 1.11 API wiring.

- [x] **Step 3: Integrate manager lifecycle and managed-source reuse into `Media3PlayerEngine`.**

  **Files:**

  - Modify `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt`.
  - Extend `player/src/test/java/com/streamvault/player/PlayerEngineFeatureConfigurationTest.kt` or add `player/src/test/java/com/streamvault/player/Media3PlayerEnginePreloadTest.kt`.
  - Extend `player/src/test/java/com/streamvault/player/playback/PreloadCoordinatorTest.kt` only for the unchanged legacy-path contract.

  **Changes:**

  - Replace the single `preloadCoordinator` field arrangement with the existing `PreloadCoordinator` plus a nullable `Media3PreloadWindowManager` session and a stored, data-only pending window for calls made before the player exists.
  - In `createPlayer`, build the current `DefaultLoadControl` and renderers exactly as today, create the manager session, and create the foreground player through `Media3PreloadWindowManager.createPlayer`. Preserve all current audio/video renderer customization, analytics listeners, live-speed control, seek increments, audio attributes, surface behavior, and MediaSession setup.
  - Derive `preloadDataSourceFactory` from the current `lastStreamInfo` with `PlayerDataSourceFactoryProvider.createFactory(..., preload = true)` when possible. If a player is created before any stream is known, pass a non-foreground fallback factory and rely on per-item source creation for later windows.
  - In `preloadWindow`, normalize and store the request if the manager session does not yet exist; otherwise update the manager immediately. Clear the legacy single-item cache when a VOD/catch-up window is accepted so stale live-zap data cannot be reused for a different content class.
  - In `prepareInternal`, look up a managed source by the exact current `StreamInfo` before trying `PreloadCoordinator.tryReuse`. If no managed source exists, preserve the existing direct factory path. Keep `preloadCoordinator.onPlaybackStarted(mediaId)` for its existing invalidation semantics.
  - When `preload(null)` is called, clear both the legacy coordinator and the sliding window. This preserves the behavior required by live recovery and channel changes.
  - When `recreatePlayer` or `resetEngineState` releases the current `ExoPlayer`, release the manager session and never reuse a `MediaSource` created by the old session. Retain only the data-only pending window if it is safe to re-add after a new player session; otherwise clear it and let the feature refresh after `READY`.
  - Preserve manager/source lifecycle across content switches without clearing the window before a next-episode or catch-up candidate can be consumed. Clear the old window only when the new content class is ineligible or when the feature submits a replacement window.
  - Add logs distinguishing `preload-window accepted`, `preload-window skipped`, `preload-source reused`, and `preload-source direct-fallback`, with sanitized identity fields.

  **Tests:**

  - A matching managed VOD/catch-up item is selected before direct source creation.
  - A changed URL, headers/DRM identity, expired item, or absent manager source uses the direct source path.
  - `preload(null)` clears both paths.
  - Recreating/resetting the engine releases the manager and does not return an old-session source.
  - Existing live `preload(StreamInfo)` behavior and live recovery clearing remain unchanged.
  - Existing player configuration tests continue to verify decoder, surface, audio-focus, buffer, and MediaSession behavior.

- [x] **Step 4: Add deterministic episode and catch-up candidate selection in `:feature:playback`.**

  **Files:**

  - Add `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerPreloadWindowSupport.kt`.
  - Add `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerPreloadWindowCoordinator.kt`.
  - Modify `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerEpisodePlaybackSupport.kt`.
  - Modify `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerProgramTimelineSupport.kt`.
  - Add `feature/playback/src/test/java/com/streamvault/feature/playback/player/PlayerPreloadWindowSupportTest.kt`.
  - Add `feature/playback/src/test/java/com/streamvault/feature/playback/player/PlayerPreloadWindowCoordinatorTest.kt`.

  **Changes:**

  - Add pure episode helpers that flatten sanitized series seasons in season/episode order and return previous/current/next entries across season boundaries. Keep `findNextEpisode` behavior unchanged and add `findPreviousEpisode` plus a bounded `buildEpisodeWindow` helper.
  - Add pure catch-up helpers that sort programs by start time, locate the selected program, and return the closest previous/next programs that are archive-playable for the current channel. Keep the existing EPG history/upcoming limits; do not query a second unbounded EPG list just for preloading.
  - Generate stable, sanitized keys such as `episode:<providerId>:<playbackEpisodeIdentity>` and `catchup:<providerId>:<channelId>:<startTime>:<endTime>`. Keys must not contain complete signed URLs, credentials, or headers.
  - `PlayerPreloadWindowCoordinator` must expose these exact operations:

    ```kotlin
    suspend fun buildEpisodeWindow(
        current: PlayerPreloadItem,
        series: Series,
        currentEpisode: Episode,
        providerId: Long,
        isCurrent: () -> Boolean
    ): PlayerPreloadWindow?

    suspend fun buildCatchUpWindow(
        current: PlayerPreloadItem,
        selectedProgram: Program,
        channel: Channel,
        timelinePrograms: List<Program>,
        providerId: Long,
        isCurrent: () -> Boolean
    ): PlayerPreloadWindow?
    ```

  - Use the already resolved current `StreamInfo` as the current item. Resolve adjacent episode streams through `PlayerContentResolver.resolvePlaybackStream` with `ContentType.SERIES_EPISODE`; resolve catch-up URLs through `PlayerProviderCoordinator.buildCatchUpUrls`, then pass each candidate through the shared playback resolver so custom provider metadata is retained.
  - Add a no-player-mutation helper to `PlayerPreparationCoordinator` named `prepareStreamForPreload(streamInfo: StreamInfo): StreamInfo?`. It may run the existing `PlaybackStreamPreparer`, must reject expired results, must not call `PlayerEngine.prepare`, and must not perform the foreground probe-before-playback step.
  - For a candidate with multiple catch-up URL variants, attempt them in provider order until one resolves; keep alternate URLs only in the normal playback/recovery path. The window contains one candidate per adjacent program.
  - Return `null` when the current item or required provider/channel context is unavailable. Skip individual unresolved neighbors, preserve order, and never fail the current playback session because a neighbor cannot be resolved.
  - Do not invent an ordering for standalone movies or generic VOD rows when the caller has no explicit ordered queue. The player contract supports future VOD queues, but this first integration covers the existing ordered series-episode and catch-up flows.

  **Tests:**

  - Episode selection covers same-season, cross-season, first-episode, and last-episode cases.
  - Catch-up selection returns chronological previous/next programs, excludes non-archive-playable programs, and handles a missing selected program.
  - Resolution preserves headers, user-agent, proxy/TLS/transport fields, stream type, DRM metadata where applicable to direct resolution, and expiration time.
  - Resolver failures skip only the failed neighbor; a current-only window remains valid.
  - Cancellation/stale-session checks stop candidate resolution and prevent a stale window from being returned.
  - Feature tests contain no Media3 imports and `verifyFeaturePlaybackBoundary` remains clean.

- [x] **Step 5: Connect window refreshes to episode and catch-up playback without changing UI behavior.**

  **Files:**

  - Modify `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerViewModel.kt`.
  - Modify `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerPlaybackContextCoordinator.kt`.
  - Modify `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerAutoPlayActions.kt`.
  - Modify `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerCatchUpActions.kt`.
  - Modify `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerGuideTimelineCoordinator.kt` only if a bounded ordered timeline snapshot is needed by the pure catch-up helper.
  - Extend `feature/playback/src/test/java/com/streamvault/feature/playback/player/PlayerCatchUpActionsTest.kt` and add focused ViewModel/coordinator interaction tests using a fake `PlayerEngine`.

  **Changes:**

  - Inject `PlayerPreloadWindowCoordinator` into `PlayerViewModel`.
  - Add a private `refreshPreloadWindow(requestVersion: Long)` that launches in the current playback session, snapshots the current resolved stream/context, builds an episode or catch-up window, checks `isActivePlaybackSession(requestVersion)`, and calls `playerEngine.preloadWindow(window)`.
  - Trigger refresh after the player has successfully prepared the current non-live item and after asynchronous series context resolution completes. Use a fingerprint of current key plus neighboring keys/stream identities to avoid duplicate network resolution and repeated manager reconciliation.
  - In `playCatchUp(program)`, store the selected `Program` in the playback context before starting candidate playback. Clear it when leaving catch-up or starting a new unrelated content session. This is required because `currentProgram` represents live wall-clock time and is not necessarily the program selected for replay.
  - Build the catch-up timeline from the existing bounded guide snapshot (`programHistory`, `currentProgram`, and `upcomingPrograms`) and the selected program. Do not change guide refresh cadence or visible history/upcoming limits.
  - Keep `PlayerAutoPlayActions`’s completion persistence, watched-state refresh, countdown, cancel action, and `playEpisode` call unchanged. The next episode should consume the manager source opportunistically through `PlayerEngine.prepare`; if unavailable, it continues through the existing direct path.
  - Clear the window when playback changes to ordinary live TV, when the catch-up context is abandoned, and during final lifecycle cleanup. Do not clear it at the beginning of every session, because that would discard the preloaded next episode before it is consumed.
  - Keep thumbnail preloading separate. `startThumbnailPreload()` remains its current six-position image-preview path and must not call the video preload manager.

  **Tests:**

  - A ready series episode requests current/previous/next candidates once and sends the expected current index.
  - A last episode sends a current-only window and does not attempt an out-of-range next episode.
  - Catch-up selection stores the selected program, resolves adjacent replay candidates, and clears the window when returning to live.
  - A stale session or newer playback request prevents a completed resolver job from updating the active engine.
  - `playNextEpisodeNow()` and automatic countdown still call the existing `playEpisode` path and do not add a second auto-play transition.
  - Live channel changes still call the legacy clear/single-item path and never send a live item to `preloadWindow`.

- [x] **Step 6: Add observability and verify the module/build contracts.**

  **Files:**

  - Extend the player preload tests and feature coordinator tests from Steps 2–5.
  - Modify `docs/upgrade.txt` only after implementation evidence exists; keep the item marked as an active pilot until the device gate in Step 7 passes.

  **Required diagnostics:**

  - Count window submissions, accepted item count, skipped live/DRM/expired/invalid count, candidate resolution failures, manager preload completions/errors, managed-source reuse, direct fallback, manager resets, and manager recreation.
  - Include content kind and resolved stream class. Include stable candidate keys only; sanitize URL-like values with `PlaybackLogSanitizer` and never log headers/cookies/tokens.
  - Keep preloading out of foreground bitrate accounting. Verify the foreground `PlayerTransferByteCounter` and its measured bitrate do not include preload bytes.

  **Commands:**

  ```powershell
  .\gradlew.bat :player:testDebugUnitTest --tests "com.streamvault.player.*Preload*" --tests "com.streamvault.player.playback.*Preload*" --no-daemon --console=plain
  .\gradlew.bat :feature:playback:testDebugUnitTest --tests "com.streamvault.feature.playback.player.*Preload*" --tests "com.streamvault.feature.playback.player.PlayerCatchUpActionsTest" --no-daemon --console=plain
  .\gradlew.bat :player:compileDebugKotlin :feature:playback:compileDebugKotlin verifyFeaturePlaybackBoundary --no-daemon --console=plain
  .\gradlew.bat :player:testDebugUnitTest :feature:playback:testDebugUnitTest :app:testDebugUnitTest --no-daemon --console=plain
  .\gradlew.bat :app:assembleDebug --no-daemon --console=plain
  git diff --check
  ```

  **Expected results:**

  - All focused and module unit tests pass.
  - `verifyFeaturePlaybackBoundary` reports no Media3 reference from `:feature:playback`.
  - The app compiles with Media3 1.11.0’s `DefaultPreloadManager` API and no deprecated preload-manager calls.
  - The debug APK assembles successfully.
  - `git diff --check` reports no whitespace errors and the diff contains only the planned player/feature tests, implementation files, and the eventual upgrade-audit update.

- [ ] **Step 7: Run the device/content pilot and make the keep/remove decision.**

  **Validation matrix:**

  - One series with at least three playable episodes, including a cross-season boundary if available.
  - One provider-backed episode whose resolved playback requires custom headers, user-agent, proxy, TLS, or transport policy.
  - One catch-up channel with at least three adjacent archive-playable EPG programs.
  - One standalone movie/VOD item to confirm no invented ordering and unchanged direct playback.
  - The affected Android TV/device/content combination if it is non-live and non-DRM.

  **For each applicable item:**

  - Capture logs from window submission through the first foreground frame for current playback, next/previous selection, manager source reuse, and direct fallback.
  - Confirm the manager never reports more than three managed sources and never starts for ordinary live-channel playback.
  - Compare the source-reuse path with a controlled direct-playback run of the same item. Record time to `READY`, time to first rendered frame, preload completion/error, and whether the resolved request shape matches the direct baseline.
  - Exercise next episode, previous/next catch-up selection, rapid content switching, recovery/error handling, app background/foreground, engine recreation, and final release.
  - Confirm no preload request changes foreground measured bitrate, decoder selection, subtitle/track behavior, proxy/TLS behavior, or playback history.

  **Acceptance gate before removing or weakening the legacy path:**

  - All eligible series-episode and catch-up cases either reuse a managed source or cleanly fall back to direct playback with no user-visible error.
  - No eligible window exceeds three managed items or two adjacent preload transfers.
  - No ordinary live channel is added to the window manager.
  - Manager lifecycle survives content switches, engine recreation, cancellation, and release without leaked threads, stale source reuse, or foreground playback errors.
  - Median and p95 start-to-ready/first-frame latency do not regress against the direct baseline on the same device/content sample. Record the measured threshold and sample size in the audit.
  - Custom headers, proxy/TLS/transport policy, URL renewal, DRM exclusion, track selection, and foreground transfer accounting remain correct.
  - Logs are sanitized and sufficient to distinguish manager reuse, successful preload with direct fallback, skipped candidate, and manager failure.

  **Documentation:** Update the second `ADD` item in `docs/upgrade.txt` with the implementation state, window cap, supported candidate sources, device/content matrix, measured latency, fallback/error counts, and the decision to retain or remove the legacy single-source path. Do not mark the item `DONE` until the acceptance gate is met.

## Reference

Media3’s official documentation states that `DefaultPreloadManager` ranks one-dimensional media items by distance from the current playing index, queries a `TargetPreloadStatusControl` for the amount to load, returns managed `MediaSource` instances for playback, and requires the returned source to be played by an `ExoPlayer` created from the same builder. The relevant APIs are [DefaultPreloadManager](https://developer.android.com/reference/androidx/media3/exoplayer/source/preload/DefaultPreloadManager), [DefaultPreloadManager.Builder](https://developer.android.com/reference/androidx/media3/exoplayer/source/preload/DefaultPreloadManager.Builder), [preload-manager concepts](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/concepts), [create/configure](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/create), and [manage/play](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/manage-play).

## Completion Definition

The implementation is complete only when Steps 1–6 are implemented and verified, Step 7 has real device/content evidence, and `docs/upgrade.txt` records the supported scope and keep/remove decision. Until then, the existing single-item coordinator remains in place as the live/recovery compatibility path and direct playback remains the fallback for every preload miss.
