# Compose Reduction Phase 5 — Settings Feature Report

Date: 2026-08-28

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
  15,240 nodes, 29,727 edges, and 377 communities. `SettingsViewModel` remains
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
- `32e716a6` — moved resource-free language option and locale label formatters
- `0e496921` — moved subtitle/player option models and label formatters
- `7692681c` — moved backup selection/management dialogs into the feature
- `0e309e15` — made backup dialog models and composables public at the boundary
- `d5ea4004` — moved player preference dialog orchestration into the feature
- `5aa18484` — made player preference dialog entry point public at the boundary
- `25c22123` — moved external playback mode dialog presentation into the feature
- `431a543b` — removed the app-side update-model typealias and updated its test
- `014e4c84` — moved provider action, warning, and M3U classification cards
- `d176dbc1` — moved settings preference-dialog orchestration and labels
- `40cd49df` — moved the settings playback section into the feature
- `0eb55acf` — moved live-stream-format label formatting to the feature
- `854fffde` — moved browsing/navigation preference section presentation
- `3e26db98` — moved combined-M3U profile card and dialog presentation
- `20244227` — moved provider-management dialog orchestration
- `dfb4449f` — moved provider settings section, provider card, diagnostics panel,
  catalog helpers, and feature-local time formatting
- `b67ef1ae` — moved recording settings dashboard/actions cards and shared
  recording presentation primitives
- `34cf974f` — moved recording metric/status display primitives and retained
  the app-owned time-window adapter for browser orchestration
- `5407ced4` — moved recording browser detail metrics/actions and the shared
  compact recording action chip
- `0d8b6046` — moved recording browser display/item helpers and playback URL
  normalization into the settings feature
- `4d06b695` — moved speed-test summary/value formatting and labels into the
  settings feature, removing the duplicate app formatter file
- `92333570` — moved recording pattern, retention, concurrency, and padding
  dialogs into the settings feature
- `32f40371` — moved recording-browser sidebar controls behind an explicit
  television-device capability input
- `80af3673` — split the recording dashboard/actions section from its
  app-owned browser launcher/navigation adapter
- `9b72c593` — moved the recording browser dialog renderer into the settings
  feature with explicit time-format and television-device inputs
- `0f52133d` — moved backup/restore, Drive-backup, and About/update/crash
  presentation into the settings feature while keeping app version/build and
  external-URI adapters in `:app`
- `27677327` — moved settings dialog orchestration, restore-sync and crash
  report dialogs, and clear-history presentation into the feature
- `8f47d919` — moved the settings overlay host into the feature and injected
  the app-owned recording-browser launcher as a composable callback
- `7975802c` — moved settings screen label/state composition and its resource
  formatters into the feature; app build verification is mapped to the neutral
  feature status type
- `5fe8a42b` — moved the settings content pane renderer into the feature and
  kept the app version label as an explicit composition-root input
- `ae832ee4` — moved locale-aware backup timestamp formatting into the feature
  while retaining the app-specific `BackupFileBridge` candidate adapter
- `579a00cc` — moved Settings and parental-control route registration into a
  feature-owned graph contract; `:app` retains route codec and screen callbacks
- `fba66992` — added feature route-pattern coverage for Settings `backupUri`
  and parental `providerId` compatibility

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

The language-formatting batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 31s
```

Supported-language ordering, system/auto fallback labels, and locale display
capitalization remain unchanged.

The subtitle/player-formatting batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 41s
```

Subtitle size/color option ordering, decoder/audio-output/surface labels, and
fallback labels remain unchanged while these resource-backed formatters now
resolve feature-owned strings.

The backup-dialog batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 2m 14s
```

Backup item ordering, initial-body focus, D-pad/click behavior, delete
confirmation, busy-state disabling, and dismissal callbacks remain unchanged;
candidate discovery and deletion persistence stay in the app composition root.

The player-preference-dialog batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 38s
```

Playback speed, time format, decoder/output/surface, buffer/protocol,
timeshift, timeout, timer, subtitle, and translation dialog ordering and
selection callbacks remain unchanged; mutations continue through the existing
settings ViewModel.

The external-playback-dialog batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 45s
```

Internal/external option ordering, selected-state handling for the legacy
ask-every-time value, focus/click behavior, and dismissal callbacks remain
unchanged.

The app-update-model ownership cleanup was verified with:

```text
gradlew.bat :feature:settings:check :app:testDebugUnitTest --tests "com.streamvault.app.ui.screens.settings.SettingsAppUpdateModelsTest" --no-daemon
BUILD SUCCESSFUL in 54s
```

The app test now imports `AppUpdateUiModel` directly from the settings feature;
the feature remains the sole production owner of that presentation model.

The provider-action-card batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 53s
```

Provider connect/sync/edit/delete/category-control actions, warning filtering
and retry actions, M3U classification toggle/refresh behavior, focus styling,
and busy-state disabling remain unchanged.

The preference-dialog batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 2m 19s
```

Landing-screen availability, guide-category selection, language/audio/subtitle
options, quality-cap selection, remote shortcut mapping, category-sort state,
and all player-dialog delegation remain unchanged; state mutations continue
through `SettingsViewModel`.

The playback-section batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 59s
```

Playback/live rows, toggle state, live-format option ordering, focus/click
behavior, and dialog-open callbacks remain unchanged.

The browsing-section batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 57s
```

Browsing toggle persistence, category-sort and mode row ordering, remote
shortcut interactions, and all dialog-open callbacks remain unchanged.

The combined-M3U presentation batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 2m 16s
```

Combined-profile selection, activation, rename/create/member dialogs,
provider ordering/toggle/remove behavior, selected-state styling, and callback
sequencing remain unchanged.

The provider-management-dialog batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 2m 8s
```

Combined-profile create/rename/member flows, provider sync/custom-sync
selection, delete confirmation/progress, busy-state disabling, and dialog-state
reset behavior remain unchanged.

The provider-presentation batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 31s
```

Provider selection/empty states, expiration and onboarding messaging, catalog
diagnostic counts, diagnostics/database-health panels, M3U controls, warning
actions, and provider callbacks remain unchanged. `AppTimeFormat` is passed
through the feature boundary and uses feature-local equivalents of the former
date/time adapters; no app package dependency was introduced.

The recording-card batch was verified with the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 55s
```

Recording storage/status cards, action-button focus styling, Wi-Fi-only toggle
callbacks, output-path summarization, recording source/failure labels, and
browser/detail call sites remain behavior-compatible; recording launchers and
persistence remain app-owned.

The recording-display-primitive batch was verified with focused compilation and
the settings feature check:

```text
gradlew.bat :feature:settings:check :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 42s
```

Metric cards, recording title/subtitle fallback, status labels, status colors,
and browser/sidebar/detail call sites remain unchanged. The browser time-window
line now uses the feature's date/time formatter with `AppTimeFormat` supplied by
the app composition layer.

The recording-browser-detail batch was verified with:

```text
gradlew.bat :feature:settings:check :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 16s
```

Detail metric cards, play/stop/cancel/skip/delete/retry actions, schedule-toggle
behavior, compact-chip focus styling, and browser/sidebar call sites remain
unchanged; recording dialog orchestration and time-window formatting remain in
the app composition root.

The recording-browser display/helper batch was then verified with:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 33s
```

The standalone recording item card, playback-URL normalization helper, and
browser secondary-line formatter now live in `:feature:settings`; the app still
owns the dialog orchestration, platform time provider, playback launcher, and
recording persistence callbacks.

The speed-test-formatting batch was verified with the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 39s
```

Speed-test value/transport/summary formatting now resolves feature resources;
the app state builder continues to supply its existing context and date-time
format, while test execution and persistence remain outside presentation.

The recording-settings-dialog batch was covered by the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 31s
```

Pattern, retention, concurrency, and padding option ordering plus dismissal and
ViewModel callbacks remain unchanged; the app retains the surrounding screen
dialog state and recording persistence orchestration.

The recording-browser-sidebar batch was covered by the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 1m 28s
```

Search filtering, status-chip toggling, D-pad focus, TV keyboard gating, and
selection callbacks remain unchanged; only the device capability lookup stays
in the app composition layer.

The recording-section split was verified by the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 44s
```

The feature now owns the recording dashboard/actions list; the app retains only
the browser launcher adapter that decodes the current route and invokes the
player callback.

The recording-browser-renderer batch was verified with the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
BUILD SUCCESSFUL in 47s
```

Dialog rendering, empty-state behavior, picker/detail focus handling, status
filters, time-window formatting, and recording callbacks remain unchanged; the
app adapter supplies only platform time/device values and player navigation.

The backup/About presentation batch was verified with the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 1m 34s
```

Backup export/import, local and USB backup actions, Drive sign-in/push/pull and
snapshot labels, About/update/crash-report rows, and their focus/callback
behavior now render from `:feature:settings`. The app supplies the version/build
label and continues to own URI launching, backup orchestration, and persistence;
the feature boundary and app Kotlin/unit-test compilation both remain clean.

The settings-dialog orchestration batch was verified with the same focused
command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 1m 38s
```

Dialog ordering, restore-sync selection behavior, crash-report scrolling and
focus handling, clear-history callbacks, and sync-overlay cancellation remain
unchanged; only the presentation/resource ownership moved to the feature.

The settings-overlay batch was verified with the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 1m 33s
```

Snackbar, recording-browser visibility, and dialog-overlay ordering remain
unchanged. The feature now hosts the overlay composition while the app retains
the recording playback/navigation adapter through the injected callback.

The settings-label-state batch was verified with the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 1m 37s
```

Settings label derivation, time-format handling, build-verification mapping,
plural summaries, and existing formatter test expectations remain unchanged;
the app retains only its platform `MainActivity` lookup and build-verifier
adapter.

The settings-content-pane batch was verified with the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 1m 34s
```

Category routing, section ordering, row/dialog callbacks, and feature-owned
labels remain unchanged; the app now supplies only the version label alongside
its existing platform and navigation callbacks.

The backup timestamp formatter batch was verified with the same focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 1m 34s
```

Local, folder, USB, and Drive backup detail timestamps retain the existing
locale-aware short date/time behavior; only the generic formatter moved into
the feature.

The feature-route registration batch was verified with the focused command:

```text
gradlew.bat :feature:settings:check :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 1m 33s
```

Settings `backupUri` and parental `providerId` arguments retain their existing
route patterns and defaults; the app continues to decode navigation routes and
supplies the screen callbacks, while the feature owns destination registration.

The route-pattern compatibility test was verified with:

```text
gradlew.bat :feature:settings:testDebugUnitTest --tests "com.streamvault.feature.settings.navigation.SettingsRoutePatternsTest" :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 29s
```

## Open work and gates

- Move the remaining Settings presentation and resources while keeping routes,
  focus restoration, semantics, callbacks, persistence, launcher ordering, and
  error text unchanged.
- Finish feature resource/locale ownership and remove transitional app
  wildcard imports/duplicate defaults once all consumers move; the feature now
  owns the Settings and parental route registration contract.
- Complete the DAO/concrete dependency audit and remove each ledger entry only
  after a domain-facing replacement exists.
- Add/refresh focused settings connected checks and perform manual TV journeys
  for parental controls, backup/restore, update, diagnostics, sync, and focus
  restoration when the required emulator/accounts/files are available.
- Do not treat the still-open playback/provider acceptance and performance
  gates as closed by this settings report.
