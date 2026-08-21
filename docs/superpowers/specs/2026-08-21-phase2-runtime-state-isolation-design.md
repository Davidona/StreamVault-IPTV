# Phase 2 Runtime State Isolation Design

**Date:** 2026-08-21

**Phase:** Phase 2 — runtime state isolation

**Parent plan:** [`COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`](../../COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md)

## Decision summary

Complete Phase 2 as a behavior-preserving runtime-boundary pass. The work will reduce the amount of state observed by the `PlayerScreen` composition root, make modal state explicitly mutually exclusive, consolidate channel-clock production, finish the hot lazy-list audit, and record compiler/runtime evidence against the Phase 0 baseline.

The work will remain inside the existing `:app` module. It will not introduce feature modules, change playback recovery, migrate surfaces to Views, or change the product UI. The Phase 2 result is a better-measured and more isolated Compose tree that is safe to use as the input to later module extraction.

## Purpose and success criteria

The modernization plan treats Compose reduction as an architecture and measurement problem rather than a source-count problem. Phase 1 split large files without changing ownership. Phase 2 must now make high-frequency state local to the consumer that renders it, so changes such as playback position, a 30-second channel-progress tick, or conditional diagnostics do not invalidate unrelated player or list content.

Phase 2 is successful only when all of the following are true:

1. Player state is grouped by consumer and frequency, with position and duration remaining inside the transport subtree.
2. Player modal visibility has one explicit active representation while preserving the existing placement and callback behavior of the two modal hosts.
3. Channel progress uses one shared clock source, collected by list/screen owners rather than independently by every card.
4. The identified Home, EPG, media-shelf, player, and dialog lazy lists have stable keys and `contentType` values where the list contains dynamic or mixed content.
5. Compiler reports identify the remaining unstable UI arguments and the implementation documents which ones were intentionally isolated or converted rather than silenced with blanket annotations.
6. Available compiler, build, unit, and benchmark evidence is compared with Phase 0. Device playback evidence is reported separately and remains an incomplete gate when `adb` or a stable provider stream is unavailable.

## Scope

### In scope

- `PlayerScreen` state-collection boundaries and consumer-specific host composables.
- Player modal-state representation and its pure transition/query behavior.
- Shared channel-progress clock ownership for dashboard, Home, search, and reusable channel cards.
- Stable lazy-list keys and `contentType` for the Phase 2 hot-list audit.
- Targeted immutable presentation models where a compiler hotspot is both high-frequency and materially larger than its rendering contract.
- Unit tests for pure state transitions, progress calculation, and presentation-model mapping.
- Compose compiler report comparison, incremental compile comparison, and available macrobenchmark evidence.
- Graph refresh after Kotlin source changes.

### Out of scope

- Player preparation, recovery, timeshift, Media3 engine behavior, or stream selection policy.
- A new Gradle module or navigation contract.
- A full XML/View migration.
- Blanket `@Stable` or `@Immutable` annotations on domain/data classes.
- A global `CompositionLocal` for all player or channel state.
- Moving modal call sites across the root player `Box`, `PlayerResumePrompt`, diagnostics, or live overlays.
- Claiming full Phase 2 exit without the repository's multi-channel live-playback protocol.

## Architecture

### Player state boundaries

`PlayerScreen` remains the route/coordinator. It keeps state required for route preparation, lifecycle/window effects, root input and Back decisions, focus policy, player surface binding, and error/recovery presentation. It should no longer collect every flow needed by a nested consumer merely because that consumer is rendered from the same root.

The player tree will use these boundaries:

```text
PlayerScreen
  route/session + lifecycle state
  root input/back/focus policy
  PlayerRenderView
  root error/notice/resolution presentation
  PlayerControlsOverlayHost
    transport controls state
    position + duration
    control-only realtime values
  PlayerControlsModalHost
    modal-dependent track/timer/episode state
  PlayerLiveOverlayHost
    channel/category/EPG overlay state
    live overlay focus and content
  PlayerDiagnosticsHost
    diagnostics state collected only while visible
```

The hosts may receive the existing `PlayerViewModel` or narrow callbacks where that is already the established package pattern, but their public state contracts must be small and consumer-specific. A host must not create a second player engine or alter ViewModel scope. Duplicate collection of a hot `StateFlow` is acceptable only when it keeps ownership local and avoids passing a broad aggregate state through the root; cold work and transformations must not be duplicated.

High-frequency values remain leaf-local:

- `currentPosition` and `duration` are collected by `PlayerControlsOverlayHost`.
- Diagnostics stats are collected only inside a visible diagnostics host.
- Channel progress time is collected once by the owning list/screen and passed to progress rendering.

### Explicit modal state

Replace the flag bundle in `PlayerModalState` with a single nullable `PlayerModal` value. `PlayerModal` is a sealed interface whose variants carry only data needed to identify the active modal, such as `TrackSelection(TrackType)`; the remaining variants are singleton objects.

`PlayerModalState` may retain read-only compatibility queries such as `showSpeedSelection`, `trackSelection`, and `hasVisibleModal` so Back/input code can be migrated without widening the diff. Those queries derive from the single active value; they do not store duplicate booleans. Opening a modal replaces the current active modal. Dismissal sets it to null. This makes exclusivity explicit without changing the existing composition locations or modal callback order.

The two existing modal hosts remain placement-preserving:

- `PlayerTopLevelModalHost` remains before the root player `Box`.
- `PlayerControlsModalHost` remains inside the root player `Box`, after `PlayerResumePrompt` and before diagnostics/live overlays.

The state representation changes; the composition placement does not.

### Shared channel clock

Create one package-local `ChannelProgressTicker`/clock implementation with the existing 30-second cadence and `WhileSubscribed` lifetime. The clock exposes a read-only `StateFlow<Long>` and contains no UI or domain model dependency.

Dashboard, Home, and search list owners collect the clock once when they render channel progress. `ChannelCard`, `LiveChannelRowSurface`, and `LiveChannelRowCard` receive the current timestamp as a value. The cards do not collect the clock themselves. The timestamp is read only by the progress leaf; card identity, focus, accessibility, and click behavior remain driven by the channel presentation data.

The clock's progress calculation will be a pure function with tests covering no program, before-start, in-progress, after-end, and zero/negative duration cases.

### Stability and list rules

The compiler report is evidence, not a reason to annotate uncertain domain models. For the confirmed Phase 2 hotspots:

- isolate `PlayerEngine` and other implementation-heavy values at the video-surface boundary;
- prefer small primitive player presentation values at host boundaries;
- use a focused immutable card model only when it removes a broad domain object from a frequently-rendered card API without losing required behavior;
- keep ordinary Kotlin lists out of new aggregate UI-state APIs when an immutable collection or a narrow item model is justified by the measured hotspot.

Every audited lazy list must have a stable key for dynamic items. Mixed lists and lists whose item renderer has materially different layouts must also provide `contentType`. Static singleton items may use their existing explicit item keys. No keys will be invented from display text where a stable domain ID exists.

## Data flow and behavior invariants

- ViewModel state remains the source of truth; this phase does not move business logic into composables.
- `PlayerScreen` still owns root input event adaptation and event-time state snapshots.
- Back priority remains numeric input, countdown/notice/modal/live-overlay handling, then controls/navigation according to the existing policy.
- Preparation remains keyed by `PlayerPrepareIdentity` and keeps the existing arguments/effect keys.
- PiP and window effects remain in `PlayerLifecycleHost`/route-owned code.
- Audio/video-offset eligibility remains gated by sync enabled and cast disconnected.
- MultiView keeps its existing visibility behavior; no new PiP restriction is added.
- Channel progress remains visually equivalent at the existing 30-second update cadence.
- No new collection is allowed inside a lazy-list item for shared clock or screen-level state.

## Testing and measurement

### Automated tests

Add or extend unit tests for:

- modal construction, replacement, derived visibility queries, and dismissal;
- channel progress calculation;
- mapping from grouped player state to narrow presentation contracts where a pure mapper is introduced.

Each new pure production function follows red-green-refactor. Composable host extraction is verified through compilation, existing player policy tests, source-level call-site checks, and applicable Compose/instrumentation coverage rather than a production-only test seam.

### Compiler and build evidence

Before and after the implementation, record:

- unstable argument count and named hotspot signatures from `app_debug-composables.txt`;
- `:app:compileDebugKotlin` incremental timings after touching the same player file;
- `:app:testDebugUnitTest`, `:app:assembleDebug`, and lint results with pre-existing failures separated from new findings;
- available Macrobenchmark interaction results.

The report must distinguish source/compiler evidence from runtime recomposition evidence. A compiler report alone does not prove fewer recompositions.

### Runtime gates

Use the existing repository protocol for at least two live channels: 2-second screenshots for at least 45 captures, preferably 61; changing frame hashes; media session `PLAYING` with `error=null`; and sanitized logs without fatal/stuck/unintended fallback evidence. If `adb` or a stable stream is unavailable, record the exact blocker and leave the Phase 2 exit criterion open.

## Rollout and rollback

Land the work in small, independently verifiable slices:

1. Pure modal state and progress-clock tests/models.
2. Player host state isolation.
3. Shared ticker call-site migration.
4. Lazy-list key/`contentType` audit.
5. Compiler/benchmark comparison and documentation.

Each slice should compile and pass focused tests before the next slice. Reverting a slice must restore the previous state ownership without changing playback policy or persisted data. Do not combine this work with unrelated player recovery or data-layer changes.

## Definition of done

Phase 2 is code-complete when the state, ticker, list, and compiler deliverables above are implemented, tested, measured, documented, and graphified. It is exit-ready only after the available build/runtime evidence is recorded and the required multi-channel live-playback protocol either passes or is explicitly documented as blocked by the environment.
