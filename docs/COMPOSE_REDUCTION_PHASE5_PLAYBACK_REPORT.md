# Phase 5 Playback Feature Extraction Report

Date: 2026-08-25
Branch: `feature/improveCompose`
Implementation endpoint: `7df21b86` (`refactor: move playback graph registration to feature`)

## Outcome

The playback implementation is now owned by `:feature:playback` for the
Player, MultiView, support services, presentation resources, tests, and graph
registration. The app remains the composition root: it supplies platform
adapters and registers the feature graph through
`registerPlaybackGraph(...)`.

The structural Phase 5 work is complete through Task 9 and the Task 10
connected checks that are available in this environment. The full live-TV
acceptance gate remains open because the fresh emulator has no seeded provider
or channels. No live-playback pass is claimed below.

## Ownership and public boundary

- `:feature:playback` owns Player and MultiView screens, coordinators, overlays,
  playback support services, feature resources, tests, and
  `PlaybackGraph.kt`.
- `:app` owns `MainActivity`, `AppNavHost`, app navigation orchestration,
  platform/service adapters, and the callback that consumes typed player
  requests.
- `registerPlaybackGraph(actions, platformHost, consumePlayerRequest)` is the
  feature graph entry point.
- `PlaybackNavigationPolicy` owns supported playback URI schemes and
  navigation-request safety. `PlaybackRoutePatterns` owns the feature route
  constants.
- The feature boundary has no production imports of `com.streamvault.app`,
  `NavController`, `NavHostController`, or `Media3PlayerEngine`.
- The six intentional temporary `:data` imports remain ledgered in
  `COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`; removing them now
  would break active provider/playback contracts.

Rollback for the Task 4 graph move is `git revert 7df21b86`; the earlier
feature commits are independently reversible in the Phase 5 extraction
history.

## Files, resources, and tests moved

The extraction moved the Player and MultiView implementation trees, playback
resources/localizations, support services, navigation graph, and their unit
and Android tests into `feature/playback`. The transparent EPG guide remains
app-owned because it is an EPG integration concern.

Task 4 also moved the navigation safety policy and route constants into the
feature. The new feature policy test covers null, blank, unsupported schemes,
all ten supported schemes, field preservation, and request-instance
preservation.

## Build and boundary evidence

Task 9's five-sample measurements are recorded in
`validation/phase5_playback/build-after/README.md`. They establish feature
local recompilation and app compile isolation; they do not justify a speed-up
claim because the before/after commands and cache states differ.

The following post-Task-4 gate passed:

```text
.\gradlew.bat :feature:playback:testDebugUnitTest :app:testDebugUnitTest --tests "com.streamvault.app.navigation.*" verifyFeaturePlaybackBoundary :app:compileDebugKotlin --console=plain --warning-mode=none
BUILD SUCCESSFUL in 12s
```

The fresh debug APK was rebuilt after cleaning the old output and installed
from:

```text
app/build/outputs/apk/debug/app-debug.apk
SHA-256: 0B346848DE643483914E54FC3EA98471C61F13DBB46922FAF25DEE2042E05DB1
```

## Connected validation

Device: `Television_1080p(AVD) - 16`, API 36, emulator `emulator-5554`.

Feature playback overlay goldens:

```text
:feature:playback:connectedDebugAndroidTest
6 tests, 6 passed
```

App connected suite:

```text
:app:connectedDebugAndroidTest
27 tests, 22 passed, 5 failed, 0 errors
```

The five existing fixture/environment failures were recorded and not counted
as passes:

1. `DownloadForegroundServiceQuotaInstrumentationTest.reducedDataSyncTimeoutReleasesDownloadLease`
   — Android timeout callback did not release the shared quota lease.
2. `LauncherProviderInstrumentationTest.watchNextProgram_supportsInsertUpdateDeleteRoundTrip`
   — the API 36 AOSP TV provider lacks the required `type` column.
3. `PlayerSmokeTest.playerControlsOverlay_playButton_canReceiveFocus`
   — no `>` node was focused.
4. `PlayerSmokeTest.categoryRailPanel_searchField_acceptsInitialFocusAndInput`
   — no text-entry node was focused.
5. `PlayerSmokeTest.playerTrackSelectionDialog_selectsAudioTrack`
   — the existing check failed at `PlayerSmokeTest.kt:178`.

Relevant passing connected coverage included the app navigation contracts
(3/3), premium route goldens (6/6), shell goldens (3/3), and the recording-
disabled golden capture. The XML result is retained at
`app/build/outputs/androidTest-results/connected/debug/TEST-Television_1080p(AVD) - 16-_app-.xml`.

## ADB smoke evidence

The fresh APK was installed with `E:\androidSdk\platform-tools\adb.exe` and
launched as `com.streamvault.app.debug/com.streamvault.app.MainActivity`.
The captured startup frame is
`validation/phase5_playback/adb-smoke/task10-home-top-navbar.png`.

The UI hierarchy reported `streamvault.destination:home` and a
`HorizontalScrollView` containing `Home`, `Live TV`, `Movies`, `Series`,
`Downloads`, `Guide`, `Search`, `Plugins`, and `Settings` across the top of
the screen. This is direct evidence of the current top-navbar startup state;
it does not validate player playback.

## Manual, live, and profile gates

- Provider-dependent manual journeys (player launch, return/Back priority,
  controls, seeking, overlays, numeric entry, zapping, PiP, Cast, MultiView,
  touch/mouse, RTL, and reduced motion) were not marked passed because the
  fresh emulator contains no provider data or channels.
- Two-channel live validation was not run. Therefore there are no channel
  names, 61-frame sequences, unique-hash counts, media-session playback
  result, or HLS log evidence to report. This is an unavailable gate, not a
  pass.
- `:benchmark:compileBenchmarkKotlin` passed. Profile generation itself was
  not run because the benchmark requires a seeded development provider and a
  release-like target with Home, Live TV, and All Channels data; the fresh
  emulator has neither. Generated baseline-profile output remains
  uncommitted.

## Graph and final status

`graphify update .` was run after this report and plan update. It rebuilt
`graphify-out/graph.json` and `GRAPH_REPORT.md` with 14,269 nodes, 27,905
edges, and 364 communities; the refreshed graph includes the extracted
feature sources and no longer places Player/MultiView implementation under
the app source tree.

Phase 5 structural extraction: **complete**.
Runtime playback acceptance: **open pending seeded-provider/device validation**.
