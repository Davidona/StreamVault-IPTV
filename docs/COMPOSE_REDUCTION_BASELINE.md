# Compose Reduction Baseline

Date: 2026-08-16
Repository state: existing user changes in `gradle.properties` and `player/build.gradle.kts` were preserved  
Purpose: Phase 0 baseline for UI architecture and Compose reduction work

## Execution update

The first behavior-preserving decomposition has been applied: `PlayerControlsOverlayHost` now lives in `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerControlsOverlayHost.kt`, while its inputs, callbacks, and `PlayerControlsOverlay` wiring remain unchanged. `PlayerScreen.kt` decreased from 1,561 to 1,432 lines.

Verification after the extraction:

- `:app:compileDebugKotlin` — passed.
- `graphify update .` — passed; graph refreshed to 12,893 nodes and 24,601 edges.
- `:app:testDebugUnitTest` — passed after the test fixture was updated to provide the already-required `m3uClassificationRepository` mock. Existing coroutine opt-in warnings remain.

This document records the Phase 0 baseline and the first Phase 1 source decomposition. Phase 0 is complete for the available emulator/development environment; physical-device and provider-auth limitations are recorded below.

## Phase 0 completion measurements

Validation environment:

- SDK: `E:\androidSdk`
- Emulator: `emulator-5554`, `AOSP_TV_on_x86`, 1920x1080, API 36
- Release-like benchmark target: `com.streamvault.app`, non-debuggable `benchmark` build type
- Seeded interaction fixture: `com.streamvault.app.debug`

### Repeated builds

| Scenario | Runs (seconds) | Result |
|---|---:|---|
| Warm `:app:assembleDebug --no-daemon` | 286.610, 12.046, 11.673, 11.445, 11.692 | Passed; run 1 included variant/configuration work, subsequent runs were stable |
| Touch `PlayerScreen.kt` + `:app:compileDebugKotlin --no-daemon` | 19.671, 9.334, 9.428, 9.447, 9.396 | Passed; four stable incremental runs were about 9.3–9.4s |

### Runtime benchmark and artifacts

- Macrobenchmark module builds and installs successfully.
- Release-like cold startup: 5 iterations, median `975.8 ms`, min `888.4 ms`, max `1,487.6 ms` on the emulator. Android reported the expected emulator warning; do not use this as a physical-device target.
- Seeded interaction journeys: dashboard scroll, Live TV navigation, EPG navigation, player-controls navigation, and settings navigation all completed 5 iterations. Dashboard diagnostic snapshot: median 87 frames, P50 frame overrun 9.5 ms, P90 27.4 ms, P99 79.2 ms.
- Release APK: `18,138,676` bytes; 3 dex files totaling `14,015,356` bytes.
- Release cold `am start -W`: `648 ms` on the same emulator.
- Release-like process memory after 5 seconds: `91,441 kB` total PSS.
- Seeded debug process memory after 5 seconds: `265,167 kB` total PSS. This is diagnostic only and includes debug/runtime overhead.

### Live TV long-run evidence

Each channel used 61 screenshots at a 2-second cadence (120 seconds nominal), with SHA-256 frame-hash progression checked.

| Channel | Frames | Unique hashes | Final media session | Result |
|---|---:|---:|---|---|
| F1 Channel | 61 | 32 | `ERROR`, `Source error` | Failed after initially rendering video; sanitized logs show provider HTTP 403 and no recovery candidate |
| US CBSN New York (D) | 61 | 61 | `PLAYING`, `error=null` | Passed; no fatal/stuck-player error observed |

The F1 result is an external stream/authentication failure, not evidence of a Compose regression. Full multi-channel playback validation remains required after playback-facing Phase 1 changes.

## Build baseline

| Scenario | Result | Cache/task details |
|---|---:|---|
| `clean :app:assembleDebug --profile --no-daemon` | 2m15.47s | 93 actionable tasks; 40 executed; 53 from cache; configuration cache stored |
| Warm `:app:assembleDebug --profile --no-daemon` | 36.264s | 89 actionable tasks; 1 executed; 88 up-to-date; configuration cache reused |
| `:app:compileDebugKotlin --profile --no-daemon` | 33s command duration | 49 actionable tasks; 1 executed; 48 up-to-date |
| Forced `:app:compileDebugKotlin --rerun-tasks` | Incomplete after 184s timeout | Compose reports were emitted; duration is not a valid benchmark |

The clean command still reused Gradle/build-cache outputs for several compilation tasks. It should therefore be described as a clean task-graph run with cache reuse, not a fully uncached machine build.

The warm command includes the cost of starting a single-use Gradle process because `--no-daemon` was used. Future comparisons should include both a normal developer-daemon scenario and a CI-style no-daemon scenario.

## Packaging snapshot

| Artifact | Size |
|---|---:|
| `app/build/outputs/apk/debug/app-debug.apk` | 50,778,921 bytes |

This is a debug APK and is not an install-size or release-size target. A release APK and per-ABI/package analysis should be added before dependency cleanup.

## Compose compiler snapshot

Reports were generated under `app/build/reports/compose-compiler/` using the diagnostic configuration added to [`app/build.gradle.kts`](../app/build.gradle.kts).

| Metric | Value |
|---|---:|
| Generated composables | 2,273 |
| Restartable composables | 2,240 |
| Skippable composables | 1,243 |
| Known unstable arguments | 1,060 |
| Inferred unstable classes | 156 of 268 analyzed classes |
| Memoized lambdas | 3,070 |
| Compose feature flags | Strong skipping, intrinsic remember, optimized non-skipping groups, pausable composition |

The generated composable count is larger than the source-level `@Composable` count because the compiler report also includes generated and nested composable groups/lambdas.

## First findings

### Shared media components

- `ChannelCard` receives an unstable `Channel`.
- `MovieCard` receives an unstable `Movie`.
- `SeriesCard` receives an unstable `Series`.
- `CategoryRow` receives an unstable `List<T>`.
- `ChannelCard` also observes the shared 30-second progress ticker from inside each card.

Initial implication: do not immediately annotate domain models as stable. Introduce feature-facing immutable UI models where the rendering contract is smaller and genuinely immutable. Revisit ticker ownership after runtime tracing.

### Player

- `PlayerScreen` itself is restartable/skippable, but its root ViewModel exposes a large state surface.
- `PlayerControlsOverlay` receives unstable `Program` and `Channel` values in addition to realtime position/duration.
- `PlayerRenderView` receives the player abstraction and should remain isolated from the rest of the composition.

Initial implication: split player state by frequency and consumer before changing player module boundaries. Position, duration, and seek preview should remain in the transport subtree. Live channel/EPG state should remain in the live overlay subtree.

### Provider setup

- `ProviderSetupScreen` receives an unstable ViewModel and owns many local draft values.
- `ProviderSetupCompletionLayer` receives unstable provider state, sets, and file values.

Initial implication: extract provider-specific form models and move persistent draft ownership toward the ViewModel. Keep launcher callbacks at the route boundary.

### Settings and navigation

- `SettingsScreen` receives an unstable ViewModel and passes unstable settings state into dialog/overlay hosts.
- `AppNavigation` receives an unstable `MainActivity` object containing repositories, managers, and mutable flows.

Initial implication: root navigation should receive small platform capability interfaces and navigation state rather than a concrete activity. Settings should eventually depend on use-case/contracts instead of concrete DAOs and preference implementations.

## Baseline limitations

The following are still required before performance targets are finalized:

- Repeated Gradle Profiler incremental scenarios.
- Compose recomposition tracing for player, Live TV, EPG, dashboard, and settings.
- Macrobenchmark or equivalent startup/interaction measurements.
- At least one constrained TV device and one phone/tablet reference device.
- Release/beta APK size and per-ABI analysis.
- Long-duration Live TV validation before and after player-facing changes.

## Immediate next implementation

The first behavior-preserving UI change should be player source decomposition and state-boundary preparation:

1. **Complete for this slice:** split the controls overlay host out of `PlayerScreen.kt`.
2. **Next:** split lifecycle, input, transport-host, and modal-host code from `PlayerScreen.kt`.
3. Keep the same ViewModel, routes, callbacks, and playback policies.
4. Run the existing player tests and full Live TV validation.
5. Compare the Compose compiler report and runtime trace with this baseline.

The next candidate is provider setup decomposition, followed by feature-module extraction once the internal boundaries are stable.
