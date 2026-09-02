# Compose Reduction Phase 5 — Catalog execution report

Date: 2026-09-03
Status: structurally extracted; connected Catalog goldens now pass

## Outcome

The Dashboard, Movies, Series, VOD, Favorites, Search, and media-detail
presentation now live in `:feature:catalog`. The app remains the composition
root: it supplies platform/service adapters, player-request and payload
compatibility, shell/Settings composition, and root graph registration. Route
strings, arguments, return destinations, callbacks, focus/test semantics, and
the four golden test names were preserved; the feature-only baselines were
re-recorded after the original app-shell fixtures proved non-equivalent.

This report covers the Catalog slice only. Playback, Provider, Settings, and
Live status remain governed by their existing Phase 5 reports; Phase 5 is not
complete and `:feature:system` is still unstarted.

## Delivery sequence and rollback

The extraction was delivered in small commits from rollback point
`d303e2aa` (pre-extraction design/plan links):

| Task | Commit | Result |
|---:|---|---|
| 0 | `626903f0` | inventory, dependency ledger, focused baseline, before measurements |
| 1 | `70bb99ea` | module and fail-closed boundary |
| 2–4 | `ed3f819b`, `70023eea`, `9ca8cf0d` | contracts, app adapters, localized resources |
| 5–6 | `6a84d503`, `91782978` | shared primitives and browse presentation |
| 7–9 | `10c3a9c4`, `3fc94ea0`, `68423dba` | detail, Dashboard, Search/Favorites |
| 10 | `e3238404` | feature-owned graph registration |
| 11 | `4c7871fd` | goldens, legacy ownership cleanup, structural gates |
| 12 | `5f1e615e` | connected acceptance evidence and semantics fix |
| 13 | current documentation commit | isolation/build measurements and status report |

To roll back the Catalog work, return to `d303e2aa`; the intermediate commits
are intentionally independently revertible.

## Ownership and boundary

`feature/catalog` production code contains 50 Kotlin/Java files, 24 unit-test
files, and 3 instrumentation Kotlin/Java files, plus feature resources and
four route goldens. The six app Catalog screen directories are absent. The
Catalog boundary reports exactly these project dependencies:

```text
:core:navigation, :core:ui, :domain, :data
```

The boundary scan passes with zero forbidden main-source references (`:app`,
`:player`, sibling features, `MainActivity`, and root NavController types).
The temporary `PreferencesRepository` and `ProviderSyncStateSource` imports
are recorded in the transitional dependency ledger. Catalog has no `:player`
or sibling-feature dependency.

App-owned adapters cover Cast route selection, stream preparation, downloads,
update/install messaging, navigation payloads, player requests, and the shell
and Dashboard customization dialog. Favorites intentionally remains a direct
feature-host surface; no new Favorites route was introduced.

## Resources, tests, and goldens

The locale audit passed with 351 expected keys, zero missing/value/format
mismatches, zero unexpected keys, and 525 explicitly allowed source-locale
fallbacks. No app locale key was removed because app-shell and compatibility
consumers still resolve shared strings.

The four Catalog goldens are feature-owned and reviewed at 1920x1080:

```text
route_dashboard_default.png
route_movies_landing.png
route_series_detail.png
route_search_results.png
```

The feature unit, lint, Android-test compilation, resource processing, boundary,
and debug assembly gates passed. The app navigation-boundary task also passes
after removing obsolete `HomeGraph.kt`/`CatalogGraph.kt` expectations.

## Connected/device evidence

Target: `emulator-5554`, `Television_1080p(AVD) - 16`, API 36, AOSP TV x86,
1920×1080 physical display, 320 dpi, animation scales 1.0.

- Feature connected suite: 5/5 passed after correcting the golden harness to
  capture the full unpadded 1920x1080 Canvas surface and re-recording reviewed
  feature-only baselines.
- App `AppNavigationContractTest`: 3/3 passed.
- App `PlatformCompatibilityMatrixTest`: 4/4 passed.
- No Catalog-caused fatal exception was reported by these runs.

The partial seeded production smoke pass on the API 36 TV emulator loaded Home,
Live TV (1,467 synced channels), Movies, Series, and Search. Movies and Series
correctly reported `Sync needed` because the configured public M3U fixture has
live channels only; Search accepted a query and rendered its no-match state.
The full Dashboard shelf, VOD, detail, Favorites host, touch/RTL/reduced-motion,
and long screenshot/logcat journey matrix remains unavailable because this
checkout has no seeded VOD/catalog fixture or production-activity journey
harness. These are open acceptance gates, not passes.

## Build isolation and performance

Five reversible feature source-edit samples and five feature test-compile
samples all passed. Warm source-edit median was 8.3s (8.2–8.5s); warm
test-compile median was 8.7s (8.7–9.0s). Raw profile names and protocol are in
`validation/phase5_catalog/performance-before.md` and
`validation/phase5_catalog/performance-after.md`.

An actual Catalog-only edit followed by `:app:assembleDebug` executed the
Catalog Kotlin task; sibling feature Kotlin tasks were `UP-TO-DATE` and did
not execute. A clean debug assembly passed in 80.7s, warm debug assembly in
14.9s, and Beta/Release packaging passed in 332.3s. These are not a
cache-equivalent five-run clean-build comparison. The focused Dashboard
`dashboardVerticalScroll` macrobenchmark was attempted on the same emulator,
but failed before metrics with `Observed no renderthread slices in trace` from
`FrameTimingQuery`; no after P50/P90/P99 values were recorded.

## Profiles and known failures

The generated profile workflow was rerun on 2026-09-03 with
`:app:generateBaselineProfile` on the API 36 TV emulator. It passed in 19m
04s; the connected profile suite completed 18 tests, with the eight configured
macrobenchmark cases skipped by configuration. The refreshed sources contain
feature Catalog descriptors and no legacy app Catalog descriptors:

| Source | legacy app Catalog descriptors | feature Catalog descriptors |
|---|---:|---:|
| `baseline-prof.txt` | 0 | 885 |
| `startup-prof.txt` | 0 | 763 |

Generated files were not hand-edited. The run exercised seeded Home/Live
journeys; the public M3U fixture has no VOD/movie/series content, so full
Catalog production journey coverage remains open even though profile refresh
is now passing.

The repository-wide `:app:check` attempt reached the app lint task but failed
on 675 lint findings (first: API-level `Trace.beginAsyncSection` in
`AppStartupCoordinator.kt`; 901 baseline-filtered findings were also reported).
This is outside Catalog ownership and is not claimed as a Catalog pass. The
Catalog lint task itself passed with 802 warnings and one hint. Gradle also
reported 24 configuration-cache diagnostics for the existing boundary task's
execution-time project access.

## Next acceptance work

1. Add a seeded production-activity Catalog fixture and execute the documented
   journeys, including Favorites and accessibility variants.
2. Re-run the Dashboard macrobenchmark on a device/trace configuration that
   emits render-thread slices, then complete the cache-equivalent paired
   performance comparison. Keep app lint remediation and all other Phase 5
   feature gates tracked separately.
