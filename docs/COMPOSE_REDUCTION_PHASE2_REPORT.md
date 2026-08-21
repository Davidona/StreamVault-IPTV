# Compose Reduction Phase 2 Report

Date: 2026-08-21  
Status: Implementation slices complete; exit validation incomplete

This report records the Phase 2 work against the goals in
[`COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`](COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md).
The purpose of Phase 2 is to reduce unnecessary invalidation in confirmed
Compose hotspots by making state ownership follow consumer and update
frequency, while preserving playback and navigation behavior. It is not a
request to annotate unstable domain models or to rewrite the UI in Views.

## Implementation completed

### Player state and modal ownership

- Replaced the broad mutable modal-state shape with one nullable
  `PlayerModalState.active` value and typed `PlayerModal` variants.
- Migrated the existing PlayerScreen modal transitions to the typed open and
  dismiss API without changing modal placement or callback behavior.
- Kept position and duration in `PlayerControlsOverlayHost` and moved other
  player state toward the host that consumes it:
  - controls and transport state in `PlayerControlsOverlayHost`;
  - diagnostics and player statistics in `PlayerDiagnosticsHost`;
  - live channel, category, EPG, numeric-input, and channel-info state in
    `PlayerLiveOverlayHost`;
  - track, speed, timer, offset, and related dialog state in
    `PlayerModalHosts`.
- Preserved the two modal-host placement boundaries: top-level modal content
  remains before player preparation, and controls modal content remains inside
  the player surface layer after the resume prompt.
- Consolidated the audio/video sync flow collection so it is collected once in
  the modal branch that needs it.

### Shared channel clock

- Added one shared 30-second channel-progress ticker per screen owner.
- Dashboard, Home, and Search collect the timestamp once and pass it to their
  channel cards.
- `ChannelCard` and `LiveChannelRowCard` no longer collect a flow from inside
  lazy-list items. Progress calculation is now a pure clamped function.

### Lazy-list audit

The hot-list review is recorded in
[`COMPOSE_REDUCTION_PHASE2_LIST_AUDIT.md`](COMPOSE_REDUCTION_PHASE2_LIST_AUDIT.md).
It covers Dashboard, Home, Search, EPG, media shelves, player overlays, and
dialogs. The changes add stable domain/composite keys and `contentType` where
lists mix layouts or refresh independently. Player quick actions now use
semantic IDs instead of display labels, so changing mute, cast, speed, aspect,
or timer labels does not change item identity.

## Compose compiler audit

The report was regenerated with:

```text
.\gradlew.bat :app:compileDebugKotlin --rerun-tasks --no-daemon --console=plain
```

The current aggregate is:

| Metric | Phase 0 | Fresh Phase 2 report |
| --- | ---: | ---: |
| Generated composables | 2,273 | 2,311 |
| Restartable composables | 2,240 | 2,278 |
| Skippable composables | 1,243 | 1,266 |
| Known unstable arguments | 1,060 | 1,122 |
| Inferred unstable classes | 156 of 268 | 160 of 331 |
| Memoized lambdas | 3,070 | 3,115 |

The aggregate compiler counts are not a Phase 2 performance win. The increase
is consistent with the added host boundaries and the existing unstable domain
arguments; it is not evidence that the refactor regressed runtime behavior,
but runtime traces are required to establish that. No blanket `@Stable` or
`@Immutable` annotations were added.

The highest-value remaining stability hotspots are the domain-shaped
`Channel`, `Movie`, and `Series` arguments in shared cards, collection-shaped
media shelf arguments, and large player-facing arguments such as `PlayerViewModel`,
`PlayerEngine`, `Program`, `Channel`, and `PlayerTimeshiftUiState`. These should
be addressed with smaller immutable UI models only when their contracts can be
proved, rather than by suppressing the diagnostic.

## Build and test evidence

The following checks passed after the Phase 2 changes:

- `:app:testDebugUnitTest` — passed; 69 actionable tasks, 8 executed and 61
  up-to-date.
- `:app:assembleDebug` — passed; 89 actionable tasks, 5 executed and 84
  up-to-date.
- `:app:compileDebugKotlin --rerun-tasks` — passed; all 49 tasks executed and
  the fresh Compose compiler report was emitted.
- Focused modal, input, and Back-policy tests — passed.
- Focused channel-progress ticker tests — passed.

The build output contains existing deprecation, opt-in, and JVM-sharing
warnings; no new compilation or unit-test failure was observed.

## Runtime and benchmark evidence

The Phase 0 baseline contains emulator macrobenchmark and long-run playback
measurements. No comparable Phase 2 frame-cost or recomposition trace has been
captured yet.

The task `:benchmark:connectedBenchmarkAndroidTest` was started. It built the
benchmark and release-like target artifacts, then waited for a connected
instrumentation device. The run was terminated because ADB/device access is
unavailable in this workspace. It is therefore an incomplete runtime attempt,
not a benchmark result.

Compiler diagnostics are useful for identifying ownership and stability
hotspots, but they do not replace recomposition tracing, frame-cost
measurement, or a before/after macrobenchmark.

## Live playback gate

The required Phase 2 live-TV validation could not be run because ADB is
unavailable. There is no Phase 2 result for the required protocol:

- two live channels;
- 61 screenshots at a two-second cadence per channel;
- changing-frame hash counts across the capture window;
- media session still `PLAYING` with `error=null`;
- sanitized logs showing healthy HLS prepare/read/first-frame or recovery;
- no fatal error, stuck-player timeout, or unintended MPEG-TS fallback.

The Phase 0 baseline remains the reference: CBSN passed that protocol, while
the F1 channel failed because the provider returned HTTP 403. Those baseline
results must not be reused as Phase 2 evidence.

## Exit assessment

Phase 2 is implementation-complete for the state-isolation, shared-clock, and
lazy-list slices, and the repository builds and tests successfully. It is not
exit-ready yet. Completion still requires:

1. a connected emulator or device for the Phase 2 macrobenchmark/recomposition
   comparison;
2. the full two-channel live-playback validation protocol; and
3. before/after runtime evidence showing whether the isolated consumers reduce
   invalidated scopes or frame cost.

Until those measurements are available, the correct status is **implementation
complete, runtime validation pending**, not a claimed performance improvement.
