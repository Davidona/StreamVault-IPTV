# Player Input Policy Design

**Date:** 2026-08-17

**Phase:** Phase 1 — source decomposition without behavior change

## Decision

Extract the root player key-routing decisions from `PlayerScreen.kt` into a
platform-free `PlayerInputPolicy.kt`. The policy receives an immutable snapshot
of the state that currently gates the root handlers and returns a typed action
plus whether the existing `onLiveOverlayInteraction()` callback must run first.
`PlayerScreen.kt` remains the owner of Compose state, `PlayerViewModel`, focus,
pointer input, Back execution, and all side effects.

The Android `KeyEvent` to policy-key conversion stays at the screen boundary in
a small adapter. This keeps Android constants out of the policy and allows the
decision rules to be unit-tested with ordinary Kotlin values.

## Invariants

- Preserve the separate `onPreviewKeyEvent` and `onKeyEvent` phases.
- Preserve action-down-only handling and the existing `notifyUserActivity()` call
  before every handled key decision.
- Preserve preview priority: live channel-up/down zapping runs only when no
  blocking overlay/dialog is active; channel-info without its sub-panel may still
  receive the interaction callback before zapping.
- Preserve modal key behavior: Back closes audio/video offset, speed, variant,
  or timer/track state in the existing order; directional/center keys pass to the
  dialog; unrelated keys are consumed.
- Preserve every center, directional, media, guide/info/menu, channel, previous,
  mute, and numeric-key branch, including RTL and catch-up conditions.
- Preserve callback order: `onLiveOverlayInteraction()` is executed before the
  selected player command, exactly as in `PlayerScreen.kt`.
- Do not move or redesign `PlayerBackNavigationPolicy`; default Back continues
  through the existing `handleBackPress()` mapping.
- Do not introduce Phase 2 modal state, navigation contracts, ViewModel APIs, or
  behavior changes.

## Policy model

`PlayerInputState` contains only values needed by the two current handlers:
content type, catch-up/RTL flags, countdown and modal visibility, live overlay
visibility, channel-info sub-panel state, controls state, numeric-input state,
and episode-picker eligibility.

`PlayerInputKey` is a platform-free sealed value with fixed keys and
`Digit(value)` for numeric input. `playerPreviewInputDecision()` returns
`PASS`, `PLAY_NEXT`, or `PLAY_PREVIOUS`, with the existing overlay-interaction
flag. `playerInputDecision()` returns typed commands such as `OPEN_EPG`,
`OPEN_CHANNEL_LIST`, `SEEK_FORWARD`, `TOGGLE_CONTROLS`, `SHOW_EPISODES`,
`TOGGLE_PLAYBACK`, `TOGGLE_MUTE`, `INPUT_NUMERIC_DIGIT`, and the modal dismissal
commands. `PASS` means the modifier returns `false`; `CONSUME` means it returns
`true` without a ViewModel/state mutation.

## Verification

Add unit tests for preview blocking/RTL-independent zapping, modal Back and
directional pass-through priority, center numeric/live behavior, RTL channel/EPG
mapping, seek behavior, episode-picker eligibility, media/guide/info/menu,
channel previous, mute repeat handling, and numeric digits. Then run the focused
policy tests, app unit tests, compile, structural checks, graph refresh, and the
existing player validation gates.
