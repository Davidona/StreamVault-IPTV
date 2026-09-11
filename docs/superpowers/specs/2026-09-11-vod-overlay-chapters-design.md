# VOD Overlay Redesign and Chapter Navigation Design

**Date:** 2026-09-11

**Status:** Approved visual direction; awaiting written-spec review

## Summary

Replace the existing VOD control card with a cinematic, video-first overlay and add native chapter navigation from Media3 1.11 metadata. The new interface must feel unrelated to the old VOD overlay while preserving every existing playback capability. Live TV controls remain visually and behaviorally unchanged.

The approved visual reference is the interactive mockup in the current brainstorming session. Its defining traits are a bottom gradient instead of a floating card, a full-width chapter-aware timeline, centered transport controls, a small number of direct actions, and right-side sheets for chapters and playback settings.

## Goals

- Replace the VOD overlay's layout, hierarchy, colors, buttons, focus treatment, and settings presentation.
- Keep the playing video visually dominant while controls are visible.
- Make play, pause, seeking, elapsed time, remaining time, and the current title immediately understandable at TV distance.
- Expose Media3 chapters through timeline markers, current-chapter context, previous/next chapter actions, and a chapter list.
- Preserve subtitles, audio, quality, episodes, mute, speed, A/V sync, timers, Cast, picture-in-picture, aspect ratio, and external-player actions.
- Provide predictable DPAD navigation and equally usable touch targets.
- Keep Media3 types inside the `:player` module and expose app-owned models through `PlayerEngine`.

## Non-goals

- Redesigning the live TV or live-timeshift overlay.
- Creating or editing chapters when a source contains none.
- Fetching chapters from provider APIs or online databases.
- Generating chapter thumbnails. Existing seek thumbnails may be shown when available.
- Adding chapter support to Cast, external-player playback, live TV, or catch-up playback in this iteration.
- Replacing the app-wide theme system.

## Visual Design

### Video-first chrome

The VOD overlay uses top and bottom gradients drawn directly over the playing video. It must not place transport controls inside a large floating card or full-width opaque panel.

The top strip contains only the StreamVault mark and the current clock. The bottom area contains, in order:

1. Content type, resolution/HDR context, title, and current chapter name.
2. Elapsed and remaining time.
3. A full-width progress track with chapter boundary markers.
4. One action row with centered transport controls and supporting actions at the sides.

The app's semantic palette remains the source of truth. Background scrims use black/transparent gradients for readability; progress and focus accents use the active theme's brand and focus colors. The implementation must not introduce a parallel VOD-only hard-coded palette.

### Transport controls

The centered transport group contains:

- Previous chapter, only when chapters exist and playback is beyond the first chapter.
- Rewind 10 seconds.
- Play or pause as the strongest visual control and initial TV focus target.
- Forward 10 seconds.
- Next chapter, only when another chapter exists.

Media-next and media-previous remote keys retain their existing episode/playlist semantics. Chapter navigation is invoked by the dedicated on-screen controls and chapter list, so the redesign does not silently change established remote behavior.

### Direct actions

The left side contains contextual navigation:

- Chapters, only when at least one visible chapter exists.
- Episodes, only for series episodes when the episode picker is available.

The right side contains frequently changed playback choices:

- Subtitles, only when subtitle choices exist.
- Audio, only when audio choices exist.
- Playback settings.

Subtitles and audio are shortcuts into their existing selection flows. They also remain reachable from Playback settings so a user never loses access when the compact row is constrained.

### Playback settings sheet

Playback settings opens as a right-side sheet over a dimmed but still-visible video. It replaces the long horizontal row of text buttons. The root sheet groups all existing VOD actions:

- Tracks: subtitles, audio, video quality.
- Playback: speed and A/V synchronization when supported.
- Display: aspect ratio and picture-in-picture.
- Session: mute, Cast or stop casting, and open in external player when supported.
- Timers: stop-playback timer and idle-standby timer, including active-state summaries.

Existing detailed selection dialogs may initially remain behind these rows, but their entry point and focus return are owned by the new sheet. Follow-up visual alignment of those detail dialogs is part of this redesign, not a separate product feature.

### Chapter sheet

The chapter action opens a right-side sheet containing:

- The chapter count and total duration.
- A vertically ordered list of visible chapters.
- A stable chapter number, title, and start timestamp for each row.
- A strong selected/current treatment on the chapter containing the current playback position.
- Initial focus on the current chapter, or the first chapter if playback precedes all chapter starts.

Selecting a row seeks to its normalized window-relative start time and closes the sheet. Returning from the sheet restores focus to the Chapters action.

Untitled chapters are displayed as `Chapter N`. Hidden chapters are never shown, never receive timeline markers, and are not targets for previous/next actions.

## Interaction Model

### Overlay states

The VOD overlay has five mutually exclusive presentation states:

- `Hidden`: unobstructed video.
- `Controls`: title, timeline, transport, and direct actions.
- `Seeking`: Controls plus the existing thumbnail preview and the target chapter name.
- `Chapters`: dimmed controls plus the chapter side sheet.
- `Settings`: dimmed controls plus the playback settings side sheet or one of its detail views.

Only one side sheet or detail modal may be active. The existing exclusive `PlayerModalState` pattern remains the source of truth and gains chapter/settings destinations.

### DPAD behavior

- Opening controls focuses Play/Pause.
- Up from transport focuses the timeline; down returns to transport.
- Left/right on the focused timeline performs the existing seek-step behavior and shows the seek preview.
- Horizontal movement inside the transport and direct-action rows follows visual order.
- Up/down between rows uses explicit focus destinations rather than relying on spatial guessing.
- Back closes a detail view, then its parent sheet, then the controls, then exits playback according to the existing back-navigation policy.
- Any accepted interaction refreshes the existing control auto-hide timer. Auto-hide is suspended while a sheet or detail modal is open.

### Touch behavior

- Tapping the video retains the existing show/hide behavior.
- Timeline dragging retains the existing seek-preview lifecycle.
- Every interactive target is at least 48 dp.
- Side sheets dismiss via Back, the close action, or tapping the dimmed scrim.
- No new swipe-only interaction is introduced.

### RTL and accessibility

- Text and side-sheet alignment follow the locale.
- Media time continues increasing left-to-right, including in RTL locales.
- Transport meanings are based on time, not reading direction.
- Every icon-only action has a localized content description.
- Focus is conveyed by shape/scale and a high-contrast ring, never color alone.
- Current chapter and active settings expose selected semantics to accessibility services.

## Chapter Data Architecture

### Player-owned model

Add an app-owned immutable model in `:player`:

```kotlin
data class PlayerChapter(
    val index: Int,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long?
)
```

Add `val chapters: StateFlow<List<PlayerChapter>>` to `PlayerEngine`. All fake/test engines must expose the same state. The feature layer never imports `androidx.media3.extractor.metadata.Chapter`.

### Extraction and normalization

Media3 1.11 exposes extracted chapter entries inside track `Format.metadata`. `Media3PlayerEngine` collects `androidx.media3.extractor.metadata.Chapter` entries when tracks or the active timeline change.

Normalization must:

1. Exclude `isHidden == true` entries.
2. Exclude entries whose start time is unset or negative after conversion.
3. Convert period-relative timestamps to window-relative timestamps by subtracting the current window's `positionInFirstPeriodUs / 1000`.
4. Clamp valid start and end positions to the known window duration when available.
5. Sort by start time.
6. Deduplicate equivalent chapter entries exposed on multiple tracks.
7. Derive a missing end from the next chapter start, or from duration for the final chapter.
8. Use the localized fallback `Chapter N` when Media3 supplies no usable title.

The chapter state is cleared before every prepare, on stop/release, and when the active media item changes. Metadata absence or malformed entries produce an empty list and never interrupt playback.

Official API behavior requires the period-to-window conversion before calling `seekTo`: https://developer.android.com/reference/androidx/media3/extractor/metadata/Chapter

### Feature-owned navigation policy

A pure feature-layer chapter policy combines `chapters` and `currentPosition` to derive:

- Current chapter.
- Previous chapter target.
- Next chapter target.
- Whether each chapter transport action is enabled.
- Chapter name shown during seek preview.

At an exact chapter boundary, that chapter becomes current. Previous-chapter behavior follows familiar media-player semantics: if playback is more than three seconds into the current chapter, seek to its start; otherwise seek to the previous chapter. Next always seeks to the next visible chapter.

The three-second restart threshold is a named constant and is covered by unit tests.

## Code Organization

The redesign must avoid making the already large `PlayerControlsChrome.kt` and `PlayerScreen.kt` larger.

### Player module

- `PlayerChapter.kt`: app-owned public chapter model.
- `ChapterMetadataMapper.kt`: Media3-to-app normalization and deduplication.
- `PlayerEngine.kt`: chapter state contract.
- `Media3PlayerEngine.kt`: lifecycle collection and chapter-state publication.

### Playback feature

- `VodChapterNavigation.kt`: pure current/previous/next policy.
- `VodOverlayState.kt`: immutable UI state and action availability.
- `VodPlayerControlsOverlay.kt`: overlay composition and layout.
- `VodTimeline.kt`: progress, markers, seek preview, and timeline semantics.
- `VodTransportControls.kt`: play/pause and seek/chapter controls.
- `VodChapterSheet.kt`: chapter list and focus restoration.
- `VodPlaybackSettingsSheet.kt`: grouped entry points for all VOD settings.
- `PlayerControlsOverlayHost.kt`: collect chapter state and adapt existing callbacks.
- `PlayerModalState.kt`, `PlayerModalHosts.kt`, and `PlayerBackNavigationPolicy.kt`: exclusive sheet/modal state and back handling.
- `PlayerControlsChrome.kt`: retain live controls and delegate non-live content to the new VOD overlay; remove the old `PlayerVodInfo` after parity is proven.

Localized strings are added to the base playback string resource. Existing translations fall back to the base language until the normal localization workflow updates them.

## Capability Parity

The old VOD overlay may be removed only after the following actions are reachable in the new UI where their existing availability conditions are satisfied:

- Play/pause, rewind, forward, and direct seek.
- Mute/unmute.
- Subtitle, audio, and video-quality selection.
- Episode selection.
- External-player launch.
- Playback speed.
- A/V synchronization.
- Stop-playback timer and idle-standby timer.
- Cast/stop casting.
- Picture-in-picture.
- Aspect ratio.

Chapter controls are hidden while casting because this iteration does not obtain chapter state from the remote player. The rest of the VOD settings retain their current Cast-specific enablement rules.

## Empty, Loading, and Failure Behavior

- Before duration is known, the timeline is visible but non-seekable and uses no fabricated duration.
- Before chapters arrive, no Chapters action, markers, title, or chapter transport controls are shown.
- If chapters arrive after controls are already visible, the layout updates without stealing focus.
- If the focused chapter action disappears because the media item changes, focus returns to Play/Pause.
- A failed seek leaves the overlay open and follows the player's existing error/retry path.
- Chapter parsing problems are logged only as sanitized debug diagnostics; they are not user-facing playback errors.

## Motion

- Controls use a short fade/vertical settle when shown and the existing auto-hide timing.
- Sheets enter and leave horizontally with a short fade on the scrim.
- Focus scale is restrained and must not cause neighboring controls to jump.
- Motion honors the platform reduced-motion setting by removing translation and retaining only immediate visibility changes.

## Testing and Acceptance

### Unit tests

- Chapter mapping: hidden, unset, duplicate, unordered, missing-title, missing-end, duration clamp, and period/window-offset cases.
- Chapter navigation: exact boundaries, the three-second previous threshold, first/last chapters, gaps, and no chapters.
- Overlay action policy: movie versus episode, missing tracks, casting, chapter availability, and compact layouts.
- Back policy: settings detail to settings root, sheet to controls, controls to hidden.

### Compose and screenshot tests

Add or replace VOD goldens for:

- Controls with no chapters.
- Controls with chapters and current-chapter context.
- Focus on Play/Pause, timeline, Chapters, Subtitles, Audio, and Settings.
- Seek preview over a chapter boundary.
- Chapter sheet with current row selected.
- Settings root and representative detail view.
- Compact touch layout and RTL layout.

The obsolete `player_controls_overlay_vod.png` golden is replaced only after the new overlay passes review. Live-control goldens must remain unchanged.

### Integration verification

- Run `:player:testDebugUnitTest` and `:feature:playback:testDebugUnitTest` after each vertical slice.
- Run playback Compose screenshot tests and review rendered output at TV and compact widths.
- Assemble the debug APK.
- Exercise one MP4 with QuickTime chapters, one MP4 with Nero chapters, one Matroska file with chapters, and one VOD without chapters on an emulator/device.
- Confirm markers, current chapter, previous/next behavior, sheet seeking, focus restoration, subtitle/audio access, and settings parity.
- Run the repository-wide `testDebugUnitTest` task before completion.

## Delivery Sequence

1. Add the chapter model, extraction, normalization, and engine state without UI changes.
2. Add pure chapter-navigation and overlay-action policies.
3. Build the new VOD overlay against fixture data and approve its goldens.
4. Wire real player state and chapter seeking.
5. Replace VOD settings entry points with the side-sheet hierarchy while preserving all actions.
6. Remove the old VOD UI path, update documentation, and run full verification.

Each sequence step must remain independently testable. Live playback behavior is not modified by any step.

