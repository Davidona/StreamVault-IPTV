# System Feature Module Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract Welcome, Downloads, and Plugins presentation into an independently compiled `:feature:system` module without changing routes, startup, download, plugin, focus, or UI behavior.

**Architecture:** `:feature:system` owns System route patterns and controller-free graph registration, the three screens and ViewModels, feature resources, neutral plugin API models, and feature tests. `:app` remains the root navigation/platform composition root and retains plugin discovery/IPC/provider/playback infrastructure, Android startup coordination, route compatibility, top-level shell composition, and platform adapters. The feature depends only on `:core:navigation`, `:core:ui`, and `:domain`; app-only sync progress, build configuration, and plugin operations cross narrow feature-owned ports.

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose/TV Material, Navigation Compose, Hilt/KSP, Coroutines/Flow, Kotlin serialization, Coil, JUnit4, Truth, Mockito-Kotlin, Robolectric, Compose UI testing, Macrobenchmark/Baseline Profile, Kover, Gradle boundary tasks, ADB, and Graphify.

**Spec:** `docs/superpowers/specs/2026-08-24-phase-5-feature-module-extraction-design.md`

## Global Constraints

- Preserve the intentional working tree; never reset, clean, delete, overwrite, or stage unrelated changes.
- This is a behavior-preserving Phase 5 extraction: preserve `welcome`, `downloads`, and `plugins` route strings; startup redirect and pop-up behavior; launcher timing; focus; semantics; test tags; lazy keys; UI text; callbacks; persistence; and error/result handling.
- Do not redesign the UI, convert a surface to Views, change download scheduling/storage policy, change plugin IPC/discovery/activation/provider/playback policy, or alter startup destination policy.
- `:feature:system` project dependencies must be exactly `:core:navigation`, `:core:ui`, and `:domain`.
- `:feature:system` must not import `com.streamvault.app`, `com.streamvault.data`, `com.streamvault.player`, `MainActivity`, `NavController`, `NavHostController`, or another `com.streamvault.feature.*` implementation package.
- `:app` retains `StreamVaultPluginManager`, `PluginMessengerClient`, `PluginWorkCoordinator`, `PluginPlaybackRouting`, plugin provider ownership/persistence, startup reconciliation, Cast/playback adaptation, `AppRouteCodec`, `AppNavHost`, Android services, and manifest entry points.
- Move the Android-independent plugin manifest/configuration/value types and `StreamVaultPluginContract` into the feature API; app plugin infrastructure consumes those types through the existing app-to-feature dependency direction.
- Welcome consumes sync progress and development seed configuration through `SystemWelcomePort`; the feature must not gain a direct `:data` or app `BuildConfig` dependency.
- Plugins consumes app plugin operations through `SystemPluginManagementPort`; the adapter delegates without adding retries, caching, validation, logging, or result rewriting.
- Downloads continues to consume the domain `DownloadManager`; Android document-picker, URI permission, external playback intent, and resource lookup behavior remains byte-for-byte equivalent where practical.
- Keep shared app resources in `:app` while any app, manifest, service, benchmark, or sibling feature still references them. Copy or move only the exact feature-owned values required to compile `com.streamvault.feature.system.R`, preserving every locale value and formatting argument.
- Use history-preserving moves where practical. Keep contract additions, app adapters, mechanical source moves, navigation cutover, and cleanup in separate commits.
- Add a focused failing test before each new contract or behavior seam, observe the expected failure, implement the minimum change, and rerun focused plus neighboring tests. Existing behavior may first be locked with passing characterization tests before a mechanical move.
- The module is justified only if post-extraction measurements show a System-only edit is at least 25% faster and its unit-test compile is at least 20% faster than the paired pre-extraction samples, sibling feature Kotlin compilation is absent, clean/warm builds are no more than 10% slower, and startup is no more than 5% slower. Record measurement noise and do not claim the gate when runs are not comparable.
- This slice does not satisfy or close existing Playback, Provider, Settings, Live, Catalog, physical-device, accessibility-service, or performance gates.
- The long-duration Live TV protocol is not required unless implementation changes player composition, playback preparation, recovery, lifecycle, surfaces, overlays, or plugin playback routing. If any such change becomes necessary, stop and split it into a separate bug-fix scope with the repository's two-channel protocol.
- Run `graphify update .` after code changes and before the final report.

## Planned File Structure

```text
feature/system/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/java/com/streamvault/feature/system/
    api/
      SystemFeatureContracts.kt          # shell callback type
      SystemPluginManagementPort.kt      # plugin DTOs and app-operation port
      SystemWelcomePort.kt               # sync progress and dev-seed inputs
    navigation/
      SystemGraph.kt                     # Welcome, Downloads, Plugins destinations
      SystemRoutePatterns.kt             # exact route constants
    presentation/
      welcome/WelcomeScreen.kt            # route wrapper, state rendering, ViewModel
      downloads/DownloadsUiState.kt
      downloads/DownloadsViewModel.kt
      downloads/DownloadsScreen.kt
      plugins/PluginsViewModel.kt
      plugins/PluginsScreen.kt
  src/main/res/values*/strings.xml        # exact System presentation resources
  src/test/...                            # boundary, route, model, and ViewModel tests
  src/androidTest/...                     # route behavior and reviewed goldens

app/src/main/java/com/streamvault/app/system/
  AppSystemBindings.kt                    # Hilt bindings for both feature ports
  AppSystemPluginManagementAdapter.kt     # delegates to app plugin runtime
  AppSystemWelcomeAdapter.kt              # maps BuildConfig and SyncProgressBus
```

The existing app plugin runtime remains under `app/plugins`. Only neutral types move to `feature/system/api`; no feature implementation depends on another feature.

---

### Task 0: Freeze System inventory, rollback point, and before measurements

**Files:**
- Create: `validation/phase5_system/task0-inventory.md`
- Create: `validation/phase5_system/source-inventory.txt`
- Create: `validation/phase5_system/project-imports.txt`
- Create: `validation/phase5_system/resource-inventory.txt`
- Create: `validation/phase5_system/test-inventory.txt`
- Create: `validation/phase5_system/consumer-inventory.txt`
- Create: `validation/phase5_system/performance-before.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`

**Interfaces:**
- Consumes: the current pre-extraction checkout, Phase 5 roadmap, accepted Phase 5 design, and existing feature reports.
- Produces: exact ownership/resource/test baselines, rollback SHA, five source-edit samples, five unit-test-compile samples, and an explicit statement that System adds no direct `:data` dependency.

- [x] **Step 1: Capture branch, rollback SHA, and working-tree ownership**

Run:

```powershell
New-Item -ItemType Directory -Force validation/phase5_system | Out-Null
git status --short --branch
git rev-parse HEAD
git branch --show-current
$systemFiles = @(
  'app/src/main/java/com/streamvault/app/ui/screens/welcome/WelcomeScreen.kt',
  'app/src/main/java/com/streamvault/app/ui/screens/downloads/DownloadsUiState.kt',
  'app/src/main/java/com/streamvault/app/ui/screens/downloads/DownloadsViewModel.kt',
  'app/src/main/java/com/streamvault/app/ui/screens/downloads/DownloadsScreen.kt',
  'app/src/main/java/com/streamvault/app/ui/screens/plugins/PluginsViewModel.kt',
  'app/src/main/java/com/streamvault/app/ui/screens/plugins/PluginsScreen.kt',
  'app/src/main/java/com/streamvault/app/navigation/graph/WelcomeGraph.kt',
  'app/src/main/java/com/streamvault/app/navigation/graph/SystemGraph.kt'
)
$systemFiles | Set-Content validation/phase5_system/source-inventory.txt
$systemFiles | ForEach-Object { "$(Get-Content -LiteralPath $_ | Measure-Object -Line | Select-Object -ExpandProperty Lines)`t$_" }
```

Expected: eight production files totaling 2,248 physical lines at planning time. Record fresh counts if the committed checkout differs, and explain every pre-existing dirty path without altering it.

- [x] **Step 2: Record imports, resources, tests, routes, and consumers**

Run:

```powershell
$systemRoots = @(
  'app/src/main/java/com/streamvault/app/ui/screens/welcome',
  'app/src/main/java/com/streamvault/app/ui/screens/downloads',
  'app/src/main/java/com/streamvault/app/ui/screens/plugins'
)
rg -n '^import com\.streamvault\.(app|feature|core|domain|data|player)' $systemRoots -g '*.kt' | Sort-Object | Set-Content validation/phase5_system/project-imports.txt
rg -o 'R\.(string|plurals|drawable|color|dimen|array)\.[A-Za-z0-9_]+' $systemRoots -g '*.kt' | ForEach-Object { ($_ -split ':')[-1] } | Sort-Object -Unique | Set-Content validation/phase5_system/resource-inventory.txt
rg --files app/src/test app/src/androidTest | rg -i '(welcome|downloads|plugins|plugin)' | Sort-Object | Set-Content validation/phase5_system/test-inventory.txt
rg -n 'WelcomeScreen|WelcomeViewModel|DownloadsScreen|DownloadsViewModel|PluginsScreen|PluginsViewModel|registerWelcomeGraph|registerSystemGraph|StreamVaultPluginContract|InstalledStreamVaultPlugin' app feature core domain data benchmark docs | Sort-Object | Set-Content validation/phase5_system/consumer-inventory.txt
```

Expected: the resource inventory contains 33 exact string references; Welcome directly imports app `BuildConfig`, app `R`, and `SyncProgressBus`; Downloads imports app `R` and app shell; Plugins imports app routes, app shell, and app plugin runtime models/manager. The only focused existing System-adjacent unit tests are app plugin runtime/model tests, so new presentation characterization tests are required.

- [x] **Step 3: Record the dependency decision in the transitional ledger**

Add a `:feature:system` section stating:

```text
Approved project dependencies: :core:navigation, :core:ui, :domain.
Direct :data imports: none.
App adapters: SystemWelcomePort maps SyncProgressBus and app BuildConfig;
SystemPluginManagementPort delegates to StreamVaultPluginManager and
ProviderSourceRegistry.
Phase 7 follow-up: keep these ports narrow; do not move plugin IPC, provider
ownership, playback routing, or Android startup infrastructure into the feature.
```

- [x] **Step 4: Capture a focused pre-extraction build baseline**

Run:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests 'com.streamvault.app.plugins.StreamVaultPluginOwnerTest' --tests 'com.streamvault.app.plugins.PluginPlaybackRoutingTest' :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: record the actual result, Gradle/JDK versions, daemon/cache state, duration, warnings, and any pre-existing failures in `task0-inventory.md`; a failing existing gate is recorded rather than relabeled as passing.

- [x] **Step 5: Capture five source-edit and five unit-test-compile samples**

Use a reversible whitespace-only edit in `WelcomeScreen.kt` for the source samples and `StreamVaultPluginOwnerTest.kt` for the test samples. Restore each file byte-identically after every run.

```powershell
./gradlew.bat :app:compileDebugKotlin --profile --no-daemon --console=plain --warning-mode=none
./gradlew.bat :app:compileDebugUnitTestKotlin --profile --no-daemon --console=plain --warning-mode=none
git diff -- app/src/main/java/com/streamvault/app/ui/screens/welcome/WelcomeScreen.kt app/src/test/java/com/streamvault/app/plugins/StreamVaultPluginOwnerTest.kt
git diff --check
```

Expected: ten comparable samples with raw duration, min/median/max, executed task set, and cache state are recorded in `performance-before.md`; both measurement files finish byte-identical to the rollback SHA and no measurement edit is committed.

- [x] **Step 6: Commit only baseline evidence**

```powershell
git add validation/phase5_system docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md
git commit -m 'docs(system): record phase 5 extraction baseline'
```

---

### Task 1: Add characterization coverage for existing System behavior

**Files:**
- Create: `app/src/test/java/com/streamvault/app/ui/screens/welcome/WelcomeViewModelTest.kt`
- Create: `app/src/test/java/com/streamvault/app/ui/screens/downloads/DownloadsViewModelTest.kt`
- Create: `app/src/test/java/com/streamvault/app/ui/screens/plugins/PluginsViewModelTest.kt`
- Create: `app/src/test/java/com/streamvault/app/ui/screens/plugins/PluginConfigurationDraftTest.kt`

**Interfaces:**
- Consumes: existing app-owned ViewModels and current plugin/download/domain contracts.
- Produces: behavior locks for provider detection/progress cutoff, download observation/actions/folder handling, plugin discovery/enable/configuration flows, and plugin draft validation/serialization.

- [x] **Step 1: Characterize Welcome provider and progress behavior**

Create a coroutine test using `StandardTestDispatcher`, a fake `ProviderRepository`, a mocked `ValidateAndAddProvider`, and a real `SyncProgressBus`. Assert:

```kotlin
@Test
fun providerObservationPublishesPresenceAndStopsWelcomeProgress() = runTest {
    providers.value = emptyList()
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertThat(viewModel.hasProviders.value).isFalse()

    providers.value = listOf(providerFixture(id = 7L))
    advanceUntilIdle()
    assertThat(viewModel.hasProviders.value).isTrue()
    assertThat(viewModel.syncProgress.value).isNull()
}
```

Also assert that an existing provider suppresses both Xtream and M3U development seed calls. The current checkout's debug BuildConfig includes an M3U validation seed, so the characterization must reflect the actual baseline rather than assume all development values are blank.

- [x] **Step 2: Characterize Downloads state and commands**

Use a fake `DownloadManager` backed by `MutableStateFlow`s and Robolectric `ApplicationProvider`. Assert observation clears loading, resume/delete use the selected ID, confirmation state is cleared before deletion, playback stops the manager before returning a resolvable view intent, and a missing `outputUri` returns `null` without stopping playback.

```kotlin
@Test
fun confirmDeleteClearsDialogAndDeletesSelectedItem() = runTest {
    val item = downloadFixture(id = "download-7")
    val viewModel = createViewModel()
    viewModel.showDeleteConfirm(item)
    viewModel.confirmDelete()
    advanceUntilIdle()
    assertThat(viewModel.uiState.value.deleteConfirmItem).isNull()
    assertThat(downloadManager.deletedIds).containsExactly("download-7")
}
```

- [x] **Step 3: Characterize Plugins discovery and configuration behavior**

Mock `StreamVaultPluginManager` and `ProviderSourceRegistry`. Assert refresh publishes plugins and sources, blank URL produces the exact current message, enable passes progress through unchanged and refreshes both lists, activity configuration calls `openPluginConfiguration`, host-schema configuration loads draft values, required blank fields block save, numeric/boolean values serialize with their current JSON primitive types, and refresh-after-action reloads configuration.

```kotlin
@Test
fun requiredBlankConfigurationFieldBlocksSave() = runTest {
    whenever(pluginManager.loadPluginConfiguration(plugin)).thenReturn(
        Result.success(requiredTextConfiguration(plugin))
    )
    val viewModel = createViewModel()
    viewModel.openPluginConfiguration(plugin)
    advanceUntilIdle()
    viewModel.updateConfigurationValue("token", "")
    viewModel.savePluginConfiguration()
    assertThat(viewModel.uiState.value.configuration?.validationErrors)
        .containsEntry("token", "Token is required")
    verify(pluginManager, never()).savePluginConfiguration(any(), any())
}
```

- [x] **Step 4: Run the characterization bundle**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests 'com.streamvault.app.ui.screens.welcome.WelcomeViewModelTest' --tests 'com.streamvault.app.ui.screens.downloads.DownloadsViewModelTest' --tests 'com.streamvault.app.ui.screens.plugins.PluginsViewModelTest' --tests 'com.streamvault.app.ui.screens.plugins.PluginConfigurationDraftTest' --no-daemon --console=plain --warning-mode=none
```

Expected: all characterization tests pass against the pre-extraction implementation. Record any test that exposes an existing defect and keep the defect outside this extraction unless separately approved.

- [x] **Step 5: Commit the behavior locks**

```powershell
git add app/src/test/java/com/streamvault/app/ui/screens/welcome app/src/test/java/com/streamvault/app/ui/screens/downloads app/src/test/java/com/streamvault/app/ui/screens/plugins
git commit -m 'test(system): lock existing presentation behavior'
```

---

### Task 2: Create `:feature:system` with a fail-closed boundary

**Files:**
- Modify: `build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `feature/system/build.gradle.kts`
- Create: `feature/system/src/main/AndroidManifest.xml`
- Create: `feature/system/src/test/java/com/streamvault/feature/system/SystemModuleBoundaryTest.kt`
- Create: `feature/system/src/test/resources/boundary-fixtures/AppPackageImport.kt`
- Create: `feature/system/src/test/resources/boundary-fixtures/FullyQualifiedAppReference.kt`
- Create: `feature/system/src/test/resources/boundary-fixtures/MainActivityReference.java`
- Create: `feature/system/src/test/resources/boundary-fixtures/RootNavigation.kt`
- Create: `feature/system/src/test/resources/boundary-fixtures/RootNavigation.java`
- Create: `feature/system/src/test/resources/boundary-fixtures/DataImport.kt`
- Create: `feature/system/src/test/resources/boundary-fixtures/PlayerImport.java`
- Create: `feature/system/src/test/resources/boundary-fixtures/PlaybackFeatureImport.kt`
- Create: `feature/system/src/test/resources/boundary-fixtures/ProviderFeatureImport.kt`
- Create: `feature/system/src/test/resources/boundary-fixtures/SettingsFeatureImport.java`
- Create: `feature/system/src/test/resources/boundary-fixtures/LiveFeatureImport.kt`
- Create: `feature/system/src/test/resources/boundary-fixtures/CatalogFeatureImport.java`

**Interfaces:**
- Consumes: the exact dependency policy in Global Constraints.
- Produces: registered `:feature:system`, `verifyFeatureSystemBoundary`, and fixture-tested rejection of app, data, player, controller, and sibling-feature implementation references.

- [x] **Step 1: Write the failing module-boundary test**

```kotlin
class SystemModuleBoundaryTest {
    @Test
    fun systemModuleDeclaresOnlyApprovedProjectDependencies() {
        val buildFile = File("build.gradle.kts")
        assertThat(buildFile.isFile).isTrue()
        val text = buildFile.readText()
        assertThat(text).contains("verifyFeatureSystemBoundary")
        assertThat(text).contains("implementation(project(\":core:navigation\"))")
        assertThat(text).contains("implementation(project(\":core:ui\"))")
        assertThat(text).contains("implementation(project(\":domain\"))")
        assertThat(text).doesNotContain("project(\":app\")")
        assertThat(text).doesNotContain("project(\":data\")")
        assertThat(text).doesNotContain("project(\":player\")")
        assertThat(text).doesNotContain("project(\":feature:")
    }
}
```

- [x] **Step 2: Run RED before module registration**

```powershell
./gradlew.bat :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.SystemModuleBoundaryTest' --no-daemon --console=plain --warning-mode=none
```

Expected: Gradle fails because project `:feature:system` is not registered.

- [x] **Step 3: Register and configure the module**

Add `include(":feature:system")` to `settings.gradle.kts`, `implementation(project(":feature:system"))` to `:app`, and `kover(project(":feature:system"))` to the root coverage aggregation without changing thresholds. Model the Android library configuration on `feature/catalog/build.gradle.kts` with namespace `com.streamvault.feature.system`, compileSdk 36, minSdk 25, Java/Kotlin 17, Compose, Kotlin serialization, Hilt/KSP, Kover, and Compose compiler reports.

Use exactly:

```kotlin
val allowedProjectDependencies = setOf(
    ":core:navigation",
    ":core:ui",
    ":domain",
)
```

Apply `libs.plugins.kotlin.serialization` because the moved plugin manifest/configuration types retain `@Serializable`. Declare only the external libraries required by the current sources: Compose BOM/UI/Material3/TV Foundation/TV Material, Material icons, Activity Compose, lifecycle runtime/viewmodel Compose, Navigation Compose, Hilt, Hilt Navigation Compose, coroutines, Kotlin serialization JSON, Coil Compose, Core KTX, JUnit, Truth, Mockito-Kotlin, Robolectric, AndroidX test runner/ext, Navigation testing, and Compose UI test JUnit4.

- [x] **Step 4: Add the boundary task and fixtures**

Use this token list in `feature/system/build.gradle.kts`:

```kotlin
val forbiddenFeatureSystemSourceTokens = listOf(
    "import com.streamvault.app",
    "com.streamvault.app",
    "import com.streamvault.data",
    "import com.streamvault.player",
    "import com.streamvault.feature.playback",
    "import com.streamvault.feature.provider",
    "import com.streamvault.feature.settings",
    "import com.streamvault.feature.live",
    "import com.streamvault.feature.catalog",
    "MainActivity",
    "NavHostController",
    "NavController",
)
```

Scan Kotlin and Java main sources and fixtures, write `build/reports/feature-system-boundary/report.txt`, require the project dependency set to equal `allowedProjectDependencies`, require zero main-source violations, and require every fixture category above. Make `check` and every module `Test` task depend on `verifyFeatureSystemBoundary`.

- [x] **Step 5: Run GREEN and inspect the report**

```powershell
./gradlew.bat :feature:system:verifyFeatureSystemBoundary :feature:system:testDebugUnitTest --no-daemon --console=plain --warning-mode=none
Get-Content feature/system/build/reports/feature-system-boundary/report.txt
```

Expected: exactly `:core:navigation,:core:ui,:domain`, zero main-source violations, and all app/data/player/controller/sibling fixtures listed.

- [x] **Step 6: Commit the module boundary**

```powershell
git add build.gradle.kts settings.gradle.kts app/build.gradle.kts feature/system
git commit -m 'build(system): add isolated feature module'
```

---

### Task 3: Define System route, shell, Welcome, and plugin contracts test-first

**Files:**
- Create: `feature/system/src/main/java/com/streamvault/feature/system/navigation/SystemRoutePatterns.kt`
- Create: `feature/system/src/main/java/com/streamvault/feature/system/api/SystemFeatureContracts.kt`
- Create: `feature/system/src/main/java/com/streamvault/feature/system/api/SystemWelcomePort.kt`
- Create: `feature/system/src/main/java/com/streamvault/feature/system/api/SystemPluginManagementPort.kt`
- Move: `app/src/main/java/com/streamvault/app/plugins/PluginModels.kt` to `feature/system/src/main/java/com/streamvault/feature/system/api/SystemPluginModels.kt`
- Move: `app/src/main/java/com/streamvault/app/plugins/StreamVaultPluginContract.kt` to `feature/system/src/main/java/com/streamvault/feature/system/api/StreamVaultPluginContract.kt`
- Move: `app/src/test/java/com/streamvault/app/plugins/StreamVaultPluginOwnerTest.kt` to `feature/system/src/test/java/com/streamvault/feature/system/api/StreamVaultPluginOwnerTest.kt`
- Modify: app plugin runtime sources/tests that consume the moved types
- Create: `feature/system/src/test/java/com/streamvault/feature/system/navigation/SystemRoutePatternsTest.kt`
- Create: `feature/system/src/test/java/com/streamvault/feature/system/api/SystemPluginModelsTest.kt`

**Interfaces:**
- Consumes: `AppDestination`, `NavigationActions`, domain `Result`, `ProviderSource`, `Section`, Flow, Compose `ColumnScope`, Android `Uri`, and JSON values.
- Produces: exact route constants, `SystemScaffoldContent`, `SystemWelcomePort`, neutral plugin types, and `SystemPluginManagementPort` used by all later tasks.

- [x] **Step 1: Write failing route and model tests**

```kotlin
@Test
fun systemRoutesPreserveCompatibilityStrings() {
    assertThat(SystemRoutePatterns.WELCOME).isEqualTo("welcome")
    assertThat(SystemRoutePatterns.DOWNLOADS).isEqualTo("downloads")
    assertThat(SystemRoutePatterns.PLUGINS).isEqualTo("plugins")
}

@Test
fun pluginOwnerKeyIsBundleSafeAndUnambiguous() {
    val left = StreamVaultPluginOwner("ab", "c", "d").toBundleSafeKey()
    val right = StreamVaultPluginOwner("a", "bc", "d").toBundleSafeKey()
    assertThat(left).isNotEqualTo(right)
    assertThat(left).isEqualTo("2:ab1:c1:d")
}
```

- [x] **Step 2: Run RED**

```powershell
./gradlew.bat :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.navigation.SystemRoutePatternsTest' --tests 'com.streamvault.feature.system.api.SystemPluginModelsTest' --no-daemon --console=plain --warning-mode=none
```

Expected: compilation fails because the route and API types do not exist.

- [x] **Step 3: Implement the exact route and shell contracts**

```kotlin
object SystemRoutePatterns {
    const val WELCOME = "welcome"
    const val DOWNLOADS = "downloads"
    const val PLUGINS = "plugins"
}

typealias SystemScaffoldContent = @Composable (
    currentDestination: AppDestination,
    title: String,
    subtitle: String?,
    compactHeader: Boolean,
    showScreenHeader: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) -> Unit
```

- [x] **Step 4: Implement the Welcome port**

```kotlin
data class WelcomeDevProviderConfig(
    val xtreamServer: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = "",
    val xtreamName: String = "",
    val m3uUrl: String = "",
    val m3uName: String = "",
)

data class WelcomeSyncProgress(
    val section: Section,
    val current: Int,
    val total: Int,
    val currentLabel: String,
    val itemsIndexed: Int,
)

interface SystemWelcomePort {
    val syncProgress: Flow<WelcomeSyncProgress?>
    val devProviderConfig: WelcomeDevProviderConfig
}
```

- [x] **Step 5: Move neutral plugin models and implement the operation port**

Use `git mv` for `PluginModels.kt`, `StreamVaultPluginContract.kt`, and `StreamVaultPluginOwnerTest.kt`; change their packages to `com.streamvault.feature.system.api`; and add explicit imports to `StreamVaultPluginManager`, `PluginMessengerClient`, `PluginPlaybackRouting`, and app plugin tests. Preserve every serialization name, default, enum value, owner-key encoding, capability check, and configuration constant. Then define:

```kotlin
interface SystemPluginManagementPort {
    suspend fun discoverPlugins(): List<InstalledStreamVaultPlugin>
    suspend fun providerSources(): List<ProviderSource>
    suspend fun installApkFromUri(uri: Uri): Result<Unit>
    suspend fun installApkFromUrl(url: String): Result<Unit>
    suspend fun setPluginEnabled(
        plugin: InstalledStreamVaultPlugin,
        enabled: Boolean,
        onProgress: (String) -> Unit,
    ): PluginActionResult
    fun openPluginConfiguration(plugin: InstalledStreamVaultPlugin): PluginActionResult
    suspend fun loadPluginConfiguration(plugin: InstalledStreamVaultPlugin): Result<PluginConfigurationSnapshot>
    suspend fun loadPluginConfigurationValues(plugin: InstalledStreamVaultPlugin): Result<JsonObject>
    suspend fun savePluginConfiguration(plugin: InstalledStreamVaultPlugin, valuesJson: String): PluginActionResult
    suspend fun runPluginConfigurationAction(plugin: InstalledStreamVaultPlugin, actionId: String): PluginActionResult
}
```

Keep `StreamVaultPluginContract` in the same feature API package so `StreamVaultPluginManifest` derived properties and app IPC code share one source of truth.

- [x] **Step 6: Run GREEN and boundary verification**

```powershell
./gradlew.bat :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.navigation.SystemRoutePatternsTest' --tests 'com.streamvault.feature.system.api.SystemPluginModelsTest' --tests 'com.streamvault.feature.system.api.StreamVaultPluginOwnerTest' :feature:system:verifyFeatureSystemBoundary :app:testDebugUnitTest --tests 'com.streamvault.app.plugins.PluginPlaybackRoutingTest' :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: feature route/model tests, the moved owner test, remaining app plugin runtime tests, and app compilation pass; the boundary report remains clean.

- [x] **Step 7: Commit the contracts**

```powershell
git add feature/system app/src/main/java/com/streamvault/app/plugins app/src/test/java/com/streamvault/app/plugins
git commit -m 'feat(system): define feature contracts and routes'
```

---

### Task 4: Implement app adapters and Hilt bindings

**Files:**
- Create: `app/src/main/java/com/streamvault/app/system/AppSystemWelcomeAdapter.kt`
- Create: `app/src/main/java/com/streamvault/app/system/AppSystemPluginManagementAdapter.kt`
- Create: `app/src/main/java/com/streamvault/app/system/AppSystemBindings.kt`
- Create: `app/src/test/java/com/streamvault/app/system/AppSystemWelcomeAdapterTest.kt`
- Create: `app/src/test/java/com/streamvault/app/system/AppSystemPluginManagementAdapterTest.kt`

**Interfaces:**
- Consumes: Task 3 ports/models, `SyncProgressBus`, app `BuildConfig`, `StreamVaultPluginManager`, and `ProviderSourceRegistry`.
- Produces: Hilt-provided `SystemWelcomePort` and `SystemPluginManagementPort` with delegation-equivalent behavior.

- [x] **Step 1: Write failing Welcome adapter tests**

Assert that all six BuildConfig-derived fields map without normalization and that aggregate progress maps only the representative progress fields:

```kotlin
@Test
fun progressMappingPreservesRepresentativeSnapshot() = runTest {
    val bus = SyncProgressBus()
    val adapter = AppSystemWelcomeAdapter(bus, devConfig)
    val observed = async { adapter.syncProgress.filterNotNull().first() }
    val session = bus.begin(providerId = 7L)
    bus.emit(session, SyncProgress(Section.VOD, 2, 5, "Movies", 44))
    assertThat(observed.await()).isEqualTo(
        WelcomeSyncProgress(Section.VOD, 2, 5, "Movies", 44)
    )
}
```

Expose an internal constructor taking `WelcomeDevProviderConfig` for tests; the injected constructor builds that value from the exact six app BuildConfig fields.

- [x] **Step 2: Write failing plugin adapter delegation tests**

Mock the manager and registry. Verify every port method forwards the same plugin, URI, URL, JSON string, action ID, enabled flag, and progress callback exactly once and returns the identical result object. Include discovery and provider source delegation.

```kotlin
@Test
fun enableDelegatesPluginFlagAndProgressCallback() = runTest {
    val callback: (String) -> Unit = mock()
    val expected = PluginActionResult(true, "Enabled")
    whenever(manager.setPluginEnabled(plugin, true, callback)).thenReturn(expected)
    assertThat(adapter.setPluginEnabled(plugin, true, callback)).isSameInstanceAs(expected)
    verify(manager).setPluginEnabled(plugin, true, callback)
}
```

- [x] **Step 3: Run RED**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests 'com.streamvault.app.system.AppSystemWelcomeAdapterTest' --tests 'com.streamvault.app.system.AppSystemPluginManagementAdapterTest' --no-daemon --console=plain --warning-mode=none
```

Expected: compilation fails because adapters and bindings do not exist.

- [x] **Step 4: Implement Welcome mapping and Hilt binding**

`AppSystemWelcomeAdapter.syncProgress` must be a pure `map` of `SyncProgressBus.aggregate`:

```kotlin
override val syncProgress: Flow<WelcomeSyncProgress?> = syncProgressBus.aggregate.map { aggregate ->
    aggregate?.representative?.progress?.let { progress ->
        WelcomeSyncProgress(
            section = progress.section,
            current = progress.current,
            total = progress.total,
            currentLabel = progress.currentLabel,
            itemsIndexed = progress.itemsIndexed,
        )
    }
}
```

Bind it as a singleton `SystemWelcomePort` in `AppSystemBindings`.

- [x] **Step 5: Implement plugin delegation and Hilt binding**

Implement `AppSystemPluginManagementAdapter` as one-line delegation per method. It must not catch exceptions, change dispatcher, re-run discovery, or alter messages. Bind it as singleton `SystemPluginManagementPort`.

- [x] **Step 6: Run GREEN and neighboring plugin tests**

```powershell
./gradlew.bat :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.api.StreamVaultPluginOwnerTest' :app:testDebugUnitTest --tests 'com.streamvault.app.system.AppSystemWelcomeAdapterTest' --tests 'com.streamvault.app.system.AppSystemPluginManagementAdapterTest' --tests 'com.streamvault.app.plugins.PluginPlaybackRoutingTest' --no-daemon --console=plain --warning-mode=none
```

Expected: adapters, the feature-owned owner test, and remaining app plugin runtime tests pass.

- [x] **Step 7: Commit adapters and bindings**

```powershell
git add app/src/main/java/com/streamvault/app/system app/src/test/java/com/streamvault/app/system
git commit -m 'refactor(system): bridge app startup and plugin services'
```

---

### Task 5: Move Welcome presentation and preserve startup behavior

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/screens/welcome/WelcomeScreen.kt` to `feature/system/src/main/java/com/streamvault/feature/system/presentation/welcome/WelcomeScreen.kt`
- Move: `app/src/test/java/com/streamvault/app/ui/screens/welcome/WelcomeViewModelTest.kt` to `feature/system/src/test/java/com/streamvault/feature/system/presentation/welcome/WelcomeViewModelTest.kt`
- Create: `feature/system/src/androidTest/java/com/streamvault/feature/system/presentation/welcome/WelcomePresentationTest.kt`
- Create/Modify: `feature/system/src/main/res/values*/strings.xml`

**Interfaces:**
- Consumes: `SystemWelcomePort`, `ProviderRepository`, `ValidateAndAddProvider`, feature resources, and navigation callbacks supplied later by `SystemGraph`.
- Produces: app-independent `WelcomeViewModel`, `WelcomeScreen`, and state-based `WelcomeContent` with unchanged seed/progress/redirect behavior.

- [x] **Step 1: Add failing port-driven seed tests**

Move the characterization test package first and replace app BuildConfig setup with a fake `SystemWelcomePort`. Add exact Xtream precedence and M3U fallback tests:

```kotlin
@Test
fun xtreamDevConfigTakesPrecedenceOverM3uConfig() = runTest {
    welcomePort.devProviderConfig = WelcomeDevProviderConfig(
        xtreamServer = "https://xtream.example",
        xtreamUsername = "user",
        xtreamPassword = "pass",
        xtreamName = "Seeded Xtream",
        m3uUrl = "https://m3u.example/list.m3u",
        m3uName = "Seeded M3U",
    )
    createViewModel()
    advanceUntilIdle()
    verify(validateAndAddProvider).loginXtream(
        XtreamProviderSetupCommand(
            serverUrl = "https://xtream.example",
            username = "user",
            password = "pass",
            name = "Seeded Xtream",
            xtreamFastSyncEnabled = true,
        )
    )
    verify(validateAndAddProvider, never()).addM3u(any())
}
```

- [x] **Step 2: Run RED**

```powershell
./gradlew.bat :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.presentation.welcome.WelcomeViewModelTest' --no-daemon --console=plain --warning-mode=none
```

Expected: compilation fails because Welcome production code is not feature-owned and does not consume `SystemWelcomePort`.

- [x] **Step 3: Move Welcome and replace app/data dependencies**

Use `git mv`, change package/R imports, inject `SystemWelcomePort`, replace `SyncProgressAggregate` with `WelcomeSyncProgress`, and read seed values from `welcomePort.devProviderConfig`. Preserve:

```kotlin
combine(welcomePort.syncProgress, acceptingProgress) { progress, accept ->
    if (accept) progress else null
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
```

Keep Xtream-first/M3U-second seed order, default names, provider observation, and the first non-null provider-state progress cutoff unchanged.

- [x] **Step 4: Extract a state rendering seam without moving effects**

Keep state collection and startup `LaunchedEffect` in `WelcomeScreen`. Extract only the existing `Box`/card branch into:

```kotlin
@Composable
internal fun WelcomeContent(
    hasProviders: Boolean?,
    syncProgress: WelcomeSyncProgress?,
    onNavigateToHome: () -> Unit,
    onNavigateToSetup: () -> Unit,
)
```

This seam is for connected rendering tests; no callback or effect moves into it.

- [x] **Step 5: Copy exact Welcome resources for every locale**

Copy the existing values for these keys into matching feature `values*` files:

```text
app_name
welcome_loading_title
welcome_loading_subtitle
sync_items_indexed_format
welcome_tagline
welcome_subtitle
welcome_setup_provider
welcome_setup_later
sync_section_live
sync_section_vod
sync_section_series
```

Retain app copies while app/manifest/sibling references remain. Do not move `welcome_bg.png`; it is not referenced by the current Welcome source, and dead-resource cleanup is outside this extraction.

- [x] **Step 6: Add connected rendering assertions**

Render `WelcomeContent` for `false`, `null`, and active progress. Assert setup/later controls, loading title/subtitle, determinate progress text, and both callbacks. Capture screenshots only after assertions pass.

- [x] **Step 7: Run GREEN**

```powershell
./gradlew.bat :feature:system:verifyFeatureSystemBoundary :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.presentation.welcome.WelcomeViewModelTest' :feature:system:compileDebugAndroidTestKotlin :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: Welcome tests and both module/app compilation pass with no forbidden import.

- [x] **Step 8: Commit Welcome ownership**

```powershell
git add app/src/main/java/com/streamvault/app/ui/screens/welcome app/src/test/java/com/streamvault/app/ui/screens/welcome feature/system
git commit -m 'refactor(system): move welcome presentation'
```

---

### Task 6: Move Downloads presentation and preserve platform actions

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/screens/downloads/DownloadsUiState.kt` to `feature/system/src/main/java/com/streamvault/feature/system/presentation/downloads/DownloadsUiState.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/downloads/DownloadsViewModel.kt` to `feature/system/src/main/java/com/streamvault/feature/system/presentation/downloads/DownloadsViewModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/downloads/DownloadsScreen.kt` to `feature/system/src/main/java/com/streamvault/feature/system/presentation/downloads/DownloadsScreen.kt`
- Move: `app/src/test/java/com/streamvault/app/ui/screens/downloads/DownloadsViewModelTest.kt` to `feature/system/src/test/java/com/streamvault/feature/system/presentation/downloads/DownloadsViewModelTest.kt`
- Create: `feature/system/src/androidTest/java/com/streamvault/feature/system/presentation/downloads/DownloadsPresentationTest.kt`
- Create/Modify: `feature/system/src/main/res/values*/strings.xml`

**Interfaces:**
- Consumes: domain `DownloadManager`, Android application context, `SystemScaffoldContent`, and feature resources.
- Produces: app-independent Downloads state, actions, launcher handling, rendering, and tests.

- [x] **Step 1: Add failing formatting and platform-action tests**

Expose the current file-size formatter as internal and add boundary cases:

```kotlin
@Test
fun fileSizeFormattingPreservesBinaryThresholds() {
    assertThat(formatDownloadFileSize(1023)).isEqualTo("1023 B")
    assertThat(formatDownloadFileSize(1024)).isEqualTo("1 KB")
    assertThat(formatDownloadFileSize(1024L * 1024)).isEqualTo("1 MB")
    assertThat(formatDownloadFileSize(1024L * 1024 * 1024)).isEqualTo("1 GB")
}
```

Retain the Task 1 tests for null/resolvable playback URI, playback-stop ordering, resume, delete, confirmation, folder intent action, persisted URI flags, display-name lookup, and messages.

- [x] **Step 2: Run RED**

```powershell
./gradlew.bat :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.presentation.downloads.DownloadsViewModelTest' --no-daemon --console=plain --warning-mode=none
```

Expected: compilation fails because Downloads sources are not feature-owned.

- [x] **Step 3: Move Downloads and replace app shell/resources**

Use `git mv`, change packages and `R` imports, and replace direct `AppScreenScaffold` use with injected `SystemScaffoldContent`. Preserve `ActivityResultContracts.OpenDocumentTree`, `takePersistableUriPermission` flags, `Intent.ACTION_VIEW`, `video/*`, `FLAG_GRANT_READ_URI_PERMISSION`, manager call order, snackbar effect, adaptive grid thresholds, status colors/labels, progress calculation, and delete dialog behavior.

The public entry point becomes:

```kotlin
@Composable
fun DownloadsScreen(
    scaffold: SystemScaffoldContent,
    viewModel: DownloadsViewModel = hiltViewModel(),
)
```

It invokes the scaffold with `AppDestination.Downloads`, title `R.string.nav_downloads`, TopBar behavior supplied by the app closure, `compactHeader = true`, and `showScreenHeader = false`.

- [x] **Step 4: Add a state rendering seam**

Keep activity result registration, context launch, snackbar effect, and ViewModel collection in `DownloadsScreen`. Extract the scaffold body and dialog into:

```kotlin
@Composable
internal fun DownloadsContent(
    uiState: DownloadsUiState,
    scaffold: SystemScaffoldContent,
    onChangeFolder: () -> Unit,
    onOpen: (DownloadItem) -> Unit,
    onResume: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
)
```

- [x] **Step 5: Copy exact Downloads resources for every locale**

Copy these exact keys and formatting arguments:

```text
nav_downloads
download_folder_default
download_folder_change
downloads_loading
downloads_empty_title
downloads_empty_hint
downloads_no_thumb
downloads_item_title
download_resume
download_delete
downloads_status_completed
downloads_status_downloading
downloads_status_failed
downloads_status_pending
downloads_status_paused
downloads_status_cancelled
downloads_delete_confirm_title
downloads_delete_confirm_msg
downloads_delete_confirm_delete
downloads_deleted
downloads_resumed
settings_cancel
```

Keep app copies needed by the foreground service, Catalog, Settings, manifests, notifications, or navigation shell.

- [x] **Step 6: Add connected rendering and action assertions**

Render loading, empty, downloading, failed, completed, and delete-confirmation states. Assert folder action, completed-card open, failed-card resume, delete confirmation/dismissal, progress percentage, stable card key, and snackbar clearing. Capture empty/completed screenshots for review.

- [x] **Step 7: Run GREEN**

```powershell
./gradlew.bat :feature:system:verifyFeatureSystemBoundary :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.presentation.downloads.DownloadsViewModelTest' :feature:system:compileDebugAndroidTestKotlin :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: Downloads unit/compile gates pass and no app/data/player/sibling feature reference appears in feature source.

- [x] **Step 8: Commit Downloads ownership**

```powershell
git add app/src/main/java/com/streamvault/app/ui/screens/downloads app/src/test/java/com/streamvault/app/ui/screens/downloads feature/system
git commit -m 'refactor(system): move downloads presentation'
```

---

### Task 7: Move Plugins presentation through the feature port

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/screens/plugins/PluginsViewModel.kt` to `feature/system/src/main/java/com/streamvault/feature/system/presentation/plugins/PluginsViewModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/plugins/PluginsScreen.kt` to `feature/system/src/main/java/com/streamvault/feature/system/presentation/plugins/PluginsScreen.kt`
- Move: `app/src/test/java/com/streamvault/app/ui/screens/plugins/PluginsViewModelTest.kt` to `feature/system/src/test/java/com/streamvault/feature/system/presentation/plugins/PluginsViewModelTest.kt`
- Move: `app/src/test/java/com/streamvault/app/ui/screens/plugins/PluginConfigurationDraftTest.kt` to `feature/system/src/test/java/com/streamvault/feature/system/presentation/plugins/PluginConfigurationDraftTest.kt`
- Create: `feature/system/src/androidTest/java/com/streamvault/feature/system/presentation/plugins/PluginsPresentationTest.kt`

**Interfaces:**
- Consumes: `SystemPluginManagementPort`, `SystemScaffoldContent`, feature-owned neutral plugin models, and core UI primitives.
- Produces: app-independent Plugins ViewModel/rendering with unchanged plugin operations and configuration behavior.

- [x] **Step 1: Change moved tests to the port and run RED**

Replace manager/registry mocks with one `SystemPluginManagementPort` mock while retaining every Task 1 assertion and the existing exception-to-message behavior.

```kotlin
@Test
fun enablePassesProgressAndPublishesUnchangedResultMessage() = runTest {
    whenever(port.setPluginEnabled(eq(plugin), eq(true), any())).thenAnswer { invocation ->
        invocation.getArgument<(String) -> Unit>(2)("Syncing plugin provider...")
        PluginActionResult(true, "Plugin enabled")
    }
    whenever(port.discoverPlugins()).thenReturn(listOf(plugin.copy(enabled = true)))
    whenever(port.providerSources()).thenReturn(listOf(providerSource))
    val viewModel = createViewModel()
    viewModel.setPluginEnabled(plugin, true)
    advanceUntilIdle()
    assertThat(viewModel.uiState.value.userMessage).isEqualTo("Plugin enabled")
    assertThat(viewModel.uiState.value.syncProgress).isNull()
}
```

Run:

```powershell
./gradlew.bat :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.presentation.plugins.PluginsViewModelTest' --tests 'com.streamvault.feature.system.presentation.plugins.PluginConfigurationDraftTest' --no-daemon --console=plain --warning-mode=none
```

Expected: compilation fails because Plugins sources are not feature-owned and still depend on app types.

- [x] **Step 2: Move Plugins and replace runtime imports**

Use `git mv`, change packages, inject only `SystemPluginManagementPort`, replace `pluginManager` and `providerSourceRegistry` calls with the corresponding port methods, and import plugin models from `feature.system.api`. Preserve existing `runCatching` sites, loading flags, exact user messages, refresh sequencing, configuration validation, JSON numeric/boolean conversion, dirty-state calculation, and action refresh behavior.

- [x] **Step 3: Replace app route/shell imports**

Remove `Routes`, `AppNavigationChrome`, and `AppScreenScaffold`. The public entry becomes:

```kotlin
@Composable
fun PluginsScreen(
    scaffold: SystemScaffoldContent,
    modifier: Modifier = Modifier,
    viewModel: PluginsViewModel = hiltViewModel(),
)
```

Invoke the scaffold with `AppDestination.Plugins`, the exact existing title/subtitle, `compactHeader = true`, and `showScreenHeader = true`. Preserve APK picker MIME order, URL dialog focus/keyboard behavior, switch semantics, owner bundle-safe lazy key, button enabled states, field rendering, and all current hard-coded plugin UI text.

- [x] **Step 4: Add a state rendering seam**

Keep picker registration, snackbar effect, dialog visibility state, and ViewModel collection in `PluginsScreen`. Extract the current scaffold content into:

```kotlin
@Composable
internal fun PluginsContent(
    uiState: PluginsUiState,
    scaffold: SystemScaffoldContent,
    showInstallUrlDialog: Boolean,
    onShowInstallUrlDialog: () -> Unit,
    onDismissInstallUrlDialog: () -> Unit,
    onInstallFromFile: () -> Unit,
    actions: PluginsActions,
)
```

Define `PluginsActions` as an internal immutable callback holder matching every existing ViewModel action; do not add policy to it.

- [x] **Step 5: Add connected Plugins coverage**

Render empty, discovered, busy enable/disable, install URL, partial discovery, and host-schema configuration states. Assert install/refresh/configure/enable callbacks, required-field indication, boolean/select/text/password rendering, save enablement, action progress, Back, and snackbar clearing. Capture empty and configuration screenshots for review.

- [x] **Step 6: Run GREEN and app plugin regression tests**

```powershell
./gradlew.bat :feature:system:verifyFeatureSystemBoundary :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.presentation.plugins.PluginsViewModelTest' --tests 'com.streamvault.feature.system.presentation.plugins.PluginConfigurationDraftTest' --tests 'com.streamvault.feature.system.api.StreamVaultPluginOwnerTest' :feature:system:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests 'com.streamvault.app.plugins.PluginPlaybackRoutingTest' :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: feature Plugins/model tests, remaining app plugin runtime tests, boundary verification, and app compilation pass.

- [x] **Step 7: Commit Plugins ownership**

```powershell
git add app/src/main/java/com/streamvault/app/ui/screens/plugins app/src/test/java/com/streamvault/app/ui/screens/plugins feature/system app/src/main/java/com/streamvault/app/plugins app/src/test/java/com/streamvault/app/plugins
git commit -m 'refactor(system): move plugins presentation'
```

---

### Task 8: Move graph registration and cut AppNavHost over to the feature

**Files:**
- Create: `feature/system/src/main/java/com/streamvault/feature/system/navigation/SystemGraph.kt`
- Create: `feature/system/src/test/java/com/streamvault/feature/system/navigation/SystemGraphTest.kt`
- Create: `feature/system/src/androidTest/java/com/streamvault/feature/system/navigation/SystemRouteGraphBehaviorTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppRouteCodec.kt`
- Modify: `app/src/test/java/com/streamvault/app/navigation/AppRouteCodecTest.kt`
- Modify: `app/src/test/java/com/streamvault/app/navigation/FeatureGraphBoundaryTest.kt`
- Modify: `app/build.gradle.kts`
- Delete after cutover: `app/src/main/java/com/streamvault/app/navigation/graph/WelcomeGraph.kt`
- Delete after cutover: `app/src/main/java/com/streamvault/app/navigation/graph/SystemGraph.kt`

**Interfaces:**
- Consumes: `SystemRoutePatterns`, `SystemScaffoldContent`, `NavigationActions`, Welcome startup callback, and the three feature screens.
- Produces: one controller-free feature graph registered by `AppNavHost`, with the app route codec remaining compatibility owner.

- [x] **Step 1: Write failing route-set and connected graph tests**

```kotlin
@Test
fun systemGraphRouteSetPreservesExistingDestinations() {
    assertThat(systemGraphRoutes()).containsExactly(
        "welcome",
        "downloads",
        "plugins",
    ).inOrder()
}
```

The connected test creates a `TestNavHostController`, registers `SystemGraph` with fake content/scaffold callbacks, navigates all three routes, and asserts each destination renders. For Welcome, assert Setup invokes:

```kotlin
actions.navigate(
    AppDestination.ProviderSetup(),
    NavigationOptions(popUpTo = AppDestination.Welcome, inclusive = true),
)
```

and Home invokes `onStartupNavigationRequested(AppDestination.Welcome)` only when the existing screen callback fires.

- [x] **Step 2: Run RED**

```powershell
./gradlew.bat :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.navigation.SystemGraphTest' :feature:system:compileDebugAndroidTestKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: compilation fails because feature `SystemGraph` does not exist.

- [x] **Step 3: Implement controller-free graph registration**

```kotlin
fun NavGraphBuilder.registerSystemGraph(
    actions: NavigationActions,
    startupReady: Boolean,
    onStartupNavigationRequested: (AppDestination) -> Unit,
    scaffold: SystemScaffoldContent,
) {
    composable(SystemRoutePatterns.WELCOME) {
        WelcomeScreen(
            onNavigateToHome = dropUnlessResumed {
                onStartupNavigationRequested(AppDestination.Welcome)
            },
            startupReady = startupReady,
            onNavigateToSetup = dropUnlessResumed {
                actions.navigate(
                    AppDestination.ProviderSetup(),
                    NavigationOptions(popUpTo = AppDestination.Welcome, inclusive = true),
                )
            },
        )
    }
    composable(SystemRoutePatterns.DOWNLOADS) { DownloadsScreen(scaffold = scaffold) }
    composable(SystemRoutePatterns.PLUGINS) { PluginsScreen(scaffold = scaffold) }
}
```

Keep the exact `dropUnlessResumed` placement from the existing Welcome graph.

- [x] **Step 4: Add the app scaffold adapter and route aliases**

In `AppNavHost`, create:

```kotlin
private fun appSystemScaffold(
    onTopLevelDestinationRequested: (AppDestination) -> Unit,
): SystemScaffoldContent = { destination, title, subtitle, compactHeader, showScreenHeader, content ->
    AppScreenScaffold(
        currentRoute = AppRouteCodec.encode(destination),
        onNavigate = { route -> AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested) },
        title = title,
        subtitle = subtitle,
        navigationChrome = AppNavigationChrome.TopBar,
        compactHeader = compactHeader,
        showScreenHeader = showScreenHeader,
        content = content,
    )
}
```

Use `SystemRoutePatterns.WELCOME` as `NavHost.startDestination` and call the feature `registerSystemGraph` once with the app scaffold closure. Keep `AppRouteCodec` as encoder/decoder and make its Welcome/Downloads/Plugins pattern constants alias the feature-owned constants.

- [x] **Step 5: Remove legacy app graph ownership and update boundary guards**

Delete both app graph files only after feature graph and app compilation succeed. Update `VerifyFeatureNavigationBoundaryTask` so app-local expected files contain only `LiveGraph.kt`; update `FeatureGraphBoundaryTest` to scan `feature/system/.../navigation`, require `SystemGraph.kt`, and no longer require `WelcomeGraph.kt` under app.

- [x] **Step 6: Run GREEN and navigation regressions**

```powershell
./gradlew.bat :feature:system:verifyFeatureSystemBoundary :feature:system:testDebugUnitTest --tests 'com.streamvault.feature.system.navigation.SystemGraphTest' :feature:system:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests 'com.streamvault.app.navigation.AppRouteCodecTest' --tests 'com.streamvault.app.navigation.FeatureGraphBoundaryTest' --tests 'com.streamvault.app.navigation.AppNavigationCoordinatorTest' :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: exact route compatibility, startup callback behavior, graph boundary, and app compilation pass with one owner for each System destination.

- [x] **Step 7: Commit navigation cutover**

```powershell
git add feature/system app/src/main/java/com/streamvault/app/navigation app/src/test/java/com/streamvault/app/navigation app/build.gradle.kts
git commit -m 'refactor(system): register feature-owned navigation'
```

---

### Task 9: Remove legacy ownership and run structural/resource gates

**Files:**
- Create: `validation/phase5_system/task9-structural-verification.md`
- Create: `validation/phase5_system/task9-resource-cleanup.md`
- Modify: app/feature resource files only where the ownership audit proves safe
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`

**Interfaces:**
- Consumes: Tasks 2-8 feature implementation and Task 0 inventories.
- Produces: one production owner per System presentation/route/type, exact dependency/resource evidence, and independent feature checks.

- [x] **Step 1: Prove old presentation and graph paths are absent**

```powershell
Test-Path app/src/main/java/com/streamvault/app/ui/screens/welcome
Test-Path app/src/main/java/com/streamvault/app/ui/screens/downloads
Test-Path app/src/main/java/com/streamvault/app/ui/screens/plugins
Test-Path app/src/main/java/com/streamvault/app/navigation/graph/WelcomeGraph.kt
Test-Path app/src/main/java/com/streamvault/app/navigation/graph/SystemGraph.kt
rg -n 'package com\.streamvault\.app\.ui\.screens\.(welcome|downloads|plugins)|fun NavGraphBuilder\.registerWelcomeGraph' app feature
```

Expected: all `Test-Path` results are false and no legacy package/registration definition remains.

- [x] **Step 2: Prove app plugin runtime remains app-owned**

```powershell
rg --files app/src/main/java/com/streamvault/app/plugins | Sort-Object
rg -n 'class StreamVaultPluginManager|class PluginMessengerClient|class PluginWorkCoordinator|fun playbackCandidates' app/src/main/java/com/streamvault/app/plugins
rg -n 'class StreamVaultPluginManager|PluginMessengerClient|PluginWorkCoordinator|fun playbackCandidates' feature/system/src/main
```

Expected: manager, messenger, work coordinator, and playback routing remain under app; none is defined by the feature. Neutral manifest/configuration models and `StreamVaultPluginContract` have a single feature API definition.

- [x] **Step 3: Run dependency and forbidden-reference gates**

```powershell
./gradlew.bat :feature:system:verifyFeatureSystemBoundary :feature:system:dependencies :feature:system:testDebugUnitTest :feature:system:lintDebug :feature:system:assembleDebug --no-daemon --console=plain --warning-mode=none
rg -n 'com\.streamvault\.(app|data|player|feature\.(playback|provider|settings|live|catalog))|MainActivity|NavHostController|NavController' feature/system/src/main
```

Expected: boundary report lists exactly three project dependencies, all feature gates pass, and the source scan returns no matches.

- [x] **Step 4: Audit locale/resource parity**

For each of the 33 Task 0 resource references, compare default and every existing localized value between app and feature. Record missing locale files, duplicate shared keys, formatting placeholders, and retained app consumers.

```powershell
rg -n '<string name="(app_name|welcome_|sync_items_indexed_format|sync_section_|nav_downloads|download_folder_|downloads_|download_resume|download_delete|settings_cancel)' app/src/main/res feature/system/src/main/res -g '*.xml'
./gradlew.bat :feature:system:lintDebug :app:lintDebug --no-daemon --console=plain --warning-mode=none
```

Expected: no feature `Resources.NotFoundException` risk, all formatting placeholders match, and keys are removed from app only when the consumer inventory proves no app/sibling/manifest/service use.

- [x] **Step 5: Run focused and neighboring JVM/build gates**

```powershell
./gradlew.bat :core:navigation:test :core:ui:testDebugUnitTest :domain:test :feature:system:testDebugUnitTest :app:testDebugUnitTest :feature:system:assembleDebug :app:assembleDebug --no-daemon --console=plain --warning-mode=none
```

Expected: report actual results with test/task names. Any existing failure must include reproduction and evidence that it predates or is unrelated to System extraction.

- [x] **Step 6: Update ledger and commit structural evidence**

Confirm the ledger states zero direct feature-to-data imports and names both app adapters. Then:

```powershell
git add validation/phase5_system docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md app/src/main/res feature/system/src/main/res
git commit -m 'test(system): verify ownership and resources'
```

---

### Task 10: Run connected System acceptance and review goldens

**Files:**
- Create: `feature/system/src/androidTest/java/com/streamvault/feature/system/SystemPresentationGoldenTest.kt`
- Create: `feature/system/src/androidTest/java/com/streamvault/feature/system/test/GoldenCapture.kt`
- Create: `feature/system/src/androidTest/assets/ui-goldens/welcome_no_provider.png`
- Create: `feature/system/src/androidTest/assets/ui-goldens/welcome_syncing.png`
- Create: `feature/system/src/androidTest/assets/ui-goldens/downloads_empty.png`
- Create: `feature/system/src/androidTest/assets/ui-goldens/downloads_completed.png`
- Create: `feature/system/src/androidTest/assets/ui-goldens/plugins_empty.png`
- Create: `feature/system/src/androidTest/assets/ui-goldens/plugins_configuration.png`
- Create: `validation/phase5_system/task10-connected-validation.md`
- Create: `validation/phase5_system/task10-device-journeys.md`
- Create: `validation/phase5_system/task10-screenshots/`

**Interfaces:**
- Consumes: the feature rendering seams, feature graph, app navigation integration, a booted authorized emulator/device, and deterministic fake states.
- Produces: reviewed visual parity, route/focus/action evidence, and explicit unavailable device/accessibility gates.

- [x] **Step 1: Add six golden cases and missing-golden guard**

Use the same pixel capture/diff convention as Catalog. Render at 1920x1080 with the app theme and deterministic fake data. The test must fail when a named baseline is absent and compare all six exact names above after review.

- [x] **Step 2: Run recording mode, inspect every image, then run comparison mode**

```powershell
./gradlew.bat :feature:system:connectedDebugAndroidTest -PsystemGoldens.record=true --no-daemon --console=plain --warning-mode=none
./gradlew.bat :feature:system:connectedDebugAndroidTest --no-daemon --console=plain --warning-mode=none
```

Expected: manually inspect text, spacing, focus border, progress, dialog sizing, download card/status, plugin controls, and configuration fields before accepting baselines. Comparison mode passes all six cases plus the missing-golden guard.

- [x] **Step 3: Run app startup and top-level route journeys**

On the API 36 Television 1080p emulator, validate:

```text
Welcome/no provider: Setup Provider, Set up later, remote focus, click, Back.
Welcome/existing provider: loading/progress, startupReady false/true, one redirect.
Configured landing: Downloads and Plugins open directly from startup preference.
Downloads: empty, active progress, failed resume, completed external playback,
folder picker cancel/select, delete cancel/confirm, snackbar, top navigation, Back.
Plugins: empty/discovered/partial states, refresh, URL dialog cancel/submit,
local picker cancel, enable/disable progress, activity configuration launch,
host-schema required/boolean/select/number/password/save/refresh/action/Back.
```

Expected: no duplicate navigation, crash, lost focus, stuck dialog, changed callback order, missing resource, wrong route, or changed plugin/download result text.

- [x] **Step 4: Exercise TV remote, touch, RTL, large text, reduced motion, and accessibility variants**

Run the deterministic presentation tests under RTL, font scale 1.3, animations disabled, and available TalkBack/accessibility service. Exercise touch/mouse activation on a phone/tablet profile when available. Record unavailable devices/services as unavailable, not passing.

- [x] **Step 5: Capture and sanitize logs**

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb logcat -c
& $adb logcat -d -v time > validation/phase5_system/task10-logcat.txt
rg -n 'FATAL EXCEPTION|AndroidRuntime|Process: com\.streamvault|IllegalStateException|ClassNotFoundException|NoSuchMethodError|Resources\$NotFoundException|ActivityNotFoundException|SecurityException' validation/phase5_system/task10-logcat.txt
```

Expected: no System-caused fatal/runtime/resource/launcher failure. Remove provider credentials, plugin URLs/tokens, file URIs, MAC addresses, and personal paths before committing evidence.

- [x] **Step 6: Record why Live long-duration validation is not triggered**

In `task10-connected-validation.md`, state that app plugin runtime, playback candidate selection, Cast URL rewrite, stream preparation, player composition, lifecycle, surfaces, and recovery were unchanged. If evidence contradicts that statement, do not waive live validation; split the playback-affecting change and run the full two-channel protocol.

- [x] **Step 7: Commit connected evidence and reviewed baselines**

```powershell
git add feature/system/src/androidTest validation/phase5_system/task10-connected-validation.md validation/phase5_system/task10-device-journeys.md validation/phase5_system/task10-screenshots
git commit -m 'test(system): record connected acceptance'
```

---

### Task 11: Prove build value, refresh profiles/graph, and write the System report

**Files:**
- Create: `validation/phase5_system/performance-after.md`
- Create: `validation/phase5_system/profile-validation.md`
- Create: `docs/COMPOSE_REDUCTION_PHASE5_SYSTEM_REPORT.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_ROADMAP.md`
- Modify: `docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`
- Modify: generated baseline/startup profile outputs only through the existing generator workflow
- Modify: `graphify-out/` through `graphify update .`

**Interfaces:**
- Consumes: Task 0 paired baseline and Tasks 1-10 implementation/evidence.
- Produces: the required optional-module value decision, final verification matrix, profile/package evidence, refreshed graph, System report, and evidence-backed Phase 5 status.

- [x] **Step 1: Capture five after source-edit and unit-test-compile samples**

Repeat Task 0's protocol with reversible whitespace-only edits in feature-owned `WelcomeScreen.kt` and `WelcomeViewModelTest.kt`:

```powershell
./gradlew.bat :feature:system:compileDebugKotlin --profile --no-daemon --console=plain --warning-mode=none
./gradlew.bat :feature:system:compileDebugUnitTestKotlin --profile --no-daemon --console=plain --warning-mode=none
git diff -- feature/system/src/main/java/com/streamvault/feature/system/presentation/welcome/WelcomeScreen.kt feature/system/src/test/java/com/streamvault/feature/system/presentation/welcome/WelcomeViewModelTest.kt
```

Expected: five comparable samples for each scenario; record raw values, min/median/max, task sets, cache state, and measurement noise. Both files finish byte-identical to their committed state.

- [x] **Step 2: Prove sibling-feature Kotlin isolation**

With one reversible System-only source edit, capture dry-run and actual task output:

```powershell
./gradlew.bat :app:assembleDebug --dry-run --console=plain > validation/phase5_system/system-edit-dry-run.txt
./gradlew.bat :app:assembleDebug --console=plain --warning-mode=none > validation/phase5_system/system-edit-task-output.txt
rg -n ':feature:(playback|provider|settings|live|catalog):compile.*Kotlin' validation/phase5_system/system-edit-task-output.txt
```

Expected: System and required app integration/package tasks run; no sibling feature Kotlin compile task executes.

- [x] **Step 3: Run clean/warm/startup guardrails and make the value decision**

```powershell
./gradlew.bat clean :app:assembleDebug --no-daemon --console=plain --warning-mode=none
./gradlew.bat :app:assembleDebug --no-daemon --console=plain --warning-mode=none
./gradlew.bat :app:assembleBeta :app:assembleRelease --no-daemon --console=plain --warning-mode=none
```

Measure cold startup to Welcome and warm startup to configured Downloads and Plugins using the existing Macrobenchmark/startup workflow. Compare paired medians against:

```text
System source edit: at least 25% faster.
System unit-test compile: at least 20% faster.
Sibling feature Kotlin compilation: absent.
Clean and warm builds: no more than 10% slower.
Startup: no more than 5% slower.
```

If comparable evidence misses a threshold beyond measured noise, mark `:feature:system` unjustified, stop before reporting it accepted, and use the Task 0 rollback SHA plus the commit sequence to prepare a normal revert review. Do not use `git reset --hard` or delete uncommitted user work.

- [x] **Step 4: Regenerate and inspect baseline/startup profiles**

```powershell
./gradlew.bat :benchmark:pixel2Api36Setup:generateBaselineProfile :app:assembleRelease --no-daemon --console=plain --warning-mode=none
rg -n 'com/streamvault/app/ui/screens/(welcome|downloads|plugins)' app/src benchmark build
rg -n 'com/streamvault/feature/system' app/src benchmark build
```

Expected: generator and release build results are recorded; fresh profile sources contain no stale app System descriptors and contain nonzero feature System descriptors when those flows are covered. Record skipped benchmark methods and unavailable physical-device approval separately.

Recorded (2026-09-05): the supported `:app:copyBaselineProfileIntoSrc` workflow
promoted fresh sources with 49,059 baseline rules and 30,237 startup rules;
both source scans contain feature System descriptors and no stale app System
descriptors. The explicit Macrobenchmark rerun completed 8/8 tests with 0
failures and 0 skipped on the API 36 TV emulator. Physical-device, accessibility
service, and authorized production-provider/plugin approval remain open.

- [x] **Step 5: Run the final automated matrix**

```powershell
./gradlew.bat :core:navigation:test :core:ui:testDebugUnitTest :domain:test :feature:system:verifyFeatureSystemBoundary :feature:system:testDebugUnitTest :feature:system:lintDebug :feature:system:compileDebugAndroidTestKotlin :feature:system:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleBeta :app:assembleRelease --no-daemon --console=plain --warning-mode=none
```

Expected: report actual task/test counts and failures. Existing failures must name the task, test, message, reproduction, and evidence of prior existence; unavailable gates remain open.

- [x] **Step 6: Refresh Graphify and inspect ownership**

```powershell
graphify update .
graphify explain 'SystemGraph'
graphify path 'AppNavHost' 'PluginsScreen'
git status --short
```

Expected: graph output resolves feature-owned System graph/screens and app-owned adapters/plugin manager, with no feature-to-app edge from System implementation.

- [x] **Step 7: Write and self-audit the System report**

`COMPOSE_REDUCTION_PHASE5_SYSTEM_REPORT.md` must include:

```text
rollback SHA and commit sequence
ownership before/after and exact source/test/resource counts
three-dependency boundary report and zero forbidden references
Welcome and plugin port signatures plus app adapter ownership
proof plugin IPC/provider/playback runtime remained in app
route/startup/landing compatibility evidence
locale/resource parity and retained duplicate-resource reasons
unit/lint/assemble/connected/golden results
TV/touch/RTL/large-text/reduced-motion/accessibility evidence
five paired source and test compile samples
sibling feature task-isolation evidence
clean/warm/startup guardrails and optional-module value decision
profile regeneration and artifact results
known failures, unavailable gates, and follow-up ownership
```

Update the roadmap from `System remains unstarted` only with actual evidence. Update the governing plan's Phase 5 status without closing unrelated open gates or claiming the full modernization complete.

- [x] **Step 8: Run documentation consistency checks**

```powershell
rg -n 'System remains unstarted|:feature:system|WelcomeGraph.kt|SystemGraph.kt|ui/screens/(welcome|downloads|plugins)' docs app feature graphify-out
git diff --check
```

Expected: historical plans/reports remain untouched; current roadmap/report/architecture status agrees with source ownership and recorded evidence; no whitespace error is reported.

- [x] **Step 9: Commit final evidence and documentation**

```powershell
git add docs/COMPOSE_REDUCTION_PHASE5_SYSTEM_REPORT.md docs/COMPOSE_REDUCTION_PHASE5_ROADMAP.md docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md validation/phase5_system graphify-out app/src/main/generated/baselineProfiles
git commit -m 'docs(system): report phase 5 feature extraction'
```

Stage only profile files produced by the verified generator. If generated paths differ, record and stage those exact paths instead.

## Exit Criteria

- `:feature:system` owns Welcome, Downloads, Plugins, their ViewModels/state/rendering, controller-free route registration, neutral plugin API models/contracts, feature resources, unit tests, connected tests, and reviewed goldens.
- `:app` owns the root navigation host/codec, shell composition, app adapters, BuildConfig, sync bus implementation, plugin manager/IPC/work/provider/playback infrastructure, Android services, and manifest entry points.
- The System boundary reports exactly `:core:navigation`, `:core:ui`, and `:domain`, with zero forbidden main-source references and fixture coverage for app/data/player/controllers/all sibling features.
- `welcome`, `downloads`, and `plugins` routes, startup redirect/pop behavior, configured landing destinations, focus, semantics, launcher behavior, UI text, lazy identity, download actions, plugin operations, configuration JSON, callback order, and error/result messages are equivalent.
- Feature tests run independently; System and app build gates have recorded outcomes; a System-only edit does not compile sibling feature Kotlin source.
- Paired measurements meet the optional-module value thresholds or the extraction is explicitly rejected and prepared for normal review/revert without destructive workspace operations.
- Resource ownership, locale parity, build/runtime/profile evidence, known failures, unavailable devices, and all remaining Phase 5 gates are documented accurately.
- Graphify output reflects feature-owned System presentation and app-owned platform/plugin runtime boundaries.
