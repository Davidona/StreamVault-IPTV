# Catalog Feature Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract Dashboard, Movies, Series, VOD, Favorites, Search, and Catalog detail presentation into an independently compiled `:feature:catalog` module without changing behavior.

**Architecture:** `:feature:catalog` owns Catalog routes, screens, ViewModels, presentation components, resources, and tests. `:app` remains the composition root and adapts app-update, plugin preparation, download-service, Cast, navigation payload, player-request, Settings-dialog, and shell implementations through Catalog-owned ports and callbacks. The feature depends only on `:core:navigation`, `:core:ui`, `:domain`, and the temporarily allowed `:data` module.

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose/TV Material, Navigation Compose, Hilt/KSP, Coroutines/Flow, JUnit, Truth, Mockito, Robolectric, Compose UI testing, Macrobenchmark, Kover, and Graphify.

**Spec:** `docs/superpowers/specs/2026-09-02-phase-5-catalog-feature-extraction-design.md`

## Global Constraints

- Preserve all existing route strings, arguments, return destinations, saved-state presentation hints, focus, test tags, semantics, lazy keys/content types, UI output, and callback order.
- Do not redesign the UI, convert screens to Views, add a Favorites route, adopt Paging, or change filtering, grouping, ranking, hydration, playback, Cast, download, update, plugin, or provider-sync policy.
- `:feature:catalog` project dependencies must be exactly `:core:navigation`, `:core:ui`, `:domain`, and `:data`.
- `:feature:catalog` must not import `com.streamvault.app`, `MainActivity`, `NavController`, `NavHostController`, `:player`, or any `com.streamvault.feature.*` implementation package.
- `:app` owns player-request construction, route compatibility, payload transport, Settings-dialog composition, app-update/install policy, platform services, and the root navigation host.
- `PreferencesRepository` and `ProviderSyncStateSource` are temporary Catalog-to-data imports; record every source site in `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`.
- Keep app resources until no remaining app, test, benchmark, manifest, or sibling-feature reference exists; preserve every locale value and formatting argument.
- Add a focused failing test before each contract or behavior change, run it red, implement the minimum change, then run focused and neighboring tests.
- Do not claim Catalog or Phase 5 completion when any required build, connected, device, performance, profile, or resource gate is unavailable or failing.
- Do not close Playback, Provider, Settings, or Live gates from this slice.
- Run `graphify update .` after code changes.

## Execution status (2026-09-03)

Tasks 0–12 are implemented and their evidence is recorded under
`validation/phase5_catalog/`. Task 13 structural/build-isolation and profile
work is also recorded there. The available seeded Home/Live/Movies/Series/
Search smoke pass is documented. A temporary local Xtream fixture follow-up
also exercised VOD/series details, episodes, saved filters, and cross-type
Search in the production activity; its semantic evidence is in
`validation/phase5_catalog/task14-xtream-fixture-journeys.md`. The checked-in
`tools/catalog_xtream_fixture.py` now provides a rerunnable development seed,
and `tools/catalog_connected_validation.py` provides a semantic ADB journey
for the fixture-backed Home/Movies/Series/detail/favorite/saved/Search path,
Movies full-library entry/back, the `Load more (60/63)` → `Pagination Movie 63`
second-page transition with Infinite scroll restored on, and the Settings-owned
Dashboard shelf customization cancel/save/reset flow. The remaining Series
pagination, browse reorder, action/direct-Favorites/accessibility journeys;
repository-wide app lint remains open.
A focused Dashboard macrobenchmark rerun now passes after public-M3U
live-channel activity populates a scrollable seeded Home shelf, but it is
diagnostic after-run evidence rather than the required cache-equivalent paired
comparison. These limitations are documented in
`docs/COMPOSE_REDUCTION_PHASE5_CATALOG_REPORT.md`. Do not interpret this
status as completion of Phase 5.

---

### Task 0: Freeze Catalog inventory, rollback point, and before measurements

**Files:**
- Create: `validation/phase5_catalog/task0-inventory.md`
- Create: `validation/phase5_catalog/source-inventory.txt`
- Create: `validation/phase5_catalog/project-imports.txt`
- Create: `validation/phase5_catalog/resource-inventory.txt`
- Create: `validation/phase5_catalog/test-inventory.txt`
- Create: `validation/phase5_catalog/performance-before.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`

**Interfaces:**
- Consumes: the clean pre-extraction checkout and the acceptance requirements in the spec.
- Produces: a reproducible ownership/resource/test baseline, five source-edit samples, five test-compile samples, and the initial Catalog transitional-dependency ledger.

- [ ] **Step 1: Confirm the rollback point and clean ownership baseline**

Run:

```powershell
New-Item -ItemType Directory -Force validation/phase5_catalog | Out-Null
git status --short
git rev-parse HEAD
git branch --show-current
rg --files app/src/main/java/com/streamvault/app/ui/screens/dashboard app/src/main/java/com/streamvault/app/ui/screens/movies app/src/main/java/com/streamvault/app/ui/screens/series app/src/main/java/com/streamvault/app/ui/screens/vod app/src/main/java/com/streamvault/app/ui/screens/favorites app/src/main/java/com/streamvault/app/ui/screens/search | Sort-Object | Set-Content validation/phase5_catalog/source-inventory.txt
```

Expected: the working tree is clean before measurement, the branch is `feature/improveCompose`, and the inventory contains 21 production files unless a newer committed change is explicitly explained in `task0-inventory.md`.

- [ ] **Step 2: Record imports, resources, tests, routes, and consumers**

Run:

```powershell
$catalogRoots = @(
  'app/src/main/java/com/streamvault/app/ui/screens/dashboard',
  'app/src/main/java/com/streamvault/app/ui/screens/movies',
  'app/src/main/java/com/streamvault/app/ui/screens/series',
  'app/src/main/java/com/streamvault/app/ui/screens/vod',
  'app/src/main/java/com/streamvault/app/ui/screens/favorites',
  'app/src/main/java/com/streamvault/app/ui/screens/search'
)
rg -n '^import com\.streamvault\.(app|feature|core|domain|data|player)' $catalogRoots | Sort-Object | Set-Content validation/phase5_catalog/project-imports.txt
rg -n 'R\.(string|plurals|drawable|color|dimen|array)\.[A-Za-z0-9_]+' $catalogRoots | Sort-Object | Set-Content validation/phase5_catalog/resource-inventory.txt
rg --files app/src/test app/src/androidTest | rg -i '(dashboard|catalog|movie|movies|series|vod|favorites|search|PremiumRouteGoldenTest)' | Sort-Object | Set-Content validation/phase5_catalog/test-inventory.txt
rg -n 'DashboardScreen|MoviesScreen|MovieDetailScreen|SeriesScreen|SeriesDetailScreen|VodScreen|FavoritesScreen|SearchScreen|registerHomeGraph|registerCatalogGraph' app feature core benchmark docs | Sort-Object | Set-Content validation/phase5_catalog/consumer-inventory.txt
```

Expected: app, Playback, and Settings couplings match the spec; Favorites has no registered route; the four golden names are present in the app mixed golden suite.

- [ ] **Step 3: Record temporary data imports in the ledger**

Add a `:feature:catalog` section naming:

```text
PreferencesRepository
  Current sites: MoviesViewModel, MovieDetailViewModel, SeriesViewModel,
  SeriesDetailViewModel, VodViewModel, FavoritesViewModel, DashboardViewModel.
  Current responsibility: persisted browse, variant, parental, dashboard,
  search-adjacent, and display preferences.
  Phase 7 direction: replace with focused domain-facing preference contracts.

ProviderSyncStateSource
  Current site: DashboardViewModel.
  Current responsibility: provider-specific synchronization status.
  Phase 7 direction: expose a domain-facing synchronization-status contract.
```

Also state that Catalog does not gain a `:player` or sibling-feature dependency.

- [ ] **Step 4: Capture the pre-extraction focused test/build baseline**

Run:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests 'com.streamvault.app.ui.screens.search.SearchViewModelTest' --tests 'com.streamvault.app.ui.screens.movies.MovieDetailViewModelCastingTest' --tests 'com.streamvault.app.ui.screens.series.SeriesDetailViewModelCastingTest' --tests 'com.streamvault.app.ui.screens.dashboard.DashboardHomeShelvesTest' --tests 'com.streamvault.app.navigation.CatalogRouteResolverTest' :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: PASS. Record command, commit, Java/Gradle versions, daemon/cache state, duration, and any pre-existing warnings in `task0-inventory.md`.

- [ ] **Step 5: Capture five source-edit and five test-compile samples**

Use a reversible whitespace-only edit in `DashboardHomeShelves.kt`, run `:app:compileDebugKotlin --profile` five times from an equivalent warm state, restore the file after each run, then repeat with a whitespace-only edit in `DashboardHomeShelvesTest.kt` and `:app:compileDebugUnitTestKotlin --profile`.

```powershell
git diff -- app/src/main/java/com/streamvault/app/ui/screens/dashboard/DashboardHomeShelves.kt app/src/test/java/com/streamvault/app/ui/screens/dashboard/DashboardHomeShelvesTest.kt
./gradlew.bat :app:compileDebugKotlin --profile --no-daemon --console=plain --warning-mode=none
./gradlew.bat :app:compileDebugUnitTestKotlin --profile --no-daemon --console=plain --warning-mode=none
git diff --check
```

Expected: ten comparable samples are recorded in `performance-before.md`; both touched files finish byte-identical to `HEAD`; no measurement edit is committed.

- [ ] **Step 6: Commit the inventory**

```powershell
git add validation/phase5_catalog docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md
git commit -m 'docs(catalog): record phase 5 extraction baseline'
```

---

### Task 1: Create `:feature:catalog` with a fail-closed boundary

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `feature/catalog/build.gradle.kts`
- Create: `feature/catalog/src/main/AndroidManifest.xml`
- Create: `feature/catalog/src/test/java/com/streamvault/feature/catalog/CatalogModuleBoundaryTest.kt`
- Create: `feature/catalog/src/test/resources/boundary-fixtures/AppPackageImport.kt`
- Create: `feature/catalog/src/test/resources/boundary-fixtures/FullyQualifiedAppReference.kt`
- Create: `feature/catalog/src/test/resources/boundary-fixtures/MainActivityReference.java`
- Create: `feature/catalog/src/test/resources/boundary-fixtures/RootNavigation.kt`
- Create: `feature/catalog/src/test/resources/boundary-fixtures/RootNavigation.java`
- Create: `feature/catalog/src/test/resources/boundary-fixtures/PlaybackFeatureImport.kt`
- Create: `feature/catalog/src/test/resources/boundary-fixtures/SettingsFeatureImport.java`
- Create: `feature/catalog/src/test/resources/boundary-fixtures/LiveFeatureImport.kt`
- Create: `feature/catalog/src/test/resources/boundary-fixtures/PlayerModuleImport.java`

**Interfaces:**
- Consumes: existing `:feature:live` Android-library and boundary-task conventions.
- Produces: an empty Compose/Hilt Catalog library and `verifyFeatureCatalogBoundary` enforcing the exact dependency/source policy.

- [ ] **Step 1: Register the module and app dependency**

Add:

```kotlin
// settings.gradle.kts
include(":feature:catalog")

// app/build.gradle.kts dependencies
implementation(project(":feature:catalog"))
```

Create the manifest:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 2: Add the library build with exact approved dependencies**

Mirror `feature/live/build.gradle.kts` for SDK 36, min SDK 25, Java/Kotlin 17, Compose, Hilt/KSP, Kover, unit, and connected-test libraries. Use exactly:

```kotlin
val allowedProjectDependencies = setOf(
    ":core:navigation",
    ":core:ui",
    ":domain",
    ":data",
)

dependencies {
    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))
    implementation(project(":domain"))
    implementation(project(":data"))
}
```

Do not add `:player` or any `:feature:*` dependency.

- [ ] **Step 3: Implement and prove the boundary verifier red**

Use these source tokens in `verifyFeatureCatalogBoundary`:

```kotlin
val forbiddenFeatureCatalogSourceTokens = listOf(
    "import com.streamvault.app",
    "com.streamvault.app",
    "import com.streamvault.player",
    "com.streamvault.player",
    "import com.streamvault.feature.playback",
    "com.streamvault.feature.playback",
    "import com.streamvault.feature.provider",
    "com.streamvault.feature.provider",
    "import com.streamvault.feature.settings",
    "com.streamvault.feature.settings",
    "import com.streamvault.feature.live",
    "com.streamvault.feature.live",
    "MainActivity",
    "NavHostController",
    "NavController",
)
```

Temporarily create `feature/catalog/src/main/java/com/streamvault/feature/catalog/BoundaryProbe.kt` containing `import com.streamvault.app.MainActivity`, then run:

```powershell
./gradlew.bat :feature:catalog:verifyFeatureCatalogBoundary --no-daemon --console=plain
```

Expected: FAIL naming both the app-package and `MainActivity` tokens. Delete the probe after the red run.

- [ ] **Step 4: Add fixtures and boundary unit coverage**

Each fixture must contain its named forbidden reference. `CatalogModuleBoundaryTest` reads the generated report and asserts the exact dependency list, no main-source violations, and detection of Kotlin and Java fixtures for app, player, Playback, Provider, Settings, Live, activity, and root-controller categories.

Run:

```powershell
./gradlew.bat :feature:catalog:verifyFeatureCatalogBoundary :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.CatalogModuleBoundaryTest' --no-daemon --console=plain
```

Expected: PASS; `feature/catalog/build/reports/feature-catalog-boundary/report.txt` names exactly four project dependencies.

- [ ] **Step 5: Commit the module boundary**

```powershell
git add settings.gradle.kts app/build.gradle.kts feature/catalog
git commit -m 'feat(catalog): add feature module boundary'
```

---

### Task 2: Add Catalog route and integration contracts test-first

**Files:**
- Create: `feature/catalog/src/main/java/com/streamvault/feature/catalog/navigation/CatalogRoutePatterns.kt`
- Create: `feature/catalog/src/main/java/com/streamvault/feature/catalog/api/CatalogFeatureContracts.kt`
- Create: `feature/catalog/src/main/java/com/streamvault/feature/catalog/api/CatalogPlatformPorts.kt`
- Create: `feature/catalog/src/test/java/com/streamvault/feature/catalog/navigation/CatalogRoutePatternsTest.kt`
- Create: `feature/catalog/src/test/java/com/streamvault/feature/catalog/api/CatalogFeatureContractsTest.kt`

**Interfaces:**
- Consumes: Core UI Compose types plus domain `StreamInfo`, `Result`, and `AppHomeDashboardShelf`.
- Produces: the exact route constants, shell/customization slots, platform/preparation/download/update/Cast ports, and Catalog-owned event models defined by the spec.

- [ ] **Step 1: Write route-pattern tests and run red**

Test exact values:

```kotlin
@Test fun `catalog route values preserve app compatibility`() {
    assertThat(CatalogRoutePatterns.HOME).isEqualTo("home")
    assertThat(CatalogRoutePatterns.MOVIES).isEqualTo("movies")
    assertThat(CatalogRoutePatterns.SERIES).isEqualTo("series")
    assertThat(CatalogRoutePatterns.VOD).isEqualTo("vod")
    assertThat(CatalogRoutePatterns.SEARCH_DESTINATION).isEqualTo("search?query={query}")
    assertThat(CatalogRoutePatterns.MOVIE_DETAIL)
        .isEqualTo("movie_detail/{movieId}?returnRoute={returnRoute}")
    assertThat(CatalogRoutePatterns.SERIES_DETAIL)
        .isEqualTo("series_detail/{seriesId}?returnRoute={returnRoute}")
}
```

Run:

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.navigation.CatalogRoutePatternsTest' --no-daemon --console=plain
```

Expected: FAIL because `CatalogRoutePatterns` does not exist.

- [ ] **Step 2: Implement route constants**

Create the object exactly as specified in the design, including both presentation-hint keys.

```kotlin
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
```

- [ ] **Step 3: Write contract usability tests and run red**

Create fakes that implement `CatalogPlatformHost`, `CatalogStreamPreparer`, `CatalogDownloadStarter`, `CatalogCastPort`, and `CatalogAppUpdatePort`. Assert that a `CatalogCastRequest` preserves every field and that `CatalogCastStartResult`, `CatalogCastPlaybackEvent`, `CatalogUiEvent`, and update-action variants are exhaustive.

```kotlin
@Test fun `cast request preserves feature owned presentation inputs`() {
    val streamInfo = StreamInfo(url = "https://example.test/movie.m3u8")
    val request = CatalogCastRequest(streamInfo, "Title", "Subtitle", "art", 42_000L)
    assertThat(request.streamInfo).isEqualTo(streamInfo)
    assertThat(request.startPositionMs).isEqualTo(42_000L)
    assertThat(request.subtitle).isEqualTo("Subtitle")
}
```

Expected: FAIL because the contracts do not exist.

- [ ] **Step 4: Implement the contracts**

Implement the signatures from the spec. `CatalogScaffoldContent` contains exactly `currentDestination: AppDestination`, `title`, `subtitle`, `CatalogNavigationChrome`, `topBarVisible`, `compactHeader`, `showScreenHeader`, and `ColumnScope` content. It does not expose header, action, modifier, or padding slots because no current Catalog root passes them.

- [ ] **Step 5: Run green and boundary checks**

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.api.*' --tests 'com.streamvault.feature.catalog.navigation.*' :feature:catalog:verifyFeatureCatalogBoundary --no-daemon --console=plain
```

Expected: PASS with no forbidden source or dependency reference.

- [ ] **Step 6: Commit the contracts**

```powershell
git add feature/catalog
git commit -m 'feat(catalog): define routes and integration ports'
```

---

### Task 3: Implement app adapters and Hilt bindings with delegation tests

**Files:**
- Create: `app/src/main/java/com/streamvault/app/catalog/AppCatalogStreamPreparer.kt`
- Create: `app/src/main/java/com/streamvault/app/catalog/AppCatalogDownloadStarter.kt`
- Create: `app/src/main/java/com/streamvault/app/catalog/AppCatalogCastPort.kt`
- Create: `app/src/main/java/com/streamvault/app/catalog/AppCatalogUpdatePort.kt`
- Create: `app/src/main/java/com/streamvault/app/di/AppCatalogModule.kt`
- Modify: `app/src/main/java/com/streamvault/app/MainActivity.kt`
- Test: `app/src/test/java/com/streamvault/app/catalog/AppCatalogAdaptersTest.kt`

**Interfaces:**
- Consumes: Task 2 ports, `StreamVaultPluginManager`, `DownloadForegroundService`, Playback Cast factory/coordinator, `PreferencesRepository`, app update policy, and `AppUpdateInstaller`.
- Produces: app-owned implementations bound to the four injectable Catalog ports plus `MainActivity : CatalogPlatformHost`.

- [ ] **Step 1: Write adapter delegation tests and run red**

Cover the following cases with concrete Mockito/Robolectric fakes:

```kotlin
@Test
fun `stream preparer delegates without rewriting result`() = runTest {
    val input = StreamInfo(url = "https://example.test/input.m3u8")
    val output = StreamInfo(url = "https://example.test/prepared.m3u8")
    whenever(pluginManager.preparePlaybackStreamInfo(input)).thenReturn(Result.success(output))

    assertThat(adapter.prepare(input)).isEqualTo(Result.success(output))
    verify(pluginManager).preparePlaybackStreamInfo(input)
}

@Test
fun `download starter forwards successful string id to foreground service`() {
    adapter.startDownload("download-42")

    val intent = shadowOf(applicationContext).nextStartedService
    assertThat(intent.component?.className).isEqualTo(DownloadForegroundService::class.java.name)
    assertThat(intent.getStringExtra("download_id")).isEqualTo("download-42")
}
```

The Cast test supplies one factory/coordinator result for each `CastStartResult`,
asserts every `CatalogCastRequest` field reaching the factory, and asserts the
ordered mapped lifecycle events. The update test supplies each
`AppUpdateActionState`, asserts the same-named `CatalogUpdateAction`, and
verifies `installDownloadedUpdate("sha-256")` receives `"sha-256"` unchanged.

Use constructor-injected function delegates around static/service entry points so tests do not launch Android services. Run:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests 'com.streamvault.app.catalog.AppCatalogAdaptersTest' --no-daemon --console=plain
```

Expected: FAIL because adapter classes do not exist.

- [ ] **Step 2: Implement preparation and download adapters**

```kotlin
@Singleton
class AppCatalogStreamPreparer @Inject constructor(
    private val pluginManager: StreamVaultPluginManager,
) : CatalogStreamPreparer {
    override suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo> =
        pluginManager.preparePlaybackStreamInfo(streamInfo)
}

@Singleton
class AppCatalogDownloadStarter @Inject constructor(
    @ApplicationContext private val context: Context,
) : CatalogDownloadStarter {
    override fun startDownload(downloadId: String) {
        DownloadForegroundService.startDownload(context, downloadId)
    }
}
```

- [ ] **Step 3: Implement Cast mapping without leaking Playback types**

`AppCatalogCastPort.startCasting` must call the existing `CastMediaRequestFactory.buildFromStreamInfo`, then `CastPlaybackCoordinator.startCasting`, and map:

```text
STARTED                  -> CatalogCastStartResult.Started
ROUTE_SELECTION_REQUIRED -> CatalogCastStartResult.RouteSelectionRequired
UNAVAILABLE              -> CatalogCastStartResult.Unavailable
UNSUPPORTED/build failure-> CatalogCastStartResult.Unsupported(CatalogMessage.CastUnsupported)
```

Map coordinator lifecycle events to `RouteSelectionCancelled` or `Finished(succeeded, message)` without exposing Playback resource IDs.

- [ ] **Step 4: Implement update mapping**

`AppCatalogUpdatePort.notice` combines the five existing cached release flows with `AppUpdateInstaller.downloadState`, applies `isRemoteVersionNewer` and `latestAppUpdateAction`, and maps every `AppUpdateActionState` by name to `CatalogUpdateAction`. `installDownloadedUpdate` delegates the supplied SHA unchanged.

- [ ] **Step 5: Bind injectable ports and implement the activity host**

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class AppCatalogModule {
    @Binds @Singleton abstract fun bindStreamPreparer(impl: AppCatalogStreamPreparer): CatalogStreamPreparer
    @Binds @Singleton abstract fun bindDownloadStarter(impl: AppCatalogDownloadStarter): CatalogDownloadStarter
    @Binds @Singleton abstract fun bindCastPort(impl: AppCatalogCastPort): CatalogCastPort
    @Binds @Singleton abstract fun bindUpdatePort(impl: AppCatalogUpdatePort): CatalogAppUpdatePort
}
```

Add `CatalogPlatformHost` to `MainActivity`'s implemented interfaces and delegate `openCastRouteChooser()` to the existing method without changing it.

- [ ] **Step 6: Run focused and app compilation checks**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests 'com.streamvault.app.catalog.AppCatalogAdaptersTest' :feature:catalog:verifyFeatureCatalogBoundary :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: PASS; the Catalog feature remains sibling-feature independent.

- [ ] **Step 7: Commit adapters**

```powershell
git add app/src/main/java/com/streamvault/app/catalog app/src/main/java/com/streamvault/app/di/AppCatalogModule.kt app/src/main/java/com/streamvault/app/MainActivity.kt app/src/test/java/com/streamvault/app/catalog
git commit -m 'feat(catalog): adapt app platform services'
```

---

### Task 4: Establish Catalog resource and localization ownership

**Files:**
- Create: `feature/catalog/src/main/res/values/strings.xml`
- Create locale strings: `feature/catalog/src/main/res/values-ar/strings.xml`, `values-cs/strings.xml`, `values-da/strings.xml`, `values-de/strings.xml`, `values-el/strings.xml`, `values-es/strings.xml`, `values-fi/strings.xml`, `values-fr/strings.xml`, `values-hu/strings.xml`, `values-in/strings.xml`, `values-it/strings.xml`, `values-iw/strings.xml`, `values-ja/strings.xml`, `values-ko/strings.xml`, `values-nb/strings.xml`, `values-nl/strings.xml`, `values-pl/strings.xml`, `values-pt/strings.xml`, `values-ro/strings.xml`, `values-ru/strings.xml`, `values-sv/strings.xml`, `values-tr/strings.xml`, `values-uk/strings.xml`, `values-vi/strings.xml`, and `values-zh/strings.xml` under `feature/catalog/src/main/res/`
- Create plural resources: `feature/catalog/src/main/res/values/plurals.xml`, `values-ar/plurals.xml`, `values-cs/plurals.xml`, `values-da/plurals.xml`, `values-de/plurals.xml`, `values-el/plurals.xml`, `values-es/plurals.xml`, `values-fi/plurals.xml`, `values-fr/plurals.xml`, `values-hu/plurals.xml`, `values-in/plurals.xml`, `values-it/plurals.xml`, `values-iw/plurals.xml`, `values-ja/plurals.xml`, `values-ko/plurals.xml`, `values-nb/plurals.xml`, `values-nl/plurals.xml`, `values-pl/plurals.xml`, `values-pt/plurals.xml`, `values-ro/plurals.xml`, `values-ru/plurals.xml`, `values-sv/plurals.xml`, `values-tr/plurals.xml`, `values-uk/plurals.xml`, `values-vi/plurals.xml`, and `values-zh/plurals.xml` under `feature/catalog/src/main/res/`
- Create: `validation/phase5_catalog/locale_audit.ps1`
- Create: `validation/phase5_catalog/task4-locale-resources.md`

**Interfaces:**
- Consumes: Task 0 resource inventory and all app locale files.
- Produces: a feature namespace containing every resource needed by moved Catalog source/tests with exact locale parity.

- [ ] **Step 1: Write the locale audit and prove it reports missing keys**

The PowerShell audit must parse XML by resource type/name, compare the Catalog default key set against every app locale containing that key, and report `missing`, `valueMismatch`, `formatMismatch`, and `unexpected` counts. Before copying resources, run:

```powershell
powershell -ExecutionPolicy Bypass -File validation/phase5_catalog/locale_audit.ps1
```

Expected: nonzero missing keys and exit code 1.

- [ ] **Step 2: Copy exact Catalog resource values**

Copy all keys referenced by Task 0 production/tests plus Cast/download messages mapped in Tasks 2-3. Preserve `%1$s`, `%1$d`, plural quantities, escaped apostrophes, newlines, and translatable flags. Use `com.streamvault.feature.catalog.R` in moved code; do not use app resource aliases.

- [ ] **Step 3: Run locale audit green**

```powershell
powershell -ExecutionPolicy Bypass -File validation/phase5_catalog/locale_audit.ps1
./gradlew.bat :feature:catalog:processDebugResources :feature:catalog:verifyFeatureCatalogBoundary --no-daemon --console=plain
```

Expected: exit code 0; all four audit counts are zero; Android resource processing passes.

- [ ] **Step 4: Commit resource ownership**

```powershell
git add feature/catalog/src/main/res validation/phase5_catalog/locale_audit.ps1 validation/phase5_catalog/task4-locale-resources.md
git commit -m 'feat(catalog): add localized feature resources'
```

---

### Task 5: Move Catalog-specific presentation primitives

**Files:**
- Create under `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/components/`: `CatalogCards.kt`, `CatalogCategoryRow.kt`, `CatalogContinueWatchingRow.kt`, `CatalogSelectionChipRow.kt`, `CatalogSavedCategoryContextCard.kt`, `CatalogSavedCategoryShortcutsRow.kt`, `CatalogReorderTopBar.kt`, `CatalogChannelProgressTicker.kt`, `CatalogBrowseScaffold.kt`, `CatalogInfiniteScrollEffect.kt`, `CatalogVodChrome.kt`, `CatalogVodClassicBrowser.kt`, `CatalogExternalRatingsStrip.kt`, `CatalogEpisodeRowCard.kt`, `CatalogGroupDialogs.kt`
- Create: `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/time/CatalogTimeFormatting.kt`
- Test: `feature/catalog/src/test/java/com/streamvault/feature/catalog/presentation/CatalogPresentationPrimitivesTest.kt`
- Test: `feature/catalog/src/androidTest/java/com/streamvault/feature/catalog/presentation/CatalogPresentationBehaviorTest.kt`

**Interfaces:**
- Consumes: Task 4 resources and existing app Catalog-only component behavior.
- Produces: app-independent feature-local rendering primitives used by every moved Catalog screen.

- [ ] **Step 1: Add focused pure tests and run red**

Cover the shared ticker/progress calculation, stable semantic key construction, VOD selection-chip identity, duration formatting, and infinite-scroll threshold decision. Preserve current edge cases: missing program, zero duration, negative position, mixed provider IDs, empty lists, and a load request already in progress.

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.presentation.CatalogPresentationPrimitivesTest' --no-daemon --console=plain
```

Expected: FAIL because feature-local primitives do not exist.

- [ ] **Step 2: Copy behavior into focused feature files**

Copy only the Catalog-consumed declarations from the current app files. Keep parameters, defaults, modifiers, keys, `contentType`, focus properties, test tags, semantics, colors, sizes, and callback order unchanged. Rename public declarations with `Catalog` prefixes only where needed to avoid accidental app/shared ownership; update moved call sites in Tasks 6-9.

- [ ] **Step 3: Remove app-specific dependencies from the copies**

Use:

```kotlin
import com.streamvault.core.ui.device.rememberIsTelevisionDevice
import com.streamvault.core.ui.time.LocalUiTimeFormat
import com.streamvault.core.ui.time.createDateTimeFormat
```

Keep the millisecond position formatter feature-local. Replace app shell/resource/navigation imports with Task 2 slots, Task 4 resources, Core UI types, or explicit callbacks. Do not import another feature.

- [ ] **Step 4: Add connected behavior coverage**

Render representative channel/movie/series cards, category row, selection chips, and VOD browse actions with fakes. Assert focusability, click callback count, selected semantics, stable tags, and D-pad activation.

```powershell
./gradlew.bat :feature:catalog:compileDebugAndroidTestKotlin :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.presentation.*' :feature:catalog:verifyFeatureCatalogBoundary --no-daemon --console=plain
```

Expected: PASS and no app/sibling-feature import.

- [ ] **Step 5: Commit primitives**

```powershell
git add feature/catalog/src/main feature/catalog/src/test feature/catalog/src/androidTest
git commit -m 'feat(catalog): move presentation primitives'
```

---

### Task 6: Move Movies, Series, and combined VOD browsing

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/screens/movies/MoviesViewModel.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/movies/MoviesViewModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/movies/MoviesScreen.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/movies/MoviesScreen.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/series/SeriesViewModel.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/series/SeriesViewModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/series/SeriesScreen.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/series/SeriesScreen.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/vod/VodViewModel.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/vod/VodViewModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/vod/VodScreenCommon.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/vod/VodScreenCommon.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/vod/VodScreen.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/vod/VodScreen.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/vod/VodReorderHelpers.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/vod/VodReorderHelpers.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/vod/VodFavoriteHelpers.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/vod/VodFavoriteHelpers.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/vod/VodCatalogBuilders.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/vod/VodCatalogBuilders.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/vod/VodBrowseStateHelpers.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/vod/VodBrowseStateHelpers.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/graph/CatalogGraph.kt`
- Test: `feature/catalog/src/test/java/com/streamvault/feature/catalog/presentation/vod/CatalogVodHelpersTest.kt`

**Interfaces:**
- Consumes: Task 2 `CatalogScaffoldContent`, Task 4 resources, Task 5 primitives, existing repositories/use cases, and temporary `PreferencesRepository`.
- Produces: app-independent Movies, Series, and VOD browse screens while the app graph temporarily registers them.

- [ ] **Step 1: Add pure VOD helper characterization tests and run them before moves**

Cover preview/search catalog construction, group membership, favorite marking, category selection, load-limit increments, reorder movement/save order, filter changes, and sort changes using the current public/internal behavior.

```powershell
./gradlew.bat :app:compileDebugUnitTestKotlin --no-daemon --console=plain
```

Expected: existing app compilation passes before ownership changes.

- [ ] **Step 2: Move VOD helpers and ViewModel, then run feature tests**

Update packages to `com.streamvault.feature.catalog.presentation.vod`, resource imports to Catalog R, and imports to Task 5 components. Preserve every function signature used by Movies/Series.

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.presentation.vod.*' :feature:catalog:compileDebugKotlin --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 3: Move Movies and Series browse ViewModels**

Change only package/import ownership. Keep state-flow topology, `WhileSubscribed` values, hydration windows, filters, sort order, grouped variants, favorite/group operations, and message behavior unchanged.

- [ ] **Step 4: Move browse screens behind the scaffold slot**

Add a required `scaffold: CatalogScaffoldContent` parameter to `MoviesScreen`, `SeriesScreen`, and `VodScreen`. Replace `AppScreenScaffold` calls with that slot, pass `AppDestination.Movies`, `AppDestination.Series`, or `AppDestination.Vod`, and use `CatalogNavigationChrome.TopBar`. Remove the raw `onNavigate` and `currentRoute` parameters because these screens use them only for the app shell.

- [ ] **Step 5: Keep the app graph compiling through temporary imports**

Update only screen imports and pass an app scaffold lambda that delegates to the unchanged `AppScreenScaffold`. Do not move graph ownership yet.

```powershell
./gradlew.bat :feature:catalog:verifyFeatureCatalogBoundary :feature:catalog:testDebugUnitTest :feature:catalog:compileDebugKotlin :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: PASS; old app browse files are absent; routes still resolve through the app graph.

- [ ] **Step 6: Commit browse ownership**

```powershell
git add app/src/main/java/com/streamvault/app/navigation/graph/CatalogGraph.kt feature/catalog app/src/main/java/com/streamvault/app/ui/screens/movies app/src/main/java/com/streamvault/app/ui/screens/series app/src/main/java/com/streamvault/app/ui/screens/vod
git commit -m 'feat(catalog): move browse presentation'
```

---

### Task 7: Move movie and series detail flows behind Catalog ports

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/screens/movies/MovieDetailViewModel.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/movies/MovieDetailViewModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/movies/MovieDetailScreen.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/movies/MovieDetailScreen.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/series/SeriesDetailViewModel.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/series/SeriesDetailViewModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/series/SeriesDetailScreen.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/series/SeriesDetailScreen.kt`
- Move: `app/src/test/java/com/streamvault/app/ui/screens/movies/MovieDetailViewModelCastingTest.kt` -> `feature/catalog/src/test/java/com/streamvault/feature/catalog/presentation/movies/MovieDetailViewModelCastingTest.kt`
- Move: `app/src/test/java/com/streamvault/app/ui/screens/series/SeriesDetailViewModelCastingTest.kt` -> `feature/catalog/src/test/java/com/streamvault/feature/catalog/presentation/series/SeriesDetailViewModelCastingTest.kt`
- Create: `feature/catalog/src/test/java/com/streamvault/feature/catalog/presentation/CatalogDownloadBehaviorTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/graph/CatalogGraph.kt`

**Interfaces:**
- Consumes: Task 2 ports/events/keys, Task 3 app bindings, Task 4 messages, and Task 5 detail components/formatting.
- Produces: app- and Playback-independent detail ViewModels/screens preserving grouped hints, Cast, copy, download, favorite, ratings, and return behavior.

- [ ] **Step 1: Rewrite Cast tests against Catalog contracts and run red**

Replace Playback fakes with a `FakeCatalogCastPort`. Preserve assertions for resume positions, titles/subtitles, artwork, route chooser events, unsupported/unavailable messages, session failure, load success/failure, cancellation, and `isCasting` reset.

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.presentation.movies.MovieDetailViewModelCastingTest' --tests 'com.streamvault.feature.catalog.presentation.series.SeriesDetailViewModelCastingTest' --no-daemon --console=plain
```

Expected: FAIL while ViewModels still depend on Playback contracts.

- [ ] **Step 2: Replace direct plugin and Cast implementations**

Inject `CatalogStreamPreparer` and `CatalogCastPort`. Build `CatalogCastRequest` from the same `StreamInfo`, title, subtitle, artwork, live flag implication, and start position. Preserve the current report-mode state machine and emit `CatalogUiEvent` instead of Playback `CastUiEvent` or resource IDs.

- [ ] **Step 3: Replace Context/service download behavior and test it**

Change `downloadMovie(context)` and `downloadEpisode(context, episode)` to context-free methods. Keep `DownloadManager.enqueueDownload` and request fields unchanged. On success call `CatalogDownloadStarter.startDownload(id)` and emit `ShowMessage(DownloadStarted)`; map error/no-URL to the matching Catalog messages.

```kotlin
verify(downloadStarter).startDownload(downloadId)
assertThat(events.first()).isEqualTo(CatalogUiEvent.ShowMessage(CatalogMessage.DownloadStarted))
```

- [ ] **Step 4: Replace MainActivity discovery in detail screens**

Add `platformHost: CatalogPlatformHost?` to both detail screens. Collect `CatalogUiEvent`; call `platformHost?.openCastRouteChooser()` for chooser events and render feature-owned message strings for message events. Keep clipboard and trailer intents at the screen boundary using Android framework APIs.

- [ ] **Step 5: Move presentation-hint key ownership**

Read `CatalogRoutePatterns.MOVIE_DETAIL_PRESENTATION_HINT_KEY` and `SERIES_DETAIL_PRESENTATION_HINT_KEY` from `SavedStateHandle`. Update app payload code to use those constants without changing where hints are copied.

- [ ] **Step 6: Update temporary app graph imports and verify**

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.presentation.movies.*' --tests 'com.streamvault.feature.catalog.presentation.series.*' :feature:catalog:verifyFeatureCatalogBoundary :app:testDebugUnitTest --tests 'com.streamvault.app.navigation.*' :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: PASS; feature source has no `MainActivity`, app service/plugin, or Playback import.

- [ ] **Step 7: Commit detail ownership**

```powershell
git add feature/catalog app/src/main/java/com/streamvault/app/navigation app/src/test/java/com/streamvault/app/ui/screens/movies app/src/test/java/com/streamvault/app/ui/screens/series app/src/main/java/com/streamvault/app/ui/screens/movies app/src/main/java/com/streamvault/app/ui/screens/series
git commit -m 'feat(catalog): move media detail presentation'
```

---

### Task 8: Move Dashboard with update and Settings composition seams

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/screens/dashboard/DashboardViewModel.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/dashboard/DashboardViewModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/dashboard/DashboardScreen.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/dashboard/DashboardScreen.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/dashboard/DashboardHomeShelves.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/dashboard/DashboardHomeShelves.kt`
- Move: `app/src/test/java/com/streamvault/app/ui/screens/dashboard/DashboardHomeShelvesTest.kt` -> `feature/catalog/src/test/java/com/streamvault/feature/catalog/presentation/dashboard/DashboardHomeShelvesTest.kt`
- Create: `feature/catalog/src/test/java/com/streamvault/feature/catalog/presentation/dashboard/DashboardUpdatePortTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/graph/HomeGraph.kt`

**Interfaces:**
- Consumes: `CatalogAppUpdatePort`, `CatalogDashboardShelfCustomizationContent`, `CatalogScaffoldContent`, Task 5 media components, and existing domain/data dependencies.
- Produces: a feature-owned Home/Dashboard screen with no Settings or app-update implementation dependency.

- [ ] **Step 1: Characterize update mapping at the Dashboard boundary and run red**

Use a fake `CatalogAppUpdatePort` to assert notice collection, all five action values, SHA forwarding on install, success message, error message, and `userMessageShown` clearing.

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.presentation.dashboard.DashboardUpdatePortTest' --no-daemon --console=plain
```

Expected: FAIL because Dashboard still injects app update implementations.

- [ ] **Step 2: Move Dashboard ViewModel and replace update imports**

Inject `CatalogAppUpdatePort`, combine `notice` at the same point in the existing Dashboard flow, and delegate install with the current notice SHA. Keep all shelf limits, provider/combined-profile logic, recording IDs, parental filtering, category pinning, synchronization state, feature selection, and message behavior unchanged.

- [ ] **Step 3: Move Dashboard rendering behind the two composition slots**

Add required `scaffold` and `dashboardShelfCustomizationContent` parameters. Replace `onNavigate: (String) -> Unit` and `currentRoute: String` with `onDestinationRequested: (AppDestination) -> Unit`; pass `AppDestination.Home` to the scaffold. Map every former `Routes` call exactly: Settings to `AppDestination.Settings()`, Live to `AppDestination.LiveTv(categoryId)`, Movies to `AppDestination.Movies`, and Series to `AppDestination.Series`. Replace the direct Settings dialog call with:

```kotlin
dashboardShelfCustomizationContent(
    uiState.homeDashboardShelves,
    { showHomeCustomizationDialog = false },
    { shelves ->
        viewModel.setHomeDashboardShelves(shelves)
        showHomeCustomizationDialog = false
    },
)
```

Use Core UI device/time contracts and Catalog media components. Preserve shelf order, keys, content types, scroll states, and focus behavior.

- [ ] **Step 4: Adapt the app Home graph temporarily**

Import the feature Dashboard screen, pass `AppScreenScaffold`, and render `DashboardShelfCustomizationDialog` only inside the app-supplied customization lambda.

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.presentation.dashboard.*' :feature:catalog:verifyFeatureCatalogBoundary :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: PASS; `feature/catalog/src/main` has no Settings or app-update import.

- [ ] **Step 5: Commit Dashboard ownership**

```powershell
git add feature/catalog app/src/main/java/com/streamvault/app/navigation/graph/HomeGraph.kt app/src/main/java/com/streamvault/app/ui/screens/dashboard app/src/test/java/com/streamvault/app/ui/screens/dashboard
git commit -m 'feat(catalog): move dashboard presentation'
```

---

### Task 9: Move Search and Favorites

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/screens/search/SearchScreen.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/search/SearchScreen.kt`
- Move: `app/src/test/java/com/streamvault/app/ui/screens/search/SearchViewModelTest.kt` -> `feature/catalog/src/test/java/com/streamvault/feature/catalog/presentation/search/SearchViewModelTest.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/favorites/FavoritesViewModel.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/favorites/FavoritesViewModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/favorites/FavoritesScreen.kt` -> `feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/favorites/FavoritesScreen.kt`
- Create: `feature/catalog/src/test/java/com/streamvault/feature/catalog/presentation/favorites/FavoritesViewModelTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/graph/CatalogGraph.kt`

**Interfaces:**
- Consumes: Task 2 scaffold/navigation callbacks, Task 4 resources, Task 5 cards/chips/ticker, domain repositories/managers, and temporary Preferences.
- Produces: app-independent Search and Favorites presentation; Favorites remains intentionally unregistered.

- [ ] **Step 1: Move Search tests and prove package transition red**

Update the test package/import to the feature namespace before moving production. Run:

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.presentation.search.SearchViewModelTest' --no-daemon --console=plain
```

Expected: FAIL because `SearchViewModel` is still app-owned.

- [ ] **Step 2: Move Search production and preserve independent failure behavior**

Move the ViewModel and screen together, add the scaffold slot, pass `AppDestination.Search()` to it, remove the raw `onNavigate` and `currentRoute` parameters, and replace app cards, ticker, device, and resources. Preserve query initialization, trimming, recent-query deduplication/cap, per-section errors, recording/scheduled badges, parental PIN flow, focus restoration, tab state, and search result keys.

- [ ] **Step 3: Add Favorites ViewModel characterization coverage**

Cover preset/provider-scope selection, section construction, managed groups, reorder start/move/save/cancel, history mapping, global shelf title/subtitle, and user-message clearing.

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.presentation.favorites.FavoritesViewModelTest' --no-daemon --console=plain
```

Expected: FAIL before Favorites production moves.

- [ ] **Step 4: Move Favorites without creating a route**

Move production, add the scaffold slot plus `currentDestination: AppDestination` and `onDestinationRequested: (AppDestination) -> Unit`, and replace app chips/dialogs/resources with Catalog/Core equivalents. Convert the raw Series-detail string action to `AppDestination.SeriesDetail(item.favorite.contentId)`. Do not add a composable destination or `AppDestination` variant.

- [ ] **Step 5: Update the temporary Search registration and verify**

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.presentation.search.*' --tests 'com.streamvault.feature.catalog.presentation.favorites.*' :feature:catalog:verifyFeatureCatalogBoundary :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: PASS; all six accepted app screen directories are now empty/absent.

- [ ] **Step 6: Commit Search and Favorites ownership**

```powershell
git add feature/catalog app/src/main/java/com/streamvault/app/navigation/graph/CatalogGraph.kt app/src/main/java/com/streamvault/app/ui/screens/search app/src/main/java/com/streamvault/app/ui/screens/favorites app/src/test/java/com/streamvault/app/ui/screens/search
git commit -m 'feat(catalog): move search and favorites presentation'
```

---

### Task 10: Move graph registration and preserve app navigation mapping

**Files:**
- Create: `feature/catalog/src/main/java/com/streamvault/feature/catalog/navigation/CatalogGraph.kt`
- Create: `feature/catalog/src/test/java/com/streamvault/feature/catalog/navigation/CatalogGraphTest.kt`
- Create: `feature/catalog/src/androidTest/java/com/streamvault/feature/catalog/navigation/CatalogGraphBehaviorTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppRouteCodec.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigationKeys.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigationPayloads.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/NavControllerNavigator.kt`
- Modify: `app/src/test/java/com/streamvault/app/navigation/CatalogRouteResolverTest.kt`
- Delete: `app/src/main/java/com/streamvault/app/navigation/graph/HomeGraph.kt`
- Delete: `app/src/main/java/com/streamvault/app/navigation/graph/CatalogGraph.kt`

**Interfaces:**
- Consumes: Tasks 2-9 screens/contracts and existing app route/player/payload helpers.
- Produces: controller-free `registerCatalogGraph` called once by `AppNavHost`, with all app-only work supplied through callbacks.

- [ ] **Step 1: Write feature graph tests and run red**

Build a test `NavHost` and assert registration/callback behavior for Home, Movies, Series, VOD, Search with empty and nonempty query, Movie Detail, Series Detail, default `returnRoute`, hint consumption, back/return, and screen callback forwarding. Assert no Favorites destination is registered.

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.navigation.CatalogGraphTest' :feature:catalog:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

Expected: FAIL because feature graph registration does not exist.

- [ ] **Step 2: Implement controller-free graph registration**

Use this callback shape, adding no app types:

```kotlin
fun NavGraphBuilder.registerCatalogGraph(
    actions: NavigationActions,
    platformHost: CatalogPlatformHost?,
    scaffold: CatalogScaffoldContent,
    dashboardShelfCustomizationContent: CatalogDashboardShelfCustomizationContent,
    onTopLevelDestinationRequested: (AppDestination) -> Unit,
    onOpenMovieDetail: (Movie, AppDestination) -> Unit,
    onOpenSeriesDetail: (Series, AppDestination) -> Unit,
    onPlayChannel: (Channel, CatalogChannelPlaybackContext) -> Unit,
    onPlayMovie: (Movie, AppDestination) -> Unit,
    onPlayEpisode: (Episode, AppDestination) -> Unit,
    onPlayHistory: (PlaybackHistory, AppDestination) -> Unit,
    consumeMoviePresentationHint: (NavBackStackEntry) -> Unit,
    consumeSeriesPresentationHint: (NavBackStackEntry) -> Unit,
)
```

Define `CatalogChannelPlaybackContext` in `CatalogFeatureContracts.kt` with `categoryId`, `providerId`, `isVirtual`, `combinedProfileId`, and `returnDestination`. Reproduce the existing Home/Catalog graph branches field-for-field.

- [ ] **Step 3: Alias app route patterns to feature constants**

Keep `AppRouteCodec` authoritative for encoding/decoding, but set Home/Movies/Series/VOD/Search/detail constants from `CatalogRoutePatterns`. Import feature-owned hint keys in payload/navigation code and remove duplicate app constants.

- [ ] **Step 4: Wire AppNavHost callbacks to existing app helpers**

Replace `registerHomeGraph` plus the app `registerCatalogGraph` with one feature call. Map:

```text
onOpenMovieDetail  -> CatalogDetailNavigationActions.openMovieDetail
onOpenSeriesDetail -> CatalogDetailNavigationActions.openSeriesDetail
onPlayChannel      -> existing toLivePlayerRequest + actions.openPlayer
onPlayMovie        -> existing toPlayerNavigationRequest + actions.openPlayer
onPlayEpisode      -> existing toPlayerNavigationRequest + actions.openPlayer
onPlayHistory      -> current ContentType branch from HomeGraph/CatalogGraph
consume hints      -> AppNavigationPayloads consume functions
scaffold           -> AppRouteCodec.encode(currentDestination) + AppScreenScaffold
customization      -> Settings DashboardShelfCustomizationDialog
```

Pass `MainActivity` as `CatalogPlatformHost?` through the existing AppNavigation/AppNavHost composition path.

- [ ] **Step 5: Run feature graph and app compatibility tests**

```powershell
./gradlew.bat :feature:catalog:testDebugUnitTest --tests 'com.streamvault.feature.catalog.navigation.*' :feature:catalog:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests 'com.streamvault.app.navigation.*' :feature:catalog:verifyFeatureCatalogBoundary :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Expected: PASS; app route strings and return behavior are unchanged; old app graph files are absent.

- [ ] **Step 6: Commit graph ownership**

```powershell
git add feature/catalog app/src/main/java/com/streamvault/app/navigation app/src/test/java/com/streamvault/app/navigation
git commit -m 'feat(catalog): register feature navigation graph'
```

---

### Task 11: Move Catalog goldens, remove legacy ownership, and prove structural cleanup

**Files:**
- Create: `feature/catalog/src/androidTest/java/com/streamvault/feature/catalog/CatalogPresentationGoldenTest.kt`
- Move four files: `app/src/androidTest/assets/ui-goldens/route_{dashboard_default,movies_landing,series_detail,search_results}.png` -> `feature/catalog/src/androidTest/assets/ui-goldens/`
- Modify: `app/src/androidTest/java/com/streamvault/app/ui/PremiumRouteGoldenTest.kt`
- Delete Catalog-only declarations/files under `app/src/main/java/com/streamvault/app/ui/components/` after consumer proof
- Modify app locale resources only for keys proven Catalog-exclusive
- Create: `validation/phase5_catalog/task11-resource-cleanup.md`
- Create: `validation/phase5_catalog/task11-structural-verification.md`

**Interfaces:**
- Consumes: feature-owned screens/components/resources and the existing four reviewed baselines.
- Produces: independent Catalog golden coverage, no legacy app Catalog presentation, and a documented resource/component residue.

- [ ] **Step 1: Split four golden cases into the Catalog suite**

Move the exact fixture setup and assertions for Dashboard, Movies landing, Series detail, and Search results. Keep theme, density, viewport, font scale, locale, fake data, test tag `golden`, and baseline names unchanged.

```powershell
./gradlew.bat :feature:catalog:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

Expected: PASS; the app mixed suite no longer contains those four methods.

- [ ] **Step 2: Prove app Catalog screen ownership is gone**

```powershell
rg -n 'package com\.streamvault\.app\.ui\.screens\.(dashboard|movies|series|vod|favorites|search)' app/src/main app/src/test
rg -n 'import com\.streamvault\.app|com\.streamvault\.feature\.(playback|provider|settings|live)|MainActivity|NavHostController|NavController' feature/catalog/src/main
```

Expected: first command has no production/test ownership matches; second command has no matches.

- [ ] **Step 3: Remove Catalog-only app component declarations after consumer scans**

For every Task 5 source declaration, run repository-wide symbol searches. Delete the app declaration/file only when every remaining production consumer is Catalog-owned. Keep `AppShellNavigation.kt` and any declaration still used by Downloads, Plugins, app routes, tests, or another feature. Record each moved, deleted, duplicated, and retained component with consumer evidence in `task11-structural-verification.md`.

- [ ] **Step 4: Remove only proven-exclusive app resources**

For every Catalog resource key, search app/feature/core/domain/data/player/benchmark source and manifests. Remove an app key only when no non-Catalog consumer remains. Re-run both Android resource processors and the locale audit.

```powershell
powershell -ExecutionPolicy Bypass -File validation/phase5_catalog/locale_audit.ps1
./gradlew.bat :feature:catalog:processDebugResources :app:processDebugResources --no-daemon --console=plain
```

Expected: PASS; the report lists any intentional duplicated keys and why they remain.

- [ ] **Step 5: Run structural/build gates**

```powershell
./gradlew.bat :feature:catalog:verifyFeatureCatalogBoundary :feature:catalog:testDebugUnitTest :feature:catalog:lintDebug :feature:catalog:compileDebugAndroidTestKotlin :feature:catalog:assembleDebug :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --warning-mode=none
```

Expected: PASS. Record exact exit codes and warnings.

- [ ] **Step 6: Commit cleanup and golden ownership**

```powershell
git add feature/catalog app/src/main app/src/test app/src/androidTest validation/phase5_catalog
git commit -m 'test(catalog): complete ownership and golden migration'
```

---

### Task 12: Run connected Catalog acceptance and device journeys

**Files:**
- Create: `validation/phase5_catalog/task12-connected-validation.md`
- Create: `validation/phase5_catalog/task12-device-journeys.md`
- Create: `validation/phase5_catalog/task12_screenshots/`
- Create: `tools/catalog_xtream_fixture.py`
- Create: `tools/tests/test_catalog_xtream_fixture.py`
- Create: `tools/catalog_connected_validation.py`
- Create: `tools/tests/test_catalog_connected_validation.py`

**Interfaces:**
- Consumes: Task 11 assembled app/feature tests and an available API 36 TV emulator with seeded data.
- Produces: connected, focus/input, route, visual, and end-to-end Catalog evidence with explicit unavailable/failure reporting.

For the deterministic fixture-backed semantic subset, start
`python tools/catalog_xtream_fixture.py --port 8765`, install a debug APK
configured with the four `xtream.dev.*` entries from `docs/DEV_SEEDING.md`,
then run:

```powershell
python tools/catalog_connected_validation.py --adb E:\androidSdk\platform-tools\adb.exe --serial emulator-5554
```

The harness writes UIAutomator XML and `report.json` under the
ignored `build/catalog-validation` directory. It never edits
`local.properties` or claims download/Cast receiver success.

- [ ] **Step 1: Verify device/package state**

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb devices -l
& $adb shell getprop sys.boot_completed
& $adb shell cmd package list packages | Select-String streamvault
```

Expected: one authorized booted target. Record serial, API, form factor, density, locale, animation scales, and installed variant.

- [ ] **Step 2: Run focused Catalog connected suites**

```powershell
./gradlew.bat :feature:catalog:connectedDebugAndroidTest --no-daemon --console=plain --warning-mode=none
```

Expected: Catalog graph, behavior, and four golden cases pass. Record test counts and any fixture limitations separately.

- [ ] **Step 3: Run app navigation/platform compatibility suites**

Use instrumentation runner filters for the existing app navigation, platform compatibility, and remaining app golden classes rather than treating the entire app suite as one opaque result. Record every class and count in `task12-connected-validation.md`.

- [ ] **Step 4: Exercise seeded TV journeys**

Capture screenshots and sanitized logcat for:

```text
Dashboard: shelves, vertical/horizontal focus, customization open/save/cancel.
Movies: category, search launch, filter, sort, load-more, reorder, detail, Back.
Series: category, filter, sort, load-more, detail, season/episode focus, Back.
VOD: mixed movie/series rows and both detail branches.
Search: typing, recent query, tabs, channel/movie/series result activation.
Details: favorite, variant, copy URL, download enqueue, Cast chooser/cancel.
Favorites: direct test-host rendering and reorder/save/cancel because no route exists.
```

Expected: no crash, stuck focus, lost top navigation, wrong return destination, duplicate callback, incorrect list identity, or new error state.

- [ ] **Step 5: Exercise phone/tablet and accessibility variants when available**

Run representative browse/detail/search journeys in touch mode, RTL locale, and reduced-motion/animation-disabled configuration. If a device/configuration is unavailable, record it as unavailable rather than passed.

- [ ] **Step 6: Capture and scan logs**

```powershell
& $adb logcat -d -v time > validation/phase5_catalog/task12-logcat.txt
rg -n 'FATAL EXCEPTION|AndroidRuntime|Process: com\.streamvault|IllegalStateException|ClassNotFoundException|NoSuchMethodError|Resources\$NotFoundException' validation/phase5_catalog/task12-logcat.txt
```

Expected: no Catalog-caused fatal/runtime/resource failure. Sanitize provider credentials, URLs, tokens, MAC addresses, and personal paths before committing evidence.

- [ ] **Step 7: Commit connected evidence**

```powershell
git add validation/phase5_catalog/task12-connected-validation.md validation/phase5_catalog/task12-device-journeys.md validation/phase5_catalog/task12_screenshots
git commit -m 'test(catalog): record connected acceptance'
```

---

### Task 13: Prove build isolation, benchmark Dashboard, refresh profiles, and report

**Files:**
- Create: `validation/phase5_catalog/performance-after.md`
- Create: `validation/phase5_catalog/profile-validation.md`
- Create: `docs/COMPOSE_REDUCTION_PHASE5_CATALOG_REPORT.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_ROADMAP.md`
- Modify: `docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`
- Modify: generated profile outputs only through the existing generator workflow

**Interfaces:**
- Consumes: Task 0 before data and Tasks 1-12 implementation/evidence.
- Produces: comparable after measurements, unrelated-feature isolation proof, profile/package evidence, graph refresh, and an evidence-bounded Catalog report.

- [ ] **Step 1: Capture five after source-edit and test-compile samples**

Repeat Task 0's protocol using a reversible whitespace-only edit in feature-owned `DashboardHomeShelves.kt` and its feature test. Run:

```powershell
./gradlew.bat :feature:catalog:compileDebugKotlin --profile --no-daemon --console=plain --warning-mode=none
./gradlew.bat :feature:catalog:compileDebugUnitTestKotlin --profile --no-daemon --console=plain --warning-mode=none
```

Record raw values, min/median/max, task sets, cache state, and comparison limitations in `performance-after.md`.

- [ ] **Step 2: Prove unrelated feature Kotlin isolation**

For one representative Catalog-only source edit, capture `--dry-run` and actual task output. Assert no `:feature:playback:compile*Kotlin`, `:feature:provider:compile*Kotlin`, `:feature:settings:compile*Kotlin`, or `:feature:live:compile*Kotlin` task executes.

```powershell
./gradlew.bat :app:assembleDebug --dry-run --console=plain > validation/phase5_catalog/catalog-edit-dry-run.txt
./gradlew.bat :app:assembleDebug --console=plain --warning-mode=none > validation/phase5_catalog/catalog-edit-task-output.txt
```

Expected: Catalog and required app integration tasks run; sibling feature Kotlin compilation does not.

- [ ] **Step 3: Run clean/warm and packaging guardrails**

```powershell
./gradlew.bat clean :app:assembleDebug --no-daemon --console=plain --warning-mode=none
./gradlew.bat :app:assembleDebug --no-daemon --console=plain --warning-mode=none
./gradlew.bat :app:assembleBeta :app:assembleRelease --no-daemon --console=plain --warning-mode=none
```

Expected: PASS. Record duration and artifact sizes; compare clean-build regression against the plan's 5% guardrail with measurement noise disclosed.

- [ ] **Step 4: Run the Dashboard Macrobenchmark comparison**

Run the existing seeded `dashboardVerticalScroll` benchmark with the same emulator/device, iteration count, compilation mode, and package used by the baseline. Record frame count and P50/P90/P99 overrun metrics before/after. Do not infer a runtime win from unmatched device/build conditions.

- [ ] **Step 5: Regenerate and inspect profiles**

Run the existing baseline-profile generator, then build release. Search generated sources/output for stale app Catalog descriptors and new feature Catalog descriptors.

```powershell
./gradlew.bat :benchmark:pixel2Api36Setup:generateBaselineProfile :app:assembleRelease --no-daemon --console=plain --warning-mode=none
rg -n 'com/streamvault/app/ui/screens/(dashboard|movies|series|vod|favorites|search)' app/src benchmark build
rg -n 'com/streamvault/feature/catalog' app/src benchmark build
```

Expected: generator/build pass, stale app Catalog descriptors are absent from fresh generated profile sources, and feature Catalog descriptors are present. Record device and skipped macrobenchmark tests.

- [ ] **Step 6: Run the final automated matrix**

```powershell
./gradlew.bat :core:navigation:test :core:ui:testDebugUnitTest :domain:test :data:testDebugUnitTest :feature:catalog:verifyFeatureCatalogBoundary :feature:catalog:testDebugUnitTest :feature:catalog:lintDebug :feature:catalog:compileDebugAndroidTestKotlin :feature:catalog:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleBeta :app:assembleRelease --no-daemon --console=plain --warning-mode=none
```

Expected: report actual results. Existing failures must include test/task, message, reproduction, and proof they predate or are unrelated; they cannot be reported as passes.

- [ ] **Step 7: Refresh the architecture graph**

```powershell
graphify update .
git status --short
```

Expected: graph output reflects `:feature:catalog`; include generated graph changes only according to repository convention and record the refresh result.

- [ ] **Step 8: Write and self-audit the Catalog report**

The report must include ownership before/after, commit/task sequence, dependencies and ports, source/resource/test counts, locale audit, unit/lint/assemble results, connected/device journeys, four goldens, build samples, isolation task set, benchmark metrics, profile evidence, artifact sizes, existing/open failures, unavailable gates, rollback point, and the statement that other Phase 5 reports remain authoritative.

Update the roadmap and governing plan only for evidence-backed Catalog status. Do not mark Phase 5 complete or System started.

- [ ] **Step 9: Commit final evidence and documentation**

```powershell
git add docs validation/phase5_catalog graphify-out app/src/main/baseline-prof.txt app/src/main/startup-prof.txt
git commit -m 'docs(catalog): report phase 5 feature extraction'
```

If generated profile paths differ, stage only files produced by the verified generator and list their actual paths in `profile-validation.md`.

---

### Task 14: Exercise deterministic Xtream VOD pagination and Settings controls

**Files:**
- Create: `tools/catalog_xtream_fixture.py`
- Create: `tools/catalog_connected_validation.py`
- Create: `validation/phase5_catalog/task14-xtream-fixture-journeys.md`
- Test: `tools/tests/test_catalog_xtream_fixture.py`

**Interfaces:**
- Consumes: the existing debug `xtream.dev.*` provider hooks and production TV
  navigation/activity.
- Produces: a local-only Xtream fixture with one live channel, 63 movies, 63
  series, detail metadata, and two episodes per series; a semantic ADB journey
  that proves the fixture-backed Catalog and Settings paths without seeding
  application state or changing production provider behavior.

- [x] **Step 1: Add deterministic fixture data and contract tests**

  Keep the first two movie/series titles stable for detail and Search checks;
  place generated items in pagination categories and assert the 63-item shape
  in the fixture unit test. The fixture uses an ephemeral port in unit tests
  and port 8765 only for the emulator's `10.0.2.2` host mapping.

- [x] **Step 2: Add the semantic connected journey**

  Prove Home/Movies/Series/detail/favorite/saved/Search, Movies full-library
  entry/back, Settings Infinite scroll off, `Load more (60/63)` activation
  through `Pagination Movie 63`, restoration of Infinite scroll on, and
  Dashboard customization cancel/save/reset. Persist UIAutomator snapshots and
  a JSON report under ignored `build/` output.

- [x] **Step 3: Run the TV journey and record bounded evidence**

  The API 36 AOSP TV run passed 5/5 feature connected tests and the semantic
  journey in `build/catalog-validation-pagination-final-15`. The fixture was
  stopped and its temporary local properties are removed after validation.

- [ ] **Step 4: Finish the remaining Catalog acceptance gates**

  Execute Series selected-library pagination, browse reorder, download
  completion, Cast receiver chooser, direct Favorites-host reorder/save/cancel,
  touch/phone/tablet/RTL/reduced-motion/accessibility variants, and a
  cache-equivalent Dashboard performance comparison. Keep app lint and other
  Phase 5 gates separate from Catalog ownership.

## Exit Criteria

- `:feature:catalog` owns Dashboard, Movies, Series, VOD, Favorites, Search, detail presentation, route registration, feature-local components, resources, tests, and goldens.
- `:app` owns only Catalog composition adapters, player-request mapping, payload compatibility, shell/Settings composition, platform services, external intents, and root graph registration.
- The Catalog boundary reports exactly four approved project dependencies and zero forbidden main-source references.
- Feature tests run independently, all structural/build/resource gates have recorded outcomes, and a Catalog-only edit does not compile sibling feature Kotlin source.
- Routes, arguments, presentation hints, return destinations, focus, input, semantics, lazy identity, visual output, Cast/download/update behavior, and callback order remain equivalent.
- Transitional data imports, duplicated resources, measurement limitations, unavailable devices, and all failing gates are explicitly documented.
