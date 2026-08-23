# Phase 3 Core UI Boundary Design

**Status:** Approved for specification review  
**Date:** 2026-08-23  
**Related plan:** [`docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`](../../COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md)

## Goal

Create a stable `:core:ui` Android library that owns reusable StreamVault presentation primitives while keeping application navigation, repositories, routes, domain models, and activity behavior in `:app`.

Phase 3 is a structural migration. It must preserve existing visuals, focus behavior, input behavior, navigation callbacks, resource text, and golden-test output.

## Current boundary problem

The repository currently has only `:app`, `:benchmark`, `:domain`, `:data`, and `:player`. Theme, design tokens, focus helpers, pointer/remote interaction, and TV control wrappers live under `app/src/main/java/com/streamvault/app/ui`. `AppShell.kt` combines reusable visual components with:

- `MainActivity` discovery and direct activity access;
- `PreferencesRepository` and provider repository collection;
- app route constants and route mapping;
- `AppTopLevelDestination` and `CatalogLayout` domain models;
- app string resources used for navigation labels and close actions.

This makes a visual change part of the `:app` presentation compilation unit and prevents later feature modules from consuming shared UI without also depending on app-owned behavior.

## Chosen approach

Use a two-sided boundary:

1. `:core:ui` owns generic rendering, design tokens, theme setup, focus/input primitives, and TV controls.
2. `:app` owns the adapter that resolves configured destinations, routes, labels, and repository-backed navigation state, then supplies a generic destination list to core rendering.

The existing screen call sites remain behaviorally compatible during this phase. They may continue calling the app-facing `AppScreenScaffold` adapter while generic components are imported from `:core:ui`. This keeps route/state migration out of Phase 3 and leaves typed navigation contracts for Phase 4.

## Module and package structure

Add:

```text
core/
  ui/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/res/font/inter_*.ttf
    src/main/java/com/streamvault/core/ui/
      components/shell/AppShellVisuals.kt
      device/TelevisionDevice.kt
      design/AppColors.kt
      design/AppMotion.kt
      design/AppShapes.kt
      design/AppSpacing.kt
      design/AppTypography.kt
      design/FocusHelpers.kt
      design/FocusSpec.kt
      interaction/MouseSupport.kt
      interaction/TvComponents.kt
      interaction/TvInteractionSounds.kt
      theme/Color.kt
      theme/Spacing.kt
      theme/Theme.kt
```

Register `:core:ui` in `settings.gradle.kts`. The module uses the existing version catalog and Compose/TV Material versions; it must not introduce a second UI toolkit or duplicate dependency versions.

The core module may depend on Android framework APIs, Compose runtime/foundation/ui, TV Material, Material icons, and the lifecycle Compose API required by the existing focus helpers. It must not depend on `:app`, `:data`, `:domain`, `:player`, navigation, Hilt, repositories, or application implementation classes.

The existing font resources used by `AppTypography` move with that implementation into `:core:ui`, so typography no longer references `com.streamvault.app.R`.

`MouseSupport` currently uses the generic `Context.isTelevisionDevice()` predicate from the app device package. Extract only that platform-neutral TV classification predicate into `com.streamvault.core.ui.device.TelevisionDevice.kt`; keep Fire TV classification, removable-storage helpers, and the app-facing `rememberIsTelevisionDevice()` wrapper in `:app`.

## Core UI API

The generic shell visual API is deliberately small:

```kotlin
enum class NavigationChrome {
    Rail,
    TopBar
}

data class UiDestination(
    val id: String,
    val label: String,
    val icon: ImageVector
)

@Composable
fun CoreAppScreenScaffold(
    currentDestinationId: String,
    destinations: List<UiDestination>,
    onDestinationSelected: (String) -> Unit,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    navigationChrome: NavigationChrome = NavigationChrome.Rail,
    topBarVisible: Boolean = true,
    compactHeader: Boolean = false,
    showScreenHeader: Boolean = true,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    topBarActions: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable ColumnScope.() -> Unit
)
```

`UiDestination` contains only rendering data and a stable ID. It must not contain a `NavController`, route parser, repository, activity, resource ID, or domain model. Core selection logic compares IDs and invokes `onDestinationSelected`; it does not know what an ID means.

`AppShellVisuals.kt` owns the visual implementation of the scaffold, rail, top bar, headers, generic status/message/load-more/metadata components, and close-action control. The close-action composable receives its content description as a `String` supplied by the caller rather than reading app resources.

The generic visual components moved from `AppShell.kt` retain their current public behavior and styling. No visual redesign, spacing change, focus-policy change, or callback-order change is part of this phase.

## App adapter

Keep app-specific navigation resolution under the existing app shell package in this focused adapter file:

```text
app/src/main/java/com/streamvault/app/ui/components/shell/AppShellNavigation.kt
```

The adapter owns:

- collection of configured top-level destinations;
- active-provider catalog-layout collection;
- mapping from `AppTopLevelDestination` to route, localized label, and icon;
- the VOD merge rule for non-split catalog layout;
- the plugin icon definition;
- the `Context`/`MainActivity` compatibility bridge required by the current implementation;
- the app-facing `AppScreenScaffold` wrapper that maps `AppNavigationChrome` to core `NavigationChrome` and passes `UiDestination` values to `CoreAppScreenScaffold`.

No core source may import this adapter or any of its app types. The adapter is intentionally transitional; Phase 4 will decide whether its state and route contracts move into navigation coordinators.

## Import and ownership migration

Update app presentation imports so generic UI consumers use `com.streamvault.core.ui` packages. Keep app-only navigation adapter imports under `com.streamvault.app.ui.components.shell`.

The following remain app-owned in Phase 3:

- screen composables and ViewModels;
- app-specific media cards and browse implementations;
- route constants and route parsing;
- resource-backed destination construction;
- repository and domain access;
- `MainActivity` and platform capabilities;
- feature-specific dialogs and content components.

The following move to core:

- theme and design tokens;
- generic focus and focus-restore helpers;
- generic pointer/remote activation helpers;
- TV interaction sound wrapper;
- `TvClickableSurface`, `TvButton`, and `TvIconButton`;
- generic shell scaffold, headers, status/message/load-more/metadata visuals.

If a component cannot cross the boundary without an app resource, domain model, route, or repository, it stays in `:app` and is not forced into core.

## Dependency guard

Add a build-time `verifyCoreUiBoundary` task in `core/ui/build.gradle.kts`. Wire `:core:ui:check` to depend on it, and add a root `verifyCoreUiBoundary` lifecycle task that depends on `:core:ui:verifyCoreUiBoundary`, so the guard is runnable both as `:core:ui:verifyCoreUiBoundary` and as `verifyCoreUiBoundary` from the repository root. It must:

1. fail if `:core:ui` declares a project dependency on `:app`, `:data`, `:domain`, `:player`, or a feature module;
2. scan core Kotlin source for imports or references to the exact banned prefixes/tokens `com.streamvault.app`, `com.streamvault.data`, `com.streamvault.domain`, `com.streamvault.player`, `androidx.navigation`, `dagger.hilt`, `MainActivity`, `NavController`, and `NavHost`;
3. be runnable directly from the repository root and wired into the core verification lifecycle;
4. report the offending file and matching text when it fails.

The check is a migration guard, not a general architectural linter. It must not ban Android framework, Compose, TV Material, or lifecycle Compose imports that the generic primitives require.

## Testing and validation

Before migration, retain the current app baseline and use the existing Phase 0 measurements as the comparison point.

Add focused unit coverage for the app adapter's pure `buildDestinationItems(configured, layout)` helper, including the existing VOD merge rule and split-catalog behavior. Preserve the existing app shell golden tests and update their imports or adapter setup so they continue to render the same rail, top bar, headers, and generic states. Those app integration goldens remain the critical golden coverage for this structural slice; moving them into `:core:ui` is explicitly out of scope because they use app resources and app-owned composition setup.

Run, at minimum:

```powershell
.\gradlew.bat verifyCoreUiBoundary
.\gradlew.bat :core:ui:compileDebugKotlin
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Run the relevant shell golden/instrumentation checks when an emulator is available. This phase does not change player composition or lifecycle code, so the full long-duration Live TV protocol is not newly required solely for the core UI move; any incidental player-facing import or behavior change must stop the migration and trigger the repository playback protocol.

Record module task impact and any unavailable emulator gates in the Phase 3 report or the main architecture plan. After production code changes, run `graphify update .` as required by the repository instructions.

## Explicit non-goals

- No `:core:navigation` module.
- No typed destination contract.
- No feature-module extraction.
- No `NavController` removal from `:app`.
- No conversion of Compose screens to Views.
- No modal/state ownership changes.
- No player recovery, transport, surface, or lifecycle changes.
- No broad app-wide visual cleanup beyond import/package changes required by the move.

## Rollback

The migration is rollback-safe at the commit level:

- move source/resources and update imports in separate mechanical commits where practical;
- keep the app adapter as the compatibility boundary until compilation and golden checks pass;
- do not delete the adapter or old call-site compatibility until the core module is proven;
- keep the dependency guard and module build independently runnable.
