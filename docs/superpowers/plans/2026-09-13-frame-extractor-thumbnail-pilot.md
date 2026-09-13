# FrameExtractor VOD Thumbnail Pilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Media3 `FrameExtractor` backend for VOD seek thumbnails while preserving the current `MediaMetadataRetriever` implementation as a safe fallback until device coverage and performance are proven.

**Architecture:** Keep the Media3 implementation inside `:player`, behind a small player-side thumbnail-extraction interface. The feature module continues to use `PlayerThumbnailCoordinator`, which tries Media3 first and falls back to the existing provider when Media3 cannot support or decode a request. Reuse the existing stream-resolution and custom data-source path so headers, proxy settings, TLS policy, and bounded timeouts remain consistent with playback.

**Tech Stack:** Kotlin, AndroidX Media3 1.11.0 `media3-inspector-frame`, Media3 `MediaSource.Factory`, OkHttp-backed `DataSource.Factory`, coroutines, Hilt, JUnit/Truth/Mockito, Android device validation.

**Spec:** `docs/upgrade.txt`, the `ADD - Media3 FrameExtractor pilot for VOD seek thumbnails` item.

## Global Constraints

- Scope is this one upgrade item only. Do not implement sliding-window preloading, custom variant ordering, Cast migration, or embedded-subtitle recovery in this change.
- Do not remove or rewrite the existing `SeekThumbnailProvider` until the device/performance gate at the end of this plan passes.
- Do not add Media3 imports to `:feature:playback`; that module's existing boundary check must continue to pass.
- Do not route thumbnail transfers through foreground bitrate/accounting listeners. Thumbnail requests use the existing preload data-source profile and remain bounded/cancellable.
- Do not attempt live, RTSP, MPEG-TS live, DRM, or otherwise unknown streams in the Media3 pilot. Live seek-preview behavior remains disabled as it is today.
- Preserve the current 10-second bucket, 480-pixel maximum preview width, 120 ms seek debounce, six-frame thumbnail preload, request-version checks, cache clearing, and lifecycle cancellation behavior.
- Keep logs sanitized: record backend, stream class, result, duration, position bucket, and failure category; never record authorization headers, cookies, tokens, or complete signed URLs.
- Existing unrelated worktree changes in catalog files are out of scope and must remain untouched.

## Existing Code to Preserve

- `feature/playback/src/main/java/com/streamvault/feature/playback/player/SeekThumbnailProvider.kt` remains the legacy backend and keeps its current URL support rules, cache, timeout, and bitmap scaling behavior.
- `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerThumbnailCoordinator.kt` remains the feature boundary; its implementation will become the backend selector.
- `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerPlaybackPreferenceActions.kt` remains responsible for seek-preview debounce, six-position preloading, request cancellation, and stale-result protection.
- `player/src/main/java/com/streamvault/player/playback/PlayerDataSourceFactoryProvider.kt` remains the source of request headers, user-agent handling, proxy/TLS policy, and timeout profiles.
- `player/src/main/java/com/streamvault/player/playback/PlayerMediaSourceFactory.kt` remains the source of Media3 media-format selection and extractor configuration.

## Implementation Steps

- [x] **Step 1: Add the player-side thumbnail extraction contract and VOD request context.**

  **Files:**

  - Add `player/src/main/java/com/streamvault/player/playback/FrameThumbnailExtractor.kt`.
  - Cover the contract through the concrete provider tests in Step 3; no standalone contract test is needed.

  **Changes:**

  - Define `FrameThumbnailRequest` with the resolved `StreamInfo` and an explicit `isLive` value supplied by the feature. The explicit content classification is required because the current resolver treats several adaptive stream classes as live-capable even when the current item is VOD.
  - Define `FrameThumbnailExtractor` with these operations:

    ```kotlin
    interface FrameThumbnailExtractor {
        fun supports(request: FrameThumbnailRequest): Boolean
        suspend fun loadFrame(request: FrameThumbnailRequest, positionMs: Long): Bitmap?
        fun clearCache()
    }
    ```

  - Keep this separate from `PlayerEngine`; thumbnail extraction is an auxiliary preview concern and must not become part of the foreground playback lifecycle or engine API.
  - Document that implementations must return `null` for unsupported/failed extraction and must not throw a thumbnail failure into foreground playback.

  **Tests:** Verify that the request carries stream metadata and explicit live/VOD state without introducing a Media3 dependency into feature code.

- [x] **Step 2: Refactor `PlayerMediaSourceFactory` so the same media-source rules can be supplied to `FrameExtractor`.**

  **Files:**

  - Modify `player/src/main/java/com/streamvault/player/playback/PlayerMediaSourceFactory.kt`.
  - Extend `player/src/test/java/com/streamvault/player/playback/PlayerMediaSourceFactoryTest.kt`.

  **Changes:**

  - Extract the existing stream-type branch that creates HLS, DASH, Smooth Streaming, progressive, MPEG-TS, RTSP, and default factories into a reusable `MediaSource.Factory` builder.
  - Keep `create(...)` behavior unchanged by having it obtain the reusable factory and then call `createMediaSource(mediaItemFor(streamInfo))`.
  - Make the existing `MediaItem` construction reusable inside `:player` through an appropriately scoped `mediaItemFor(streamInfo)` helper. Preserve media ID, title metadata, and existing DRM configuration for normal playback.
  - Make the reusable factory accept the existing `preload` argument so frame extraction receives `PlayerTimeoutProfile.PRELOAD` and does not attach foreground transfer listeners.
  - Preserve the current progressive extractor flags and CEA subtitle formats. Do not introduce a second media-format selection table for thumbnails.
  - Use the same `PlayerRetryPolicy` plumbing for the factory. The thumbnail provider will still enforce its shorter outer operation timeout, so a source failure returns control to the coordinator quickly.
  - Keep RTSP and MPEG-TS live branches available to normal playback but make them unreachable from the Media3 thumbnail provider through its support policy.

  **Tests:** Confirm existing media-source tests still pass and add assertions that the reusable factory receives the expected resolved type, preload mode, and media item URI/media ID. Keep the MPEG-TS playback policy tests intact.

- [x] **Step 3: Add the Media3 `FrameExtractor` implementation and dependency.**

  **Files:**

  - Modify `gradle/libs.versions.toml` to add `media3-inspector-frame` using the existing `media3` version reference.
  - Modify `player/build.gradle.kts` to add `implementation(libs.media3.inspector.frame)`.
  - Add `player/src/main/java/com/streamvault/player/playback/Media3FrameThumbnailExtractor.kt`.
  - Add `player/src/test/java/com/streamvault/player/playback/Media3FrameThumbnailExtractorTest.kt`.

  **Support policy:**

  - Return `false` when the request is live, has a blank/invalid URL, contains DRM, or resolves to `MPEG_TS_LIVE`, `RTSP`, or `UNKNOWN`.
  - Return `true` for non-live `PROGRESSIVE`, `HLS`, `DASH`, and `SMOOTH_STREAMING` requests. Explicit stream type takes precedence through the existing `StreamTypeResolver` behavior.
  - Keep support policy deterministic and side-effect free; it must not open a network connection.

  **Extraction behavior:**

  - Build the `MediaItem` and reusable `MediaSource.Factory` from `PlayerMediaSourceFactory`, passing `preload = true` so all existing request properties and bounded preload timeouts are reused.
  - Build `FrameExtractor` with `FrameExtractor.Builder(context, mediaItem)`, supply the custom `MediaSource.Factory`, and set `SeekParameters.CLOSEST_SYNC` for seek-bar previews. Exact frame precision is not required by the existing UI, while closest-sync avoids unnecessary decode work.
  - Use the existing 10-second bucket and a cache key based on the stable media identity plus bucket position, rather than the raw signed URL. Keep cache entries bounded with an Android `LruCache`.
  - Run `build`, `getFrame`, and `close` on a single background extraction dispatcher so one `FrameExtractor` instance is never accessed from multiple application threads. Create a short-lived extractor per request, close it in `finally`, and do not retain decoder sessions across stream changes.
  - Await the returned `ListenableFuture<FrameExtractor.Frame>` without adding a new foreground dependency. Cancel the future when the coroutine is cancelled, and wrap the operation in the existing 8-second thumbnail timeout.
  - Read `FrameExtractor.Frame.bitmap`, scale it using the same 480-pixel maximum used by the legacy provider, cache the scaled bitmap, and return `null` for cancellation, timeout, unsupported format, network, decoder, or other extraction failures.
  - Emit sanitized diagnostic events for support decisions, cache hits/misses, success latency, timeout, cancellation, and failure category. Include whether the request used Media3 and the resolved stream class.

  **Tests:**

  - Test support decisions for VOD progressive, VOD HLS, VOD DASH, VOD Smooth Streaming, live content, RTSP, MPEG-TS live, unknown, DRM, blank, and malformed URLs.
  - Test bucketed cache-key behavior and that a cached frame avoids a second extraction.
  - Test bitmap downscaling does not exceed 480 pixels on the long edge.
  - Test extraction failure, timeout, and coroutine cancellation return `null` and release/cancel the extractor operation. Use a small injectable test seam for the future/session boundary if direct `FrameExtractor` construction cannot be exercised in a JVM test.
  - Do not make JVM tests depend on a real provider URL or a decoder installed on the developer machine.

- [x] **Step 4: Turn `PlayerThumbnailCoordinator` into the Media3-first/legacy-fallback selector.**

  **Files:**

  - Modify `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerThumbnailCoordinator.kt`.
  - Add `feature/playback/src/test/java/com/streamvault/feature/playback/player/PlayerThumbnailCoordinatorTest.kt`.

  **Changes:**

  - Inject both the player-side `FrameThumbnailExtractor` and the existing `SeekThumbnailProvider`.
  - Keep the legacy URL available to the coordinator because the current resolved `StreamInfo` may not exist briefly during startup. When resolved info is available, pass it to Media3; if its URL differs from the preview URL, use a copy with the current preview URL while preserving headers, stream type, proxy, TLS, and DRM metadata.
  - Preserve the existing feature-facing operations, extending them only with optional resolved stream info and explicit live state:

    ```kotlin
    internal fun supportsFrameExtraction(
        streamUrl: String,
        streamInfo: StreamInfo?,
        isLive: Boolean
    ): Boolean

    internal suspend fun loadFrame(
        streamUrl: String,
        streamInfo: StreamInfo?,
        isLive: Boolean,
        positionMs: Long
    ): Bitmap?
    ```

  - Selection order is: Media3 support and successful extraction, then legacy support and extraction. If Media3 is unsupported or returns `null`, record the reason and allow the legacy provider to preserve current behavior.
  - `clearCache()` must clear both backends.
  - Do not expose a user-facing setting in this pilot. The fallback is the rollback path while the backend is being measured.

  **Tests:** Verify Media3 success prevents a legacy call, Media3 unsupported/failure invokes legacy when supported, neither backend returns `null`, and cache clearing reaches both implementations. Verify the current URL and stream metadata are forwarded correctly.

- [x] **Step 5: Connect the coordinator to seek preview and thumbnail preloading without changing UI behavior.**

  **Files:**

  - Modify `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerPlaybackPreferenceActions.kt`.
  - Extend `feature/playback/src/test/java/com/streamvault/feature/playback/player/PlayerPlaybackPreferenceActionsTest.kt` only where the coordinator call signature requires it.

  **Changes:**

  - Add one private helper that builds the current thumbnail request inputs from `currentResolvedPlaybackUrl`/`currentStreamUrl`, `currentResolvedStreamInfo`, and `currentContentType == ContentType.LIVE`.
  - Pass those same inputs to both `updateSeekPreview` and `startThumbnailPreload`; do not reconstruct stream metadata differently in the two paths.
  - Keep the current early return for live content, 120 ms debounce, request-version guard, six planned positions, sequential preloading, in-flight key tracking, and lifecycle cancellation unchanged.
  - Keep the legacy URL-only path available when no resolved `StreamInfo` is present so startup and transient resolution states do not regress.
  - Keep `PlayerViewModel` lifecycle cleanup unchanged; its existing thumbnail job cancellation and cache clearing should now clean both backends through the coordinator.

  **Tests:** Retain the existing preload-position and preload-key tests. Add coverage that VOD calls use the resolved stream metadata, live calls do not start extraction, and a late Media3 result cannot overwrite a newer seek-preview request.

- [x] **Step 6: Verify module boundaries, build output, and fallback behavior.**

  **Commands:**

  ```powershell
  .\gradlew.bat :player:testDebugUnitTest --tests "com.streamvault.player.playback.*FrameThumbnail*" --tests "com.streamvault.player.playback.PlayerMediaSourceFactoryTest" --no-daemon --console=plain
  .\gradlew.bat :feature:playback:testDebugUnitTest --tests "com.streamvault.feature.playback.player.PlayerThumbnailCoordinatorTest" --tests "com.streamvault.feature.playback.player.PlayerPlaybackPreferenceActionsTest" --no-daemon --console=plain
  .\gradlew.bat :player:compileDebugKotlin :feature:playback:compileDebugKotlin verifyFeaturePlaybackBoundary --no-daemon --console=plain
  .\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
  ```

  **Checks:**

  - Confirm the dependency resolves to Media3 1.11.0 and the feature boundary reports no forbidden Media3 reference.
  - Confirm no foreground playback transfer listener receives thumbnail bytes.
  - Confirm rapid scrubbing cancels or supersedes old extraction and no stale bitmap updates the current preview.
  - Confirm a forced Media3 failure logs a sanitized fallback event and the old provider still produces the preview where it previously did.
  - Run `git diff --check` and review the diff to ensure only the planned player/thumbnail files, dependency catalog, tests, and documentation are changed.

- [ ] **Step 7: Run the device/content pilot and record the decision.**

  **Validation matrix:**

  - One local or bundled progressive VOD file.
  - One remote progressive VOD URL that requires the app's custom headers or user-agent behavior.
  - One VOD HLS item.
  - One VOD DASH item.
  - One Smooth Streaming item if the app has a real supported source.
  - The affected Android device/content combination from the original report, if it is VOD and not live/DRM.

  **For each item:** Scrub to 0 seconds, 10 seconds, 60 seconds, and near the end; record whether a frame appears, Media3 extraction latency, cache-hit behavior, fallback usage, and any decoder/network error. Repeat rapid scrubbing to verify cancellation. Verify normal foreground playback remains unaffected while thumbnails load.

  **Acceptance gate before removing the legacy provider:**

  - Every supported non-live content class in the matrix returns correct frames on the target device, or the unsupported class is explicitly documented.
  - No Media3 thumbnail request exceeds the 8-second bound; no decoder/session work remains after cancellation or stream change.
  - Media3 does not increase the legacy baseline failure rate and does not make median or p95 extraction latency more than 20% worse on the same content/device matrix.
  - No playback error, stuck-player event, header/proxy regression, or foreground bitrate-accounting change is observed.
  - Logs are sanitized and include enough evidence to distinguish Media3 success, Media3 failure with legacy fallback, and legacy-only extraction.

  **Documentation:** Append the measured matrix, device/content identifiers, fallback counts, latency summary, and the keep/remove decision to the relevant `FrameExtractor` item in `docs/upgrade.txt`. Leave the item marked as an active pilot if any acceptance gate remains open.

## Reference

Media3 documents `FrameExtractor` as an `@UnstableApi` that extracts decoded frames from a `MediaItem`; its API exposes `getFrame(positionMs)`, requires `close()`, and recommends `CLOSEST_SYNC` when exact precision is unnecessary. The extracted bitmap is available as `FrameExtractor.Frame.bitmap`: [FrameExtractor API](https://developer.android.com/reference/androidx/media3/inspector/frame/FrameExtractor), [FrameExtractor.Frame API](https://developer.android.com/reference/androidx/media3/inspector/frame/FrameExtractor.Frame).

## Completion Definition

The implementation is complete only when Steps 1–6 are implemented and verified, Step 7 has real device/content evidence, and `docs/upgrade.txt` records whether the legacy implementation remains required. Until then, this plan intentionally leaves the old `MediaMetadataRetriever` path in place.
