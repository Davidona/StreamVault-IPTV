# Phase 5 Settings Feature Module Extraction Design

Date: 2026-08-27
Status: Approved for implementation planning

## Context

Phase 5 moves presentation out of `:app` into independently compiled feature
modules without changing behavior. Playback now lives in `:feature:playback`
and provider setup lives in `:feature:provider`. Their reports retain open
runtime, manual, and performance gates; this settings extraction does not close
or reinterpret those gates.

Settings is the next roadmap slice. Its current package contains 71 production
Kotlin files and 19,483 lines, plus 12 unit-test files and 1,031 test lines.
Graphify identifies `SettingsViewModel` as a god node with 112 connections.
The ViewModel has 27 constructor dependencies and coordinates preferences,
providers, EPG, recordings, parental controls, backup/restore, Drive backup,
application updates, diagnostics, TV surfaces, and direct DAO state.

The extraction is therefore a contract-first, staged ownership transfer. It
is not a one-shot package rename and not an opportunity to redesign Settings.

## Goal

Create `:feature:settings` as the sole owner of Settings presentation,
parental-control presentation, backup/restore presentation, settings state and
dialogs, feature resources, tests, and graph registration while keeping
`:app` as the platform and navigation composition root.

The extracted feature must compile and test independently. A settings-only
source edit must not compile playback or provider feature source. Routes,
arguments, focus, semantics, callbacks, persistence, file-launcher behavior,
and visual output remain equivalent.

## Non-goals

- Do not convert Settings or backup flows to classic Views in Phase 5.
- Do not redesign the settings navigation rail, rows, dialogs, or section
  organization.
- Do not split `SettingsViewModel` behavior or replace its state model during
  the mechanical ownership move.
- Do not change backup formats, conflict resolution, Drive behavior, provider
  synchronization, recording policy, parental-control policy, or app-update
  policy.
- Do not extract Downloads, Plugins, Live, EPG, catalog, or system presentation.
- Do not add a feature-to-feature implementation dependency.
- Do not claim Phase 5 completion while playback/provider/settings acceptance
  or performance gates remain open.

## Current ownership and coupling

### Settings package

The move unit is:

```text
app/src/main/java/com/streamvault/app/ui/screens/settings/
app/src/test/java/com/streamvault/app/ui/screens/settings/
```

It includes the root screen, sections, dialogs, action coordinators, derived
observers, formatting/model helpers, `SettingsViewModel`, the parental-control
group route/ViewModel, backup preview, Drive backup, provider management,
recording browsing, EPG configuration, app-update presentation, and speed test.

### Navigation

`app/navigation/graph/SystemGraph.kt` currently registers Downloads, Settings,
Plugins, and parental-control groups. `AppRouteCodec` owns compatibility
encoding/decoding for the Settings backup URI and parental provider ID.

Settings extraction moves only Settings and parental-control graph
registration. Downloads and Plugins remain app-owned. Compatibility encoding
and external-intent parsing remain app-owned.

### Existing provider backup-preview seam

`:feature:provider` exposes `ProviderBackupPreviewContent` and
`ProviderBackupPreviewRequest`. `AppNavHost` currently satisfies that contract
with the app-owned `BackupImportPreviewDialog` from Settings.

Settings takes ownership of `BackupImportPreviewDialog` without changing the
provider API. `AppNavHost`, as composition root, continues to translate the
provider request field-for-field into the settings composable. Neither feature
imports the other.

### Direct implementation dependencies

The current settings package directly imports these `:data` types:

| Implementation type | Current responsibility | Phase 7 removal direction |
|---|---|---|
| `ProgramDao` | provider program-count diagnostics | domain diagnostics query |
| `XtreamIndexJobDao` | Xtream index status observation | domain sync-status query |
| `XtreamLiveOnboardingDao` | live onboarding progress | domain onboarding-status query |
| `XtreamIndexJobEntity` | index status mapping | domain sync-status model |
| `XtreamLiveOnboardingStateEntity` | onboarding status mapping | domain onboarding-status model |
| `DatabaseMaintenanceSnapshot` | database maintenance UI mapping | domain maintenance snapshot |
| `PreferencesRepository` | settings persistence and observation | domain application-settings contract |
| `ProviderSyncCommands` | repair/cancel commands | domain provider-sync commands |
| `SyncRepairSection` | repair section selection | domain provider-sync section |

The feature also uses concrete `AudioCompatibilityMemoryStore` from `:player`.
That dependency is allowed by the Phase 5 technical-module rules but is
recorded as a Phase 7 contract-cleanup candidate.

### App implementation dependencies

The package currently imports app resources, shell components, shared display
models, backup files, diagnostics, update services, TV-surface managers,
TV-input work, `MainActivity`, route codecs, locale helpers, and build
verification. These imports cannot cross the feature boundary. They are
resolved through the ownership rules and ports below.

## Target dependency direction

```text
:app
  -> :feature:settings
  -> :feature:provider
  -> :feature:playback
  -> :core:navigation / :core:ui / :domain / :data / :player

:feature:settings
  -> :core:navigation
  -> :core:ui
  -> :domain
  -> :data       # temporary, every imported type ledgered
  -> :player     # AudioCompatibilityMemoryStore audit candidate
```

`:feature:settings` must not depend on `:app`, `:feature:provider`, or
`:feature:playback`. Feature-to-feature navigation is expressed with core
destinations and app callbacks.

## Target ownership

### `:feature:settings` owns

- Settings and parental-control route patterns and graph registration.
- `SettingsScreen`, `SettingsViewModel`, `SettingsUiState`, action helpers,
  observers, sections, rows, dialogs, formatters, and operational UI models.
- `ParentalControlGroupScreen`, its ViewModel, state, and components.
- Backup/restore and Drive-backup presentation, including
  `BackupImportPreviewDialog`.
- Settings-owned backup file selection/management UI behavior behind the
  platform file port.
- Settings-owned resources, locale translations, unit tests, connected tests,
  Compose diagnostics, and module-boundary verification.
- Pure app-update presentation models and selection/formatting policy. The
  update transport/installer implementation remains app-owned.
- The dashboard-shelf customization dialog, because it is invoked only from
  Settings and edits a persisted settings preference.

### `:app` retains

- `MainActivity`, root `NavHostController`, `AppNavHost`, external intents,
  `AppRouteCodec`, and navigation payload storage.
- Downloads and Plugins registration in the app system graph.
- `BackupFileBridge` implementation used by external-intent parsing.
- `CrashReportStore` installation and storage implementation.
- app-update download/network/install implementations and worker entry points.
- Watch Next, launcher recommendations, TV-input manager/worker, build identity,
  package verification, and other platform implementations.
- Hilt adapters from app implementations to settings feature ports.
- Mapping `ProviderBackupPreviewRequest` to the settings-owned preview dialog.

### Neutral shared ownership

- Generic shell visuals, TV empty/search controls, PIN entry, time formatting,
  and device classification use or move to `:core:ui` only when their APIs are
  app-agnostic.
- Persisted display preference enums used by Settings plus Live or Catalog move
  to `:domain`; storage values and parsing remain unchanged.
- Typed settings and parental destinations remain in `:core:navigation`.

## Public feature boundary

### Routes and graph registration

The feature owns stable patterns matching the existing strings:

```kotlin
object SettingsRoutePatterns {
    const val SETTINGS = "settings"
    const val SETTINGS_DESTINATION = "settings?backupUri={backupUri}"
    const val PARENTAL_CONTROL_GROUPS = "parental_control_groups/{providerId}"
}

fun NavGraphBuilder.registerSettingsGraph(
    actions: NavigationActions,
    platformHost: SettingsPlatformHost,
    navigationDestinations: List<UiDestination>,
    onTopLevelRouteRequested: (String) -> Unit,
    onTopLevelDestinationRequested: (AppDestination) -> Unit,
)
```

The graph reads the existing `backupUri` and `providerId` arguments, uses
`dropUnlessResumed` for provider create/edit and parental navigation, and
passes controller-free callbacks to the screens. It never imports
`AppRouteCodec`, a root controller, `MainActivity`, or app payload storage.

`AppRouteCodec` keeps constructing and decoding the same routes. The app system
graph stops registering Settings only after `AppNavHost` registers the feature
graph.

### Platform host

Route-owned Android actions use one narrow host supplied by `:app`:

```kotlin
interface SettingsPlatformHost {
    val buildInfo: SettingsBuildInfo
    val backupFiles: SettingsBackupFileHost
    fun officialBuildStatus(): SettingsOfficialBuildStatus
    fun playRecording(request: SettingsRecordingPlaybackRequest)
    fun shareBackup(uri: Uri): Result<Unit>
    fun shareCrashReport(): Result<Unit>
    fun removableBackupDirectory(): File?
}
```

`SettingsBuildInfo`, `SettingsOfficialBuildStatus`, and
`SettingsRecordingPlaybackRequest` are feature-owned immutable value types.
The host adds no policy; app adapters delegate to current implementations.
The feature retains Activity Result launchers and their callback order.

`SettingsBackupFileHost` exposes the current managed-backup, picker-free,
folder, removable-storage, URI, delete, and publish operations using
`SettingsBackupFileCandidate` values. It delegates to app-owned
`BackupFileBridge`; no storage policy or filename behavior is reimplemented in
the feature.

### Injected operational ports

App-owned services used by the feature ViewModel are exposed through focused
interfaces:

```kotlin
interface SettingsSurfaceRefreshPort {
    suspend fun refreshWatchNext()
    suspend fun refreshRecommendations()
    suspend fun refreshTvInputCatalog()
    fun enqueueTvInputCatalogRefresh()
}

interface SettingsDiagnosticsPort {
    fun latestReport(): SettingsCrashReport?
    fun deleteLatestReport(): Boolean
}

interface SettingsAppUpdatePort {
    val downloadState: StateFlow<SettingsUpdateDownloadState>
    fun isRemoteVersionNewer(
        remoteVersionCode: Int?,
        remoteVersionName: String,
        remotePublishedAt: String?,
    ): Boolean
    suspend fun fetchLatestRelease(): Result<SettingsReleaseInfo>
    suspend fun refreshDownloadState(): SettingsUpdateDownloadState
    suspend fun startDownload(release: SettingsReleaseInfo): Result<Unit>
    suspend fun installDownloadedUpdate(expectedSha256: String?): Result<Unit>
}
```

The feature value types preserve every field consumed by current settings
presentation. App adapters map current update/diagnostics objects without
changing error strings, timestamps, HTTPS validation, download state, or
installer behavior. The feature owns the pure auto-check interval and
download/install action-selection policy; the app port retains the
BuildConfig/channel-aware version comparison.

## Screen and state boundary

Phase 5 keeps the current `SettingsUiState`, action coordinators, observer
topology, and ViewModel call order. The extraction may split route-owned
platform launchers from `SettingsScreen`, but must not split the ViewModel by
responsibility in the same change. That decomposition belongs to a later
behavior-focused phase with dedicated tests.

The target composition is:

```text
SettingsGraph
  SettingsRoute
    Activity Result launchers and SettingsPlatformHost
    SettingsScreen
      Settings navigation rail/content pane
      sections and rows
      SettingsScreenOverlays
      backup/restore dialogs

  ParentalControlGroupRoute
    ParentalControlGroupScreen
```

`SettingsScreen` remains renderable with an explicitly supplied ViewModel/state
and callbacks. Existing test tags, content descriptions, focus request order,
dialog ordering, lazy-item ordering, and Back behavior remain unchanged.

## Resources

The settings package references 665 unique resources: 660 strings and five
plurals. Feature-owned settings and parental keys move locale by locale into
`:feature:settings`. A resource is removed from `:app` only when no app source,
test, manifest, benchmark, or other feature references it. Shared keys may be
duplicated temporarily when ownership is not yet safe to transfer; duplication
is recorded in the report.

The module namespace is `com.streamvault.feature.settings`, and moved source
uses `com.streamvault.feature.settings.*` packages and
`com.streamvault.feature.settings.R`.

## Migration sequence

1. Record inventory, rollback SHA, baseline commands, dependencies, routes,
   resources, tests, and open acceptance gates.
2. Add the module, coverage wiring, and fail-closed boundary task with Kotlin
   and Java violation fixtures.
3. Establish neutral shared UI/model contracts and app-supplied platform ports.
4. Move the backup-preview implementation first and keep the provider contract
   compatible through app composition.
5. Move parental-control presentation and verify its provider-ID route.
6. Move settings models/actions/observers and their tests mechanically.
7. Move `SettingsViewModel` and add app adapters/Hilt bindings for operational
   ports.
8. Move remaining Settings UI and resources without changing behavior.
9. Register the settings graph from `AppNavHost`, then remove Settings routes
   from the app system graph.
10. Remove legacy ownership, audit dependencies, update the ledger, run all
    structural/runtime gates, regenerate profiles, refresh Graphify, and write
    the feature report.

## Error handling and rollback

- Invalid or missing settings backup URI retains the existing empty/default
  behavior.
- Missing recording playback or platform capability remains a no-op with the
  current user-message behavior.
- File permission, unavailable picker, share, removable-storage, update,
  diagnostics, backup, Drive, sync, and provider errors keep their current
  messages and callback ordering.
- Backup partial-import checkpoint and retry state is preserved.
- The app-owned Settings route remains wired until the feature graph compiles,
  unit tests pass, and an installed app reaches both Settings destinations.
- Commits separate contracts, mechanical moves, graph wiring, and cleanup so
  each slice is independently revertible.

## Verification

### Automated structural gates

- `:feature:settings:verifyFeatureSettingsBoundary`
- `:feature:settings:testDebugUnitTest`
- `:feature:settings:lintDebug`
- `:feature:settings:check`
- `:feature:settings:assembleDebug`
- app navigation compatibility tests and feature-graph boundary tests
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- source scans for app/root-controller/feature-implementation imports
- runtime dependency report and transitional ledger comparison
- `graphify update .` after code changes

### Connected and manual behavior gates

- Settings route with and without `backupUri`.
- Settings navigation rail, D-pad focus restoration, Back behavior, touch,
  mouse, RTL, reduced motion, and accessibility semantics.
- All selection/value/PIN/provider/EPG/recording/update/backup dialogs.
- Parental category protection/hiding, search, PIN, save/reset, and route return.
- Backup create/share/local/folder/USB flows where supported; preview choices,
  conflict strategies, partial import, restore synchronization, and Drive
  sign-in/push/pull/manage/delete.
- Provider create/edit callbacks from Settings and provider backup-preview
  rendering through the unchanged provider request contract.
- Recording folder launcher and recording playback handoff.

Unavailable hardware, credentials, accounts, files, or provider data are
reported as open gates, not passes.

### Build and profile evidence

- Five before/after settings-only incremental source-edit runs.
- Five before/after settings-test compile runs.
- Clean debug and warm no-change guardrails.
- Executed task-set proof that a settings edit does not compile playback or
  provider feature source.
- Profile regeneration and stale app-settings descriptor scan after package
  moves.
- Controlled runtime benchmark evidence for settings scrolling and dialog
  opening when the environment supports it.

## Exit criteria

- Settings, parental controls, backup/restore presentation, state, dialogs,
  resources, tests, and graph registration are owned by `:feature:settings`.
- `:app` registers the settings graph and supplies platform adapters only.
- Provider backup preview still renders through `ProviderBackupPreviewContent`
  with field-for-field callback compatibility.
- No settings feature source imports `com.streamvault.app`, a root navigation
  controller, provider/playback feature implementations, or `MainActivity`.
- All temporary `:data` imports are complete in the transitional ledger.
- Feature tests run independently and a feature-only edit is isolated from
  unrelated feature compilation.
- Verification and open runtime/performance gates are recorded honestly in
  `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_REPORT.md`.
