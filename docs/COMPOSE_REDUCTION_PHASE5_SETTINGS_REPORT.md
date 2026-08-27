# Compose Reduction Phase 5 — Settings Feature Report

Date: 2026-08-27

## Current status

The settings slice is **in progress**. A compilable, independently checked
`:feature:settings` module now owns the first behavior-preserving presentation
layers: shared settings contracts, backup-preview rendering, parental controls,
settings state/models/actions/observers, preference mapping, backup/restore and
EPG actions, the Hilt `SettingsViewModel`, and the related feature tests.
`:app` remains the composition root and continues to own the platform adapters
and the portions of the Settings screen that have not yet moved.

This is not Phase 5 completion. The remaining screen/dialog/resource
extraction, app adapter cleanup, full graph registration,
and runtime/manual acceptance are still open. The existing playback and
provider runtime gates remain open under their respective reports.

## Ownership and boundary evidence

- `:feature:settings` is registered and consumed by `:app` through the feature
  dependency; the app still owns root navigation and platform composition.
- The feature boundary allows only `:core:navigation`, `:core:ui`, `:domain`,
  temporary ledgered `:data`, and the audited `:player` dependency.
- The feature source has no `com.streamvault.app`, `NavController`,
  `NavHostController`, `MainActivity`, `:feature:provider`, or
  `:feature:playback` references.
- Feature-defined ports cover backup/restore, diagnostics, TV-surface refresh,
  sync, pairing, update checks/downloads, and external settings actions. App adapters remain
  responsible for Android intents, workers, installers, and other platform
  operations.
- `ProviderBackupPreviewRequest` and `ProviderBackupPreviewContent` remain
  source-compatible; the provider flow can request the settings-owned preview
  without a provider implementation dependency.
- The nine direct settings `:data` implementation imports are recorded in
  `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`; the separate
  `AudioCompatibilityMemoryStore` audit is recorded there as a non-`:data`
  dependency.
- Graphify was refreshed after the presentation move; the current corpus has
  15,084 nodes, 29,274 edges, and 379 communities. `SettingsViewModel` remains
  a high-connectivity coordination node while moved presentation nodes now
  resolve under `feature/settings`.
- The moved shared widget's default overview values match the app catalog
  exactly. Locale catalogs remain app-owned until the full settings resource
  batch moves them together; a partial locale copy would trigger the feature's
  existing MissingTranslation lint set and is therefore intentionally not
  treated as complete resource extraction.

## Delivered commits

- `e12f816f` — inventory and baseline evidence
- `1c0e6ae6` — settings feature module boundary
- `422c2810` — shared settings presentation contracts
- `e9a44e24` — settings platform service ports
- `58da5a74` — settings-owned backup preview rendering
- `0441d1e6` — parental controls extraction
- `20a3f3f5` — settings state and actions extraction
- `3090ad75` — feature lint fixes (network permission and Compose resource lookup)
- `3a9dda13` — settings ViewModel and operational observer/action ownership
- `296bb639` — shared settings widgets, EPG source/assignment presentation,
  dialog state, and the parental action model
- `4c814fe1` — selection dialog primitives, timeout dialog/formatter, and
  top-navigation dialog presentation
- `39cf0532` ג€” moved Live TV/parental presentation into the feature source set
- `55642c6a` ג€” feature-owned Live TV/parental strings and enum label formatters
- `b00eb575` ג€” moved the sync progress overlay and its feature-owned strings
- `3a1e264a` ג€” moved the app-update label and action-state formatters
- `19ffbe42` ג€” moved the Internet Speed Test card and feature-owned labels
- `5a9a5bce` ג€” moved provider sync dialogs and their feature-owned labels
- `25a04b89` ג€” moved provider selector, catalog-stat, and status-badge widgets
- `ef1d965d` ג€” moved parental PIN/protection-level dialog orchestration
- `81135ed5` ג€” moved quality-cap/category-sort dialogs and label formatters
- `08524a90` ג€” moved timer, A/V offset, and generic text-value dialogs
- `3f2c490d` — moved the settings navigation rail and shared navigation item
- `eaff72a5` — moved Privacy presentation behind explicit action callbacks
- `8ef92853` — moved shared settings rows and Live TV quick-filter presentation
- `47553c08` — moved resource-free settings primitive formatters
- `5d60ea1b` — moved mode-selection dialogs and enum/remote label formatters

The design/spec and detailed implementation plan are tracked documentation for
the ongoing slice:

- `docs/superpowers/specs/2026-08-27-phase-5-settings-feature-extraction-design.md`
- `docs/superpowers/plans/2026-08-27-settings-feature-extraction.md`

## Automated verification

The following command completed successfully with exit code 0 after the lint
fix:

```text
gradlew.bat :feature:settings:check --console=plain --warning-mode=none
BUILD SUCCESSFUL in 31s
```

The focused cross-module command also completed successfully with exit code 0:

```text
gradlew.bat :feature:settings:verifyFeatureSettingsBoundary \
  :feature:settings:testDebugUnitTest \
  :app:compileDebugKotlin \
  :app:compileDebugUnitTestKotlin \
  --console=plain --warning-mode=none
BUILD SUCCESSFUL in 5s
```

These checks cover the fail-closed source boundary, feature debug unit tests,
app Kotlin compilation, and app unit-test compilation. No runtime or connected
device claim is implied by these commands.

The shared/EPG presentation batch was rechecked after the move with:

```text
gradlew.bat :feature:settings:check --console=plain --warning-mode=none
BUILD SUCCESSFUL in 1m 8s

gradlew.bat :app:compileDebugUnitTestKotlin --console=plain --warning-mode=none
BUILD SUCCESSFUL in 13s
```

The app compile initially found the expected cross-module seam (moved symbols
were still internal or lacked feature imports); those integration points were
made explicit and the subsequent feature check and app unit-test compile both
passed. The settings feature still has no app, navigation-controller, or
activity references.

The follow-up dialog batch also passed:

```text
gradlew.bat :feature:settings:check --console=plain --warning-mode=none
BUILD SUCCESSFUL in 54s

gradlew.bat :app:compileDebugKotlin --console=plain --warning-mode=none
BUILD SUCCESSFUL in 12s

gradlew.bat :app:compileDebugUnitTestKotlin --console=plain --warning-mode=none
BUILD SUCCESSFUL in 5s
```

The Live TV/parental presentation batch was then checked as one focused build:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 44s
```

This run included the feature boundary task, feature debug/release unit tests,
lint, app Kotlin compilation, and app unit-test compilation. The feature source
still has no app, navigation-controller, or activity references.

The sync-overlay batch was verified with the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 35s
```

The check preserved the overlay's cancel-focus behavior and confirmed the
feature boundary and app integration remain clean.

The app-update formatter batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 25s
```

The pure formatters now resolve feature resources while the app update adapter
and launch/install callbacks remain in the composition root.

The Internet Speed Test card batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 29s
```

The card remains callback-driven; speed-test execution and persistence stay
outside the presentation module.

The provider-sync dialog batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 22s
```

The provider type branching, custom selection state, D-pad/click behavior, and
callbacks remain unchanged; only their presentation ownership moved.

The provider-widget batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 24s
```

Feature-owned status/catalog labels now back the shared widgets while the
provider card's orchestration remains in `:app`.

The parental-dialog batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 20s
```

PIN verification, pending protection-level handling, and dismissal callbacks
remain behavior-compatible; only dialog presentation ownership moved.

The quality-cap/category-sort dialog batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 10s
```

Selection ordering, selected-state rendering, category descriptions, and
quality-cap callbacks remain unchanged.

The timer/value-dialog batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 15s
```

Timer preset ordering, offset clamping/reset/save behavior, and trimmed text
callbacks remain unchanged.

The navigation rail batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 16s
```

Navigation category order, selected-state styling, focus requester placement,
and category callbacks remain unchanged.

The Privacy presentation batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 53s
```

Parental-control dialog state, incognito and Xtream compatibility toggles, and
clear-history dialog callbacks remain unchanged.

The shared-row and quick-filter presentation batch was verified with the
focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 18s
```

Row focus/click behavior and quick-filter add/remove/empty-state behavior remain
unchanged.

The resource-free formatter batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 4s
```

Byte/timestamp formatting, playback-speed labels, timeout preset ordering, and
recording presentation call sites remain unchanged.

The mode-dialog batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 3m 12s
```

Mode option ordering, selected-state rendering, remote shortcut option labels,
and dismissal/selection callbacks remain unchanged.

## Open work and gates

- Move the remaining Settings presentation and resources while keeping routes,
  focus restoration, semantics, callbacks, persistence, launcher ordering, and
  error text unchanged.
- Finish feature resource/locale ownership and the settings graph registration;
  remove transitional app wildcard imports once all consumers move.
- Complete the DAO/concrete dependency audit and remove each ledger entry only
  after a domain-facing replacement exists.
- Add/refresh focused settings connected checks and perform manual TV journeys
  for parental controls, backup/restore, update, diagnostics, sync, and focus
  restoration when the required emulator/accounts/files are available.
- Do not treat the still-open playback/provider acceptance and performance
  gates as closed by this settings report.
