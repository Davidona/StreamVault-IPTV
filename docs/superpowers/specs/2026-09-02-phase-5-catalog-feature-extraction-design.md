# Phase 5 Catalog Feature Extraction Design

Date: 2026-09-02

## Status

Approved scope derived from the governing Compose reduction plan and the Phase
5 roadmap. This document defines the dedicated design for the fifth Phase 5
slice, `:feature:catalog`.

## Goal

Move Dashboard, Movies, Series, combined VOD, Favorites, Search, and catalog
detail presentation out of `:app` into an independently compiled and tested
`:feature:catalog` Android library. A Catalog-only change must stop recompiling
unrelated feature presentation, while routes, return behavior, screen output,
focus, input, lazy-list identity, Cast, download, and update behavior remain
unchanged.

This is an ownership and build-boundary change. It is not a visual redesign, a
Compose-to-Views conversion, a repository rewrite, or a change to playback,
Cast, download, provider synchronization, filtering, grouping, ranking,
parental-control, or navigation policy.

## Current baseline

The unextracted Catalog surface currently contains 21 production Kotlin files
and approximately 14,662 lines under these app packages:

- `ui/screens/dashboard`
- `ui/screens/movies`
- `ui/screens/series`
- `ui/screens/vod`
- `ui/screens/favorites`
- `ui/screens/search`

The app also owns `HomeGraph`, `CatalogGraph`, catalog payload transport, detail
navigation helpers, Catalog-only card/browse components, four Catalog-focused
unit tests, and four Catalog route golden cases inside the mixed app golden
suite.

The current code has four boundary problems that must be resolved during the
move:

1. Catalog screens import app navigation, shell, device, time, formatting,
   resources, `MainActivity`, `StreamVaultPluginManager`,
   `DownloadForegroundService`, and app-update implementations.
2. Dashboard directly renders the Settings-owned
   `DashboardShelfCustomizationDialog`.
3. Movie and Series detail ViewModels directly import Playback-owned Cast
   implementations and resources.
4. Seven Catalog ViewModels import `PreferencesRepository`, and Dashboard also
   imports `ProviderSyncStateSource`. These are allowed temporary `:data`
   dependencies, but they must be recorded in the Phase 5 transitional ledger.

## Scope

### `:feature:catalog` owns

- `CatalogRoutePatterns` and controller-free `registerCatalogGraph`.
- The Home/Dashboard route and media shelves.
- Movies browse and movie detail presentation.
- Series browse and series detail presentation.
- Combined VOD browse presentation and its shared browse policies/helpers.
- Search presentation and its ViewModel.
- Favorites presentation and its ViewModel. Favorites remains unregistered if
  it is unregistered before extraction; this slice does not create a new route.
- Catalog-specific cards, rows, filters, dialogs, browse chrome, time/progress
  ticker, formatting, and presentation models.
- Catalog resources, unit tests, connected behavior tests, and Catalog golden
  fixtures.
- Catalog boundary verification and Compose compiler diagnostics.

### `:app` retains

- `MainActivity`, `AppNavHost`, the root `NavHostController`, external intents,
  startup routing, compatibility encoding/decoding, and saved-state payload
  transport.
- Player-request construction for channels, movies, episodes, and playback
  history.
- The implementation that opens the Cast route chooser.
- The `StreamVaultPluginManager` adapter used to prepare copy/download streams.
- The `DownloadForegroundService` launcher.
- App-update version/channel policy and `AppUpdateInstaller` integration.
- The composition seam that renders the Settings-owned dashboard-shelf dialog.
- App navigation-destination collection and mapping into Core UI shell models.
- Platform and Hilt adapters that implement Catalog-owned ports.

### Other modules retain

- `:feature:settings` retains `DashboardShelfCustomizationDialog` because it
  edits a persisted settings preference and is also a Settings surface.
- `:feature:playback` retains Cast request construction, coordination, playback
  events, message mapping, and player presentation.
- `:core:navigation` retains `AppDestination`, `NavigationActions`,
  `NavigationOptions`, and player navigation request types.
- `:core:ui` retains generic shell visuals, image wrappers, TV device
  detection, time-format composition locals, focus/input primitives, and
  feature-neutral visual components.
- `:domain` retains repositories, use cases, managers, models, and pure catalog
  policies.
- `:data` temporarily supplies `PreferencesRepository` and
  `ProviderSyncStateSource` until the Phase 7 contract cleanup.

### Excluded

- Downloads, Plugins, Welcome, Settings, Live, EPG, Playback, Provider, and
  MultiView screen extraction.
- New Favorites navigation or product-visible route changes.
- Paging-library adoption, new loading behavior, new sorting/filtering rules,
  or list-window size changes.
- Cast, download, app-update, plugin, playback preparation, and provider-sync
  policy changes.
- Closing open acceptance or performance gates belonging to Playback,
  Provider, Settings, or Live.

## Chosen approach

Use one Catalog module with app-owned adapters and composition slots.

This is preferred over leaving Dashboard in `:app` because the accepted plan
and roadmap explicitly place Dashboard media shelves in Catalog, and its media
models/components overlap heavily with Movies, Series, Search, and Favorites.
The cross-feature integrations are narrow enough to express as callbacks or
ports.

This is preferred over adding `:feature:catalog -> :feature:settings` or
`:feature:catalog -> :feature:playback` dependencies because Phase 5 prohibits
feature implementation dependencies. The app already depends on all feature
modules and is the correct composition point for those integrations.

Splitting Dashboard into a separate module is rejected for this slice. Its
current size does not justify another Gradle module, and doing so would reduce
the build-isolation value of the accepted Catalog grouping.

## Module and package shape

~~~text
:feature:catalog
  api/
    CatalogFeatureContracts.kt
    CatalogPlatformPorts.kt
  navigation/
    CatalogGraph.kt
    CatalogRoutePatterns.kt
  presentation/
    components/
    dashboard/
    favorites/
    movies/
    search/
    series/
    vod/
    time/
~~~

Production packages use `com.streamvault.feature.catalog.*`. Existing source
files move with history where practical. Large screens are not redesigned or
arbitrarily decomposed as part of the module move; only focused files required
for app-independent contracts and feature-local shared components are added.

The module's approved project dependencies are exactly:

~~~text
:core:navigation
:core:ui
:domain
:data
~~~

The module must not depend on `:app`, `:player`, or any `:feature:*`
implementation module. The `:data` dependency is temporary and all imported
types are listed in
`docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`.

## Feature contracts

The feature owns small contracts that contain no app or sibling-feature types.
Names and shapes are fixed for the implementation plan.

~~~kotlin
enum class CatalogNavigationChrome { Rail, TopBar }

typealias CatalogScaffoldContent = @Composable (
    currentDestination: AppDestination,
    title: String,
    subtitle: String?,
    navigationChrome: CatalogNavigationChrome,
    topBarVisible: Boolean,
    compactHeader: Boolean,
    showScreenHeader: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) -> Unit

typealias CatalogDashboardShelfCustomizationContent = @Composable (
    currentShelves: List<AppHomeDashboardShelf>,
    onDismiss: () -> Unit,
    onSave: (List<AppHomeDashboardShelf>) -> Unit,
) -> Unit

interface CatalogPlatformHost {
    fun openCastRouteChooser()
}

interface CatalogStreamPreparer {
    suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo>
}

interface CatalogDownloadStarter {
    fun startDownload(downloadId: String)
}
~~~

`CatalogScaffoldContent` intentionally exposes exactly the arguments used by
the current Catalog routes. None of the six Catalog root screens currently
passes custom header, action, modifier, or content-padding slots. The app maps
`currentDestination` through `AppRouteCodec` and captures the existing
top-level destination callback when it delegates to `AppScreenScaffold`.

Catalog-internal navigation uses typed Core destinations rather than app route
strings:

~~~kotlin
data class CatalogChannelPlaybackContext(
    val categoryId: Long?,
    val providerId: Long,
    val isVirtual: Boolean,
    val combinedProfileId: Long?,
    val returnDestination: AppDestination,
)
~~~

Dashboard and Favorites receive `onDestinationRequested:
(AppDestination) -> Unit` for their content actions. Movies, Series, VOD, and
Search no longer accept a raw `onNavigate` string solely to configure the app
shell; their scaffold slot captures the app-owned destination selection
callback. Favorites continues to accept an explicit current destination from
its direct test host because it has no registered route.

Cast behavior is translated through Catalog-owned request/result/event types:

~~~kotlin
data class CatalogCastRequest(
    val streamInfo: StreamInfo,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val startPositionMs: Long,
)

enum class CatalogMessage {
    CastStarted,
    CastUnavailable,
    CastUnsupported,
    CastItemUnavailable,
    CastSessionFailed,
    CastLoadFailed,
    DownloadStarted,
    DownloadFailed,
    DownloadUrlUnavailable,
}

sealed interface CatalogCastStartResult {
    data object Started : CatalogCastStartResult
    data object RouteSelectionRequired : CatalogCastStartResult
    data object Unavailable : CatalogCastStartResult
    data class Unsupported(val message: CatalogMessage) : CatalogCastStartResult
}

sealed interface CatalogCastPlaybackEvent {
    data object RouteSelectionCancelled : CatalogCastPlaybackEvent
    data class Finished(
        val succeeded: Boolean,
        val message: CatalogMessage,
    ) : CatalogCastPlaybackEvent
}

interface CatalogCastPort {
    val playbackEvents: Flow<CatalogCastPlaybackEvent>
    suspend fun startCasting(request: CatalogCastRequest): CatalogCastStartResult
}

sealed interface CatalogUiEvent {
    data object OpenCastRouteChooser : CatalogUiEvent
    data class ShowMessage(val message: CatalogMessage) : CatalogUiEvent
}
~~~

The app adapter delegates to the existing Playback Cast factory/coordinator
and maps results field-for-field. Movie and Series detail ViewModels retain the
current pending/report-mode behavior but expose `CatalogUiEvent` and feature
resource messages.

Dashboard update behavior uses an app-owned adapter:

~~~kotlin
enum class CatalogUpdateAction {
    None,
    DownloadLatest,
    Downloading,
    InstallLatest,
    InstallPermissionRequired,
}

data class CatalogUpdateNotice(
    val latestVersionName: String,
    val downloadSha256: String?,
    val action: CatalogUpdateAction,
)

interface CatalogAppUpdatePort {
    val notice: Flow<CatalogUpdateNotice?>
    suspend fun installDownloadedUpdate(expectedSha256: String?): Result<Unit>
}
~~~

The app implementation owns current-build comparison, cached release mapping,
download-state mapping, and installation. The Catalog ViewModel only displays
the mapped notice and requests installation.

## Navigation seam

`CatalogRoutePatterns` owns these existing strings without changing their
values:

~~~kotlin
object CatalogRoutePatterns {
    const val HOME = "home"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val VOD = "vod"
    const val SEARCH = "search"
    const val SEARCH_DESTINATION = "search?query={query}"
    const val MOVIE_DETAIL = "movie_detail/{movieId}?returnRoute={returnRoute}"
    const val SERIES_DETAIL = "series_detail/{seriesId}?returnRoute={returnRoute}"
    const val MOVIE_DETAIL_PRESENTATION_HINT_KEY = "movie_detail_presentation_hint"
    const val SERIES_DETAIL_PRESENTATION_HINT_KEY = "series_detail_presentation_hint"
}
~~~

`AppRoutePatterns` aliases these constants so external routes and compatibility
tests remain app-owned. `AppRouteCodec` continues to encode and decode
`AppDestination` and return-route query values.

The feature graph registers Home, Movies, Series, VOD, Search, Movie Detail,
and Series Detail. It receives callbacks for:

- top-level destination requests;
- opening movie and series detail with presentation hints;
- channel, movie, episode, and history playback requests;
- consuming/copying movie and series presentation hints;
- app shell rendering and dashboard shelf customization.

The feature graph never constructs `PlayerNavigationRequest`, imports
`AppRouteCodec`, accesses `AppNavigationPayloads`, or receives a root
controller. `AppNavHost` maps each callback to the existing app helpers.

The existing `HomeGraph` is folded into `registerCatalogGraph`. The old
app-owned `HomeGraph` and `CatalogGraph` are removed only after the feature graph
passes focused graph tests and app navigation integration tests.

## State, data, and platform boundaries

- Existing ViewModel state flows, collection frequency, initial values, and
  callback order remain unchanged.
- Filtering, ranking, grouping, hydration, sorting, and list-window behavior
  move mechanically with their current owners.
- Movie/Series presentation-hint keys become feature-owned; the app payload
  adapter uses those keys when copying hints into the destination entry.
- `CatalogStreamPreparer` replaces direct plugin-manager access for copy and
  download URL preparation.
- Catalog ViewModels continue to use domain `DownloadManager`; successful
  enqueue calls invoke `CatalogDownloadStarter`, and user messages become
  `CatalogUiEvent` values rendered by the screen.
- `CatalogPlatformHost` replaces `MainActivity` discovery for the Cast chooser.
- `CatalogAppUpdatePort` replaces app-update implementation imports.
- Catalog screens use `core.ui.device.rememberIsTelevisionDevice`,
  `LocalUiTimeFormat`, and Core UI date/time formatting.
- The existing millisecond-duration formatter moves into Catalog presentation
  because only Catalog detail screens consume it after extraction.

## Shared presentation ownership

Components whose production consumers are all in the accepted Catalog scope
move to feature-local packages. This includes the current Catalog cards,
`CategoryRow`, continue-watching row, saved-category shortcuts, selection chips,
reorder bar, VOD chrome/classic browser, infinite-scroll effect, browse shell
content, external-ratings strip, episode row, and shared channel-progress
ticker.

The app shell wrapper does not move because Downloads, Plugins, and app-owned
routes still consume it. Catalog receives an app-supplied scaffold slot that
continues to use the same destination list and `CoreAppScreenScaffold` visuals.

Generic Core UI components remain in `:core:ui`. This slice does not move a
component into Core merely to make Catalog compile. A component is copied or
moved into Catalog when its behavior or resource vocabulary is Catalog-specific.

## Resources and localization

Every resource referenced by moved production or test code is copied into the
Catalog namespace before source moves. All currently supported locale values
are copied with identical text, formatting arguments, plurals, and resource
types.

App copies are removed only when no remaining app source, test, manifest,
benchmark, or sibling feature references the key. Shared app keys may remain
duplicated during Phase 5 when ownership is not yet safe to change; duplicates
are listed in the execution report.

The four Catalog golden fixtures move from the mixed app route suite to a
Catalog-owned connected suite:

- `route_dashboard_default`
- `route_movies_landing`
- `route_series_detail`
- `route_search_results`

Pixel output is expected to remain identical. Baselines are regenerated only
when package/resource mechanics make the existing file unusable and the visual
diff has been reviewed as behavior-equivalent.

## Error handling and rollback

- Missing or invalid detail IDs keep the existing loading/error behavior.
- Missing presentation hints fall back to repository detail lookup exactly as
  before.
- Stream preparation, Cast start, Cast lifecycle, download enqueue, update
  installation, clipboard, and external-intent failures keep their current
  user-visible messages and no-op/fallback behavior.
- A null `CatalogPlatformHost` makes route-chooser opening a no-op, matching the
  current non-`MainActivity` behavior.
- Search section failures remain independent and retain the current degraded
  and empty-state behavior.
- Every migration task compiles independently. App graphs may temporarily
  import feature-owned screens until feature graph registration is complete.
- Original app resources and wrappers are removed only after the new feature
  destinations pass. Rollback is a wiring/package revert, not a data migration.

## Verification

### Structural and unit

- `:feature:catalog:verifyFeatureCatalogBoundary`
- `:feature:catalog:compileDebugKotlin`
- `:feature:catalog:testDebugUnitTest`
- `:feature:catalog:lintDebug`
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- `:app:assembleBeta`
- `:app:assembleRelease`

The boundary verifier rejects app packages, `MainActivity`, root navigation
controllers, `:player`, and every sibling feature implementation. It also
asserts the exact four approved project dependencies and tests Kotlin and Java
fixtures for every forbidden category.

### Navigation and behavior

- Route-pattern and graph callback tests cover Home, Movies, Series, VOD,
  Search query defaults, Movie Detail, Series Detail, return destinations, and
  presentation-hint consumption.
- App tests continue to cover `AppRouteCodec`, payload copying, detail opening,
  return navigation, and player-request mapping.
- ViewModel tests cover existing search history, dashboard shelf ordering,
  grouped detail hints, Cast start/lifecycle behavior, download launching, and
  update installation mapping.
- Connected tests cover D-pad focus, touch/mouse activation, search typing,
  filtering/sorting, shelves, combined VOD, detail return, Cast chooser events,
  and lazy-list load-more behavior.
- The four feature golden cases pass from the Catalog module.

### Build and runtime

- Capture five before/after incremental Catalog source-edit runs.
- Capture five before/after incremental Catalog unit-test compile runs.
- Record clean debug and warm no-change guardrails.
- Prove a Catalog-only edit does not compile Playback, Provider, Settings, or
  Live Kotlin source.
- Run the seeded dashboard vertical-scroll Macrobenchmark before and after.
- Exercise Dashboard, Movies, Series, VOD, Search, details, and return routes on
  the API 36 TV emulator, plus phone/tablet and RTL/reduced-motion coverage when
  available.
- Regenerate baseline/startup profiles because packages and method descriptors
  move; verify stale app Catalog descriptors are absent and feature Catalog
  descriptors are present.
- This slice does not require the long-duration Live TV protocol unless an
  implementation change touches playback-facing behavior beyond request
  mapping. Any such change is out of scope and must stop for separate approval.

## Documentation and graph maintenance

Execution produces:

- `docs/COMPOSE_REDUCTION_PHASE5_CATALOG_REPORT.md`
- `validation/phase5_catalog/` inventory, build, resource, connected, device,
  profile, and benchmark evidence
- a `:feature:catalog` section in the transitional dependency ledger
- roadmap and governing-plan status updates based only on completed evidence

After code changes, run `graphify update .` and record the graph refresh in the
Catalog report.

## Exit criteria

- Dashboard, Movies, Series, VOD, Favorites, Search, and detail presentation are
  owned by `:feature:catalog`.
- `:app` contains Catalog composition adapters and compatibility/navigation
  mapping, not Catalog screen implementations.
- The feature has exactly the four approved project dependencies and no app,
  root-controller, player, or sibling-feature implementation imports.
- Existing routes, arguments, presentation hints, return destinations, focus,
  semantics, lazy keys/content types, UI output, and callback sequence are
  preserved.
- Catalog unit and connected tests run independently.
- A Catalog-only edit does not compile unrelated feature source.
- Temporary data imports and remaining duplicated resources are fully recorded.
- Build, runtime, profile, device, and golden evidence is recorded without
  overstating unavailable or failed gates.
- Playback, Provider, Settings, and Live open gates remain governed by their
  existing reports.
