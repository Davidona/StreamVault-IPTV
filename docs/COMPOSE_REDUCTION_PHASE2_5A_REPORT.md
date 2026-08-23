# Compose Reduction Phase 2.5A Report

Date: 2026-08-23

## Scope

Phase 2.5A hardens the live-category flow path exposed by `ChannelRepository`.
It is an independent data-layer optimization. Movie, series, VOD, and
`CategoryRepository` caching are not included, and no baseline-profile work is
included.

## Implementation

- Added the singleton `ChannelCategoryFlowCache` at
  `data/src/main/java/com/streamvault/data/repository/ChannelCategoryFlowCache.kt`.
- The cache stores one `shareIn(replay = 1)` flow per provider ID in a
  `ConcurrentHashMap`, uses the application repository scope, applies
  `distinctUntilChanged()`, and observes `ProviderDao.getAll()` to evict deleted
  providers.
- The shared flow uses a 30-second `WhileSubscribed` stop timeout. That keeps
  the Room observation alive across a short screen/task re-entry gap while
  provider deletion still cancels the per-provider scope immediately.
- `ChannelRepositoryImpl` now keeps the original Room-backed category
  construction in `buildCategoriesFlow(providerId)`. `getCategories()` uses the
  shared replaying flow, while `getCategoriesSnapshot()` builds a fresh flow and
  calls `first()` without consulting the cache.
- `MultiViewViewModel` now uses `getCategoriesSnapshot()` for its command-style
  picker load, removing the stale one-shot read from the replayable UI API.
- Hardened the existing Live TV/category macrobenchmark setup so it uses the
  seeded debug fixture, follows the TV focus path to enter Live TV, and asserts
  that the seeded `All Channels` category is present before measuring
  navigation. Re-entry returns to Home through the app's existing `ACTION_VIEW`
  landing intent so the route transition does not depend on retained TV focus.
- Added a separate `liveTvCategoryReentryCategoryBuild` journey. It leaves and
  re-enters Live TV inside the measured block and reports both the
  `StreamVault.CategoryFlow.Build` trace-section count and the new
  `StreamVault.CategoryFlow.UpstreamStart` and
  `StreamVault.CategoryFlow.UpstreamStop` counts alongside frame timing. The
  lifecycle hooks are emitted at the shared-flow upstream boundary, so they can
  distinguish a restart from a retained subscription. These traces are still
  not a substitute for a direct Room query-count measurement.

## Test coverage

Focused tests cover:

- same-provider flow reuse;
- independent flows for different providers;
- replay to a second subscriber;
- active Room/upstream refreshes;
- duplicate suppression through `distinctUntilChanged()`;
- provider deletion eviction and recreation without the old replay;
- fresh snapshot reads after the replayed result has become stale;
- category visibility/count changes after parental-control preference changes;
- quick subscriber re-entry inside the stop timeout, proving that it does not
  start a second Room observation;
- existing grouping, count, parental-filtering, and decorative-row behavior.

Validation completed:

```text
:data:testDebugUnitTest
:app:testDebugUnitTest
:app:compileDebugKotlin
:app:assembleDebug
:benchmark:compileBenchmarkKotlin
:benchmark:connectedBenchmarkBenchmarkAndroidTest
```

All completed successfully on 2026-08-22.

The repository-wide `gradlew test` task was also run. It stopped on the
pre-existing `domain:test` failure
`AppHomeDashboardShelfTest.defaultOrder matches the existing home layout`
(`AppHomeDashboardShelfTest.kt:10`); this test and its shelf implementation
are outside the Phase 2.5A changes. The Phase 2.5A-targeted module checks above
remain green.

## Runtime benchmark status

ADB was validated through `E:\androidSdk\platform-tools\adb.exe` on
`Television_1080p(AVD) - 16`. The installed debug APK before the change was
treated as the pre-2.5A baseline (`com.streamvault.app.debug`, versionCode 18,
SHA-256 `64B39335C0FD3810CD71AFB2463915C0067596120F5535EBAFF02F71EDDA11A9`).
The current debug APK was then installed and verified by device hash
(`C1119B6B2857FC0301EE208271C4BA2EED85ADE2DD831E6C5E7E6487176EAEB1`).

Both runs used five iterations of
`liveTvCategoryAndChannelNavigation`, `FrameTimingMetric`, and an explicit
`All Channels` readiness assertion:

| Metric | Pre-2.5A | Current 2.5A |
| --- | ---: | ---: |
| Frame counts | 38, 44, 48, 46, 46 | 48, 49, 42, 44, 46 |
| Frame-count median | 46 | 46 |
| CPU frame P50 | 27.553 ms | 31.120 ms |
| CPU frame P90 | 37.713 ms | 41.799 ms |
| Frame overrun P50 | 19.703 ms | 24.895 ms |
| Frame overrun P90 | 36.915 ms | 38.832 ms |

The journey passed before and after and confirmed category availability, but
this metric measures rendering/input navigation, not Room query count. It does
not demonstrate a cache win; the current run was noisier/slower on this
emulator/debug target. The benchmark also carries the standard warnings that
an emulator and debuggable target are diagnostic only, not representative
release-device performance.

The strengthened re-entry benchmark was rerun on
`Television_1080p(AVD) - 16` with five iterations using
`:benchmark:connectedBenchmarkBenchmarkAndroidTest` and the seeded debug
fixture. The harness starts from Home without killing the debug process,
performs the first Live TV entry inside the measured block, and leaves
`targetPackageOnly` disabled because the interaction fixture is the separate
`.debug` package. The latest run used the 30-second stop timeout and captured:

| Metric | Min | Median | Max |
| --- | ---: | ---: | ---: |
| `categoryBuildCountCount` | 2 | 2 | 2 |
| `categoryUpstreamStartCountCount` | 2 | 2 | 2 |
| `categoryUpstreamStopCountCount` | 1 | 1 | 1 |
| `frameCount` | 83 | 87 | 90 |

For comparison, the same task-reset journey with the stop timeout temporarily
set to zero also captured `2/2/1` for build/start/stop in every iteration. The
30-second setting therefore did not produce a measurable reduction in this
specific Activity-task reset; the boundary is longer or otherwise different
from the short subscriber gap covered by the deterministic unit test. This is
useful lifecycle evidence, but it is not a quantified cache win. A direct Room
query-count or category-ready-latency benchmark remains open before claiming a
runtime reduction. Both runs are diagnostic only because they use an emulator
and a debuggable seeded target.

The focused unit test is the stronger proof for the cache contract: a second
subscriber arriving before the timeout observes the replay without a second
upstream start or stop. Provider deletion and preference changes remain covered
by the same test suite.

The benchmark harness now explicitly launches `com.streamvault.app.debug` for
interaction journeys while leaving release startup/profile checks on the
release target. This fixes the previous target-package mismatch that could
silently measure an unseeded release app.
