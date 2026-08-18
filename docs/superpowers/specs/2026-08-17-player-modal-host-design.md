# Player Modal Hosts Design

**Date:** 2026-08-17

**Phase:** Phase 1 — source decomposition without behavior change

**Parent plan:** [`COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`](../../COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md)

## Decision summary

Extract the nine existing player-dialog call sites from `PlayerScreen.kt` into `PlayerModalHosts.kt` while preserving their current composition placement, order, predicates, state ownership, callback order, ViewModel scope, Back behavior, focus behavior, navigation behavior, and Picture-in-Picture behavior.

The file will contain two internal composable hosts rather than one host moved to a new common location:

- `PlayerTopLevelModalHost` contains `ProgramHistoryDialog` and `MultiViewPlannerDialog` and is invoked at their current top-level position before the root player `Box`.
- `PlayerControlsModalHost` contains the remaining seven call sites and is invoked at their current position inside the root player `Box`, after `PlayerResumePrompt` and before diagnostics/live overlays.

This placement-preserving split is intentionally safer than the parent plan's conceptual single `PlayerDialogHost`. The hosts may be consolidated after Phase 2 introduces explicit mutually exclusive modal state and validates the resulting composition-order change. Phase 1 will not make that state or ordering change.

## Alternatives considered

1. **Two placement-preserving hosts in one file — selected.** This extracts the responsibility without moving either dialog group across the root player `Box` or `PlayerResumePrompt`. Its temporary cost is two callback-heavy host APIs.
2. **One host at the controls-dialog location.** This is visually simpler, but it moves program history and MultiView into the root player `Box` and changes their ordering relative to `PlayerResumePrompt`.
3. **One host at the current top-level location.** This moves all controls-related dialogs out of the root player `Box` and changes their ordering relative to the prompt and overlays.
4. **Introduce explicit modal state and then use one host.** This is the preferred eventual shape, but it changes state representation and exclusivity and therefore remains Phase 2 work.

## Goal

Make player modal rendering a clearly located presentation responsibility so later state isolation and `:feature:playback` extraction are easier to review, without changing runtime behavior in this slice.

Success means that a reviewer can map every old dialog invocation to one new invocation with the same data, predicate, order, and callback body. Reducing line count is secondary to preserving behavior.

## Current context

`PlayerScreen.kt` currently owns the player coordinator, modal state, lifecycle and Picture-in-Picture handling, Back handling, input routing, focus restoration, controls auto-hide policy, overlay composition, and nine dialog call sites representing eight dialog types:

1. `ProgramHistoryDialog`
2. `MultiViewPlannerDialog`
3. `PlayerTrackSelectionDialog`
4. `ChannelVariantSelectionDialog`
5. `PlayerSpeedSelectionDialog`
6. `PlayerSleepTimerDialog` for stop playback
7. `PlayerSleepTimerDialog` for idle standby
8. `PlayerAudioVideoOffsetDialog`
9. `PlayerEpisodeSelectionDialog`

The first two are composed before the root player `Box`. The remaining seven are composed inside that `Box`. `PlayerResumePrompt` is a full-screen in-content prompt between those groups, not one of the extracted dialog call sites.

Multiple modal booleans can currently be true at the same time. Phase 2 will replace that representation with explicit modal state; Phase 1 must preserve the current possibility, source order, Back priority, and resulting presentation behavior.

## Architecture and ownership

Create:

```text
app/src/main/java/com/streamvault/app/ui/screens/player/PlayerModalHosts.kt
```

Both hosts are `internal` and presentation-only. Their inputs are read-only values and narrow callbacks copied from the existing call sites.

The hosts must not:

- Receive `PlayerViewModel`, `NavController`, `MainActivity`, a player engine, or mutable state holders.
- Read flows, create `remember` state, launch effects, install a `BackHandler`, or mutate `PlayerScreen` state.
- Compute navigation destinations or call navigation directly.
- Change modal exclusivity, dismissal policy, focus restoration, controls auto-hide behavior, or overlay priority.
- Introduce a sealed modal type, grouped modal state, route contract, new ViewModel API, or new dependency.

`MultiViewViewModel` is the one explicit dependency-injection exception. `PlayerTopLevelModalHost` obtains it with `hiltViewModel()` only inside the existing `showSplitDialog && currentChannel != null` branch and passes it to `MultiViewPlannerDialog`. This preserves lazy creation and the current `ViewModelStoreOwner`; it must not be hoisted into `PlayerScreen` or created when the dialog is absent.

All modal booleans and `showTrackSelection` remain owned by `PlayerScreen`. The following also remain in `PlayerScreen` unchanged:

- `anyOverlayVisible` and its focus-restoration effect.
- Controls auto-hide inputs and effect keys.
- `PlayerBackNavigationState`, `playerBackAction`, and the existing action-to-mutation mapping.
- Root pointer/key interception while a modal is active.
- Modal-opening callbacks supplied to controls and live overlays.
- Audio/video-sync disabling cleanup.

## Exact composition placement and order

`PlayerTopLevelModalHost` replaces only the current contiguous top-level block. It remains after live-variant observation and before player preparation identity/effects. Within the host, composition order is:

1. `ProgramHistoryDialog`
2. `MultiViewPlannerDialog`

`PlayerControlsModalHost` replaces only the current contiguous dialog block inside the root player `Box`. It remains after `PlayerResumePrompt` and before diagnostics and live overlays. Within the host, composition order is:

1. `PlayerTrackSelectionDialog`
2. `ChannelVariantSelectionDialog`
3. `PlayerSpeedSelectionDialog`
4. Stop-playback `PlayerSleepTimerDialog`
5. Idle-standby `PlayerSleepTimerDialog`
6. `PlayerAudioVideoOffsetDialog`
7. `PlayerEpisodeSelectionDialog`

No dialog call may move across `PlayerResumePrompt`, the root player `Box`, diagnostics, or live-overlay composition during this phase. Parameter evaluation must also remain at the equivalent call-site scope; presentation strings may be passed into a host after evaluation with the same resources and fallbacks.

## Visibility contract

The hosts preserve these exact predicates:

| Dialog | Required visibility predicate |
|---|---|
| Program history | `!isInPictureInPictureMode && showProgramHistory` |
| MultiView planner | `showSplitDialog && currentChannel != null` |
| Track selection | Host is outside PiP and `showTrackSelection != null` remains interpreted by the existing dialog |
| Channel variant selection | Host is outside PiP; pass `showVariantSelection` and `currentChannel` unchanged |
| Playback speed | Host is outside PiP; pass `showSpeedSelection` unchanged |
| Stop-playback timer | Host is outside PiP; pass `showStopPlaybackTimerDialog` unchanged |
| Idle-standby timer | Host is outside PiP; pass `showIdleStandbyTimerDialog` unchanged |
| Audio/video offset | Host is outside PiP and `visible` remains `showAudioVideoOffsetDialog && audioVideoSyncEnabled && castConnectionState != CastConnectionState.CONNECTED` |
| Episode selection | Host is outside PiP; pass `showEpisodePicker` unchanged |

MultiView is intentionally **not** newly hidden in Picture-in-Picture mode. That is the current behavior. Changing it, even if desirable, requires a separately scoped behavior proposal and is prohibited here.

Visibility booleans must not be cleared merely because Picture-in-Picture or another eligibility condition temporarily prevents composition. Phase 1 preserves the existing state lifetime and cleanup behavior.

## Data and callback contract

Every host parameter maps directly to an existing dialog argument. Do not replace narrow callbacks with a feature-wide action type in this phase.

The following data expressions must remain equivalent:

- Program history receives `programHistory`.
- MultiView receives the non-null `currentChannel` as `pendingChannel`.
- Track selection receives the current track type, audio tracks, subtitle tracks, video qualities, and live-translation availability/activity values.
- Variant selection receives `currentChannel`.
- Speed selection receives `playbackSpeed`.
- Timer dialogs receive their existing resource titles and the corresponding stop/idle selected minutes.
- Audio/video offset receives `audioVideoOffsetState` and `canSaveChannel = currentChannel != null`.
- Episode selection preserves `currentSeries?.name ?: playbackTitle.ifBlank { title }`, `currentSeriesSeasons.orEmpty()`, `currentEpisode?.id ?: internalChannelId`, and `currentEpisode?.seasonNumber ?: seasonNumber`.

Callbacks passed from `PlayerScreen` preserve these exact orders:

| Interaction | Required callback order |
|---|---|
| Select program history item | Play catch-up, then clear `showProgramHistory` |
| Dismiss program history | Clear `showProgramHistory` |
| Launch MultiView | Clear `showSplitDialog`, hand off playback, then invoke navigation to `Routes.MULTI_VIEW` |
| Dismiss MultiView | Clear `showSplitDialog` |
| Select subtitle | Deactivate live translation, then select the subtitle track |
| Select live translation | Clear the selected subtitle track, then activate live translation |
| Select stop-playback timer | Notify user activity, set the timer, then clear its dialog flag |
| Select idle-standby timer | Notify user activity, set the timer, then clear its dialog flag |
| Dismiss audio/video offset | Clear its dialog flag, then dismiss the preview |
| Select episode | Clear `showEpisodePicker`, then start episode playback |

All remaining method references and dismiss callbacks are passed through unchanged. The extraction must not add automatic dismissal after audio, video, variant, speed, offset-save, or offset-reset callbacks unless the current dialog or ViewModel already performs it.

## Back, focus, and input invariants

This extraction does not move or rewrite Back, focus, or input policy.

- `BackHandler(enabled = !resumePrompt.show)` remains in `PlayerScreen`.
- `PlayerBackNavigationState` receives the same modal inputs in the same priority order.
- Closing audio/video offset from Back still clears the flag before dismissing the preview.
- `anyOverlayVisible` contains the same modal terms and continues to gate root focus and pointer handling.
- Controls auto-hide observes the same modal keys and conditions.
- Dialog-specific focus requesters and key handling remain inside their existing dialog implementations.

The new hosts must not wrap dialogs in focus groups, key handlers, additional layout containers, animations, or semantics nodes.

## Error handling and nullability

No new error state is introduced.

- A null `currentChannel` suppresses the MultiView dialog without clearing `showSplitDialog`, matching current behavior.
- Existing nullable track type, channel, series, season, episode, and title fallbacks are preserved.
- The host does not catch callback exceptions or reorder actions to compensate for them; existing failure behavior remains unchanged.

## Verification and acceptance gates

### Mechanical review

- Compare the before/after diff and account for all nine old call sites and all nine new call sites.
- Confirm only imports and the two contiguous dialog blocks moved; state declarations, effects, Back/input logic, modal-opening callbacks, and dialog implementations remain unchanged.
- Confirm host call placement and internal call order match this document.
- Confirm every argument expression and callback body is textually identical or demonstrably equivalent.
- Run `git diff --check`.

### Automated checks

Run from the repository root:

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:lintDebug --no-daemon --console=plain
```

Run the existing player/instrumentation and golden coverage on the Television 1080p emulator. If the complete connected suite cannot run because of an external environment failure, record the exact command, failing infrastructure condition, and the narrower successful checks; do not report the acceptance gate as passed.

No new production abstraction solely for testing is required in this mechanical slice. If focused host tests can use the existing Compose test infrastructure without changing dialog behavior, add coverage for:

- Program history hidden in PiP.
- MultiView retaining its current non-PiP-gated predicate.
- The composite audio/video-offset predicate.
- Callback ordering for program selection, MultiView launch, timer selection, audio/video-offset dismissal, subtitle/live-translation selection, and episode selection.

### Manual functional smoke test

On a TV emulator or device, verify:

- Player launch and normal playback.
- Controls open/close and auto-hide.
- Remote Back priority and focus restoration.
- Program history open, dismiss, and selection.
- MultiView planner open, dismiss, and launch.
- Track, variant, speed, both timer, audio/video-offset, and episode dialogs where applicable.
- Picture-in-Picture entry/exit, including program-history suppression and no newly introduced MultiView rule.
- Cast-connected suppression of audio/video offset where applicable.

Record unavailable scenarios explicitly; an untested scenario is not a pass.

### Full player and Live TV validation

Because dialog composition moves, this change is not merge-ready until the repository's full player protocol passes:

- Validate more than one live channel.
- Capture at a 2-second cadence for at least 45 screenshots over roughly 90 seconds; prefer 61 screenshots over roughly two minutes.
- Confirm changing screenshot hashes throughout the full window.
- Confirm the media session remains `PLAYING` with `error=null`.
- Confirm logs contain no fatal player error, stuck-player timeout, or unintended MPEG-TS fallback.
- Record sanitized HLS prepare/read/first-frame or expected recovery evidence.
- Report channel names, screenshot count, interval, unique hash count, media-session result, and log findings.

Build, installation, launch, a single screenshot, or one live channel is not sufficient validation.

### Documentation and graph

- Update the Phase 1 status in the parent plan only after the applicable gates pass.
- Run `graphify update .` after Kotlin source changes. Documentation-only edits do not require claiming that the implementation graph is current.

## Rollback

Keep the extraction as a standalone, history-preserving change where practical. Rolling back `PlayerModalHosts.kt` and restoring the two original contiguous blocks must restore the prior composition without data, state, ViewModel API, or navigation migrations.

Do not combine this extraction with playback fixes, modal-state cleanup, visual changes, or dependency movement.

## Non-goals and future-phase boundary

- No new dialog behavior or corrected modal behavior, including no new MultiView PiP restriction.
- No modal exclusivity enforcement or sealed `PlayerModal`/`PlayerDialogState`.
- No state grouping, state-frequency isolation, or relocation from `PlayerScreen`.
- No route/screen split, navigation contract, or feature-module extraction.
- No change to player preparation, recovery, timeshift, surface ownership, lifecycle, or window behavior.
- No ViewModel API or dependency changes other than imports required by the file move.
- No baseline-profile work and no changes to PR #162.

Those items remain governed by their existing later phases in the parent plan.

## Definition of done

This slice is complete only when:

- Both placement-preserving hosts exist in `PlayerModalHosts.kt` and all nine call sites have moved.
- The visibility, ordering, data, callback, Back, focus, input, ViewModel-scope, and nullability contracts above are preserved.
- No future-phase state or navigation design has been introduced.
- Mechanical review, compile, unit, lint, applicable instrumentation/golden checks, manual smoke testing, and full multi-channel Live TV validation have passed or are explicitly reported as incomplete blockers.
- The parent plan describes the same Phase 1 boundary and does not claim completion prematurely.
