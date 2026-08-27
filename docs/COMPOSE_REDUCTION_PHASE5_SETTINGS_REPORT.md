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
  14,994 nodes, 29,048 edges, and 378 communities. `SettingsViewModel` remains
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
