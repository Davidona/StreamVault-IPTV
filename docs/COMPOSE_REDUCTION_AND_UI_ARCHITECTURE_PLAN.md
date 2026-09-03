# Compose Reduction and UI Architecture Modernization Plan

Status: In progress  
Owner: Unassigned  
Created: 2026-08-15  
Scope: Android application UI, navigation, build structure, and UI-facing module boundaries

## Current execution state

Phase 0 is complete for the available development environment. The build, artifact, memory, Compose compiler, Macrobenchmark, and live-playback baseline is recorded in [`COMPOSE_REDUCTION_BASELINE.md`](COMPOSE_REDUCTION_BASELINE.md), and the Compose compiler diagnostic outputs are generated under `app/build/reports/compose-compiler/`. Physical-device measurements and a stable provider stream are explicitly listed as follow-up limitations, not treated as successful release-quality measurements.

The Phase 1 source decomposition is now code-complete: `PlayerControlsOverlayHost`, `PlayerLifecycleHost`, the platform-free `PlayerBackNavigationPolicy`, the placement-preserving `PlayerModalHosts`, and the event-time-tested `PlayerInputPolicy` are extracted from the player root; ProviderSetup is split into provider-specific forms, dialogs, import helpers, and components; and AppNavigation is split into startup/external-navigation policy, adapters, contracts, and graph registration. These changes preserve the existing callback behavior and keep packages and Gradle modules unchanged. The remaining manual and baseline instrumentation gates are recorded as incomplete in the execution plan.

The full `:app:testDebugUnitTest` task now passes after the test fixture was updated to provide the already-required `m3uClassificationRepository` mock. No production behavior was changed; existing coroutine opt-in warnings remain.

The ProviderSetup slice now also places the source selector panel, provider-specific form content, and advanced-options section in dedicated files; the root continues to own draft state, launchers, effects, and ViewModel calls. The second no-behavior-change decomposition moved the remaining provider branches into explicit Xtream, Stalker, M3U, and Jellyfin form composables, and moved completion, compatibility-selector, validation-error, and password-transformation leaves into dedicated same-package files.

The AppNavigation split is now complete for Phase 1: startup resolution, route/request contracts, navigation adapters, external-navigation dispatch, and graph registration are in dedicated same-package files; `AppNavigation.kt` retains state collection and orchestration only. No typed-route or module boundary was introduced.

The review follow-up also closed player input freshness regressions: input and Back decisions now build their snapshots at event time, including the ViewModel-backed numeric-channel buffer, with regression tests covering digit-entry updates followed by immediate confirmation or Back. Focused player, provider, and navigation unit tests pass. Long-duration live playback and manual TV smoke checks remain required gates. The later API 36 emulator pass is recorded in the Phase 5 playback report; it covers seeded startup, preview, fullscreen, controls, and Back, while the full stability/manual matrix remains open.

This branch was synchronized with the current local `develop` in merge commit `c2a2658f`, and synchronized again after `develop` advanced in merge commit `6e85d5c7` from local `develop` `5294fbd5`. Both merges were conflict-free. The second merge brought the backup/restore ledger, database migration, TV-input synchronization, and related settings/data changes. No production compatibility changes were needed for the Phase 1 extraction; the only adaptation was removing a duplicate `m3uClassificationRepository` field and constructor argument from `HomeViewModelTest` created by overlapping test-fixture changes. The merged tree passes `:app:testDebugUnitTest`, the focused backup/sync data tests, and `:app:assembleDebug`. The full `:data:testDebugUnitTest` suite runs 1,060 tests but retains the `ProviderExecutionArchitectureTest` failure at `data/src/test/java/com/streamvault/data/provider/ProviderExecutionArchitectureTest.kt:276` (expected dependency budget 40, actual 42).

## 1. Executive summary

StreamVault should reduce Compose by making it an intentional UI technology choice instead of the default location for all application behavior. Compose should remain the primary technology for dynamic, focus-heavy TV experiences such as Live TV, EPG, playback overlays, media shelves, and MultiView. Form-heavy and mostly static surfaces such as provider setup, settings, backup/import, and simple onboarding should be evaluated for classic Views after their state and navigation boundaries are cleaned up.

The largest gains will not come from mechanically converting composables to XML. They will come from:

1. Moving UI code out of the monolithic `:app` compilation unit into a small number of feature modules.
2. Separating route/state ownership from pure rendering.
3. Preventing frequently changing state from invalidating entire screens.
4. Removing data-layer and Media3 implementation details from presentation code.
5. Keeping shared UI APIs small and stable.
6. Measuring build and runtime behavior before and after every migration phase.

The recommended end state is a hybrid UI architecture with five to seven feature modules, a stable `:core:ui` module, feature-owned navigation registration, and explicit UI technology decisions per feature.

## 2. Current-state assessment

The following measurements were taken from the repository on 2026-08-15. They are architectural indicators, not performance benchmarks.

| Area | Current observation | Architectural effect |
|---|---:|---|
| `app/src/main` | Approximately 296 Kotlin files and 88,885 lines | Most application-facing changes converge on one Android module |
| Compose footprint | Approximately 458 composable functions | Compose compilation and presentation behavior are concentrated in `:app` |
| UI screens | Approximately 69,741 lines and 360 composables below `ui/screens` | Features are organized by package but not isolated at the build boundary |
| `ProviderSetupScreen.kt` | 3,651 lines and 32 composables | Provider forms, Android file operations, pairing, draft state, and dialogs share one file |
| `PlayerScreen.kt` | 1,561 lines and approximately 57 `collectAsState*` calls | Independent state streams can invalidate a very broad composition scope |
| `AppNavigation.kt` | 1,108 lines | Route declaration, startup resolution, navigation policy, and feature wiring are centralized |
| Player overlay package | Approximately 6,284 lines and 47 composables | Playback UI is partially decomposed by file but still coordinated through a large root screen |
| Settings package | Approximately 19,318 lines and 127 composables | Settings has become a large sub-application inside `:app` |
| Shared card ticker | Each `ChannelCard` collects the same 30-second ticker | Every visible card owns a subscription and participates in periodic recomposition |

The graph report also identifies the following highly connected hubs:

- `SettingsViewModel`: 172 edges.
- `Media3PlayerEngine`: 111 edges.
- `TvClickableSurface()`: 91 edges.

High connectivity is not automatically a defect. It does indicate that changes to these APIs can affect many features and that their responsibilities should be deliberately constrained.

### 2.1 Build-time causes

The likely build-time contributors are:

- Nearly all Compose UI lives in `:app`, so incremental isolation stops at the file level rather than the feature-module level.
- `:app` owns navigation, feature screens, Hilt wiring, services, Cast integration, TV input, update handling, and UI.
- Large source files create expensive invalidation and analysis units for Kotlin and the Compose compiler.
- `:app` directly declares Compose Material 3, TV Material, Media3, Room, Retrofit, OkHttp, WorkManager, Cast, and other dependencies.
- Hilt and KSP also run in the same module that contains the largest Kotlin/Compose source set.
- Shared UI code currently imports app navigation, `MainActivity`, domain models, and concrete application concerns, which makes extraction harder.

The existing Gradle profile showing an up-to-date `assembleDebug` in roughly four seconds is not a useful compilation baseline because `compileDebugKotlin` was up-to-date. Phase 0 must establish clean, warm, and realistic incremental measurements.

### 2.2 Runtime causes

The likely runtime contributors are:

- Broad state observation at screen roots, particularly in `PlayerScreen`.
- Large UI state objects that contain many lists and maps with uncertain Compose stability.
- Frequent player state changes mixed with infrequent metadata and dialog state.
- Repeated state collectors inside list items.
- Complex TV focus, pointer, key, animation, image, and overlay behavior sharing the same composition scopes.
- Feature screens that combine rendering, file I/O launchers, navigation policy, lifecycle handling, local draft state, and ViewModel events.
- Potentially unnecessary use of both Material 3 and TV Material components in the same presentation layer.

These are hypotheses until Phase 0 records traces and benchmark results.

## 3. Goals

### 3.1 Primary goals

- Reduce incremental build time for changes isolated to one UI feature.
- Reduce broad and unnecessary recomposition on low-powered TV devices.
- Make state ownership and navigation ownership obvious from the directory structure.
- Allow selected features to use classic Views without forcing the entire application to use one UI toolkit.
- Keep Live TV and player behavior unchanged during architectural migration.
- Reduce direct dependencies from presentation code to `data` implementation types and concrete Media3 classes.
- Make feature code independently testable.
- Preserve TV remote focus, accessibility, RTL, phone, tablet, and TV behavior.

### 3.2 Secondary goals

- Reduce large source files to reviewable units.
- Reduce `:app` to the Android composition root.
- Standardize design-system and interaction primitives.
- Make Compose compiler stability and recomposition reports part of performance work.
- Prepare for baseline profiles and macrobenchmarks.

### 3.3 Goal priority and timing

| Goal | When | How we know it is working |
|---|---|---|
| Preserve current UI and playback behavior during structural changes | Every phase | Existing tests pass; manual smoke tests pass; player validation shows no new errors, stuckness, or focus regressions |
| Make state, rendering, and platform responsibilities easier to locate | Phase 1 | Large screens are split into focused files with explicit route/screen/state boundaries |
| Reduce unnecessary recomposition in confirmed hotspots | Phase 2 | Compose reports and runtime traces show fewer invalidated scopes or lower frame cost |
| Improve incremental build isolation | Phases 3–6 | Feature-only edits recompile fewer tasks and meet the baseline-relative build target |
| Use Compose or Views intentionally per feature | Phases 5–6 | Each candidate has a measured maintenance/runtime reason, not a toolkit preference |
| Reduce `:app` coupling and direct implementation dependencies | Phases 3–7 | Feature modules depend on contracts and `:app` becomes primarily the composition root |

The first active goal is structural safety: complete the player decomposition while preserving behavior. Performance targets are later goals until runtime and repeated build measurements confirm the hotspots.

### 3.4 Non-goals

- Rewriting the entire UI in XML.
- Changing the product design during the architecture migration.
- Replacing Navigation Compose immediately.
- Replacing Hilt, Room, Media3, or the domain/data architecture.
- Creating one Gradle module per screen.
- Annotating mutable or uncertain models as `@Stable` or `@Immutable` merely to silence Compose diagnostics.
- Combining architecture work with playback recovery policy changes.

## 4. Architecture principles

1. Feature ownership comes before toolkit ownership. A feature owns its screens, state, actions, components, tests, and resources.
2. Routes collect state; screens render state. Android integration and ViewModels stay at the route boundary.
3. Frequently changing state belongs near the leaf that renders it.
4. Shared UI must be generic. `:core:ui` must not import app routes, `MainActivity`, DAOs, provider implementations, or concrete Media3 engines.
5. Features depend on contracts, not implementations. Presentation should prefer domain/use-case interfaces and player API abstractions.
6. Module boundaries should reflect meaningful team and rebuild boundaries, not individual files.
7. Each phase must preserve behavior and have a rollback point.
8. Runtime changes are validated in release-like builds on representative low-powered devices.
9. Player architecture changes require full live playback validation, not launch-only validation.

## 5. Target module architecture

```text
:app
  MainActivity
  StreamVaultApp
  root Hilt composition
  root navigation host
  platform services
  deep-link and external-intent dispatch

:domain
  business models
  repository contracts
  use cases
  platform-independent policies

:data
  Room
  network providers
  repository implementations
  sync
  DataStore and encrypted preferences implementations

:player
  player API
  Media3 implementation
  playback policies
  timeshift
  surface/view binding

:core:ui
  theme and design tokens
  generic TV focus and input primitives
  generic cards, dialogs, loading, and empty states
  image-loading wrappers
  adaptive screen primitives

:core:navigation
  typed destination contracts
  common navigation request models
  no screen implementations

:feature:live
  Live TV browsing
  channel/category UI
  EPG and guide
  preview UI

:feature:catalog
  dashboard media shelves
  movies
  series
  VOD
  favorites
  search

:feature:playback
  Player route and screen
  player controls and overlays
  player dialogs
  MultiView presentation

:feature:provider
  provider setup and editing
  import and pairing presentation

:feature:settings
  settings
  parental controls
  backup/restore presentation

:feature:system
  welcome
  downloads
  plugin management UI
```

The final module count can be adjusted after measuring Gradle configuration and clean-build effects. `:feature:system` can remain in `:app` initially if its size does not justify extraction.

### 5.1 Dependency direction

```text
                         +----------------+
                         |      :app      |
                         +--------+-------+
                                  |
              +-------------------+-------------------+
              |                   |                   |
       +------v------+      +-----v------+      +-----v------+
       | :feature:*  |      | :core:ui   |      | :core:nav  |
       +------+------+      +-----+------+      +-----+------+
              |                   |                   |
              +-------------------+-------------------+
                                  |
                         +--------v-------+
                         |    :domain     |
                         +--------+-------+
                                  ^
                    +-------------+-------------+
                    |                           |
             +------+-+                    +----+----+
             | :data  |                    | :player |
             +--------+                    +---------+
```

Practical dependency rules:

- `:app` may depend on every feature because it is the composition root.
- Feature modules may depend on `:core:ui`, `:core:navigation`, `:domain`, and explicitly approved API modules.
- Feature modules should not depend on another feature's implementation.
- Feature-to-feature navigation uses destination contracts and callbacks.
- `:data` and `:player` must not depend on feature modules or `:core:ui`.
- `:core:ui` must not depend on `:app`, `:data`, or feature modules.
- A temporary feature-to-`:data` dependency may be allowed during extraction, but it must be tracked and removed before that feature's migration is considered complete.

### 5.2 Why feature modules improve builds

Moving code into modules means a player UI edit recompiles `:feature:playback` and its downstream consumer rather than the complete UI source set. This primarily improves incremental builds. It may make clean builds slightly slower because there are more Gradle tasks, so clean-build time is a guardrail rather than the main success metric.

The shared modules must remain small and stable. A frequently edited `:core:ui` module would invalidate every feature and erase much of the incremental-build benefit.

## 6. UI technology strategy

Compose should be selected where its declarative state, TV focus APIs, animation, and dynamic layout provide a clear benefit.

| Feature or surface | Initial technology decision | Reason |
|---|---|---|
| Live TV browser | Keep Compose | Dynamic lists, focus, preview, overlays, adaptive modes |
| EPG | Keep Compose | Custom grid behavior, time-based rendering, focus, dynamic loading |
| Player overlays | Keep Compose | Layered visibility, state-driven controls, TV input and focus |
| Player video surface | Keep View/Media3 surface behind an interop boundary | Video rendering should remain outside Compose drawing |
| MultiView | Keep Compose initially | Dynamic slot layout and state-driven controls |
| Dashboard/media shelves | Keep Compose | Lazy media rows and adaptive content |
| Movies/series/VOD | Keep Compose initially | Shared media components and dynamic catalog lists |
| Provider setup | Evaluate classic Views after state extraction | Large form, many text fields, launchers, imports, and provider-specific sections |
| Settings | Evaluate classic Views after feature extraction | Form/list-heavy, mostly static, high source volume |
| Backup/import | Strong candidate for Views or DialogFragment | Platform document contracts and form-like flow |
| Welcome/onboarding | Either toolkit | Small surface; choose based on maintenance cost |
| Plugin management | Keep current implementation until measured | No evidence yet that toolkit choice is the bottleneck |

### 6.1 Decision gate for converting a feature to Views

A feature should move from Compose to Views only when all of the following are true:

- Its Route/Screen and state boundaries are already clean.
- A benchmark or trace shows meaningful composition, layout, allocation, or interaction cost.
- The feature is mostly static or form-based.
- TV focus and accessibility behavior can be preserved or improved.
- The conversion removes Compose from the feature module or substantially reduces a hot composition tree.
- The additional interop/navigation complexity is acceptable.

Using `AndroidView` around an entire XML screen is not considered a complete Compose reduction. It may reduce the internal composition tree, but it retains a Compose host and introduces an interop boundary. A Views-only feature should preferably use a Fragment or Activity entry point with ViewBinding and an explicit result/navigation contract.

## 7. Standard feature hierarchy

Each feature should use a predictable presentation structure:

```text
feature/<name>/src/main/java/com/streamvault/feature/<name>/
  navigation/
    <Feature>Destination.kt
    <Feature>NavGraph.kt

  presentation/
    <Feature>Route.kt
    <Feature>Screen.kt
    <Feature>UiState.kt
    <Feature>Action.kt
    <Feature>Effect.kt
    <Feature>ViewModel.kt

    components/
    dialogs/
    model/

  domain/                  # optional feature-local orchestration
  resources/
```

### 7.1 Route responsibility

The route may:

- Obtain a ViewModel.
- Collect lifecycle-aware state.
- Translate navigation callbacks.
- Observe one-shot effects.
- Register Activity Result contracts.
- Access Android-only services when they cannot be behind an interface.

The route should not contain substantial layout code.

### 7.2 Screen responsibility

The screen should:

- Accept immutable state and event callbacks.
- Render the feature.
- Be previewable and testable without Hilt.
- Avoid repositories, DAOs, concrete player engines, and direct navigation-controller calls.
- Avoid launching business work directly.

### 7.3 State frequency boundaries

State should be grouped by update frequency and consumer:

```text
FeatureRouteState       infrequent route/identity/loading changes
FeatureContentState     content data and selection changes
FeatureOverlayState     dialogs, panels, transient interaction state
FeatureRealtimeState    position, progress, clock, diagnostics
```

Do not combine a one-second playback position with large channel lists and infrequently changing metadata in a single root state object.

### 7.4 Event pattern

Prefer a small event surface over dozens of unrelated lambdas when a feature has many actions:

```kotlin
sealed interface PlayerAction {
    data object TogglePlayback : PlayerAction
    data object OpenChannels : PlayerAction
    data class SelectChannel(val channelId: Long) : PlayerAction
    data class SeekTo(val positionMs: Long) : PlayerAction
}

@Composable
fun PlayerScreen(
    state: PlayerScreenState,
    onAction: (PlayerAction) -> Unit,
    modifier: Modifier = Modifier,
)
```

Do not force every small component to understand the feature-wide action type. Translate feature actions into narrow callbacks at section boundaries when that produces a clearer component API.

## 8. Detailed refactoring plans

### 8.1 Root application and navigation

Current concern: `AppNavigation` owns startup routing, external requests, destination resolution, route argument parsing, and every feature's callback wiring.

Target structure:

```text
app/navigation/
  AppNavHost.kt
  AppNavigator.kt
  AppStartupCoordinator.kt
  ExternalNavigationCoordinator.kt
  TopLevelNavigationPolicy.kt

feature/*/navigation/
  LiveNavGraph.kt
  CatalogNavGraph.kt
  PlaybackNavGraph.kt
  ProviderNavGraph.kt
  SettingsNavGraph.kt
```

`AppNavHost` should read like a registry:

```kotlin
NavHost(...) {
    welcomeGraph(...)
    providerGraph(...)
    liveGraph(...)
    catalogGraph(...)
    playbackGraph(...)
    settingsGraph(...)
}
```

Actions:

- Move pure route construction and parsing into typed destination contracts.
- Move startup landing resolution out of the composable into a coordinator or ViewModel.
- Keep the `NavController` in `:app`.
- Pass navigation callbacks into feature graph registration.
- Avoid passing `MainActivity` into shared UI or feature screens.
- Expose explicit platform capabilities, such as PiP, through small interfaces.

### 8.2 Core UI and AppShell

Current concern: `AppShell` contains reusable visual structures but also imports app routes, `MainActivity`, and app-specific navigation behavior.

Split it into:

```text
:core:ui
  AppScreenScaffold
  AppScreenHeader
  NavigationRailVisuals
  TopBarVisuals
  Focus primitives
  Generic destination item model

:app or :core:navigation
  destination list resolution
  current route mapping
  navigation callbacks
  activity/platform actions
```

The generic destination model should contain only rendering information and a stable ID. It should not contain a `NavController` or app repository.

Design-system rules:

- Feature code should use StreamVault wrappers for common surfaces, text, buttons, dialogs, focus indication, and spacing.
- Raw Material 3 and TV Material usage should be limited to `:core:ui` unless a feature needs a specialized primitive.
- Do not make a single universal component with dozens of optional parameters.
- Keep focus behavior explicit and testable.

### 8.3 Player

Current concern: `PlayerScreen` observes many flows at the root and owns lifecycle, PiP, window flags, key handling, overlay focus, preparation, dialogs, player controls, and navigation.

Target composition:

```text
PlayerRoute
  PlayerLifecycleHost
  PlayerScreen
    PlayerVideoSurface
    PlayerInputHost
    PlayerOverlayHost
      PlayerTransportRoute
      PlayerLiveOverlayRoute
      PlayerDialogHost
      PlayerNoticeHost
      PlayerDiagnosticsRoute
```

Recommended state groups:

- `PlayerSessionState`: identity, content type, title, current media/channel/episode.
- `PlayerPlaybackState`: ready/buffering/error/playing, video format, engine identity.
- `PlayerControlsState`: visibility, mute, speed, aspect ratio, tracks, timers.
- `PlayerLiveState`: channel/category/EPG overlays and numeric input.
- `PlayerDialogState`: one sealed representation of the active modal instead of many booleans.
- `PlayerRealtimeState`: position, duration, seek preview, and diagnostics.

Specific actions:

- Keep `currentPosition` and `duration` collection inside transport controls.
- Collect diagnostics only while diagnostics are visible; the current conditional collection is a pattern to preserve.
- Replace many dialog booleans with a sealed `PlayerModal` where modal states are mutually exclusive.
- Move root key handling into a dedicated, testable input policy that returns actions.
- Move PiP and window-flag behavior behind a platform interface owned by the route.
- Keep player preparation keyed by a stable preparation identity.
- Stop casting `PlayerEngine` to `Media3PlayerEngine` from presentation code; expose required capabilities through the player API.
- Keep player surface creation and binding in `:player`.
- Preserve all recovery, timeshift, and live transport policies during UI refactoring.

Player acceptance checks are defined in Section 12 and include the project's long-duration live playback validation.

### 8.4 Provider setup

Current concern: `ProviderSetupScreen` owns more than 20 local draft fields, file import and cleanup, QR generation, Android launchers, protocol-specific forms, advanced Stalker options, pairing, sync progress, and dialogs.

Target files:

```text
provider/presentation/
  ProviderSetupRoute.kt
  ProviderSetupScreen.kt
  ProviderSetupUiState.kt
  ProviderSetupAction.kt
  ProviderSetupEffect.kt
  ProviderSetupViewModel.kt

  forms/
    M3uProviderForm.kt
    XtreamProviderForm.kt
    StalkerProviderForm.kt
    JellyfinProviderForm.kt
    ProviderAdvancedOptions.kt

  import/
    ProviderImportCoordinator.kt
    ProviderImportDialogs.kt
    ProviderFileImport.kt

  pairing/
    ProviderPairingCard.kt
    ProviderPairingUiModel.kt

  components/
    ProviderTextField.kt
    ProviderSourceSelector.kt
    ProviderPolicyOption.kt
```

Specific actions:

- Move the provider draft into the ViewModel, backed by `SavedStateHandle` where process restoration matters.
- Keep only transient visual state in `rememberSaveable`, such as the currently expanded section.
- Move file copy/cleanup and QR generation out of the composable file.
- Use provider-specific draft models rather than one flat list of unrelated strings.
- Convert data-layer Stalker configuration types to feature/domain UI models at the route/ViewModel boundary.
- Keep Activity Result launchers in `ProviderSetupRoute` and communicate results through actions.
- Split create, edit, import, pairing, and sync-result behavior into explicit states.
- After decomposition, benchmark a Compose implementation against a ViewBinding implementation before choosing a final toolkit.

### 8.5 Settings

Current concern: settings presentation and `SettingsViewModel` are major graph hubs, and several settings files directly use DAOs, DataStore implementation types, and operational data entities.

Target structure:

```text
settings/presentation/
  SettingsRoute.kt
  SettingsScreen.kt
  SettingsSection.kt
  SettingsAction.kt
  SettingsEffect.kt

  sections/
    GeneralSettings.kt
    PlaybackSettings.kt
    LiveTvSettings.kt
    GuideSettings.kt
    RecordingSettings.kt
    ProviderSettings.kt
    BackupSettings.kt
    AboutSettings.kt

  dialogs/
  backup/
  parental/
```

Specific actions:

- Split the ViewModel by responsibility or introduce focused coordinators/use cases.
- Replace direct DAO dependencies with domain-level query/command contracts.
- Replace concrete `PreferencesRepository` use in presentation-facing classes with an application settings contract.
- Keep backup file launchers and platform directory interactions at the route/platform boundary.
- Define a stable row model for list-based settings if a classic `RecyclerView` implementation is evaluated.
- Keep each settings section independently testable.

### 8.6 Live TV and EPG

Current concern: Live TV and EPG are naturally complex and should not be rewritten merely to reduce Compose count.

Specific actions:

- Keep Compose.
- Split dialog ownership from the main screen state.
- Keep preview player state isolated from channel/category list state.
- Ensure all lazy items have stable keys and useful `contentType` values.
- Avoid rebuilding provider lookup maps and filtered collections in composition.
- Expose only visible-window data where possible.
- Ensure time ticker changes invalidate only current-program/progress elements.
- Keep focus restoration and remote input policies in dedicated helpers with tests.
- Benchmark channel navigation, category switching, EPG horizontal movement, and preview start/stop.

### 8.7 Catalog, favorites, and search

Specific actions:

- Consolidate shared movie/series/VOD browsing behavior behind feature-local presentation models.
- Keep shared media cards in `:core:ui` only when they are genuinely feature-neutral.
- Keep feature-specific card decorations and actions inside `:feature:catalog`.
- Move filtering, ranking, grouping, and sort construction out of composables.
- Ensure list instances are reused when content has not changed.
- Use paging/windowed loading where lists are too large to materialize at once.
- Standardize media item keys across combined-provider variants.

## 9. Compose runtime rules

These rules should be documented and enforced during the migration:

- Collect state at the lowest practical owner.
- Use `collectAsStateWithLifecycle` for UI-observed flows.
- Do not create one collector per list item for shared state.
- Use stable keys for every dynamic lazy item.
- Add `contentType` to mixed lazy lists and grids.
- Perform sorting, grouping, and large map creation outside composition.
- Use `remember` for expensive pure derivations whose keys are correct.
- Use `derivedStateOf` only when it reduces invalidations; do not use it as decoration.
- Keep high-frequency reads in drawing/layout lambdas or leaf composables when possible.
- Prefer immutable feature UI models.
- Use immutable collections when they materially improve stability and allocation behavior.
- Apply `@Immutable` only when every exposed property is deeply immutable by contract.
- Avoid passing mutable collections into composables.
- Avoid broad `CompositionLocal` state for feature data.
- Avoid unnecessary animations on reduced-motion or constrained-device paths.
- Do not load full-resolution artwork when the rendered size is small.
- Keep image requests stable and sized to the display target.
- Treat focus state as local visual state unless the feature needs to restore it across navigation.

## 10. Build performance plan

### 10.1 Establish scenarios

Measure at least these scenarios:

1. Clean `assembleDebug`.
2. Warm no-change `assembleDebug`.
3. Incremental change to a player composable.
4. Incremental change to a settings composable.
5. Incremental change to `:core:ui`.
6. Incremental change to a domain model used by several features.
7. Unit-test compilation for a single feature.
8. Release or beta compilation for representative CI behavior.

Use Gradle Profiler for repeatable incremental scenarios when practical. Record median and p95 across multiple runs rather than one result.

### 10.2 Compose compiler reports

Add reports for Compose modules during the diagnostic phase:

```kotlin
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

Reports should remain build artifacts unless a small summarized baseline is intentionally committed. Review:

- Restartable versus skippable composables.
- Unstable parameter types.
- Large generated composable groups.
- Feature models that force unnecessary invalidation.

### 10.3 Gradle structure

- Introduce convention plugins for Android library, Compose feature, and non-Compose feature configuration after two feature modules prove the desired pattern.
- Keep annotation processors and KSP only in modules that need them.
- Use `implementation` by default; use `api` only for deliberate public contracts.
- Keep module public APIs small to minimize downstream recompilation.
- Preserve configuration-cache compatibility.
- Avoid dynamic dependency versions.
- Audit direct `:app` dependencies after feature extraction.

### 10.4 Dependency cleanup candidates

- Remove direct Media3 dependencies from `:app` after all direct Media3 use is behind `:player` APIs.
- Remove direct Room/Retrofit/OkHttp dependencies from UI feature modules.
- Evaluate replacing `material-icons-extended` with a small set of vector resources or core icons.
- Centralize and audit regular Material 3 versus TV Material usage.
- Keep debug-only tooling and LeakCanary out of release dependency graphs.
- Compare APK and dex composition before and after dependency cleanup; do not assume source dependency removal equals packaged size reduction.

## 11. Phased delivery plan

Every phase should be independently mergeable. Avoid a long-running branch that moves all features at once.

### Phase 0 - Baseline and guardrails

Purpose: establish evidence and prevent regressions.

Deliverables:

- Build benchmark scenarios and recorded baseline.
- Compose compiler reports for `:app`.
- A `:benchmark` or equivalent Macrobenchmark module plan, followed by implementation.
- Runtime traces for startup, dashboard scroll, Live TV navigation, EPG navigation, player controls, and settings.
- Representative device matrix.
- Baseline APK/dex and memory measurements.

Exit criteria:

- Results are reproducible across at least five warm/incremental runs and three runtime iterations per scenario.
- Debug-only Compose overhead is not used to judge release runtime.
- The team agrees on baseline-relative targets in Section 13.

Phase 0 execution outcome (2026-08-16): the five-run build repeatability checks, five-iteration release-like cold-start benchmark, five-iteration interaction journeys, release APK/dex snapshot, emulator memory snapshot, and two-channel long-duration playback evidence are recorded in `COMPOSE_REDUCTION_BASELINE.md`. Macrobenchmark interaction journeys use the seeded debug fixture because the release-like benchmark target has no provider data; those results are diagnostic only. The CBSN channel completed the full playback window, while the F1 channel received an external HTTP 403 after initially rendering video. Full playback validation therefore remains a Phase 1 gate for playback-facing changes.

### Phase 1 - Source decomposition without behavior change

Purpose: make later changes reviewable and lower merge risk.

Deliverables:

- Split `ProviderSetupScreen.kt` into provider-specific forms, dialogs, import helpers, and components.
- Split `PlayerScreen.kt` into lifecycle, input, overlay host, and modal host files. For the modal slice, preserve the current two composition locations and source order rather than forcing every dialog through one relocated call site: top-level program-history/MultiView dialogs remain before the root player `Box`, while controls-related dialogs remain inside it after `PlayerResumePrompt`. The detailed contract is defined in [`superpowers/specs/2026-08-17-player-modal-host-design.md`](superpowers/specs/2026-08-17-player-modal-host-design.md).
- Split `AppNavigation.kt` into startup/external navigation policy and graph registration files.
- Keep packages and Gradle modules unchanged.
- Add or move tests around extracted pure policies.
- During source-only player splits, preserve every existing visibility predicate, composition order, callback sequence, Back/input/focus rule, effect key, state owner, and ViewModel scope. In particular, do not add a new Picture-in-Picture restriction to MultiView, and preserve the audio/video-offset dialog's sync-enabled and cast-disconnected gates.
- Do not introduce Phase 2 modal exclusivity or grouped modal state merely to simplify a Phase 1 host API. Callback-heavy, placement-preserving interfaces are acceptable temporary boundaries when they make the mechanical diff safer.

Exit criteria:

- No intended UI behavior change.
- Screenshots/golden tests remain equivalent.
- Existing unit and applicable instrumentation suites pass; if a repository baseline test fails, record the exact command and failure and keep the Phase 1 gate open rather than attributing it to the extraction without evidence.
- Live TV validation passes if player composition or lifecycle code moved.
- Manual TV smoke coverage passes for affected dialogs, Back/focus behavior, controls auto-hide, and Picture-in-Picture behavior. Unavailable scenarios are recorded as incomplete gates, not counted as passes.

### Phase 2 - Runtime state isolation

Purpose: reduce broad recomposition before modularization.

Deliverables:

- Player state grouped by consumer and frequency.
- Position/duration state isolated to transport controls.
- Player modal state represented explicitly.
- Shared channel ticker ownership reduced.
- Stable keys and `contentType` audited for hot lazy lists.
- Large UI models audited with Compose compiler reports.
- Recomposition traces compared to Phase 0.

Exit criteria:

- No increase in player errors or stuck playback.
- Measurable reduction in recomposed scopes or frame cost on at least one identified hot flow.
- No material regression on reference devices.

### Phase 2.5A - Hardened live-category flow caching

Purpose: isolate the live-category replay optimization from Compose state
isolation and preserve fresh command-style reads.

Deliverables:

- Cache one replayed `ChannelRepository.getCategories(providerId)` flow per
  provider in the data layer, backed by Room and evicted when the provider is
  removed.
- Keep `getCategoriesSnapshot(providerId)` as an explicit fresh one-shot API
  for command-style callers; do not make `first()` read from the replay cache.
- Cover replay, active Room updates, duplicate suppression, preference changes,
  provider deletion/recreation, and fresh snapshot behavior with focused tests.
- Measure category entry/re-entry or query work separately from Phase 2
  recomposition and frame-cost evidence.

Exit criteria:

- No stale one-shot category reads or cached data surviving provider deletion.
- Shared collectors reuse one upstream category computation.
- Existing grouping, counts, parental filtering, and decorative-row behavior
  remain unchanged.
- Benchmark evidence demonstrates reduced repeated category work or faster
  category re-entry. If the available emulator cannot measure query work
  reliably, record the limitation rather than treating rendering metrics as a
  cache win.

Implementation/report: [`COMPOSE_REDUCTION_PHASE2_5A_REPORT.md`](COMPOSE_REDUCTION_PHASE2_5A_REPORT.md).

### Phase 2.5B - Baseline-profile and startup optimization

Purpose: separate startup refactoring from baseline-profile generation and
measure each benefit independently. This is the maintained replacement for the
baseline-profile portion of PR #162; PR #162's generated files are not reused.

Deliverables:

- Capture a ten-iteration cold-start `CompilationMode.None()` baseline before
  the startup refactor, including TTID, artifacts, DEX counts, device details,
  and trace references.
- Move process maintenance behind an internal singleton
  `AppStartupCoordinator`: process-only work starts once from
  `StreamVaultApp`, while Watch Next, launcher recommendations, and TV-input
  refresh wait for a one-shot `MainActivity` first-frame signal on televisions.
  Expensive dependencies are injected through `Provider<T>`, task failures are
  isolated with supervisor semantics, and coordinator/task trace sections are
  named.
- Use the existing `:benchmark` module as the only profile producer. Apply the
  AndroidX Baseline Profile plugin at `1.4.1` to `:app` and `:benchmark`, merge
  one maintained profile into the main app, save generated sources only under
  `app/src/main/generated/baselineProfiles/`, and keep ordinary builds from
  generating profiles automatically.
- Guard profile collection with seeded Home and `All Channels` assertions.
  Keep `startup` limited to cold launch and ready Home; keep Home, Live TV,
  EPG, and player-control traversal in the independently collected
  `criticalJourneys` profile.
- Provide controlled `coldStartupNoCompilation()` and
  `coldStartupWithBaselineProfile()` benchmarks with ten iterations and
  `StartupTimingMetric`; the treatment must use
  `BaselineProfileMode.Require`.
- Verify source separation/subset rules, compiled beta/release profiles,
  startup-optimized R8 metadata, secret-free shipping artifacts, and the
  artifact-size budget.

Exit criteria:

- Code and tooling checks pass, generated sources are nonempty and distinct,
  and profile packaging is proven for beta and release.
- Post-refactor/no-profile and with-profile measurements are compared against
  the pre-2.5B baseline. Emulator results are diagnostic only.
- A constrained physical TV completes two ten-iteration comparisons: no
  profile does not regress median/P90 TTID by more than 5%, with-profile median
  is at least 5% faster in both comparisons, with-profile P90 is not more than
  5% slower, and traces show TV integration work after the first UI frame.
- Keep generated profile output uncommitted until packaging and the physical
  performance gate succeed. Record open gates in the phase report.

Implementation/report: [`COMPOSE_REDUCTION_PHASE2_5B_REPORT.md`](COMPOSE_REDUCTION_PHASE2_5B_REPORT.md).

### Phase 3 - Core UI boundary

Purpose: create a stable reusable presentation foundation.

Deliverables:

- Add `:core:ui`.
- Move theme, design tokens, generic focus/input primitives, and truly generic components.
- Separate `AppShell` visuals from app navigation and activity behavior.
- Establish dependency rules with a build-time or static check where practical.

Exit criteria:

- `:core:ui` does not depend on `:app`, `:data`, or feature implementations.
- A core UI change intentionally recompiles dependents; a feature UI change does not recompile unrelated features.
- Golden coverage exists for critical shared TV components, with committed
  baselines rather than a first-run self-baseline.

### Phase 4 - Navigation contracts

Purpose: allow feature extraction without central navigation growth.

Deliverables:

- Add `:core:navigation` if typed contracts justify a separate module.
- Extract typed destinations and player navigation requests.
- Add `NavGraphBuilder` registration extensions per feature area.
- Move startup and external navigation orchestration out of the root composable.

Exit criteria:

- `AppNavHost` is primarily a graph registry.
- Features do not access the root `NavController` directly.
- Deep links, startup player requests, return routes, and external navigation are covered by tests.

Completed report: [COMPOSE_REDUCTION_PHASE4_REPORT.md](COMPOSE_REDUCTION_PHASE4_REPORT.md)

Review hardening note (2026-08-25): startup landing navigation no longer waits
for optional player-target lookup, deep-link detail returns replace the current
detail entry when their typed target is absent from the back stack, catalog
layout changes reconcile the current top-level route, and golden helpers now
require checked-in assets. App and playback baseline assets are present in the
current hardening working tree; recording-disabled golden, navigation-contract,
and playback-overlay runs previously passed on the API 36
`Television_1080p` emulator. The hardening slice remains pending its own commit.

### Phase 5 - Feature module extraction

Detailed delivery artifacts:

- [Phase 5 feature extraction roadmap](COMPOSE_REDUCTION_PHASE5_ROADMAP.md)
- [Phase 5 accepted architecture design](superpowers/specs/2026-08-24-phase-5-feature-module-extraction-design.md)
- [Playback feature extraction implementation plan](superpowers/plans/2026-08-24-playback-feature-extraction.md)
- [Provider feature extraction implementation plan](superpowers/plans/2026-08-26-provider-feature-extraction.md)
- [Settings and Live regression audit](COMPOSE_REDUCTION_PHASE5_SETTINGS_LIVE_REGRESSION_AUDIT.md)
- [Catalog feature extraction design](superpowers/specs/2026-09-02-phase-5-catalog-feature-extraction-design.md)
- [Catalog feature extraction implementation plan](superpowers/plans/2026-09-02-catalog-feature-extraction.md)
- [Catalog feature extraction execution report](COMPOSE_REDUCTION_PHASE5_CATALOG_REPORT.md)

Recommended extraction order:

1. `:feature:playback` because it has the highest runtime and state-isolation value.
2. `:feature:provider` because it has the largest composable source file and a clear boundary.
3. `:feature:settings` because it is large and highly connected.
4. `:feature:live` because Live TV and EPG share player-preview and channel concepts.
5. `:feature:catalog` because movies, series, VOD, favorites, and search share media UI.
6. `:feature:system` only if measurement shows value.

For each module:

- Move source, resources, tests, and Hilt bindings needed by the feature.
- Expose only route registration and destination contracts.
- Remove app-package imports from feature implementation.
- Track temporary `:data` implementation dependencies.
- Measure feature edit build time before and after extraction.

Exit criteria:

- A change inside one feature does not recompile unrelated feature source.
- Feature tests can run independently.
- `:app` contains orchestration and platform entry points, not feature presentation implementations.

Phase 5 execution status (2026-08-27): the structural playback extraction and
feature-boundary work through Task 9 is complete, and the Task 10 report is
recorded in [COMPOSE_REDUCTION_PHASE5_PLAYBACK_REPORT.md](COMPOSE_REDUCTION_PHASE5_PLAYBACK_REPORT.md).
Feature overlay connected coverage passed 7/7 (six golden cases plus the
missing-golden guard); the app connected suite passed 22/27, with five existing
fixture/provider/focus failures recorded in the report. The fresh ADB startup
check confirms the current app's horizontal top
navbar. Seeded two-channel playback produced real video and 61 unique frames
per channel, but both channels hit repeated recoverable HLS live-window source
errors during the required window, so runtime acceptance remains open. Full
manual journey coverage, macrobenchmark performance comparison, and physical-
device validation also remain open. The release-like profile gate itself passed
after correcting the live-controls probe: the full
`generateBaselineProfile` collection, merge, source copy, and install completed
successfully on 2026-08-26; the focused `criticalJourneys` run also passed.
An isolated rerun added a clean 61/61-frame Dare To Dream HLS window, but the
French channel reproduced stalls, an unintended MPEG-TS fallback to a malformed
URL, and a final source error (56 unique frames); the two-channel stability
gate remains open. Detailed evidence is in the linked playback report.

Provider execution status (2026-08-27): the provider module, setup/edit/import/
pairing presentation state, resources, tests, graph registration, ownership
cleanup, boundary checks, feature lint, unit tests, and debug assembly are
complete. SDK-local ADB reached the connected API 36 TV emulator: focused
  provider completion (1/1), app navigation (3/3), platform compatibility
  (4/4), golden (1/1), and playback overlay (7/7) checks passed. Manual source switching, D-pad focus, document
choosers, Drive account-picker handoff, QR pairing start/stop, and a sanitized
cold-start route smoke check, and seeded existing-provider edit/cancel journey
also passed without a fatal app error; full provider completion journeys remain
open. The release-like profile regeneration was rerun successfully on the now-idle
host (10 profile tests passed, eight unrelated macrobenchmarks skipped, stale
app-provider paths removed, and `:app:assembleRelease` passed). Paired
incremental-performance samples are still open because the provider extraction
and related navigation/golden changes are uncommitted; a valid before/after
comparison requires a committed post-extraction ref. See
[COMPOSE_REDUCTION_PHASE5_PROVIDER_REPORT.md](COMPOSE_REDUCTION_PHASE5_PROVIDER_REPORT.md).

Live execution status (2026-08-30): the Home Live TV and EPG presentation
extraction is structurally complete under `:feature:live`, with the app
retaining route/platform/player-request/MultiView adapters. The Live boundary,
feature checks, translated-resource parity audit, six reviewed feature golden
baselines, connected suite, populated MultiView planner journey, and required
two-channel long-playback protocol are recorded in
[COMPOSE_REDUCTION_PHASE5_LIVE_REPORT.md](COMPOSE_REDUCTION_PHASE5_LIVE_REPORT.md).
The follow-up device passes also cover RTL/reduced-motion interaction, D-pad
activation of category/channel/Guide search inputs, quick-filter form input,
hidden category/channel restore, populated Favorites reorder/save-order, and
the populated MultiView placement/removal/replacement/clear and two-slot launch
paths, plus reversible quick-filter save/reload/remove persistence. The full
  fixture-dependent Home/EPG journey matrix, formal paired performance and
  clean-build guardrails remain open. Current-checkout clean/warm samples and
  Beta/Release packaging are recorded. Profile generation now passes on the
  seeded API 36 TV emulator, with fresh sources containing zero stale app
  Home/EPG descriptors and nonzero `feature/live` descriptors; the eight
  separate macrobenchmark tests were skipped by configuration and remain an
  open performance gate. The app-owned Live route golden was reviewed and
  regenerated with its connected assertion passing. Catalog is now structurally
  extracted under `:feature:catalog`; its execution report records passing
  connected goldens, a public-M3U Home/Live/Movies/Series/Search smoke pass,
  and a diagnostic temporary Xtream fixture run covering VOD/series details,
  episodes, saved filters, and cross-type Search. The checked-in
  `tools/catalog_xtream_fixture.py` now provides a rerunnable development
  seed, and `tools/catalog_connected_validation.py` provides a semantic ADB
  journey for the fixture-backed Home/Movies/Series/detail/favorite/saved/
  Search path; the remaining action/direct-Favorites/accessibility journeys,
  successful
  diagnostic Dashboard benchmark rerun after populating a scrollable seeded
  Home shelf, open paired benchmark gate, refreshed release-like profiles, and
  current app lint baseline failure remain explicitly tracked. System remains
  unstarted; Playback, Provider, and Settings gates remain governed by their
  separate reports.

### Phase 6 - Optional Views migrations

Purpose: reduce Compose where evidence and maintenance characteristics support it.

Candidate order:

1. Backup/import flows.
2. Provider setup forms.
3. Settings sections.
4. Simple onboarding.

For each candidate, build a small benchmarkable vertical slice first. Compare:

- Initial render and interaction frame time.
- Memory and allocations.
- TV focus behavior.
- Accessibility.
- Source complexity and testability.
- Incremental build impact.
- Navigation/interoperability cost.

Exit criteria:

- Conversion demonstrates a measurable benefit or a clear maintenance win.
- Converted modules omit the Compose plugin when they contain no Compose code.
- The change does not create a fragile Compose/View navigation boundary.

### Phase 7 - Dependency and API cleanup

Purpose: finish the architecture rather than leaving transitional dependencies permanent.

Deliverables:

- Domain-facing settings/preferences contracts.
- Player capability API without presentation casts to `Media3PlayerEngine`.
- Provider setup UI models without direct Stalker implementation types.
- Removal of unused direct dependencies from `:app` and feature modules.
- Final package and naming cleanup.
- Updated architecture documentation and diagrams.

Exit criteria:

- No presentation package imports DAOs or remote provider implementation classes.
- Concrete Media3 use is confined to player/platform implementation code.
- Temporary migration dependencies are removed or explicitly documented with owners.

## 12. Validation strategy

### 12.1 Build validation

Record for every architectural phase:

- Clean build median and p95.
- Warm no-change build median.
- Incremental feature-edit build median and p95.
- Kotlin compilation and KSP task durations.
- Number of tasks executed versus up-to-date/from-cache.
- Gradle configuration time.
- CI build duration when available.

Do not compare one clean build against one warm build.

### 12.2 Runtime benchmark flows

Benchmark at least:

- Cold startup to welcome/home content.
- Warm startup to the configured landing destination.
- Dashboard vertical and horizontal scrolling.
- Live TV category change and repeated channel focus movement.
- Live preview start, stop, and replacement.
- EPG horizontal time movement and vertical channel movement.
- Player controls open/close.
- Player channel list and EPG overlay navigation.
- Repeated channel zapping.
- MultiView open, focus movement, and slot replacement.
- Provider setup typing and provider-tab switching.
- Settings scrolling and dialog opening.

Collect:

- Frame timing and jank.
- Main-thread slices in Perfetto.
- Composition/recomposition traces where available.
- Java/Kotlin allocations.
- PSS and heap behavior on constrained devices.
- Time to initial display and time to fully drawn.

### 12.3 Device matrix

At minimum:

- A constrained Android TV/Google TV class device.
- A Fire TV class device when available.
- The Television 1080p emulator used by existing tests.
- A phone/tablet reference device if those form factors remain supported.
- One modern high-performance device as a regression control.

### 12.4 Player and Live TV validation

Any phase that changes player composition, lifecycle, state collection, surface ownership, navigation, or overlays must use the repository's full live playback protocol:

- Validate more than one live channel.
- Capture at a 2-second cadence.
- Capture at least 45 screenshots for about 90 seconds; prefer 61 screenshots for about two minutes.
- Confirm changing screenshot hashes through the full window.
- Confirm the media session remains `PLAYING` with `error=null`.
- Check logs for fatal player errors, stuck-player timeout, and unintended MPEG-TS fallback.
- Record HLS prepare/read/first-frame or expected recovery evidence.
- Report channel names, screenshot count, interval, unique hash count, media-session result, and log findings.

Build success, installation, launch, or a single screenshot is not sufficient player validation.

### 12.5 Functional regression coverage

- Navigation and deep links.
- Startup landing preference.
- Provider create/edit/import.
- Backup and restore.
- TV remote focus and back behavior.
- Mouse and touch behavior.
- RTL layout and navigation.
- Reduced motion.
- Accessibility labels and focus order.
- PiP.
- Cast.
- TV input and recommendations.
- Parental controls.
- Playback recovery and timeshift.

## 13. Success metrics

Phase 0 should produce the final approved numbers. The following are initial target ranges, all relative to the recorded baseline:

| Metric | Initial target | Guardrail |
|---|---:|---:|
| Incremental build after feature-only UI edit | At least 25% faster | Must not regress |
| Incremental unit-test compile for extracted feature | At least 20% faster | Must not regress |
| Clean debug build | Improvement is optional | No more than 10% slower without documented reason |
| Warm no-change build | Maintain or improve | No more than 10% slower |
| Slow/janky frames in selected hot flows | At least 20% reduction where a hotspot is confirmed | No regression on other benchmark flows |
| Player controls open latency | At least 10% improvement if currently measurable | No visible focus delay |
| Startup time | Maintain or improve during structural phases | No more than 5% regression |
| Memory during Live TV/player | Maintain or improve | No sustained growth or new leak |
| Player stability | Equivalent or better | Zero new fatal/stuck/fallback regressions |
| `:app` presentation source | Majority moved to feature modules | `:app` must not regain feature implementations |

Targets should be revised if Phase 0 shows measurement noise larger than the proposed improvement.

## 14. Proposed pull-request sequence

This sequence keeps reviews focused and creates rollback points:

1. Add build/runtime benchmark infrastructure and capture baseline.
2. Add Compose compiler report configuration and stability audit notes.
3. Extract pure player input policies with tests, and extract modal composition through placement-preserving hosts with explicit visibility and callback contracts.
4. Split `PlayerScreen` files without behavior changes.
5. Isolate player real-time state and validate Live TV fully.
6. Split provider setup files without behavior changes.
7. Move provider draft and import behavior to explicit route/ViewModel state.
8. Add `:core:ui` and move only theme/design/focus primitives.
9. Separate AppShell visuals from navigation and activity dependencies.
10. Split navigation policy and feature graph registration.
11. Extract `:feature:playback`.
12. Extract `:feature:provider`.
13. Extract `:feature:settings`.
14. Extract `:feature:live`.
15. Extract `:feature:catalog`.
16. Run Views proof of concept for backup/import or one provider form.
17. Make toolkit decision per candidate feature.
18. Remove transitional data/player implementation dependencies.
19. Final performance report and architecture documentation update.

Large moves should use history-preserving file moves where possible. Mechanical moves and behavior changes should be separate PRs.

## 15. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Feature modules increase clean-build task overhead | Optimize for incremental builds, keep module count modest, measure clean-build guardrail |
| Core UI becomes a new god module | Keep it generic, small, stable, and dependency-restricted |
| Moving state changes behavior | First extract files without behavior changes; then change state ownership with tests |
| Player refactor introduces playback regressions | Separate UI architecture from recovery policy; use full multi-channel long-duration validation |
| Compose/View interop complicates navigation | Convert only after a vertical-slice proof and explicit navigation contract |
| `@Immutable` hides mutable behavior | Annotate only deeply immutable contracts and validate with compiler reports/tests |
| Feature extraction exposes too many APIs | Default to internal visibility and expose route registration plus small contracts |
| Temporary feature-to-data dependencies become permanent | Track them in the migration PR and require removal in Phase 7 |
| Golden tests become brittle during file moves | Preserve semantics and test tags; avoid visual changes in structural PRs |
| Long-running refactor causes merge conflicts | Use small sequential PRs and move one feature at a time |

## 16. Rollback strategy

- Every PR must compile and pass its scoped validation independently.
- Preserve old route entry points until a feature module is fully wired and tested.
- For state isolation, keep adapters that can map old ViewModel flows into the new state model during transition.
- For Views experiments, keep the existing Compose destination behind a debug or development switch until the comparison is complete.
- Do not delete the original implementation in the same commit that introduces an unvalidated replacement.
- Playback policy and recovery code must remain unchanged during presentation-only refactors unless a separate bug-fix scope explicitly authorizes it.

## 17. Definition of done

The modernization is complete when:

- `:app` is primarily the platform and navigation composition root.
- Major UI areas are isolated in a small number of feature modules.
- Route, Screen, UiState, Action, and Effect responsibilities are consistently applied.
- Player high-frequency state is isolated from infrequently changing screen state.
- Provider setup no longer owns a flat collection of draft fields and platform work inside one composable.
- Settings presentation does not directly depend on DAOs or concrete data implementations.
- Feature presentation does not cast to `Media3PlayerEngine`.
- `:core:ui` is generic and stable.
- Navigation is registered per feature and the root nav host is readable.
- Compose remains where it provides clear product value.
- Views are used only where measurement or maintenance evidence supports them.
- Incremental build targets are met or documented with evidence.
- Runtime benchmark flows show no regressions and confirmed hotspots improve.
- Full Live TV/player validation passes after all playback-facing phases.
- Documentation and graph output reflect the final architecture.

## 18. Immediate next actions

- [ ] Approve this plan's target module grouping.
- [ ] Assign an owner for Phase 0 measurements.
- [x] Capture initial clean-task-graph and warm Gradle baselines. See `docs/COMPOSE_REDUCTION_BASELINE.md`.
- [x] Add Compose compiler reports and inspect player/provider stability output. See `docs/COMPOSE_REDUCTION_BASELINE.md`.
- [x] Complete the first no-behavior-change slice by extracting `PlayerControlsOverlayHost`.
- [x] Extract and unit-test the player Back-navigation priority policy without changing event handling.
- [x] Extract player lifecycle/window effects into `PlayerLifecycleHost` without changing effect keys or callbacks.
- [x] Extract and unit-test the player root input decision policy while keeping Android event adaptation, state mutation, focus, pointer, and Back execution in `PlayerScreen.kt`.
- [x] Add event-time freshness guards for player input and Back decisions, including numeric-channel buffer regressions.
- [x] Complete the second no-behavior-change `ProviderSetup` decomposition slice; package it as a review PR after the remaining manual gates.
- [x] Complete the Phase 1 `AppNavigation` policy, adapter, external-request, and graph-registration split.
- [ ] Run a manual app smoke test for player launch, controls, remote/back handling, seeking, and overlay actions.
- [ ] Run full multi-channel Live TV validation before marking the player phase complete.
- [x] Add or define the Macrobenchmark module and constrained-device benchmark flows.
- [ ] Create the first no-behavior-change PR for `PlayerScreen` decomposition after the manual smoke test.
- [ ] Package the completed second no-behavior-change `ProviderSetupScreen` decomposition as a review PR.
- [ ] Revisit the initial success targets after baseline data is available.

## 19. PR #162 assessment

PR [#162](https://github.com/Davidona/StreamVault-IPTV/pull/162) is directionally aligned with this plan, but it should be reviewed as four separate changes. It currently targets `master`; if `develop` is the integration branch, retarget it or apply the accepted commits to `develop` before synchronizing `feature/improveCompose`.

| PR commit/change | Plan relationship | Recommendation |
|---|---|---|
| Split Home preview state from `HomeUiState` | Directly advances Phase 2 runtime state isolation and the current route/state boundary work | Good merge candidate after Home preview, fullscreen handoff, cleanup, and loading/error tests pass |
| Cache live category flows per provider | Useful data/runtime optimization, but not specifically a Compose refactor | Keep as a separate data-layer change; add replay, refresh, preference-change, provider-removal, and one-shot `first()` tests before merging |
| `compose-stability.conf` for all `domain.model` classes | Supports the stability goal, but the wildcard is broader than the proof currently provides | Do not accept unchanged; audit every UI-facing model and replace the wildcard with a verified allowlist or genuinely immutable UI models |
| Baseline profile module and generated profiles | Fits Phase 2.5B startup/profile optimization | Keep the Gradle/profile-installation concept, but use the existing `:benchmark` producer, separate startup from critical journeys, regenerate on current code, and validate release/beta behavior before committing output |

The Home preview split is the part most directly connected to this Compose plan. The stability configuration is the highest correctness risk because Kotlin `List`/`Map` properties are not immutable by type, even when the containing model uses `val` properties. The category cache also needs explicit freshness tests because `shareIn(replay = 1)` changes the behavior of callers that use `first()`.

The four generated profile files in PR #162 are identical: beta and release contain the same rules, and `baseline-prof.txt` is also identical to `startup-prof.txt`. The generator currently sets `includeInStartupProfile = true` around startup, top-navigation traversal, and list scrolling, causing the whole journey to be treated as startup code. Startup-profile generation should cover only startup-critical paths; general Home/navigation/scrolling journeys belong in the Baseline Profile without being promoted into the Startup Profile.

Recommended integration order:

1. Merge the accepted Home preview change into `develop` after adding focused tests.
2. Review the category cache as an independent data PR.
3. Narrow and validate the stability configuration separately.
4. Add baseline-profile generation separately after the benchmark/device setup is available.
5. Synchronize `feature/improveCompose` with the resulting `develop` and rerun Phase 0 measurements.

Execution update (2026-08-22): the category cache is implemented and reported
as Phase 2.5A, and the startup coordinator/profile tooling is implemented and
reported as Phase 2.5B. The API 36 TV emulator proves profile collection and
shipping packaging for beta, release APK, and release AAB, and provides a
diagnostic startup benefit. The Phase 2.5B physical-device and release-APK-size
gates remain open; generated profile output is therefore not yet a
release-approved artifact.

Phase 2.5A measurement update (2026-08-23): the category re-entry
Macrobenchmark now counts category presentation builds plus shared-flow
upstream starts and stops. The cache keeps each provider's Room observation
alive for 30 seconds after its last subscriber, while provider removal cancels
the entry immediately. The deterministic unit test confirms that a subscriber
returning within that window replays the latest value without a second upstream
start.

The connected seeded-emulator task-reset journey was rerun after a clean debug
process reset. Five iterations captured `2/2/1` for build/upstream-start/
upstream-stop (min/median/max) and 83/87/90 frames. A controlled diagnostic run
with the stop timeout set to zero captured the same `2/2/1` lifecycle counts,
so this Activity-task boundary did not demonstrate a measurable cache win. The
result is useful lifecycle evidence but not a quantified runtime reduction; a
direct Room query-count or category-ready-latency benchmark remains open. The
emulator and debuggable seeded target are diagnostic only, and the separate
`.debug` interaction package is intentionally used to provide deterministic
seeded data.

### 19.1 Baseline profile maintenance policy

- Treat the generator tests and reusable critical-user-journey helpers as the source of truth. Never hand-maintain generated profile rules.
- Cherry-pick or recreate the baseline-profile module and build configuration when this phase begins; regenerate the text files from the then-current `develop` code instead of carrying PR #162's generated files forward.
- Keep startup generation separate from general performance journeys. Startup, Home, Live TV/EPG, and player-controls journeys should be explicit and independently reviewable.
- Use a pinned Gradle-managed device for reproducible generation where practical. Use physical constrained-TV hardware for before/after benchmarking.
- If beta and release execute the same code paths, set `mergeIntoMain = true` and maintain one generated profile. Keep variant-specific profiles only when their code or journeys genuinely differ.
- Regenerate before release candidates and after material startup/navigation changes, feature-module moves, package or method-signature changes, major Compose/Kotlin/AGP upgrades, or changes to the profiled journeys.
- Commit generated output only after the generator succeeds, the release artifact contains the compiled profile, and macrobenchmarks show a benefit without unacceptable APK, memory, or compilation cost.
- Do not enable profile generation on every ordinary developer build. A dedicated release-candidate or scheduled CI job avoids doubling normal build time while still keeping profiles current.

Official maintenance references: [Create Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile), [Configure Baseline Profile generation](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles), and [Startup Profiles](https://developer.android.com/topic/performance/startupprofiles/dex-layout-optimizations).

## Appendix A - Initial file mapping

The following is a proposed migration map. Exact names may change during implementation.

| Current file or package | Proposed destination |
|---|---|
| `MainActivity.kt` | `:app` |
| `navigation/AppNavigation.kt` | `:app` plus feature `navigation/*NavGraph.kt` files |
| `ui/theme` | `:core:ui` |
| `ui/design` | `:core:ui` |
| Generic parts of `ui/interaction` | `:core:ui` |
| Generic parts of `ui/components` | `:core:ui` |
| App-specific parts of `ui/components/shell` | `:app` or feature navigation shell |
| `ui/screens/player` | `:feature:playback` |
| `ui/screens/multiview` | `:feature:playback` |
| `ui/screens/provider` | `:feature:provider` |
| `ui/screens/settings` | `:feature:settings` |
| `ui/screens/home` | `:feature:live` |
| `ui/screens/epg` | `:feature:live` |
| `ui/screens/movies` | `:feature:catalog` |
| `ui/screens/series` | `:feature:catalog` |
| `ui/screens/vod` | `:feature:catalog` |
| `ui/screens/favorites` | `:feature:catalog` |
| `ui/screens/search` | `:feature:catalog` |
| `ui/screens/dashboard` | `:feature:catalog` initially; reconsider if it becomes an app-level aggregator |
| `ui/screens/welcome` | `:feature:system` or `:app` initially |
| `ui/screens/downloads` | `:feature:system` or dedicated feature if it grows |
| `ui/screens/plugins` | `:feature:system` or dedicated feature if plugin UI grows |

## Appendix B - Suggested local commands

Examples use PowerShell from the repository root.

Build profile:

```powershell
.\gradlew.bat --stop
.\gradlew.bat clean :app:assembleDebug --profile --no-daemon
.\gradlew.bat :app:assembleDebug --profile
```

Focused checks during early source decomposition:

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

After feature extraction, run the feature's checks directly before the app integration checks:

```powershell
.\gradlew.bat :feature:playback:compileDebugKotlin
.\gradlew.bat :feature:playback:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

After modifying code files, refresh the repository graph:

```powershell
graphify update .
```

## Appendix C - Architecture review checklist

Use this checklist for every migrated feature:

- [ ] The feature has one clear Route entry point.
- [ ] The Screen can render without Hilt or a `NavController`.
- [ ] UI state is immutable by contract.
- [ ] High-frequency state is isolated near its consumer.
- [ ] Lazy collections use stable keys.
- [ ] Mixed lazy content uses `contentType`.
- [ ] Business sorting/filtering is outside composition.
- [ ] Android launchers and platform services are at the route boundary.
- [ ] No DAO or remote implementation type appears in presentation APIs.
- [ ] No concrete Media3 engine cast appears in presentation code.
- [ ] Shared components are truly generic before moving to `:core:ui`.
- [ ] Navigation is expressed through callbacks or destination contracts.
- [ ] Focus, accessibility, RTL, touch, mouse, and remote behavior are tested.
- [ ] Build impact is measured.
- [ ] Runtime impact is measured for hot flows.
- [ ] Player-facing changes pass full live playback validation.
