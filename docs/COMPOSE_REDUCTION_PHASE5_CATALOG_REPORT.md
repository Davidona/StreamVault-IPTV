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
Live TV (1,319 synced channels in the latest run), Movies, Series, and Search.
Movies and Series
correctly reported `Sync needed` because the configured public M3U fixture has
live channels only; Search accepted `3ABN` and rendered seven Live TV results
(Movies 0, Series 0), while the earlier `CNN` query rendered its no-match
state.

As a follow-up, a temporary local Xtream-compatible fixture was used with the
same debug `MainActivity` on this emulator. It returned one live channel, two
movies, two series, movie metadata, and two episodes per series. The run
rendered Dashboard media shelves, Movies and Series browse cards, movie and
series details, season/episode rows, movie and series favourite toggles, saved
filters, and a five-result cross-type Search query (Live TV 1, Movies 2,
Series 2). The semantic route/title/count evidence and the exact fixture
limitations are recorded in
`validation/phase5_catalog/task14-xtream-fixture-journeys.md`. The fixture is
now available as the checked-in development tool
`tools/catalog_xtream_fixture.py`; the repeatable semantic journey is exposed
by `tools/catalog_connected_validation.py`. This remains diagnostic evidence,
not a production-provider pass.

The same semantic harness now also opens the Settings-owned Dashboard shelf
customization dialog through TV focus, proves cancel leaves the persisted
seven-shelf order unchanged, saves a six-shelf edit, and resets/saves the
default order again. The rerun and surface list are recorded in
`validation/phase5_catalog/task14-xtream-fixture-journeys.md`. The remaining
browse load-more/reorder/return, download, Cast chooser, direct Favorites-host,
touch/phone/tablet, RTL, reduced-motion/accessibility, and long screenshot/logcat
matrix are still open.

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
cache-equivalent five-run clean-build comparison. A focused Dashboard
`dashboardVerticalScroll` rerun on the same emulator passed 1/1 with five warm
iterations after public-M3U live-channel activity populated a scrollable Home
recent-channel shelf. It emitted `frameCount` min/median/max 88/91/98,
`frameDurationCpuMs` P50/P90/P95/P99 60.0/70.7/78.7/92.7, and
`frameOverrunMs` P50/P90/P95/P99 63.1/78.9/89.7/106.1. The earlier empty Home
fixture had no scrollable content and failed before metrics with
`Observed no renderthread slices in trace` from `FrameTimingQuery`. This rerun
is diagnostic after-run evidence only: no cache-equivalent pre-extraction run
was captured, and the public M3U fixture still lacks VOD/series content, so the
formal paired performance gate remains open.

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
journeys; the public M3U fixture has no VOD/movie/series content. The separate
temporary Xtream journey run is documented as diagnostic and does not change
the profile input or close the full Catalog production journey gate.

The repository-wide `:app:check` attempt reached the app lint task but failed
on 675 lint findings (first: API-level `Trace.beginAsyncSection` in
`AppStartupCoordinator.kt`; 901 baseline-filtered findings were also reported).
This is outside Catalog ownership and is not claimed as a Catalog pass. The
Catalog lint task itself passed with 802 warnings and one hint. Gradle also
reported 24 configuration-cache diagnostics for the existing boundary task's
execution-time project access.

## Next acceptance work

1. Use the checked-in Xtream fixture (or approved provider data) to execute the
   remaining browse, detail-action, direct Favorites, and accessibility
   journeys, and keep the fixture invocation in the validation protocol.
2. Capture a cache-equivalent pre-extraction run and pair it with the
   successful Dashboard benchmark evidence, using the same seeded content,
   device, iteration count, and compilation mode. Keep app lint remediation and
   all other Phase 5 feature gates tracked separately.
