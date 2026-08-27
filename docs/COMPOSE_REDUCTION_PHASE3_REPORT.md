# Compose Reduction Phase 3 Report

Date: 2026-08-23

## Status

Phase 3 is complete. The reusable presentation foundation now lives in
`:core:ui`; app navigation, resources, repositories, and Activity behavior
remain in `:app` behind an adapter. The dependency guard, JVM tests, connected
TV instrumentation, shell goldens, premium-route goldens, and debug APK build
all pass.

## New boundary

- Added `:core:ui` and included it from the root Gradle settings.
- `:app` depends on `:core:ui`; `:core:ui` has no project dependencies.
- Added `verifyCoreUiBoundary` at the module and root levels. It rejects
  project dependencies and references to app/data/domain/player/navigation/Hilt
  APIs from core source.
- Core source contains no app resources, navigation controllers, domain models,
  repositories, Activity references, or player references.

The guard command and result were:

```text
.\gradlew.bat verifyCoreUiBoundary
Verified :core:ui boundary: no project dependencies and no banned source references.
```

## Moved or extracted source

- Design tokens, typography, focus helpers, theme, and Inter font resources move
  to `com.streamvault.core.ui.design` and `com.streamvault.core.ui.theme`.
- TV interaction primitives and mouse/remote activation handling move to
  `com.streamvault.core.ui.interaction`.
- The television-device predicate moves to
  `com.streamvault.core.ui.device`, with the original feature/UI-mode
  short-circuit behavior preserved.
- Generic shell visuals move to
  `com.streamvault.core.ui.components.shell`, including the core scaffold,
  navigation chrome, headers, status/message states, load-more card, metadata
  strip, and close action.
- App-owned media cards and browse-specific panels remain in `:app`.
- `AppShellNavigation` remains in `:app` and maps app routes, localized labels,
  resources, repository-backed configuration, and Activity callbacks into the
  core shell API. The existing VOD catalog merge rule is covered by unit tests.

## Verification

| Check | Result |
| --- | --- |
| `verifyCoreUiBoundary` | Pass |
| `:core:ui:compileDebugKotlin` | Pass |
| `:core:ui:testDebugUnitTest` | Pass |
| `:app:compileDebugKotlin` | Pass |
| `:app:testDebugUnitTest` | Pass; 363 tests |
| `:app:assembleDebug` | Pass |
| `:core:ui:compileDebugAndroidTestKotlin` | Pass |
| `:core:ui:connectedDebugAndroidTest` | Pass; 1/1 on `Television_1080p(AVD) - 16` |
| `ShellGoldenTest` | Pass; 3/3 on `Television_1080p(AVD) - 16` |
| `PremiumRouteGoldenTest` | Pass; 6/6 on `Television_1080p(AVD) - 16` |
| `git diff --check` | Pass |

The app connected tests installed the current checkout's debug APK for
`com.streamvault.app` over the same-package app instance on the emulator. No
release or differently sourced APK was used for these results.

## Task impact and graph refresh

The build graph now makes the intended boundary explicit: changes in core UI
compile `:core:ui` and its app consumer, while app-only UI remains downstream
of core. The verification run completed `:core:ui` compilation/tests, app
compilation, and `assembleDebug`. No before/after build-time benchmark was
claimed.

`graphify update .` completed successfully:

```text
Rebuilt: 13613 nodes, 26500 edges, 362 communities
graph.json and GRAPH_REPORT.md updated in graphify-out
```

`graph.html` was skipped by graphify because the graph exceeds its 5,000-node
visualization limit.

## Warnings and resolved issue

The extracted device predicate initially read `resources.configuration`
eagerly, which broke two mocked `WatchNextManagerTest` cases. The original app
implementation returned early for known TV feature flags; that short-circuit
was restored in core. The complete app unit suite then passed.

Gradle reports configuration-cache warnings for the custom boundary task
because it intentionally inspects `project` during task execution. The task
still passes and is marked incompatible with configuration cache by design.

## Scope confirmation

This phase changed UI module ownership, package imports, and the app-to-core
shell contract. It did not change player composition, playback lifecycle,
stream recovery, or Live TV playback behavior; the long-duration Live TV
validation protocol was therefore not newly required for this structural UI
move.

## Review hardening follow-up (2026-08-25)

The original golden result above was produced by a helper that silently recorded
the first screenshot when no baseline existed. The helper is now strict and
loads committed assets from `app/src/androidTest/assets/ui-goldens/`. The
app shell and premium-route PNG baselines are now checked in and were
revalidated with recording disabled on the API 36 `Television_1080p` emulator.
This prevents a clean emulator from accepting a visual regression as its own
baseline.

Implementation commits:

- `ef421345` — add core UI module boundary
- `9ce82152` — extract television device predicate
- `66dfac60` — move design system into core UI
- `c6853787` — move TV interaction primitives into core UI
- `517b272f` — extract core shell visuals and app navigation adapter
- `d2a9a69b` — preserve shell focus stacking
