# Compose Reduction Phase 5 — Catalog execution report

Date: 2026-09-02
Status: structurally extracted; acceptance gates remain open

## Outcome

The Dashboard, Movies, Series, VOD, Favorites, Search, and media-detail
presentation now live in `:feature:catalog`. The app remains the composition
root: it supplies platform/service adapters, player-request and payload
compatibility, shell/Settings composition, and root graph registration. Route
strings, arguments, return destinations, callbacks, focus/test semantics, and
the four reviewed golden assets were preserved.

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

The four goldens moved byte-for-byte into the feature:

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

- Feature connected suite: 1 behavior test passed; the four golden methods
  reached their assertions but failed the pre-pixel dimension check because
  the captured Compose window is 1824 px wide while the reviewed baselines are
  1920 px wide. Assets were not regenerated.
- App `AppNavigationContractTest`: 3/3 passed.
- App `PlatformCompatibilityMatrixTest`: 4/4 passed.
- No Catalog-caused fatal exception was reported by these runs.

Production seeded Dashboard/Movies/Series/VOD/Search/Details journeys,
Favorites host journeys, phone/tablet, RTL, reduced-motion, screenshot cadence,
and sanitized logcat evidence were unavailable: this checkout has no seeded
provider/catalog fixture or production-activity journey harness. These are
open acceptance gates, not passes.

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
cache-equivalent five-run clean-build comparison, and the Dashboard
macrobenchmark was not run.

## Profiles and known failures

The checked-in generated profile sources remain stale for this move:

| Source | legacy app Catalog descriptors | feature Catalog descriptors |
|---|---:|---:|
| `baseline-prof.txt` | 748 | 0 |
| `startup-prof.txt` | 726 | 0 |

The existing generator workflow is documented as passing in the Live/Provider
reports, but a fresh Catalog-specific generation was not repeated because it
requires the unavailable seeded journey harness and takes roughly 25 minutes.
Generated files were not hand-edited; profile regeneration is an open gate.

The repository-wide `:app:check` attempt reached the app lint task but failed
on 675 lint findings (first: API-level `Trace.beginAsyncSection` in
`AppStartupCoordinator.kt`; 901 baseline-filtered findings were also reported).
This is outside Catalog ownership and is not claimed as a Catalog pass. The
Catalog lint task itself passed with 802 warnings and one hint. Gradle also
reported 24 configuration-cache diagnostics for the existing boundary task's
execution-time project access.

## Next acceptance work

1. Run the profile generator on a seeded device and verify legacy descriptors
   disappear while feature descriptors appear.
2. Re-run the four goldens on the 1920-pixel baseline window or review a new
   1824-pixel baseline set.
3. Add a seeded production-activity Catalog fixture and execute the documented
   journeys, including Favorites and accessibility variants.
4. Run the Dashboard macrobenchmark and a cache-equivalent paired performance
   comparison. Keep app lint remediation and all other Phase 5 feature gates
   tracked separately.
