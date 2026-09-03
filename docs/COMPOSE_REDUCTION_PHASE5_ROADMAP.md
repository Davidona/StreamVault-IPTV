# Compose Reduction Phase 5 Roadmap

Date: 2026-08-24

## Purpose

Phase 5 moves feature presentation from the monolithic :app module into a small
number of independently compiled and tested feature modules. It is a sequence
of behavior-preserving extractions, not one large migration.

The governing design is
docs/superpowers/specs/2026-08-24-phase-5-feature-module-extraction-design.md.

## Delivery sequence

| Order | Module | Primary move unit | Special gate |
|---:|---|---|---|
| 1 | :feature:playback | Player, overlays, MultiView, playback graph | Full two-channel long-duration live validation |
| 2 | :feature:provider | Provider setup/edit/import/pairing | Create/edit/import, file launchers, focus and typing |
| 3 | :feature:settings | Settings, parental control, backup/restore presentation | Backup/restore, dialogs, DAO/concrete dependency audit |
| 4 | :feature:live | Home Live TV, categories, EPG, preview presentation | Preview handoff, EPG navigation, multi-channel live validation |
| 5 | :feature:catalog | Dashboard, movies, series, VOD, favorites, search | Detail return routes, shelves, search, lazy-list behavior |
| 6 | :feature:system | Welcome, downloads, plugins | Extract only if measured isolation exceeds module overhead |

## Standard extraction shape

Every feature follows the same review sequence:

1. Record source ownership, app/project imports, resources, Hilt bindings,
   tests, and representative incremental build measurements.
2. Create the Android library module and a boundary task that rejects :app,
   root controller, and unapproved project dependencies.
3. Move typed route patterns and controller-free graph registration.
4. Introduce the minimum platform/service ports needed to remove app imports.
5. Move source, resources, ViewModels, Hilt bindings, unit tests, and
   feature-owned instrumentation tests with behavior unchanged.
6. Wire AppNavHost to feature registration and remove the old implementation
   only after the new destination passes.
7. Run independent feature checks, app integration checks, functional/device
   gates, build measurements, profile maintenance, and graph refresh.
8. Write docs/COMPOSE_REDUCTION_PHASE5_<FEATURE>_REPORT.md and update the
   transitional dependency ledger.

## Shared guardrails

- Features may depend on :core:navigation, :core:ui, :domain, and relevant
  technical modules such as :player.
- A temporary :data dependency is allowed only when every imported type is
  recorded in docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md.
- Feature implementations never depend on each other or on :app.
- AppNavHost remains the root graph registry.
- Routes, arguments, focus, semantics/test tags, UI output, and callback
  sequence remain unchanged during extraction.
- Playback recovery, timeshift, and stream policy changes require a separate
  bug-fix scope.
- Generated baseline/startup profiles are regenerated after package/signature
  moves and are never hand-maintained.

## Measurement gates

For each extracted feature, record:

- five before/after incremental feature-edit build runs;
- five before/after incremental feature-test compile runs;
- clean debug and warm no-change guardrails;
- the exact task set triggered by a feature-only edit;
- feature and app source/file counts before and after;
- runtime or functional evidence for the feature's hot flows;
- device, unavailable-gate, and existing-failure details.

The phase succeeds when a feature edit no longer recompiles unrelated feature
source, feature tests run independently, and most presentation code has moved
out of :app without behavior or stability regressions.

## Current plan

- Playback:
  docs/superpowers/plans/2026-08-24-playback-feature-extraction.md
- Playback execution report:
  docs/COMPOSE_REDUCTION_PHASE5_PLAYBACK_REPORT.md
- Provider extraction implementation plan:
  docs/superpowers/plans/2026-08-26-provider-feature-extraction.md
- Provider execution report:
  docs/COMPOSE_REDUCTION_PHASE5_PROVIDER_REPORT.md
- Settings extraction implementation plan:
  docs/superpowers/plans/2026-08-27-settings-feature-extraction.md
- Settings execution report:
  docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_REPORT.md
- Live extraction implementation plan:
  docs/superpowers/plans/2026-08-28-live-feature-extraction.md
- Live execution report:
  docs/COMPOSE_REDUCTION_PHASE5_LIVE_REPORT.md
- Catalog extraction design:
  docs/superpowers/specs/2026-09-02-phase-5-catalog-feature-extraction-design.md
- Catalog extraction implementation plan:
  docs/superpowers/plans/2026-09-02-catalog-feature-extraction.md
- Catalog execution report:
  docs/COMPOSE_REDUCTION_PHASE5_CATALOG_REPORT.md
- Transitional dependency ledger:
  docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md

The settings implementation plan and execution report document the structural
extraction, resource ownership cleanup, dependency audit, and open runtime
gates. The Live implementation plan and report now document the Home/EPG
extraction, translated-resource parity, reviewed feature goldens, connected
checks, populated MultiView planner journey, and two-channel runtime evidence.
The exhaustive fixture-dependent journeys and formal paired performance and
clean-build guardrails remain explicit. Current-checkout clean/warm samples
and successful Beta/Release packaging are recorded. Profile generation now
passes on the seeded API 36 TV emulator, with fresh sources containing zero
stale app Home/EPG descriptors and nonzero `feature/live` descriptors; the
eight separate macrobenchmark tests were skipped by configuration and remain
an open performance gate. The app-route Live golden was reviewed and
regenerated from the current palette, with its connected assertion passing.
Catalog extraction is structurally implemented with passing connected golden
checks and current-checkout build-isolation samples. The public-M3U seeded
Home/Live/Movies/Series/Search smoke pass is supplemented by a diagnostic
temporary Xtream fixture run covering VOD/series details, episodes, saved
filters, and cross-type Search; the semantic evidence is recorded in the
Catalog validation directory. A committed rerunnable fixture is still needed
for the remaining Dashboard/action/direct-Favorites/accessibility journeys.
A focused Dashboard macrobenchmark rerun passed 1/1 with five warm iterations
once public-M3U live-channel activity populated a scrollable Home shelf
(`frameCount` min/median/max 88/91/98; `frameOverrunMs` P50/P90/P95/P99
63.1/78.9/89.7/106.1). The earlier empty Home fixture emitted no render-thread
slices; the rerun is diagnostic after-run evidence, not a cache-equivalent
paired comparison. The paired comparison and repository-wide app lint remain
open.
The release-like profile workflow now passes with feature Catalog descriptors.
System remains unstarted.
