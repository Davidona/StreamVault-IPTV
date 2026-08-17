# Player Modal Host Design

**Date:** 2026-08-17
**Phase:** Phase 1 — source decomposition without behavior change

## Goal

Extract the existing player dialog composition from `PlayerScreen.kt` into a focused `PlayerModalHost.kt` without changing playback behavior, navigation, state ownership, callback order, visibility rules, or Picture-in-Picture behavior.

## Current context

`PlayerScreen.kt` currently owns the player coordinator, lifecycle effects, Back handling, overlay composition, and the invocation of nine dialog call sites representing eight dialog types:

- `ProgramHistoryDialog`
- `MultiViewPlannerDialog`
- `PlayerTrackSelectionDialog`
- `ChannelVariantSelectionDialog`
- `PlayerSpeedSelectionDialog`
- two `PlayerSleepTimerDialog` instances
- `PlayerAudioVideoOffsetDialog`
- `PlayerEpisodeSelectionDialog`

The screen also owns every state variable that controls those dialogs. The extraction must leave that state in `PlayerScreen.kt` so this remains a source decomposition rather than a runtime-state change.

## Design

Create `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerModalHost.kt` with one internal composable. Its parameter list will contain the current Picture-in-Picture flag, dialog visibility/state values, dialog presentation data, and explicit dismiss/selection/action callbacks derived from the existing call sites. Parameters are read-only values and explicit callbacks. The host will not receive `PlayerViewModel`, mutate `PlayerScreen` state directly, create new effects, or alter navigation decisions.

The host will preserve these existing rules:

1. Program history and split-screen dialogs are hidden in Picture-in-Picture mode.
2. The split-screen dialog is rendered only when `showSplitDialog` is true and `currentChannel` is non-null; its `MultiViewViewModel` remains obtained with `hiltViewModel()` at the dialog boundary.
3. Track, variant, speed, timer, audio/video offset, and episode dialogs remain inside the non-Picture-in-Picture guard.
4. Existing callback sequencing remains unchanged, including clearing dialog state before episode playback or multi-view handoff, dismissing audio/video offset previews, and deactivating live translation before subtitle selection.
5. The host does not include `PlayerResumePrompt` or diagnostic/live overlays; those are separate composition concerns and remain in `PlayerScreen.kt` for this slice.

## Alternatives considered

1. **Extract the modal host now — recommended.** It directly matches the Phase 1 plan, removes a cohesive rendering responsibility, and keeps state/effects in the root. The main cost is an explicit callback-heavy interface.
2. **Extract key-event handling first.** This could remove more lines, but it crosses input routing, overlay priority, ViewModel actions, and mutable screen state, making behavior comparison and review riskier.
3. **Start `ProviderSetupScreen` decomposition.** That file is substantially larger, but its source-specific form split is a broader slice with more state and import flows. It should follow a smaller player boundary.

## Verification

- Inspect the before/after diff to confirm only dialog invocation moved and callback expressions remain equivalent.
- Run `:app:compileDebugKotlin --no-daemon --console=plain`.
- Run `:app:testDebugUnitTest --no-daemon --console=plain`.
- Run `git diff --check`.
- Run `graphify update .` after the Kotlin source change.
- Preserve the existing Phase 1 requirement for manual player smoke testing and full multi-channel playback validation; this extraction does not claim those gates are complete.

## Non-goals

- No new dialog behavior, state model, navigation route, ViewModel API, or dependency.
- No Phase 2 runtime-state isolation or baseline-profile work.
- No changes to PR #162.
