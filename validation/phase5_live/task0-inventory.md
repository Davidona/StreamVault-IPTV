# Phase 5 Live extraction inventory

Date: 2026-08-28

## Scope and rollback point

The live slice is the fourth Phase 5 module extraction: Home Live TV,
categories/channels, EPG/guide, preview presentation, and the required app
composition seams. Catalog and System are explicitly out of scope. Settings,
Provider, and Playback remain governed by their existing reports and open gates.

Rollback point before implementation: `ea349d83dceb8baa45ef1a18b7a45fa295a264f5`
(`docs(live): plan phase 5 feature extraction`). The working tree was clean
when this inventory began.

## Move unit

The current production move unit contains 13 Kotlin files and 10,156 lines:

```text
app/src/main/java/com/streamvault/app/ui/screens/home/HomePreviewUiState.kt
app/src/main/java/com/streamvault/app/ui/screens/home/HomeScreen.kt
app/src/main/java/com/streamvault/app/ui/screens/home/HomeScreenDialogs.kt
app/src/main/java/com/streamvault/app/ui/screens/home/HomeSidebarComponents.kt
app/src/main/java/com/streamvault/app/ui/screens/home/HomeViewModel.kt
app/src/main/java/com/streamvault/app/ui/screens/epg/EpgControlComponents.kt
app/src/main/java/com/streamvault/app/ui/screens/epg/EpgGridComponents.kt
app/src/main/java/com/streamvault/app/ui/screens/epg/EpgHeroComponents.kt
app/src/main/java/com/streamvault/app/ui/screens/epg/EpgScreen.kt
app/src/main/java/com/streamvault/app/ui/screens/epg/EpgScreenDialogs.kt
app/src/main/java/com/streamvault/app/ui/screens/epg/EpgViewModel.kt
app/src/main/java/com/streamvault/app/ui/screens/epg/GuideDateTime.kt
app/src/main/java/com/streamvault/app/ui/screens/epg/PlayerTransparentGuideOverlay.kt
```

Focused tests are:

```text
app/src/test/java/com/streamvault/app/ui/screens/home/HomeViewModelTest.kt
app/src/test/java/com/streamvault/app/ui/screens/epg/EpgViewModelTest.kt
app/src/test/java/com/streamvault/app/ui/screens/epg/GuideDateTimeTest.kt
app/src/test/java/com/streamvault/app/ui/screens/epg/ProgramReminderIssueMessageTest.kt
```

`PlayerTransparentGuideOverlay.kt` is currently unreferenced and was deferred
from Playback by the accepted Phase 5 design. It moves with EPG but is not
registered as a new route.

## Current coupling

The move unit has 140 distinct project imports. App implementation imports are:

```text
com.streamvault.app.R
com.streamvault.app.device.rememberIsTelevisionDevice
com.streamvault.app.navigation.Routes
com.streamvault.app.plugins.StreamVaultPluginManager
com.streamvault.app.tvinput.TvInputChannelSyncManager
com.streamvault.app.ui.components.*
com.streamvault.app.ui.components.dialogs.*
com.streamvault.app.ui.components.shell.*
com.streamvault.app.ui.model.guideLookupKey
com.streamvault.app.ui.remote.*
com.streamvault.app.ui.screens.epg.*
com.streamvault.app.ui.time.*
```

Direct implementation dependencies that need an ownership seam are:

| Type | Current use | Planned treatment |
|---|---|---|
| `PreferencesRepository` | Home/EPG preference flows and preview engine configuration | Retain as temporary ledgered `:data` dependency |
| `ProviderSyncStateSource` | Home provider-sync state | Retain as temporary ledgered `:data` dependency |
| `StreamVaultPluginManager` | Preview stream preparation | `LivePreviewStreamPreparer` port + app adapter |
| `TvInputChannelSyncManager` | TV-input refresh after Home data refresh | `LiveSurfaceRefreshPort` + app adapter |
| `LivePreviewHandoffManager` | Fullscreen/reverse preview handoff | `LivePreviewHandoffPort` + app adapter |
| `MultiViewManager` | Home slot count/capacity | `LiveMultiViewStatusPort` + app adapter |
| `MultiViewViewModel` / `MultiViewPlannerDialog` | Home planner UI | app-composed `LiveMultiViewPlannerContent` callback |

The feature remains allowed to depend on `:player` for `PlayerEngine`, render
surfaces, and player API values. It must not depend on `:feature:playback`.

## Shared presentation ownership

These current app components are used outside the live slice and cannot be
moved into Live without making Catalog depend on Live:

```text
CategoryRow, ChannelCard, ChannelProgressTicker, ReorderTopBar,
SelectionChipRow, CategoryOptionsDialog
```

Live receives behavior-preserving, distinctly named local equivalents while
the existing app implementations remain for Dashboard, Movies, Series,
Favorites, Search, and VOD. Core UI is used for app-independent shell, focus,
button, image, time, device, notification, and empty-state primitives.

Live-only app components such as source switching, Home category dialogs,
guide lookup identity, and live remote-shortcut dispatch move into the feature.

## Resources

The initial scan found 196 unique resource references across Home and EPG. They
cover Home/category/channel/quick-filter/group UI, preview states, EPG grid and
controls, archive/reminder/recording conflict, notification permission, and
shared action labels. Resource migration is locale-by-locale; an app resource
is removed only after repository-wide consumer search proves it is no longer
needed. Shared keys may be duplicated temporarily with identical values.

## Routes and consumers

Current route patterns are:

```text
live_tv?categoryId={categoryId}
epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}
```

`AppNavHost` calls `app.navigation.graph.LiveGraph.registerLiveGraph`; that
graph calls `HomeScreen` and `FullEpgScreen`. `AppRouteCodec` remains the
compatibility encoder/decoder and currently builds the exact `LiveTv` and
`Guide` typed destinations. The new feature graph will receive typed callbacks;
the app will continue constructing `PlayerNavigationRequest` values.

## Baseline verification

The focused pre-extraction command completed successfully with exit code 0:

```text
./gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.ui.screens.home.HomeViewModelTest" --tests "com.streamvault.app.ui.screens.epg.EpgViewModelTest" --tests "com.streamvault.app.ui.screens.epg.GuideDateTimeTest" --tests "com.streamvault.app.ui.screens.epg.ProgramReminderIssueMessageTest" :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none

BUILD SUCCESSFUL in 3m 38s
136 actionable tasks: 3 executed, 133 up-to-date
```

The source/test performance samples are captured separately in
`validation/phase5_live/performance-before.md`. Because this workstation has
other Java/Gradle processes active, samples must record cache/setup state and
must not be treated as a steady-state target closure without comparable
post-extraction runs.

## Existing open gates carried forward

- Playback long-duration two-channel stability, manual coverage, and performance gates remain open under `docs/COMPOSE_REDUCTION_PHASE5_PLAYBACK_REPORT.md`.
- Provider full completion journeys and paired incremental measurements remain open under `docs/COMPOSE_REDUCTION_PHASE5_PROVIDER_REPORT.md`.
- Settings DAO cleanup, steady-state performance comparison, fixture-dependent flows, and full-shell coverage remain open under `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_REPORT.md`.
- Live extraction must add its own preview handoff, EPG navigation, and two-channel long-duration validation. None of these gates is closed by the baseline command above.
