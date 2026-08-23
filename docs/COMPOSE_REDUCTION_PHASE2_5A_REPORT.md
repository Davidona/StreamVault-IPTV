# Compose Reduction Phase 2.5A Report

Date: 2026-08-22

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
  `StreamVault.CategoryFlow.UpstreamStart` count alongside frame timing. The
  latter is emitted at the shared-flow upstream boundary, so a non-zero value
  means that Room-backed category work restarted during re-entry. These traces
  are still not a substitute for a direct Room query-count measurement.

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

The strengthened re-entry benchmark was run on
`Television_1080p(AVD) - 16` with five iterations using
`:benchmark:connectedBenchmarkBenchmarkAndroidTest` and the seeded debug
fixture. The result was stable across all runs:

| Metric | Min | Median | Max |
| --- | ---: | ---: | ---: |
| `categoryBuildCountCount` | 0 | 0 | 0 |
| `categoryUpstreamStartCountCount` | 0 | 0 | 0 |
| `frameCount` | 49 | 51 | 53 |

The trace evidence shows no category recomputation or upstream restart during
the measured re-entry journey. This is stronger than frame timing alone, but
it is an after-only observation: a controlled no-cache/pre-2.5A run and direct
Room query-count or category-ready-latency evidence are still required before
claiming a quantified caching improvement. The result is also diagnostic only
because it uses an emulator and a debuggable seeded target.

The benchmark harness now explicitly launches `com.streamvault.app.debug` for
interaction journeys while leaving release startup/profile checks on the
release target. This fixes the previous target-package mismatch that could
silently measure an unseeded release app.
