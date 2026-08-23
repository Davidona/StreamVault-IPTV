# Phase 3 Core UI Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Create :core:ui, move reusable Compose/TV presentation primitives into it, and leave app navigation and repository-backed destination resolution behind an app-owned adapter without changing behavior.

**Architecture:** :core:ui will contain theme/design tokens, generic focus and pointer/remote primitives, TV controls, TV-device classification, and generic shell visuals. :app will retain route/domain/resource/repository knowledge in AppShellNavigation.kt, convert app destinations to UiDestination, and delegate rendering to CoreAppScreenScaffold.

**Tech Stack:** Android library module, Kotlin/JVM 17, existing Compose BOM, Compose Foundation/UI, TV Foundation/Material, Material icons, Lifecycle Runtime Compose, JUnit, existing Compose golden/instrumentation infrastructure, Gradle verification tasks, and graphify.

**Spec:** docs/superpowers/specs/2026-08-23-core-ui-boundary-design.md

## Global Constraints

- Preserve existing visuals, focus behavior, pointer/remote activation, navigation callbacks, resource text, and golden output.
- :core:ui must not depend on :app, :data, :domain, :player, navigation, Hilt, repositories, or feature implementations.
- Core APIs must use stable rendering data only; UiDestination must not contain a NavController, route parser, repository, activity, resource ID, or domain model.
- Keep app-specific Fire TV, removable-storage, route, repository, domain, and resource logic in :app.
- Do not introduce :core:navigation, typed destinations, feature modules, Views migrations, modal/state changes, or player lifecycle/recovery changes.
- Use the existing version catalog and Compose/TV versions; add only the missing direct compose-foundation alias.
- Every production-code migration ends with graphify update .
- If a player-facing behavior change is detected, stop and apply the repository's full Live TV validation protocol.

---

### Task 1: Add the :core:ui module and dependency guard

**Files:**

- Modify: settings.gradle.kts
- Modify: build.gradle.kts
- Modify: gradle/libs.versions.toml
- Modify: app/build.gradle.kts
- Create: core/ui/build.gradle.kts
- Create: core/ui/src/main/AndroidManifest.xml

**Interfaces:**

- Produces the com.streamvault.core.ui Android library module.
- Produces :core:ui:verifyCoreUiBoundary and root verifyCoreUiBoundary Gradle tasks.
- Later tasks consume the module's Compose, TV Material, lifecycle, and test dependencies.

- [ ] **Step 1: Register the module and direct Compose Foundation alias**

Add include(":core:ui") to settings.gradle.kts. Add this alias beside the existing Compose aliases:

~~~toml
compose-foundation = { group = "androidx.compose.foundation", name = "foundation" }
~~~

- [ ] **Step 2: Create the Android library build file**

Create core/ui/build.gradle.kts with the existing library/Kotlin/Compose plugins, namespace com.streamvault.core.ui, compileSdk = 36, minSdk = 25, Java/Kotlin 17, and no project dependencies. Use:

~~~kotlin
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.tv.foundation)
    implementation(libs.compose.tv.material)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
}
~~~

Do not apply Hilt, KSP, Kover, navigation, or app-only plugins.

- [ ] **Step 3: Add the app consumer dependency**

Add the core UI project dependency to the existing app dependencies, before the data/player dependencies:

~~~kotlin
implementation(project(":core:ui"))
~~~

This is the only new project dependency required by the Phase 3 presentation migration.

- [ ] **Step 4: Add the core boundary guard before moving source**

In core/ui/build.gradle.kts, add a task that scans src/main Kotlin files and fails with the file and matching token for any of these exact banned tokens:

~~~kotlin
val bannedCoreUiTokens = listOf(
    "com.streamvault.app",
    "com.streamvault.data",
    "com.streamvault.domain",
    "com.streamvault.player",
    "androidx.navigation",
    "dagger.hilt",
    "MainActivity",
    "NavController",
    "NavHost"
)
~~~

The task must also inspect resolved project dependencies and reject any project dependency whose path is not empty. Wire check to depend on it. Add this root task to build.gradle.kts:

~~~kotlin
tasks.register("verifyCoreUiBoundary") {
    group = "verification"
    dependsOn(":core:ui:verifyCoreUiBoundary")
}
~~~

Keep the scan limited to the exact tokens above so Android framework, Compose, TV Material, and Lifecycle Compose remain legal.

- [ ] **Step 5: Add the manifest and run the module guard**

Create the minimal library manifest:

~~~xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
~~~

Run:

~~~powershell
.\gradlew.bat verifyCoreUiBoundary
~~~

Expected: BUILD SUCCESSFUL; the task reports no project dependency and no banned source token.

### Task 2: Extract and test the generic television-device predicate

**Files:**

- Create: core/ui/src/main/java/com/streamvault/core/ui/device/TelevisionDevice.kt
- Create: core/ui/src/test/java/com/streamvault/core/ui/device/TelevisionDeviceTest.kt
- Modify: app/src/main/java/com/streamvault/app/device/DeviceSupport.kt

**Interfaces:**

- Produces com.streamvault.core.ui.device.Context.isTelevisionDevice() for generic core interaction code.
- Keeps the existing app package extension as a compatibility wrapper.
- Leaves isFireTvDevice, removableAppStorageDirs, and rememberIsTelevisionDevice app-owned.

- [ ] **Step 1: Write the failing pure predicate tests**

Create tests for the behavior currently implemented in DeviceSupport.kt:

~~~kotlin
class TelevisionDeviceTest {
    @Test
    fun leanbackFeatureIdentifiesTelevision() {
        assertThat(classifyTelevisionDevice(
            hasLeanback = true,
            hasLeanbackOnly = false,
            hasTelevision = false,
            hasFireTv = false,
            uiModeType = null,
            screenWidthDp = 0,
            hasTouchscreen = true
        )).isTrue()
    }

    @Test
    fun touchDeviceBelowTelevisionFallbackIsNotTelevision() {
        assertThat(classifyTelevisionDevice(
            hasLeanback = false,
            hasLeanbackOnly = false,
            hasTelevision = false,
            hasFireTv = false,
            uiModeType = null,
            screenWidthDp = 600,
            hasTouchscreen = true
        )).isFalse()
    }
}
~~~

- [ ] **Step 2: Run the focused test and verify the expected missing-symbol failure**

Run:

~~~powershell
.\gradlew.bat :core:ui:testDebugUnitTest --tests "com.streamvault.core.ui.device.TelevisionDeviceTest"
~~~

Expected: FAIL because classifyTelevisionDevice and the core source do not exist yet.

- [ ] **Step 3: Implement the minimal pure predicate and Context adapter**

Implement internal classifyTelevisionDevice(...) with the existing precedence: any Leanback/television/Fire TV feature is true; then television UI mode is true; otherwise return true only when there is no touchscreen and screenWidthDp >= 900. Add fun Context.isTelevisionDevice() that reads the same Android signals and delegates to the pure predicate.

Update app DeviceSupport.kt to preserve its existing API through an aliased core import:

~~~kotlin
import com.streamvault.core.ui.device.isTelevisionDevice as coreIsTelevisionDevice

fun Context.isTelevisionDevice(): Boolean = coreIsTelevisionDevice()
~~~

- [ ] **Step 4: Run the focused test and app compile check**

Run:

~~~powershell
.\gradlew.bat :core:ui:testDebugUnitTest --tests "com.streamvault.core.ui.device.TelevisionDeviceTest"
.\gradlew.bat :app:compileDebugKotlin
~~~

Expected: PASS and no app callers lose the existing com.streamvault.app.device.isTelevisionDevice symbol.

### Task 3: Move theme and design tokens with font resources

**Files:**

- Move: app/src/main/java/com/streamvault/app/ui/design/AppColors.kt to core/ui/src/main/java/com/streamvault/core/ui/design/AppColors.kt
- Move: app/src/main/java/com/streamvault/app/ui/design/AppMotion.kt to core/ui/src/main/java/com/streamvault/core/ui/design/AppMotion.kt
- Move: app/src/main/java/com/streamvault/app/ui/design/AppShapes.kt to core/ui/src/main/java/com/streamvault/core/ui/design/AppShapes.kt
- Move: app/src/main/java/com/streamvault/app/ui/design/AppSpacing.kt to core/ui/src/main/java/com/streamvault/core/ui/design/AppSpacing.kt
- Move: app/src/main/java/com/streamvault/app/ui/design/AppTypography.kt to core/ui/src/main/java/com/streamvault/core/ui/design/AppTypography.kt
- Move: app/src/main/java/com/streamvault/app/ui/design/FocusHelpers.kt to core/ui/src/main/java/com/streamvault/core/ui/design/FocusHelpers.kt
- Move: app/src/main/java/com/streamvault/app/ui/design/FocusSpec.kt to core/ui/src/main/java/com/streamvault/core/ui/design/FocusSpec.kt
- Move: app/src/main/java/com/streamvault/app/ui/theme/Color.kt to core/ui/src/main/java/com/streamvault/core/ui/theme/Color.kt
- Move: app/src/main/java/com/streamvault/app/ui/theme/Spacing.kt to core/ui/src/main/java/com/streamvault/core/ui/theme/Spacing.kt
- Move: app/src/main/java/com/streamvault/app/ui/theme/Theme.kt to core/ui/src/main/java/com/streamvault/core/ui/theme/Theme.kt
- Move: app/src/main/res/font/inter_bold.ttf to core/ui/src/main/res/font/inter_bold.ttf
- Move: app/src/main/res/font/inter_medium.ttf to core/ui/src/main/res/font/inter_medium.ttf
- Move: app/src/main/res/font/inter_regular.ttf to core/ui/src/main/res/font/inter_regular.ttf
- Move: app/src/main/res/font/inter_semibold.ttf to core/ui/src/main/res/font/inter_semibold.ttf
- Modify: all Kotlin consumers of com.streamvault.app.ui.design or com.streamvault.app.ui.theme

**Interfaces:**

- Produces the same public token/theme names under com.streamvault.core.ui.design and com.streamvault.core.ui.theme.
- Produces com.streamvault.core.ui.theme.StreamVaultTheme without an app resource dependency.

- [ ] **Step 1: Move source/resources and update packages**

Move the files with history preservation, change package declarations to com.streamvault.core.ui.*, and change AppTypography.kt from com.streamvault.app.R to the generated core R. Keep all values, typography construction, CompositionLocals, and names unchanged.

- [ ] **Step 2: Update imports mechanically**

Replace only these package references:

~~~text
com.streamvault.app.ui.design  -> com.streamvault.core.ui.design
com.streamvault.app.ui.theme   -> com.streamvault.core.ui.theme
~~~

Update production code, unit tests, and instrumentation tests. Do not change app screen behavior or styling.

- [ ] **Step 3: Compile core and app**

Run:

~~~powershell
.\gradlew.bat :core:ui:compileDebugKotlin
.\gradlew.bat :app:compileDebugKotlin
~~~

Expected: PASS with no com.streamvault.app.R reference remaining under core/ui/src/main.

### Task 4: Move generic interaction and focus primitives

**Files:**

- Move: app/src/main/java/com/streamvault/app/ui/interaction/MouseSupport.kt to core/ui/src/main/java/com/streamvault/core/ui/interaction/MouseSupport.kt
- Move: app/src/main/java/com/streamvault/app/ui/interaction/TvComponents.kt to core/ui/src/main/java/com/streamvault/core/ui/interaction/TvComponents.kt
- Move: app/src/main/java/com/streamvault/app/ui/interaction/TvInteractionSounds.kt to core/ui/src/main/java/com/streamvault/core/ui/interaction/TvInteractionSounds.kt
- Modify: all consumers of com.streamvault.app.ui.interaction

**Interfaces:**

- Produces mouseClickable, TvClickableSurface, TvButton, TvIconButton, TvInteractionSounds, and rememberTvInteractionSounds under com.streamvault.core.ui.interaction.
- Core interaction code imports only com.streamvault.core.ui.device.isTelevisionDevice and core design tokens.

- [ ] **Step 1: Add the focused activation behavior test**

Add an internal pure helper in TvComponents.kt and test it before moving the file. The test must be concrete:

~~~kotlin
@Test
fun enterKeyActivatesOnlyOnUpAndConsumesBothEvents() {
    assertThat(remoteActivationHandling(true, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, false))
        .isEqualTo(RemoteActivationHandling.Consume)
    assertThat(remoteActivationHandling(true, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, false))
        .isEqualTo(RemoteActivationHandling.Activate)
}

@Test
fun longClickLeavesActivationSequenceToTvMaterial() {
    assertThat(remoteActivationHandling(true, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, true))
        .isEqualTo(RemoteActivationHandling.Ignore)
}
~~~

The helper returns Ignore for disabled/non-activation keys and long-click paths, Consume for activation down/up events that should be intercepted, and Activate only for an activation key-up without a long-click handler.

- [ ] **Step 2: Run the narrow test before moving implementation**

Run the focused target before the package move. If it passes against the current implementation, record it as a characterization test and retain it as the regression guard.

- [ ] **Step 3: Move source and update imports**

Change packages to com.streamvault.core.ui.interaction, remove the app-device import from MouseSupport.kt, and import the core TV-device predicate. Make activateOnRemoteKey delegate to the tested remoteActivationHandling helper. Preserve pointer, touch, mouse, long-press, remote-key, and sound behavior exactly.

- [ ] **Step 4: Run interaction and compilation checks**

Run:

~~~powershell
.\gradlew.bat :core:ui:testDebugUnitTest
.\gradlew.bat :core:ui:compileDebugKotlin
.\gradlew.bat :app:compileDebugKotlin
~~~

### Task 5: Extract generic shell visuals and define the core scaffold API

**Files:**

- Create: core/ui/src/main/java/com/streamvault/core/ui/components/shell/AppShellVisuals.kt
- Create: core/ui/src/androidTest/java/com/streamvault/core/ui/components/shell/CoreAppScreenScaffoldTest.kt
- Modify: core/ui/build.gradle.kts for Compose UI test dependencies
- Modify: app/src/main/java/com/streamvault/app/ui/components/shell/AppShell.kt during the adapter split

**Interfaces:**

- Produces NavigationChrome, UiDestination, and CoreAppScreenScaffold.
- Produces generic AppScreenHeader, AppHeroHeader, AppSectionHeader, StatusPill, AppMessageState, LoadMoreCard, ContentMetadataStrip, and AppTopBarCloseAction.
- AppTopBarCloseAction accepts caller-provided contentDescription: String and does not access app resources.

- [ ] **Step 1: Write the failing core scaffold semantic test**

Add a Compose UI test that sets a core scaffold with currentDestinationId = "home" and asserts the root node exposes streamvault.destination:home. Also assert that selecting a destination invokes the callback with that destination ID. The test must use only UiDestination, a Material icon, and plain strings.

- [ ] **Step 2: Run the new test and verify the missing-symbol failure**

Run:

~~~powershell
.\gradlew.bat :core:ui:connectedDebugAndroidTest
~~~

Expected: FAIL because the core scaffold API does not exist. If no emulator is available, compile the test and record the unavailable connected-test gate; do not count it as runtime validation.

- [ ] **Step 3: Implement the core visual API without app knowledge**

Move the visual portions of AppShell.kt into AppShellVisuals.kt. Replace app route/resource/domain references with currentDestinationId, destinations: List<UiDestination>, onDestinationSelected, plain labels, caller-provided close-action descriptions, and NavigationChrome. Preserve gradients, spacing, shapes, focus requesters, focus scaling, interaction sounds, semantics, selected-state colors, callback order, and composition order.

- [ ] **Step 4: Add test dependencies and run the focused test**

Add these dependencies to :core:ui:

~~~kotlin
androidTestImplementation(platform(libs.compose.bom))
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
androidTestImplementation(libs.androidx.test.ext.junit)
androidTestImplementation(libs.androidx.test.runner)
debugImplementation("androidx.compose.ui:ui-test-manifest")
~~~

Run :core:ui:connectedDebugAndroidTest again. Expected: PASS when a compatible emulator is available.

### Task 6: Add and test the app-owned navigation adapter

**Files:**

- Delete: app/src/main/java/com/streamvault/app/ui/components/shell/AppShell.kt after generic visual code is moved
- Create: app/src/main/java/com/streamvault/app/ui/components/shell/AppShellNavigation.kt
- Create: app/src/test/java/com/streamvault/app/ui/components/shell/AppShellNavigationTest.kt
- Modify: app screen call sites that import AppNavigationChrome or shell helpers

**Interfaces:**

- Produces the app-facing AppScreenScaffold compatibility wrapper.
- Produces app-owned AppNavigationChrome mapping to core NavigationChrome.
- Produces an internal pure buildDestinationItems(configured, layout) helper retaining the VOD merge rule.
- Produces rememberAppDestinationItems() mapping app labels/routes/icons into List<UiDestination>.

- [ ] **Step 1: Write failing adapter tests**

Create these behaviors:

~~~kotlin
@Test
fun splitCatalogPreservesConfiguredMovieAndSeriesDestinations() {
    val result = buildDestinationItems(
        configured = listOf(AppTopLevelDestination.MOVIES, AppTopLevelDestination.SERIES),
        layout = CatalogLayout.SPLIT
    )

    assertThat(result.map { it.route })
        .containsExactly(Routes.MOVIES, Routes.SERIES)
        .inOrder()
}

@Test
fun unifiedCatalogReplacesMovieAndSeriesWithOneVodDestination() {
    val result = buildDestinationItems(
        configured = listOf(
            AppTopLevelDestination.HOME,
            AppTopLevelDestination.MOVIES,
            AppTopLevelDestination.SERIES
        ),
        layout = CatalogLayout.UNIFIED_VOD
    )

    assertThat(result.map { it.route })
        .containsExactly(Routes.HOME, Routes.VOD)
        .inOrder()
}
~~~

- [ ] **Step 2: Run focused tests and verify the missing-symbol failure**

Run:

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.ui.components.shell.AppShellNavigationTest"
~~~

Expected: FAIL because the adapter test/helper does not exist.

- [ ] **Step 3: Implement the adapter with existing behavior**

Move the current destination data class, buildDestinationItems, rememberDestinationItems, AppTopLevelDestination mapping, plugin icon, and MainActivity context bridge into AppShellNavigation.kt. Keep the pure helper internal and testable. In the composable adapter:

1. collect the same configured destinations and active-provider catalog layout;
2. convert each app destination to UiDestination(id, stringResource(labelRes), icon);
3. map AppNavigationChrome.Rail/TopBar to NavigationChrome.Rail/TopBar;
4. delegate rendering to CoreAppScreenScaffold;
5. pass title, subtitle, header, actions, padding, and content lambdas unchanged.

Do not move route constants, repository collection, or MainActivity access into core.

- [ ] **Step 4: Run adapter tests and compile**

Run:

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.ui.components.shell.AppShellNavigationTest"
.\gradlew.bat :app:compileDebugKotlin
~~~

Expected: PASS and existing screen call sites remain source-compatible.

### Task 7: Migrate consumers, preserve golden coverage, and remove old app UI copies

**Files:**

- Modify: all app/src/main/java Kotlin imports of com.streamvault.app.ui.design, .interaction, and .theme
- Modify: app/src/androidTest/java/com/streamvault/app/ui/components/shell/ShellGoldenTest.kt
- Modify: app/src/androidTest/java/com/streamvault/app/ui/PremiumRouteGoldenTest.kt
- Modify: all tests importing moved core UI packages
- Delete: obsolete files under app/src/main/java/com/streamvault/app/ui/design, ui/interaction, and ui/theme

**Interfaces:**

- Generic presentation consumers compile against com.streamvault.core.ui.
- App screens retain only app adapter imports for AppScreenScaffold and AppNavigationChrome.

- [ ] **Step 1: Update generic imports mechanically**

Replace only:

~~~text
com.streamvault.app.ui.design  -> com.streamvault.core.ui.design
com.streamvault.app.ui.theme   -> com.streamvault.core.ui.theme
com.streamvault.app.ui.interaction -> com.streamvault.core.ui.interaction
~~~

Keep app-only navigation adapter imports under com.streamvault.app.ui.components.shell. Do not rewrite route or screen logic.

- [ ] **Step 2: Update golden fixtures only for package/API names**

Keep the existing golden scenarios, dimensions, test data, labels, and visual parameters. Update theme and generic component imports to core, while using the app adapter for navigation-backed scaffold setup.

- [ ] **Step 3: Run compile and unit gates**

Run:

~~~powershell
.\gradlew.bat :core:ui:check
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
~~~

- [ ] **Step 4: Run shell golden/instrumentation validation**

Run the existing shell and premium-route golden tests using the repository's established connected-test command. Expected: unchanged golden output. If no emulator/device is available, record the exact unavailable command and leave the gate open.

- [ ] **Step 5: Confirm old package and forbidden dependency removal**

Run:

~~~powershell
rg -n "com\.streamvault\.app\.ui\.(design|interaction|theme)" app core --glob '*.kt'
rg -n "com\.streamvault\.app|com\.streamvault\.data|com\.streamvault\.domain|com\.streamvault\.player|androidx\.navigation|dagger\.hilt|MainActivity|NavController|NavHost" core/ui/src/main --glob '*.kt'
~~~

Expected: no old generic imports under app and no banned core tokens.

### Task 8: Full Phase 3 verification, graph refresh, and report

**Files:**

- Create: docs/COMPOSE_REDUCTION_PHASE3_REPORT.md
- Modify: docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md only when execution evidence requires a status/checklist update
- Update: graphify-out/ through graphify update .

**Interfaces:**

- Produces evidence for module independence, intentional recompilation boundaries, and golden coverage.

- [ ] **Step 1: Run complete build checks**

Run:

~~~powershell
.\gradlew.bat verifyCoreUiBoundary
.\gradlew.bat :core:ui:compileDebugKotlin
.\gradlew.bat :core:ui:testDebugUnitTest
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
~~~

Record pass/fail output and pre-existing baseline failures separately.

- [ ] **Step 2: Capture module/task impact**

Record relevant Gradle task output or build scans, including that a core UI source change compiles :core:ui and its app consumer while an app-only screen change does not add a dependency from core. Do not claim a performance improvement without before/after measurements.

- [ ] **Step 3: Refresh the repository graph**

Run from the repository root:

~~~powershell
graphify update .
~~~

Confirm completion and that the core module/source appears in updated graph output.

- [ ] **Step 4: Write the Phase 3 report**

Document the new module/package structure, dependency guard command/result, moved source/resources, adapter responsibilities, compile/unit/golden results, task impact, unavailable emulator gates, pre-existing failures, and explicit confirmation that no player behavior changed.

- [ ] **Step 5: Commit in reviewable slices**

Use separate commits where practical for module/guard setup, primitive migration, shell/adapter extraction, consumer imports, and verification/report updates. Run the narrow checks for each slice and Task 8 completely before final handoff.
