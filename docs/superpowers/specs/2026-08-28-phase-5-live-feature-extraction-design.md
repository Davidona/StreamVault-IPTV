# Phase 5 Live Feature Module Extraction Design

Date: 2026-08-28  
Status: Approved for implementation planning

## Context

Phase 5 moves presentation out of `:app` into independently compiled feature
modules without changing behavior. `:feature:playback` and `:feature:provider`
are extracted. `:feature:settings` is codewise extracted and independently
checked, with its remaining acceptance and performance gates recorded in
`docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_REPORT.md`. Those open gates remain
separate and are not reopened or reinterpreted by this slice.

The next roadmap item is `:feature:live`. Its current move unit contains Home
Live TV browsing, category and channel presentation, EPG/guide presentation,
preview rendering and state, remote-input policy, dialogs, Hilt ViewModels,
resources, and tests. The production Home and EPG packages currently contain
13 Kotlin files and approximately 10,700 lines. Graphify identifies
`HomeViewModel` and `EpgViewModel` as god nodes with 69 and 71 connections.

The extraction crosses three existing ownership seams:

- Home and EPG directly use app implementations such as
  `StreamVaultPluginManager` and `TvInputChannelSyncManager`.
- Preview handoff and MultiView are implemented in `:feature:playback`, but
  Phase 5 prohibits feature implementation dependencies.
- Home and EPG still consume app-owned shell, card, selection, category,
  remote-input, time-format, and dialog presentation.

The extraction is therefore contract-first and staged. It is not a package
rename, a Live TV redesign, a preview-policy rewrite, or a Catalog extraction.

## Goal

Create `:feature:live` as the sole owner of Home Live TV and EPG presentation,
state, dialogs, preview UI, feature resources, tests, and graph registration,
while `:app` remains the platform and navigation composition root.

The feature must compile and test independently. A live-only source edit must
not compile Settings, Provider, Playback, Catalog, or System presentation
source. Route arguments, list ordering, stable identities, focus restoration,
remote shortcuts, callback order, preview handoff, EPG navigation, recording
and reminder actions, and visual output remain equivalent.

## Non-goals

- Do not convert Home Live TV or EPG away from Compose.
- Do not redesign Home, EPG, their modes, dialogs, navigation chrome, or
  preview layout.
- Do not split or behaviorally refactor `HomeViewModel` or `EpgViewModel`
  during the mechanical ownership transfer.
- Do not change preview preparation, adaptive reprime, decoder/surface/buffer
  setup, playback recovery, timeshift, HLS fallback, or player lifecycle.
- Do not move `LivePreviewHandoffManager`, `MultiViewManager`,
  `MultiViewViewModel`, or `MultiViewPlannerDialog` out of
  `:feature:playback`.
- Do not add `:feature:live -> :feature:playback` or any other feature-to-
  feature implementation dependency.
- Do not start Dashboard, Movies, Series, VOD, Favorites, Search, Downloads,
  Plugins, Welcome, Catalog, or System extraction.
- Do not close Playback, Provider, or Settings acceptance/performance gates.
- Do not claim Phase 5 completion from completion of this slice.

## Current ownership and coupling

### Live presentation move unit

```text
app/src/main/java/com/streamvault/app/ui/screens/home/
app/src/main/java/com/streamvault/app/ui/screens/epg/
app/src/test/java/com/streamvault/app/ui/screens/home/
app/src/test/java/com/streamvault/app/ui/screens/epg/
```

The production move unit includes:

- `HomeScreen`, `HomeScreenDialogs`, `HomeSidebarComponents`,
  `HomePreviewUiState`, and `HomeViewModel`.
- `EpgScreen`, `EpgControlComponents`, `EpgGridComponents`,
  `EpgHeroComponents`, `EpgScreenDialogs`, `GuideDateTime`,
  `PlayerTransparentGuideOverlay`, and `EpgViewModel`.
- `HomeViewModelTest`, `EpgViewModelTest`, `GuideDateTimeTest`, and
  `ProgramReminderIssueMessageTest`.

`PlayerTransparentGuideOverlay` is currently unreferenced. The accepted Phase
5 design explicitly deferred it from Playback to this slice. It moves with EPG
so there is one owner, but it remains unregistered and behaviorally unchanged.

### Navigation

`app/navigation/graph/LiveGraph.kt` currently owns `live_tv` and `epg`
destination registration. `AppRouteCodec` owns compatibility encoding and
decoding for:

```text
live_tv?categoryId={categoryId}
epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}
```

The feature takes ownership of the stable patterns and graph registration.
`AppRouteCodec` retains external compatibility, typed-destination encoding,
payload transport, and player-request construction.

### Preview and MultiView coupling

Home and EPG use the `:player` API for auxiliary preview engines and directly
use Playback implementation types for handoff and MultiView. The new feature
must not import those types. `:app`, which already depends on both features,
adapts the existing implementations to live-owned ports without adding policy.

### Direct implementation dependencies

The current packages directly use these non-domain implementations:

| Implementation | Current responsibility | Phase 5 treatment |
|---|---|---|
| `PreferencesRepository` | live/guide preferences and preview engine configuration | temporary `:data` dependency; ledger every source site |
| `ProviderSyncStateSource` | Home provider synchronization state | temporary `:data` dependency; ledger source site |
| `StreamVaultPluginManager` | preview stream preparation | replace with `LivePreviewStreamPreparer` app adapter |
| `TvInputChannelSyncManager` | refresh TV input after provider refresh | replace with `LiveSurfaceRefreshPort` app adapter |
| `LivePreviewHandoffManager` | preview/fullscreen/reverse handoff | replace with `LivePreviewHandoffPort` app adapter |
| `MultiViewManager` | slot count and capacity | replace with `LiveMultiViewStatusPort` app adapter |
| `MultiViewViewModel` / `MultiViewPlannerDialog` | planner presentation and launch | inject app-composed `LiveMultiViewPlannerContent` |

All remaining direct `:data` imports are recorded in
`docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`. The direct
`:player` dependency remains approved for `PlayerEngine`, render surfaces,
engine qualifiers, and immutable player API types.

## Target dependency direction

```text
:app
  -> :feature:live
  -> :feature:playback
  -> :feature:provider
  -> :feature:settings
  -> :core:navigation / :core:ui / :domain / :data / :player

:feature:live
  -> :core:navigation
  -> :core:ui
  -> :domain
  -> :data       # temporary; every direct implementation import ledgered
  -> :player     # preview engine and render-surface API only
```

`:feature:live` must not depend on `:app`, `:feature:playback`,
`:feature:provider`, `:feature:settings`, or a root navigation controller.

## Target ownership

### `:feature:live` owns

- `LiveRoutePatterns` and `registerLiveGraph`.
- Home Live TV screen, state, preview state, ViewModel, categories, channels,
  sidebars, quick filters, reorder UI, dialogs, and remote-shortcut policy.
- EPG screen, state, ViewModel, grid, hero, controls, dialogs, override UI,
  reminder/recording presentation, date/time helpers, and the deferred
  transparent-guide source.
- Live-owned playback request value types passed to app callbacks.
- `LivePreviewHandoffPort`, `LivePreviewStreamPreparer`,
  `LiveSurfaceRefreshPort`, `LiveMultiViewStatusPort`, and immutable boundary
  values.
- Live-specific copies/adapters of presentation that currently lives in
  `:app` but cannot move to `:core:ui` without coupling Core UI to domain
  models or starting Catalog work.
- Feature strings/plurals/drawables, locale translations, unit tests,
  connected tests, golden fixtures, Compose diagnostics, and module-boundary
  verification.

### `:app` retains

- `MainActivity`, root `NavHostController`, `AppNavHost`, external intents,
  `AppRouteCodec`, navigation payload storage, and startup/top-level policy.
- Construction of `PlayerNavigationRequest` from live-owned playback request
  values and invocation of `NavigationActions.openPlayer`.
- App adapters for plugin stream preparation, TV-input refresh, preview
  handoff, and MultiView status.
- Composition of Playback-owned `MultiViewPlannerDialog` and navigation to
  `AppDestination.MultiView`.
- App-owned shared component implementations still used by Catalog/System.
  They are not deleted or moved merely because Live receives feature-local
  equivalents.
- Generated profile production and app-level compatibility tests.

### Neutral shared ownership

- Shell visuals, device classification, notification permission gating,
  generic TV buttons/surfaces, PIN dialogs, image wrappers, and time-format
  primitives use existing `:core:ui` APIs.
- `GuideLookupKey` and live remote-shortcut dispatch are live-specific and move
  into `:feature:live`.
- Shared app components that accept domain entities remain in `:app` for
  Catalog until that slice. Live-local equivalents use distinct names where
  both implementations coexist; Catalog source is otherwise untouched.

## Public feature boundary

### Routes and graph registration

The feature owns route strings exactly matching current compatibility:

```kotlin
object LiveRoutePatterns {
    const val LIVE_TV = "live_tv"
    const val LIVE_TV_DESTINATION = "live_tv?categoryId={categoryId}"
    const val EPG = "epg"
    const val EPG_DESTINATION =
        "epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}"
}
```

Graph registration is controller-free:

```kotlin
fun NavGraphBuilder.registerLiveGraph(
    navigationDestinations: List<UiDestination>,
    onDestinationRequested: (AppDestination) -> Unit,
    onPlayChannel: (LiveChannelPlaybackRequest) -> Unit,
    onPlayArchive: (LiveArchivePlaybackRequest) -> Unit,
    multiViewPlannerContent: LiveMultiViewPlannerContent,
)
```

The graph reads the existing argument defaults (`-1L`, `-1L`, and `false`),
constructs Home and Guide return destinations with the same values, and passes
controller-free callbacks to `HomeScreen` and `FullEpgScreen`. It never imports
`AppRouteCodec`, `Routes`, `NavController`, `MainActivity`, app payloads, or a
Playback implementation.

The app maps `LiveChannelPlaybackRequest` and `LiveArchivePlaybackRequest`
field-for-field into the existing `PlayerNavigationRequest` construction path.
The feature request types preserve channel/provider/category identity,
combined-profile/source filters, virtual-category state, archive bounds/title,
and the typed return destination.

### Preview ports

The live feature defines:

```kotlin
enum class LivePreviewOrigin { HOME, GUIDE }

data class LivePreviewSession(
    val engine: PlayerEngine,
    val channelId: Long,
    val providerId: Long,
    val streamInfo: StreamInfo,
)

interface LivePreviewHandoffPort {
    val reverseHandoffOrigin: Flow<LivePreviewOrigin?>

    fun registerPreviewSession(
        channel: Channel,
        streamInfo: StreamInfo,
        engine: PlayerEngine,
        origin: LivePreviewOrigin,
    )

    fun beginFullscreenHandoff(channelId: Long, engine: PlayerEngine?): Boolean
    fun consumeReverseHandoff(origin: LivePreviewOrigin): LivePreviewSession?
    fun clear(engine: PlayerEngine?)
}

interface LivePreviewStreamPreparer {
    suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo>
}
```

`AppLivePreviewHandoffAdapter` delegates to the existing singleton
`LivePreviewHandoffManager`, maps only enum/session value types, and preserves
the same manager instance consumed by Playback. It does not reproduce timeout,
release, or handoff policy. `AppLivePreviewStreamPreparer` delegates to
`StreamVaultPluginManager.preparePlaybackStreamInfo` and preserves the existing
result and error message.

Home and EPG retain their separate preview state and existing preview engine
logic. The ports replace imports only; they do not move player policy into the
live feature.

### TV surface and MultiView ports

```kotlin
interface LiveSurfaceRefreshPort {
    suspend fun refreshTvInputCatalog()
}

data class LiveMultiViewStatus(
    val channelCount: Int = 0,
    val slotCapacity: Int = 4,
)

interface LiveMultiViewStatusPort {
    val status: Flow<LiveMultiViewStatus>
}

typealias LiveMultiViewPlannerContent = @Composable (
    pendingChannel: Channel?,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
) -> Unit
```

The app surface adapter delegates to `TvInputChannelSyncManager`. The app
MultiView adapter combines the existing `MultiViewManager.slots` and persisted
two-slot layout preference into the same count/capacity values currently
computed by `HomeViewModel`. The planner lambda renders the existing
Playback-owned planner with its existing Hilt scope and invokes callbacks in
the same order. Live never imports Playback implementation packages.

## Screen and state boundary

Phase 5 keeps current state topology and ViewModel method order. The target is:

```text
LiveGraph
  HomeRoute
    HomeScreen
      live/category/channel presentation
      isolated HomePreviewUiState consumer
      HomeDialogsHost
      app-composed MultiView planner content

  EpgRoute
    FullEpgScreen
      guide grid/hero/controls
      isolated preview fields and render surface
      EpgScreenDialogs
```

`HomeUiState`, `HomePreviewUiState`, `EpgUiState`, `EpgOverrideUiState`, and
`ProgramReminderUiState` retain their current fields and defaults during the
move. Existing lazy keys/content types, focus requesters, saveable state,
dialog ordering, lifecycle effects, permission gating, and Back behavior are
preserved. Any missing stable key or `contentType` found by the inventory is
recorded as a separate behavior/performance follow-up unless adding it is
provably identity-preserving and covered by focused tests.

The screens use `CoreAppScreenScaffold`, `UiDestination`, core time-format and
device APIs, and feature-local presentation components. They remain renderable
with explicitly supplied ViewModels/state and callbacks, without a root
controller or app activity.

## Resources

The initial scan found 196 unique resource references in the Home/EPG move
unit. The implementation inventory records exact default and locale ownership
before moving any key.

Feature-owned Home, Live TV, EPG, preview, reminder, recording-conflict, and
dialog resources move locale by locale into `:feature:live`. A key is removed
from `:app` only when no app source, test, manifest, benchmark, or other feature
uses it. Shared keys may be duplicated temporarily with identical values; the
duplication and future owner are recorded in the live report.

The module namespace is `com.streamvault.feature.live`. Moved source uses
`com.streamvault.feature.live.*` and `com.streamvault.feature.live.R`.

## Migration sequence

1. Record rollback SHA, exact sources/tests/resources/imports/routes, current
   test state, source counts, baseline commands, and all existing open gates.
2. Add `:feature:live`, coverage/Compose configuration, and a fail-closed
   boundary task with Kotlin and Java violation fixtures.
3. Add route patterns and live-owned playback request values with compatibility
   tests before changing app graph wiring.
4. Add preview, preparation, TV-surface, and MultiView contracts; implement
   app adapters and Hilt bindings with delegation tests.
5. Replace app shell/time/device/remote/shared-presentation imports with core
   APIs or distinct live-local presentation equivalents.
6. Move Home state, ViewModel, preview state, tests, UI, dialogs, and resources.
7. Move EPG state, ViewModel, tests, UI, dialogs, deferred overlay, and
   resources.
8. Register the feature graph from `AppNavHost`; app callbacks construct player
   requests and compose the existing MultiView planner.
9. Remove legacy live ownership only after feature, app integration, connected,
   and installed-route checks pass.
10. Audit temporary dependencies/resources, capture build measurements,
    regenerate profiles, run full live acceptance, refresh Graphify, and write
    `docs/COMPOSE_REDUCTION_PHASE5_LIVE_REPORT.md`.

## Error handling and rollback

- Missing or invalid route arguments retain existing defaults.
- No provider, empty category/channel, stale EPG, preview preparation failure,
  permission denial, reminder delivery issue, recording conflict, and EPG
  override errors keep current messages and callback ordering.
- Preview cancellation, replacement, adaptive reprime, fullscreen handoff,
  reverse handoff, clear, engine stop, and engine release ordering remain
  unchanged.
- MultiView planner dismissal and launch clear dialog/preview state in the same
  order before app navigation.
- App adapters delegate and map values only; they do not catch, translate, or
  retry failures unless the existing call site already does so.
- The app-owned live destinations remain wired until the feature graph compiles,
  unit tests pass, and the installed app reaches both Live TV and EPG.
- Contracts, mechanical moves, graph wiring, cleanup, profiles, and acceptance
  evidence are separate rollback points.

## Verification

### Automated structural gates

- `:feature:live:verifyFeatureLiveBoundary`
- `:feature:live:testDebugUnitTest`
- `:feature:live:lintDebug`
- `:feature:live:compileDebugAndroidTestKotlin`
- `:feature:live:check`
- `:feature:live:assembleDebug`
- route-pattern, playback-request mapping, app-adapter, and graph callback tests
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- source scans for app/root-controller/feature-implementation imports
- runtime dependency/ledger/resource ownership comparison
- `graphify update .` after code changes

### Connected and manual behavior gates

- Live TV route with and without `categoryId`.
- EPG route with category, anchor time, and favorites combinations.
- Category/source switching, search, quick filters, hidden items, favorites,
  custom groups, pin/unpin, reorder, parental PIN, and focus restoration.
- Repeated channel focus, preview start/stop/replacement, fullscreen handoff,
  reverse handoff, and MultiView planner/launch.
- EPG horizontal time movement, vertical channel movement, paging, day/prime-
  time jumps, density/mode/favorites filters, search, overrides, reminders,
  recording conflicts, archive launch, and Back behavior.
- TV remote, touch/mouse, RTL, reduced motion, accessibility semantics, and
  notification permission behavior.

Unavailable providers, archive windows, recording storage, notification
permission, accounts, hardware, or physical devices are recorded as open gates,
not passes.

### Mandatory live playback protocol

Because this slice changes preview composition, preview handoff, navigation,
and render-surface ownership, validation must cover at least two live channels.
For each channel:

- Capture 61 screenshots at a two-second cadence where practical (never fewer
  than 45 screenshots for approximately 90 seconds).
- Confirm changing screenshot hashes through the full window.
- Confirm the media session remains `PLAYING` with `error=null`.
- Confirm sanitized HLS prepare/read/first-frame or intended recovery evidence.
- Confirm no fatal player error, stuck-player timeout, or unintended MPEG-TS
  fallback.
- Record channel name, screenshot count, interval, unique hash count, media
  session result, and log findings.

A build, install, launch, single screenshot, or short visual check is not live
acceptance.

### Build and profile evidence

- Five before/after live-only incremental source-edit runs.
- Five before/after moved-test compilation runs.
- Clean debug and warm no-change guardrails.
- Executed-task proof that a live edit does not compile unrelated feature
  source.
- Profile regeneration after package/signature moves, followed by scans for
  stale `com/streamvault/app/ui/screens/home` and `/epg` descriptors.
- Runtime benchmark evidence for category switching, channel focus, preview
  start/stop/replacement, and EPG horizontal/vertical navigation when the
  environment supports reliable measurement.

## Exit criteria

- Home Live TV and EPG presentation, state, dialogs, resources, tests, preview
  UI, and graph registration are owned by `:feature:live`.
- `:app` registers the live graph and supplies player-navigation callbacks,
  app adapters, and Playback-owned MultiView planner content only.
- No live feature source imports `com.streamvault.app`, a root navigation
  controller, `MainActivity`, or another feature implementation.
- Preview handoff continues through the same Playback singleton via the app
  adapter; no handoff/release policy is duplicated.
- All temporary `:data` imports are complete in the transitional ledger.
- Feature tests run independently and a live-only edit is isolated from
  unrelated feature compilation.
- Multi-channel long-duration validation and all available Live/EPG functional
  gates are recorded with exact evidence.
- Remaining unavailable or failed gates are stated in
  `docs/COMPOSE_REDUCTION_PHASE5_LIVE_REPORT.md` without claiming Phase 5
  completion or closing Settings, Provider, or Playback gates.
