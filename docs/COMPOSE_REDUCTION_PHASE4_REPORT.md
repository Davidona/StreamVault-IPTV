# Compose Reduction Phase 4 Report

Date: 2026-08-24  
Phase: 4 — Navigation contracts  
Status: Architecture exit criteria met; one unrelated existing player smoke fixture remains red.

## Scope and outcome

Phase 4 established the navigation boundary needed for Phase 5 feature extraction without changing feature module ownership or player runtime behavior. The root navigation composable now wires the host and lifecycle effects, while typed contracts, startup/external coordination, route compatibility, and graph registration are separated behind explicit interfaces.

The implementation does not change player composition, playback lifecycle, stream recovery, or stream policy. It changes how destinations and navigation requests reach the existing screens.

## Module and package structure

- `:core:navigation` is a pure Kotlin module with no project dependencies. It owns `AppDestination`, player navigation requests, navigation actions, and navigation request contracts.
- `:core:ui` remains independent of `:app`, `:data`, and feature implementations. Its existing boundary and golden checks remain active.
- `app/navigation` owns Android intent parsing, route strings, AndroidX Navigation adapters, domain presentation-hint transport, and the application coordinator.
- `app/navigation/graph` contains controller-free `NavGraphBuilder` registration extensions for Welcome, Provider, Home, Live, Catalog, Player, and System areas.
- Build-time guards verify the core and feature navigation boundaries. `FeatureGraphBoundaryTest` covers the source shape in the app test suite.

## Contracts and compatibility

The typed contracts cover:

- top-level and detail destinations through `AppDestination`;
- player launches, payload identity, and typed return destinations through `PlayerNavigationRequest`;
- internal navigation actions and queued navigation commands;
- external URI, legacy route, and Android intent requests;
- navigation options such as single-top and pop-up behavior.

`AppRouteCodec` and the compatibility `Routes` facade preserve the existing route strings, argument names, URI validation, legacy route parsing, typed extras, playlist/backup/search/action-view handling, and presentation-hint transport. Existing app-owned domain adapters remain in `app/navigation` until feature extraction.

## Coordinator and graph responsibilities

`AppNavigationCoordinator` owns startup resolution, external request submission, command queue/state, acknowledgement, top-level navigation requests, and resumed-destination handoff. `MainActivity` parses platform intents and submits typed requests. `AppNavigation.kt` is now a narrow host/wiring boundary with lifecycle effects that execute and acknowledge commands exactly once.

`AppNavHost` supplies the `NavHost` and delegates destination registration to the seven graph extensions. Those extensions receive `NavigationActions` and feature-specific ports; they do not receive or reference the root `NavController`.

The Phase 5 move units are therefore clear: playback/player graph and screen implementation first, followed by provider, settings, live/EPG, and catalog. Each future feature can retain its registration extension while consuming only typed navigation contracts and an injected action port.

## Verification evidence

### Static, JVM, and build checks

The exact Phase 4 gate passed:

```text
.\gradlew.bat verifyCoreNavigationBoundary verifyFeatureNavigationBoundary verifyCoreUiBoundary :core:navigation:test :core:ui:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug --console=plain
```

Result: `BUILD SUCCESSFUL`.

- `:core:navigation:test`: 6 tests, 0 failures.
- `:core:ui:testDebugUnitTest`: 6 tests, 0 failures.
- `:app:testDebugUnitTest`: 391 tests, 0 failures.
- `:app:compileDebugKotlin` and `:app:assembleDebug`: passed.
- All three boundary verification tasks: passed.

The build reported 9 configuration-cache problems representing 4 unique warnings. They are from the existing `:core:navigation:verifyCoreNavigationBoundary` and `:core:ui:verifyCoreUiBoundary` tasks accessing `Task.project` during execution; the new `verifyFeatureNavigationBoundary` task was not implicated.

### Connected TV coverage

The suites ran on `Television_1080p(AVD) - 16` (API 36) using the current checkout's debug APK over the same application package:

- `:core:ui:connectedDebugAndroidTest`: 1 passed.
- `AppNavigationContractTest`: 2 passed.
- `ShellGoldenTest`: 3 passed, 0 skipped.
- `PremiumRouteGoldenTest`: 6 passed.
- `PlayerSmokeTest`: 1 passed, 3 failed.

The three player smoke failures are fixture/UI-test assertions outside the Phase 4 navigation contract. Phase 4 did not modify `PlayerSmokeTest` or the directly composed rail, controls-overlay, and track-dialog sources:

- `categoryRailPanel_searchField_acceptsInitialFocusAndInput`: no text-action node was focused;
- `playerControlsOverlay_playButton_canReceiveFocus`: the `>` node was not focused;
- `playerTrackSelectionDialog_selectsAudioTrack`: the click did not update the test callback's selected track id.

The passing player smoke case was `playerControlsOverlay_showsMuteActionWhenMuted`. The exact failure details are in the generated connected-test report under `app/build/reports/androidTests/connected/debug`.

### Source-shape and graph checks

- No `NavHostController` or `NavController` references were found under `app/src/main/java/com/streamvault/app/ui` or `app/src/main/java/com/streamvault/app/navigation/graph`.
- No app, data, domain, player, Compose, Hilt, AndroidX Navigation, `NavController`, or `NavHost` references were found in `core/navigation/src/main`.
- `AppNavHost.kt` contains the host plus graph registration calls and no feature screen implementations.
- `graphify update .` completed successfully: 13,854 nodes and 26,866 edges. HTML visualization was skipped because the graph exceeds its 5,000-node limit.

## Gates not exercised

The long-duration live-TV playback protocol and manual player behavior matrix were not rerun for this phase because Phase 4 did not move or alter player composition, playback lifecycle, stream recovery, or stream policy. The player smoke suite was run and its known fixture failures are recorded above rather than counted as passes.

## Phase 5 handoff

Phase 5 can extract feature modules behind the established typed contracts. The first extraction should preserve `registerPlayerGraph` and the player request/return contracts as the seam, then move the provider, settings, live/EPG, and catalog registration units without reintroducing root-controller access.

## Review hardening follow-up (2026-08-25)

The Phase 4 review found two navigation regressions that the original report did
not cover:

- startup landing routes were held behind the optional favorite/history player
  lookup; landing navigation is now published immediately and the player
  request is completed asynchronously;
- typed detail returns used a `popUpTo(Player)` fallback even when the deep-link
  stack contained no Player destination; the fallback now replaces the current
  detail entry and navigates to the typed target (or Home).

The review also found that the app and extracted playback golden helpers were
accepting a first-run screenshot as its own baseline. Both helpers now require
checked-in `androidTest/assets/ui-goldens/*.png` files unless the explicit
`recordGoldens=true` instrumentation argument is supplied. The app and playback
baseline PNGs are present in the current hardening working tree, and the
recording-disabled shell, premium-route, navigation-contract, missing-baseline,
and playback-overlay runs previously passed on the API 36
`Television_1080p` emulator. The hardening files still need their own commit;
once committed, this makes the golden and connected navigation gates
reproducible for future reviewers.

The follow-up also hardens startup player delivery against event ordering: a
Live TV destination resume is recorded even if startup navigation acknowledgement
arrives later, and acknowledgement retries the deferred player enqueue. Focused
coordinator coverage now includes resume-before-acknowledgement, deferred player
lookup completion, and catalog-layout route reconciliation.
