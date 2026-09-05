# Phase 5 Playback Feature Extraction Report

Date: 2026-08-27
Branch: `feature/improveCompose`
Implementation endpoint: `7df21b86` (`refactor: move playback graph registration to feature`)

## Outcome

The playback implementation is now owned by `:feature:playback` for the
Player, MultiView, support services, presentation resources, tests, and graph
registration. The app remains the composition root: it supplies platform
adapters and registers the feature graph through
`registerPlaybackGraph(...)`.

The structural Phase 5 work is complete through Task 9 and the Task 10
connected checks that were available in the initial environment. That initial
emulator session had no seeded provider or channels; a later seeded-provider
run is recorded below. The full live-TV acceptance gate remains open because
the seeded run encountered repeated recoverable live-window source errors. No
live-playback acceptance pass is claimed.

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
7 tests, 7 passed
```

The seven tests include the six checked-in overlay baselines and the
recording-disabled missing-baseline guard.

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

The connected suite was rerun on 2026-08-26 after the navigation hardening;
it reproduced the same 22/27 result and the same five failures above. The
focused navigation-contract (3/3) and app golden-capture (1/1) runs passed,
and the full playback overlay suite passed 7/7.

The release-like profile gate was rerun with the seeded debug fixture installed
alongside `com.streamvault.app`. The benchmark helpers now drive the release
target during baseline collection (ordinary macrobenchmarks retain the debug
fixture), and player-control detection uses the app's `MENU` control-toggle
shortcut rather than the live channel-info `DPAD_CENTER` shortcut. The earlier
full `:app:generateBaselineProfile` attempt failed at the one-shot player
controls assertion; after this harness correction, the focused connected
`BaselineProfileGenerator#criticalJourneys` test passed (1/1, 2026-08-26).
The clean full `:app:generateBaselineProfile` rerun then passed on 2026-08-26
in 39m28s: 10 profile-collection tests passed, 8 unrelated macrobenchmark
tests were skipped, generated beta profiles were copied, startup rules were
merged, and the merged profile was installed. The separate macrobenchmark
performance and physical-device gates remain open. Follow-up
`verifyBaselineProfileSources`, `:app:assembleBeta`, `:app:assembleRelease`,
and `:app:bundleRelease` checks also passed.

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
  touch/mouse, RTL, and reduced motion) were not fully accepted; the seeded
  ADB run covered channel launch and sustained video, while the remaining
  journeys still need explicit manual coverage.
- Two-channel live validation was run and is recorded below. Both channels
  rendered video and recovered to final `PLAYING`, but neither passed the
  stability gate because repeated live-window source-error transitions were
  observed during the required window.
- `:benchmark:compileBenchmarkKotlin` passed. The focused release-like
  `criticalJourneys` connected run and the subsequent full
  `:app:generateBaselineProfile` generation/merge/install task passed after the
  benchmark key/selector fix (10 profile tests passed, 8 unrelated
  macrobenchmarks skipped). `verifyBaselineProfileSources`, beta/release APK
  assembly, and release AAB bundling passed afterward. A macrobenchmark
  performance comparison and physical-device validation remain open.

### Fresh seeded-provider ADB validation (2026-08-26)

The configured public M3U seed was rebuilt into the debug APK, installed with
`E:\androidSdk\platform-tools\adb.exe`, and allowed to sync 1,459 channels.
Both channels below rendered real video and produced 61 unique screenshots at
two-second cadence. Neither is an acceptance pass because the stream entered
repeated recoverable `BehindLiveWindowException`/`Source error` transitions
during the two-minute window; the final media session recovered to
`PLAYING` with `error=null`.

| Channel | Frames / unique SHA-256 | Final media session | HLS evidence | Recovery/error evidence | Result |
|---|---:|---|---|---|---|
| 3ABN English | 61 / 61 | `PLAYING`, `error=null` | 8 prepares, 6 first frames | 5 live-window retries, 35 `state=ERROR` records | Open/fail |
| 3ABN French | 61 / 61 | `PLAYING`, `error=null` | 4 prepares, 4 first frames | 3 live-window retries, 21 `state=ERROR` records | Open/fail |

No `fatal-error`, stuck-player timeout, MPEG-TS fallback, or malformed-HLS
fallback marker appeared in either isolated log. The captured frames visibly
contain the two live broadcasts, so this is a playback-recovery stability
failure rather than an empty catalog or navigation failure. Raw local
artifacts are under `validation/phase5_playback/live-validation/` and remain
uncommitted.

### Cross-upstream ADB validation follow-up (2026-08-26)

The capture transport was then moved onto the emulator so screenshot timing
was not dominated by Windows-side ADB startup. Each run captured 61 frames and
recorded on-device timestamps; the frame hashes and final media-session state
were checked after pulling the completed directory.

| Channel | Upstream family | Frames / unique SHA-256 | Timestamp intervals | Final media session | HLS/recovery evidence | Result |
|---|---|---:|---|---|---|---|
| 00s Replay | Pluto HLS | 61 / 61 | 1.41–2.74s, avg 2.09s | `PLAYING`, `error=null` | Continuous HLS playlist responses; 0 fatal/stuck/live-window/MPEG-TS markers | Stability pass |
| 3ABN Dare To Dream Network | 3ABN HLS | 61 / 61 | 1.23–6.08s, avg 2.08s | `PLAYING`, `error=null` | 3 HLS prepares, 2 first frames, 1 live-window retry, 7 `state=ERROR`, 9 source-error records; no fatal/stuck/MPEG-TS fallback | Not accepted |

The two channels use different upstream families, so the live-window behavior
is not limited to the earlier English/French pair. The 3ABN run still renders
video and recovers, but the repeated source-error transitions keep the Phase 5
stability gate open. The long startup gaps on that run also make it unsuitable
as a clean cadence pass. The raw local artifacts remain uncommitted under
`validation/phase5_playback/live-validation/`.

### Isolated two-channel rerun (2026-08-27)

Each channel was isolated with `adb logcat -c` before launch and captured at
the requested two-second cadence for 61 screenshots. Raw screenshots were
transient and removed after hashing; no credentials, tokens, or other private
payloads were retained.

| Channel | Screenshots | Unique hashes | Final media session | Log findings | Result |
|---|---:|---:|---|---|---|
| 3ABN Dare To Dream Network | 61 | 61 | `PLAYING`, `error=null` | HLS prepare/first-frame; recoverable video-stall/reprepare and release-timeout retry; no fatal error, `BehindLiveWindowException`, MPEG-TS fallback, or stuck-player marker | Stability pass for this window |
| 3ABN French | 61 | 56 | `ERROR`, `error=Source error` | Video stalls selected `XTREAM_TS_FALLBACK`; subsequent `MPEG_TS_LIVE` prepares targeted a malformed `1/live/...` URL and ended in `fatal-error` | Not accepted |

The Dare-to-Dream run satisfies the screenshot, final-session, and no-fatal/
no-fallback criteria for its isolated window. The French run does not: it
reproduces the source/recovery failure and loses frame progression before the
window ends. The two-channel Phase 5 stability gate therefore remains open;
the recovery-policy implementation was not changed in this validation slice.

### Manual seeded playback journey (2026-08-26)

On the same debug APK/emulator, a manual remote-input pass completed the
provider-backed route handoff: Home → Live TV → All Channels → `00s Replay`
preview → fullscreen player. The fullscreen player rendered video, the MENU
shortcut opened the extracted controls chrome (including Mute, PiP, Audio,
Video Quality, and Split Screen), and the controls auto-hide returned to an
unobstructed video frame after the configured timeout. BACK returned to the
Live TV route with the channel preview still present; the media session was
left in a recoverable paused state after leaving fullscreen.

Captured evidence is under
`validation/phase5_playback/manual-validation/20260826/`, including
`manual-00s-controls-last.png` (controls visible),
`manual-controls-autohide-end.png` (video after auto-hide),
`manual-preview.xml`/`manual-full.xml` (preview/fullscreen route checks), and
`manual-back-route.xml` (return to Live TV). This is partial manual coverage;
seeking, numeric entry/zapping, track/quality dialogs, PiP, Cast, MultiView,
touch/mouse, RTL, and reduced-motion journeys remain unaccepted.

### Connected smoke and macrobenchmark follow-up (2026-08-26)

The focused `:app:connectedDebugAndroidTest` rerun of `PlayerSmokeTest` used
the same seeded emulator and reproduced the three known fixture/focus failures
(controls play-button focus, category-rail search-field focus, and audio-track
fixture setup); the mute-action test passed. This matches the previously
recorded baseline and is not attributed to the extraction.

The two playback-relevant Macrobenchmark journeys also completed five of five
iterations on `Television_1080p(AVD) - 16`:

| Journey | Frame-count runs | Frame-count median | CPU frame P50/P90 | Overrun P50/P90 |
|---|---|---:|---:|---:|
| `liveTvCategoryAndChannelNavigation` | 30, 31, 30, 30, 31 | 30 | 606.8 / 939.8 ms | 922.4 / 1,278.7 ms |
| `playerControlsOpenAndNavigate` | 21, 31, 22, 32, 27 | 27 | 804.7 / 2,476.7 ms | 1,313.2 / 4,890.4 ms |

The JSON and Perfetto artifacts are under
`benchmark/build/outputs/connected_android_test_additional_output/` (the exact
device directory is ignored build output). These runs prove that the seeded
journeys execute after extraction, but the emulator timings are materially
noisier than the earlier 2.5A snapshot and include live HLS preparation and
decoder work. They are therefore diagnostic rather than a clean before/after
performance comparison; the Phase 5 performance gate remains open pending a
controlled paired baseline and, ideally, a constrained physical-TV run. Because
the host was under heavy load during this capture, no performance conclusion
should be drawn from these numbers; repeat the paired run on an idle host.

### Navigation and golden hardening follow-up (2026-08-26)

The in-progress review-hardening slice adds coordinator coverage for startup
resume-before-acknowledgement, deferred player lookup completion, and
catalog-layout route reconciliation. It also corrects the startup handoff so a
resume event recorded before acknowledgement still triggers the deferred player
command after acknowledgement. These changes are separate from the playback
extraction endpoint and do not alter player preparation or recovery policy.
The focused app unit rerun for this hardening slice passed 8/8 tests
(`AppNavigationCoordinatorTest` 7/7 and `PlaybackProgressGuardrailTest` 1/1).

### Attribution boundary

The Phase 5 extraction is not the source-level change that introduced this
behavior. `git diff --name-status 3b1becba..HEAD --
player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt
player/src/main/java/com/streamvault/player/playback` is empty: the
`Media3PlayerEngine` and retry-policy implementations were not modified by
the Phase 5 extraction commits. The observed path is the existing
`onPlayerError` → `handlePlaybackError` → live-window retry flow in `:player`.
The two tested channels are also related streams from the same public 3ABN
source, so a source-window interaction remains possible; this evidence does
not yet justify changing the recovery policy.

### Playback lint and connected golden follow-up (2026-09-05)

The extracted Playback module now passes `:feature:playback:lintDebug` after
targeted Compose/resource fixes: the golden fixture caches its
`FocusRequester`, MultiView uses `LocalActivity` and a recomposition-aware
`stringResource`, and the historically default-locale-only strings and
intentional `one`/`other` plurals carry narrow lint annotations. The focused
connected `PlayerOverlayGoldenTest` then passed 6/6 on the API 36 TV emulator.
The full feature sweep also passed all six feature unit-test tasks and all six
feature lint tasks. The current `:app:lintDebug` gate now passes with the
existing baseline; stale baseline entries remain separate cleanup work.

## Graph and final status

`graphify update .` was run after this report and plan update. It rebuilt
`graphify-out/graph.json` and `GRAPH_REPORT.md` with 14,272 nodes, 27,916
edges, and 362 communities; the refreshed graph includes the extracted
feature sources and no longer places Player/MultiView implementation under
the app source tree.

Phase 5 structural extraction: **complete**.
Runtime playback acceptance: **open pending live-recovery stability, manual
journeys, matched macrobenchmark comparison, and physical-device validation**. The focused and full
release-like profile journeys passed after the benchmark harness fix, including
baseline-profile generation, merge, and installation.
