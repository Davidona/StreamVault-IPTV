# Phase 4 Navigation Contracts Design

Date: 2026-08-23

## Status

Proposed design for Phase 4 of the Compose reduction and UI architecture
plan. This design prepares the application for Phase 5 feature-module
extraction without changing player composition, playback lifecycle, stream
recovery, or screen visuals.

## Goal

Separate navigation contracts from Android Navigation implementation and move
startup/external navigation orchestration out of the root composable, while
preserving current routes, deep links, player handoffs, return behavior, and
golden output.

## Current problem

The current navigation package combines several responsibilities:

- `AppNavigation.kt` owns the remembered root `NavHostController`, provider
  preference collection, landing resolution, startup player lookup, external
  request consumption, catalog-route correction, and graph composition.
- `AppNavigationGraph.kt` registers destinations but also performs direct
  navigation, reads/writes saved-state payloads, resolves player return routes,
  and owns feature-specific transition behavior.
- `AppNavigationContracts.kt` contains raw route strings, route builders,
  domain-object adapters, player request serialization, and Android Navigation
  imports in one file.
- `MainActivity` parses platform intents into app navigation requests, while
  the root composable decides when those requests may be executed.
- Feature screens receive callbacks today, but the graph implementation still
  owns the root controller and many feature-specific navigation decisions.

This prevents moving a feature with its graph registration and navigation
contract into a Phase 5 module without also moving root navigation knowledge.

## Chosen architecture

### `:core:navigation`: contract-only module

Add `:core:navigation` as a small Kotlin/JVM-compatible module with no
project dependencies and no references to `:app`, `:data`, `:domain`,
`:player`, Compose, AndroidX Navigation, Hilt, or Activities.

The module owns only stable, serializable navigation values:

- `AppDestination`, a sealed typed destination model for route identity and
  typed arguments.
- `PlayerNavigationRequest`, containing primitive player handoff data and a
  typed optional return destination.
- `ExternalNavigationRequest`, covering destination, search, player, playlist
  import, and backup import requests.
- `NavigationCommand` and `NavigationActions`, the feature-facing navigation
  contract. These expose navigation intent without exposing `NavController`.
- Shared navigation keys/contract constants that are required across the app
  and future feature modules.

The contract model uses primitives, strings, lists of primitives, and other
core navigation values. Domain objects such as `Channel`, `Movie`, `Episode`,
`MovieDetailPresentationHint`, and `SeriesDetailPresentationHint` do not cross
this module boundary. App or future feature modules adapt those objects into
navigation requests at their ownership boundary.

The contract module does not encode Android route strings. Route syntax,
argument names, URI encoding, and compatibility parsing remain app adapters
until a feature module owns its route codec.

### `:app`: platform and composition adapter

The app remains responsible for Android Navigation and app/domain integration:

- `AppRouteCodec` maps `AppDestination` to the existing route strings and
  decodes supported route arguments.
- Domain adapters map channels, movies, episodes, and playback history into
  `PlayerNavigationRequest` values.
- Legacy external-route parsing maps old route strings into typed
  `AppDestination` values before navigation execution.
- Android intent parsing remains at the Activity/platform boundary, including
  URI validation and the existing backup-file bridge.
- `NavControllerNavigator` is the only object allowed to translate typed
  navigation commands into `NavController` operations.
- App-specific presentation-hint storage remains in the app adapter until the
  movie and series features move in Phase 5.

The app may depend on `:core:navigation`; the core navigation module may not
depend on the app or any feature implementation.

## Navigation data flow

```text
MainActivity / TV launcher / app-owned caller
        |
        v
typed ExternalNavigationRequest or NavigationCommand
        |
        v
AppNavigationCoordinator
  - startup landing resolution
  - startup player lookup
  - external request de-duplication/acknowledgement
  - lifecycle-safe pending commands
        |
        v
AppNavHost's narrow command collector
        |
        v
NavControllerNavigator
        |
        v
typed AppDestination -> AppRouteCodec -> NavHost route
```

The root `AppNavigation` composable becomes wiring: it obtains the app
navigation dependencies, creates/receives the controller, and calls
`AppNavHost`. It does not resolve startup targets, parse external requests,
decide catalog corrections, or contain feature navigation branches.

The coordinator owns pending-command state and idempotence. A command is
acknowledged only after the navigator successfully executes it while the
current entry is resumed. Recomposition, Activity recreation, and repeated
`onNewIntent` delivery must not duplicate a startup player launch or external
request.

## Typed contracts

The exact names may be refined during implementation, but the public shape is
fixed by these rules:

```kotlin
sealed interface AppDestination : java.io.Serializable

data class PlayerNavigationRequest(
    val streamUrl: String,
    val title: String,
    val channelId: String? = null,
    val internalId: Long = -1L,
    val categoryId: Long? = null,
    val providerId: Long? = null,
    val isVirtual: Boolean = false,
    val combinedProfileId: Long? = null,
    val combinedSourceFilterProviderId: Long? = null,
    val contentType: String = "LIVE",
    val artworkUrl: String? = null,
    val archiveStartMs: Long? = null,
    val archiveEndMs: Long? = null,
    val archiveTitle: String? = null,
    val returnDestination: AppDestination? = null,
    val seriesId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeId: Long? = null
) : java.io.Serializable

sealed interface ExternalNavigationRequest : java.io.Serializable

sealed interface NavigationCommand : java.io.Serializable

interface NavigationActions {
    fun navigate(destination: AppDestination)
    fun openPlayer(request: PlayerNavigationRequest)
    fun back(): Boolean
    fun returnTo(destination: AppDestination?): Boolean
}
```

`AppDestination` includes typed equivalents for the current top-level,
detail, provider setup, player, search, guide, settings, plugin, downloads,
multi-view, and welcome destinations. Optional query values are represented as
nullable or typed fields rather than embedded route strings. The app route
codec preserves the existing sentinel/default behavior when converting these
values to Android Navigation arguments.

Invalid or unsafe player requests remain rejected at the app boundary before
they reach the player destination. The supported stream-scheme policy is
unchanged.

## Graph registration and feature isolation

Rename the central graph entry point to `AppNavHost` and keep it primarily a
registry. It owns the single `NavHost` call, start destination, shared app
graph dependencies, and registration order. It does not contain feature screen
branches or startup/external resolution.

Split registration into app-owned extensions that are shaped to move with
future feature modules:

- `registerWelcomeGraph`
- `registerProviderGraph`
- `registerHomeGraph`
- `registerLiveGraph`
- `registerCatalogGraph`
- `registerPlayerGraph`
- `registerSystemGraph`

Each extension receives typed `NavigationActions` and the minimum feature
dependencies it needs. No registration extension accepts the root
`NavHostController`. Destination lambdas may use their own
`NavBackStackEntry` for typed arguments and saved state, but navigation side
effects go through `NavigationActions` or a feature-owned app adapter.

This gives Phase 5 a direct extraction unit: a feature module can move its
screen code, graph registration extension, route argument codec, and feature
navigation tests together. The app root will retain only registry composition
and cross-feature navigation policies.

## Startup and external navigation

Extract the current root-composable logic into testable app services:

- `StartupNavigationResolver` resolves the configured landing destination and
  optional first-favorite/last-watched player request from explicit repository
  ports and input values. It preserves the current provider/combined-profile,
  hidden-channel, virtual-category, and return-to-live behavior.
- `ExternalNavigationRequestParser` converts Activity intents and legacy route
  extras into typed requests. It preserves search, player, provider setup,
  playlist import, backup import, and ACTION_VIEW fallback behavior.
- `AppNavigationCoordinator` accepts startup inputs and external requests,
  exposes pending typed commands, prevents duplicate handling, and reports
  acknowledgement only after successful navigation.

`MainActivity` remains responsible for Android-only concerns such as intent
extras, URI grants, backup inbox copying, and forwarding `onNewIntent`. It no
longer owns a navigation-specific mutable flow that the root composable must
interpret directly; it submits typed requests to the coordinator boundary.

## Back and return behavior

Player requests carry a typed optional return destination. The navigator keeps
the existing preference order:

1. Pop to an existing matching return destination when possible.
2. Navigate to the typed return destination if it is not already in the stack.
3. Fall back to Home when no return target exists or the stack cannot be
   restored.

Movie and series detail presentation hints remain in app-owned saved-state
adapters during Phase 4. Their behavior and keys remain unchanged. This avoids
putting domain-specific variant models into `:core:navigation` while preserving
the handoff needed by the current detail screens.

## Testing strategy

### Core navigation tests

- Every `AppDestination` encodes/identifies its typed arguments without raw
  route strings.
- `PlayerNavigationRequest` preserves all fields, including series identity
  and typed return destination.
- External request variants are serializable and distinguishable.
- Navigation action fakes record typed commands without a `NavController`.

### App navigation tests

- Route codec round trips all current destinations and sentinel values.
- Legacy external routes decode to the same typed destinations as before.
- Unsafe or missing player URLs are rejected.
- Startup landing resolution covers normal landing, first favorite, last
  watched, provider source, combined source, hidden channels, and no-target
  fallback.
- External intent parsing covers player, destination, search, playlist import,
  backup import, malformed legacy route, and ACTION_VIEW fallback.
- Return-route behavior covers existing-stack pop, missing-stack navigation,
  and Home fallback.

### Graph and connected coverage

- Registration extensions compile without accepting a root controller.
- A fake `NavigationActions` verifies screen callbacks emit typed commands.
- Existing shell, premium-route, player overlay, and player smoke coverage is
  rerun after the graph split.
- Connected tests cover startup player requests, external navigation, player
  return behavior, and deep-link entry on the TV emulator when available.

## Non-goals

- No feature module extraction occurs in Phase 4.
- No player composition, lifecycle, recovery, or stream policy changes occur.
- No visual redesign or route renaming occurs.
- No domain model migration into `:core:navigation` occurs.
- No claim is made about build-time improvement without before/after
  measurements.

## Phase 5 handoff

Phase 4 is exit-ready only when `AppNavHost` is a registry, feature graph
extensions do not receive the root controller, typed contracts are independent
of app/domain/data/player code, and tests cover deep links, startup player
requests, return routes, and external navigation. Phase 5 can then move a
feature by transferring its registration extension and screen-owned adapters
without expanding the root navigation file.
