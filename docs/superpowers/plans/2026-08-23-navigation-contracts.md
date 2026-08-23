# Phase 4 Navigation Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create typed, app-independent navigation contracts; move startup and external navigation orchestration out of the root composable; and reduce `AppNavHost` to a feature-graph registry ready for Phase 5 extraction.

**Architecture:** Add a pure Kotlin `:core:navigation` module for serializable destination/request/action contracts. Keep route strings, Android intent parsing, AndroidX `NavController`, domain adapters, and presentation-hint transport in `:app`; split the app graph into controller-free `NavGraphBuilder` registration extensions that communicate through typed actions.

**Tech Stack:** Kotlin/JVM 17, Android Gradle Plugin 8.12-era project conventions, AndroidX Navigation Compose, Kotlin coroutines/StateFlow, Hilt activity-retained scope, JUnit 4, Truth, Mockito Kotlin, Robolectric, Compose instrumentation tests, Gradle static boundary tasks, and graphify.

**Spec:** `docs/superpowers/specs/2026-08-23-navigation-contracts-design.md`

## Global Constraints

- `:core:navigation` must have no project dependencies and no references to `:app`, `:data`, `:domain`, `:player`, Android, Compose, AndroidX Navigation, Hilt, Activities, `NavController`, or `NavHost`.
- Preserve all existing route strings, argument names, intent extra names, URI validation, startup landing behavior, player payload fields, return behavior, screen visuals, and focus behavior.
- Feature graph registration files must not accept or reference the root `NavHostController`; only `AppNavigation.kt`, `AppNavHost.kt`, and `NavControllerNavigator.kt` may reference it in production navigation source.
- Domain objects and movie/series presentation-hint models remain app-owned during Phase 4.
- Phase 4 does not extract feature modules or change player composition, playback lifecycle, stream recovery, or stream policy.
- Use JVM 17 everywhere; Android modules retain compile SDK 36 and minimum SDK 25.
- Add failing tests before implementation changes, run the narrow test after each slice, and commit each independently reviewable task.
- After modifying code, run `graphify update .` from the repository root.

## Planned file structure

```text
core/navigation/
  build.gradle.kts
  src/main/kotlin/com/streamvault/core/navigation/
    AppDestination.kt
    PlayerNavigationRequest.kt
    NavigationRequest.kt
    NavigationActions.kt
  src/test/kotlin/com/streamvault/core/navigation/
    AppDestinationTest.kt
    PlayerNavigationRequestTest.kt
    NavigationRequestTest.kt

app/src/main/java/com/streamvault/app/navigation/
  AppNavigation.kt                  # composition wiring only
  AppNavHost.kt                     # one NavHost plus registration calls
  AppRouteCodec.kt                  # typed destination <-> existing route
  AppNavigationPayloads.kt          # saved-state player/detail payload bridge
  CatalogDetailNavigationActions.kt # app/domain detail hint boundary
  NavControllerNavigator.kt         # only NavController command executor
  AppNavigationLifecycle.kt         # lifecycle-resumed command gate
  AppNavigationCoordinator.kt       # pending startup/external commands
  NavigationCoordinatorModule.kt    # command ID source binding
  StartupNavigationResolver.kt      # repository-backed startup selection
  ExternalNavigationRequestParser.kt# Android Intent -> typed request
  PlayerNavigationAdapters.kt       # domain model -> core player request
  graph/
    WelcomeGraph.kt
    ProviderGraph.kt
    HomeGraph.kt
    LiveGraph.kt
    CatalogGraph.kt
    PlayerGraph.kt
    SystemGraph.kt
```

---

### Task 1: Add the pure `:core:navigation` module and enforce its boundary

**Files:**

- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `core/navigation/build.gradle.kts`

**Interfaces:**

- Consumes: root Kotlin JVM plugin alias and existing JUnit/Truth version-catalog aliases.
- Produces: `:core:navigation`, `:core:navigation:verifyCoreNavigationBoundary`, and root `verifyCoreNavigationBoundary`.

- [ ] **Step 1: Register the module and app dependency**

Add to `settings.gradle.kts`:

```kotlin
include(":core:navigation")
```

Add beside the existing core UI dependency in `app/build.gradle.kts`:

```kotlin
implementation(project(":core:navigation"))
implementation(project(":core:ui"))
```

- [ ] **Step 2: Create the pure Kotlin module and static guard**

Create `core/navigation/build.gradle.kts`:

```kotlin
import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

val bannedCoreNavigationTokens = listOf(
    "com.streamvault.app",
    "com.streamvault.data",
    "com.streamvault.domain",
    "com.streamvault.player",
    "android.",
    "androidx.compose",
    "androidx.navigation",
    "dagger.hilt",
    "MainActivity",
    "NavController",
    "NavHost"
)

val verifyCoreNavigationBoundary = tasks.register("verifyCoreNavigationBoundary") {
    group = "verification"
    description = "Verifies that core navigation contains contracts only."
    notCompatibleWithConfigurationCache(
        "The boundary scan reads resolved Gradle model state at execution time."
    )
    doLast {
        val projectDependencies = configurations
            .flatMap { configuration ->
                configuration.dependencies
                    .withType<ProjectDependency>()
                    .filter { dependency -> dependency.path != project.path }
                    .map { dependency -> "${configuration.name}:${dependency.path}" }
            }
            .distinct()
        check(projectDependencies.isEmpty()) {
            ":core:navigation must not declare project dependencies: ${projectDependencies.joinToString()}"
        }

        val sourceRoot = layout.projectDirectory.asFile.resolve("src/main")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().flatMapIndexed { index, line ->
                    bannedCoreNavigationTokens.filter(line::contains).map { token ->
                        "${file.relativeTo(sourceRoot)}:${index + 1}: $token"
                    }
                }
            }
            .toList()
        check(violations.isEmpty()) {
            ":core:navigation contains forbidden references:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyCoreNavigationBoundary)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
```

- [ ] **Step 3: Add the root verification entry point**

Add to root `build.gradle.kts`:

```kotlin
tasks.register("verifyCoreNavigationBoundary") {
    group = "verification"
    description = "Verifies that :core:navigation remains contract-only."
    dependsOn(":core:navigation:verifyCoreNavigationBoundary")
}
```

- [ ] **Step 4: Run the empty-module checks**

Run:

```powershell
.\gradlew.bat verifyCoreNavigationBoundary :core:navigation:compileKotlin :app:compileDebugKotlin --console=plain
```

Expected: all tasks pass and the guard reports no project dependency or banned-source violation.

- [ ] **Step 5: Commit the module boundary**

```powershell
git add settings.gradle.kts build.gradle.kts app/build.gradle.kts core/navigation/build.gradle.kts
git commit -m "build: add core navigation boundary"
```

### Task 2: Define typed destinations and navigation request contracts

**Files:**

- Create: `core/navigation/src/main/kotlin/com/streamvault/core/navigation/AppDestination.kt`
- Create: `core/navigation/src/main/kotlin/com/streamvault/core/navigation/PlayerNavigationRequest.kt`
- Create: `core/navigation/src/main/kotlin/com/streamvault/core/navigation/NavigationRequest.kt`
- Create: `core/navigation/src/main/kotlin/com/streamvault/core/navigation/NavigationActions.kt`
- Create: `core/navigation/src/test/kotlin/com/streamvault/core/navigation/AppDestinationTest.kt`
- Create: `core/navigation/src/test/kotlin/com/streamvault/core/navigation/PlayerNavigationRequestTest.kt`
- Create: `core/navigation/src/test/kotlin/com/streamvault/core/navigation/NavigationRequestTest.kt`

**Interfaces:**

- Consumes: Java `Serializable` only.
- Produces: `AppDestination`, `PlayerNavigationRequest`, `ExternalNavigationRequest`, `NavigationCommand`, `NavigationOptions`, and `NavigationActions`.

- [ ] **Step 1: Write failing typed-contract tests**

Create tests that refer to the not-yet-created contracts:

```kotlin
class AppDestinationTest {
    @Test
    fun detailDestinationKeepsTypedReturnTarget() {
        val destination = AppDestination.MovieDetail(
            movieId = 42L,
            returnDestination = AppDestination.Search("night")
        )

        assertThat(destination.movieId).isEqualTo(42L)
        assertThat(destination.returnDestination)
            .isEqualTo(AppDestination.Search("night"))
    }
}
```

```kotlin
class PlayerNavigationRequestTest {
    @Test
    fun serializableRoundTripPreservesEpisodeAndReturnIdentity() {
        val request = PlayerNavigationRequest(
            streamUrl = "https://example.com/e.m3u8",
            title = "Episode",
            providerId = 9L,
            contentType = "SERIES_EPISODE",
            returnDestination = AppDestination.SeriesDetail(12L),
            seriesId = 12L,
            seasonNumber = 3,
            episodeNumber = 4,
            episodeId = 77L
        )

        val restored = java.io.ObjectInputStream(
            java.io.ByteArrayInputStream(
                java.io.ByteArrayOutputStream().also { bytes ->
                    java.io.ObjectOutputStream(bytes).use { it.writeObject(request) }
                }.toByteArray()
            )
        ).use { it.readObject() as PlayerNavigationRequest }

        assertThat(restored).isEqualTo(request)
    }
}
```

```kotlin
class NavigationRequestTest {
    @Test
    fun externalAndInternalRequestsRemainTyped() {
        assertThat(ExternalNavigationRequest.Search("sports"))
            .isNotEqualTo(ExternalNavigationRequest.Destination(AppDestination.Home))
        assertThat(NavigationCommand.OpenPlayer(PlayerNavigationRequest("https://x", "X")))
            .isInstanceOf(NavigationCommand.OpenPlayer::class.java)
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

```powershell
.\gradlew.bat :core:navigation:test --tests "com.streamvault.core.navigation.*" --console=plain
```

Expected: compilation fails because the contract types do not exist.

- [ ] **Step 3: Implement the typed destination model**

Create `AppDestination.kt` with these exact variants:

```kotlin
sealed interface AppDestination : Serializable {
    data object Welcome : AppDestination
    data object Home : AppDestination
    data class LiveTv(val categoryId: Long? = null) : AppDestination
    data object Movies : AppDestination
    data object Series : AppDestination
    data object Vod : AppDestination
    data object Downloads : AppDestination
    data class Guide(
        val categoryId: Long? = null,
        val anchorTimeMs: Long? = null,
        val favoritesOnly: Boolean = false
    ) : AppDestination
    data class Settings(val backupUri: String? = null) : AppDestination
    data object Plugins : AppDestination
    data class Search(val query: String? = null) : AppDestination
    data class ProviderSetup(
        val providerId: Long? = null,
        val importUri: String? = null
    ) : AppDestination
    data class MovieDetail(
        val movieId: Long,
        val returnDestination: AppDestination? = null
    ) : AppDestination
    data class SeriesDetail(
        val seriesId: Long,
        val returnDestination: AppDestination? = null
    ) : AppDestination
    data class ParentalControlGroups(val providerId: Long) : AppDestination
    data object Player : AppDestination
    data object MultiView : AppDestination
}
```

Import `java.io.Serializable` and require positive IDs for detail/parental destinations in `init` blocks.

- [ ] **Step 4: Implement player and command contracts**

Move the existing player fields unchanged into core, replacing `returnRoute: String?` with:

```kotlin
val returnDestination: AppDestination? = null
```

Define requests/actions:

```kotlin
sealed interface ExternalNavigationRequest : Serializable {
    data class Search(val query: String) : ExternalNavigationRequest
    data class Player(val request: PlayerNavigationRequest) : ExternalNavigationRequest
    data class Destination(val destination: AppDestination) : ExternalNavigationRequest
    data class ImportM3u(val uri: String) : ExternalNavigationRequest
    data class ImportBackup(val uri: String) : ExternalNavigationRequest
}

data class NavigationOptions(
    val launchSingleTop: Boolean = false,
    val restoreState: Boolean = false,
    val saveState: Boolean = false,
    val popUpTo: AppDestination? = null,
    val inclusive: Boolean = false
) : Serializable

sealed interface NavigationCommand : Serializable {
    data class Navigate(
        val destination: AppDestination,
        val options: NavigationOptions = NavigationOptions()
    ) : NavigationCommand
    data class OpenPlayer(val request: PlayerNavigationRequest) : NavigationCommand
    data object Back : NavigationCommand
    data class ReturnTo(val destination: AppDestination?) : NavigationCommand
}

interface NavigationActions {
    fun navigate(
        destination: AppDestination,
        options: NavigationOptions = NavigationOptions()
    )
    fun openPlayer(request: PlayerNavigationRequest)
    fun back(): Boolean
    fun returnTo(destination: AppDestination?): Boolean
}
```

- [ ] **Step 5: Run core tests and boundary verification**

```powershell
.\gradlew.bat :core:navigation:test verifyCoreNavigationBoundary --console=plain
```

Expected: all core tests and the boundary guard pass.

- [ ] **Step 6: Commit typed contracts**

```powershell
git add core/navigation/src
git commit -m "feat: add typed navigation contracts"
```

### Task 3: Add the app route codec and legacy route compatibility

**Files:**

- Create: `app/src/main/java/com/streamvault/app/navigation/AppRouteCodec.kt`
- Create: `app/src/test/java/com/streamvault/app/navigation/AppRouteCodecTest.kt`
- Modify: `app/src/test/java/com/streamvault/app/navigation/ExternalDestinationTest.kt`
- Modify: `app/src/test/java/com/streamvault/app/navigation/RoutesTest.kt`
- Keep temporarily: `app/src/main/java/com/streamvault/app/navigation/ExternalNavigationRequest.kt` until Task 6 migrates Android intent producers/consumers

**Interfaces:**

- Consumes: `AppDestination` from Task 2.
- Produces: `AppRoutePatterns`, `AppRouteCodec.encode(AppDestination): String`, `AppRouteCodec.decode(String): AppDestination?`, and legacy-route parsing through the codec.

- [ ] **Step 1: Write failing route round-trip tests**

Create parameterized-style assertions without introducing a new test framework:

```kotlin
@Test
fun typedDestinationsPreserveExistingRoutes() {
    val cases = mapOf(
        AppDestination.Home to "home",
        AppDestination.LiveTv(42L) to "live_tv?categoryId=42",
        AppDestination.Guide(21L, 1_700_000_000_000L, true) to
            "epg?categoryId=21&anchorTime=1700000000000&favoritesOnly=true",
        AppDestination.Search("night shift") to "search?query=night%20shift",
        AppDestination.ProviderSetup(7L, "content://playlist/1") to
            "provider_setup?providerId=7&importUri=content%3A%2F%2Fplaylist%2F1"
    )

    cases.forEach { (destination, route) ->
        assertThat(AppRouteCodec.encode(destination)).isEqualTo(route)
        assertThat(AppRouteCodec.decode(route)).isEqualTo(destination)
    }
}

@Test
fun nestedReturnDestinationRoundTrips() {
    val destination = AppDestination.SeriesDetail(
        seriesId = 42L,
        returnDestination = AppDestination.Search("night")
    )
    assertThat(AppRouteCodec.decode(AppRouteCodec.encode(destination)))
        .isEqualTo(destination)
}
```

- [ ] **Step 2: Run the focused route tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.navigation.AppRouteCodecTest --console=plain
```

Expected: compilation fails because `AppRouteCodec` does not exist.

- [ ] **Step 3: Implement route patterns and deterministic encoding**

Move the existing pattern constants into `AppRoutePatterns`. Implement `encode` with the same path/argument names and sentinel values currently used by `Routes`:

```kotlin
internal object AppRoutePatterns {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val LIVE_TV = "live_tv"
    const val LIVE_TV_DESTINATION = "live_tv?categoryId={categoryId}"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val VOD = "vod"
    const val DOWNLOADS = "downloads"
    const val EPG = "epg"
    const val EPG_DESTINATION =
        "epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}"
    const val SETTINGS = "settings"
    const val SETTINGS_DESTINATION = "settings?backupUri={backupUri}"
    const val PLUGINS = "plugins"
    const val PLAYER = "player"
    const val SEARCH = "search"
    const val SEARCH_DESTINATION = "search?query={query}"
    const val PROVIDER_SETUP = "provider_setup?providerId={providerId}&importUri={importUri}"
    const val MOVIE_DETAIL = "movie_detail/{movieId}?returnRoute={returnRoute}"
    const val SERIES_DETAIL = "series_detail/{seriesId}?returnRoute={returnRoute}"
    const val PARENTAL_CONTROL_GROUPS = "parental_control_groups/{providerId}"
    const val MULTI_VIEW = "multi_view"
}
```

Use `android.net.Uri.encode` for values to preserve Android route encoding. Decode query values defensively; malformed values return `null` for that value and never throw.

- [ ] **Step 4: Keep a temporary `Routes` façade while consumers migrate**

Retain an internal compatibility object in `AppRouteCodec.kt` that delegates route construction to typed destinations:

```kotlin
internal object Routes {
    const val PROVIDER_SETUP = AppRoutePatterns.PROVIDER_SETUP
    const val HOME = AppRoutePatterns.HOME
    const val LIVE_TV = AppRoutePatterns.LIVE_TV
    const val LIVE_TV_DESTINATION = AppRoutePatterns.LIVE_TV_DESTINATION
    const val MOVIES = AppRoutePatterns.MOVIES
    const val SERIES = AppRoutePatterns.SERIES
    const val VOD = AppRoutePatterns.VOD
    const val DOWNLOADS = AppRoutePatterns.DOWNLOADS
    const val EPG = AppRoutePatterns.EPG
    const val EPG_DESTINATION = AppRoutePatterns.EPG_DESTINATION
    const val SETTINGS = AppRoutePatterns.SETTINGS
    const val SETTINGS_DESTINATION = AppRoutePatterns.SETTINGS_DESTINATION
    const val PLUGINS = AppRoutePatterns.PLUGINS
    const val PLAYER = AppRoutePatterns.PLAYER
    const val SEARCH = AppRoutePatterns.SEARCH
    const val SEARCH_DESTINATION = AppRoutePatterns.SEARCH_DESTINATION
    const val MOVIE_DETAIL = AppRoutePatterns.MOVIE_DETAIL
    const val SERIES_DETAIL = AppRoutePatterns.SERIES_DETAIL
    const val WELCOME = AppRoutePatterns.WELCOME
    const val PARENTAL_CONTROL_GROUPS = AppRoutePatterns.PARENTAL_CONTROL_GROUPS
    const val MULTI_VIEW = AppRoutePatterns.MULTI_VIEW

    fun liveTv(categoryId: Long? = null): String =
        AppRouteCodec.encode(AppDestination.LiveTv(categoryId))

    fun epg(categoryId: Long? = null, anchorTime: Long? = null, favoritesOnly: Boolean? = null): String =
        AppRouteCodec.encode(
            AppDestination.Guide(categoryId, anchorTime, favoritesOnly ?: false)
        )
}
```

Do not keep player/domain factory methods in this façade; Task 4 moves those separately.

- [ ] **Step 5: Migrate legacy external destination tests**

Replace `ExternalDestination.fromLegacyRoute(route)` assertions with `AppRouteCodec.decodeLegacyExternalRoute(route)`. Preserve the existing supported subset: Home, Plugins, Provider Setup, Movie Detail, and Series Detail. Unsupported external routes still return `null`.

- [ ] **Step 6: Run route tests and app compilation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.navigation.*Route*Test" :app:compileDebugKotlin --console=plain
```

Expected: route/legacy tests pass and existing route consumers still compile through the compatibility façade.

- [ ] **Step 7: Commit route compatibility**

```powershell
git add app/src/main/java/com/streamvault/app/navigation/AppRouteCodec.kt app/src/test/java/com/streamvault/app/navigation
git commit -m "refactor: add typed app route codec"
```

### Task 4: Move player requests to core and isolate domain adapters

**Files:**

- Create: `app/src/main/java/com/streamvault/app/navigation/PlayerNavigationAdapters.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/PlayerNavigationPolicy.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigationGraph.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigationAdapters.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigationStartupPolicy.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/ExternalNavigationRequest.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/PlaybackHistoryNavigation.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigationContracts.kt`
- Modify: `app/src/main/java/com/streamvault/app/MainActivity.kt`
- Modify: `app/src/main/java/com/streamvault/app/tv/LauncherRecommendationsManager.kt`
- Modify: `app/src/main/java/com/streamvault/app/tvinput/TvInputChannelSyncManager.kt`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsRecordingSection.kt`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt`
- Modify: `app/src/test/java/com/streamvault/app/navigation/RoutesTest.kt`
- Modify: `app/src/test/java/com/streamvault/app/navigation/PlayerNavigationRequestTest.kt`
- Modify: `app/src/test/java/com/streamvault/app/navigation/PlaybackHistoryNavigationTest.kt`

**Interfaces:**

- Consumes: core `PlayerNavigationRequest` and typed return destinations.
- Produces: `Channel.toLivePlayerRequest`, `Movie.toPlayerNavigationRequest`, `Episode.toPlayerNavigationRequest`, `PlaybackHistory.toPlayerNavigationRequest`, `playerNavigationRequest`, and `safePlayerNavigationRequest`.

- [ ] **Step 1: Convert existing player tests to typed returns and core imports**

Update the guide-return test:

```kotlin
val request = channel.toLivePlayerRequest(
    categoryId = 9L,
    providerId = 7L,
    returnDestination = AppDestination.Guide(
        categoryId = 9L,
        anchorTimeMs = 1_700_000_360_000L,
        favoritesOnly = false
    )
)
assertThat(request.returnDestination)
    .isEqualTo(AppDestination.Guide(9L, 1_700_000_360_000L, false))
```

Import `PlayerNavigationRequest` from `com.streamvault.core.navigation` in player safety/history tests.

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.navigation.RoutesTest --tests com.streamvault.app.navigation.PlayerNavigationRequestTest --tests com.streamvault.app.navigation.PlaybackHistoryNavigationTest --console=plain
```

Expected: compilation fails because the typed adapter functions do not exist and old requests still use `returnRoute`.

- [ ] **Step 3: Implement domain-to-core factories**

Create adapters with the existing field mapping:

```kotlin
internal fun Channel.toLivePlayerRequest(
    categoryId: Long? = this.categoryId,
    providerId: Long? = this.providerId,
    isVirtual: Boolean = false,
    combinedProfileId: Long? = null,
    combinedSourceFilterProviderId: Long? = null,
    returnDestination: AppDestination? = null
): PlayerNavigationRequest = playerNavigationRequest(
    streamUrl = streamUrl,
    title = name,
    channelId = epgChannelId,
    internalId = id,
    categoryId = categoryId ?: ChannelRepository.ALL_CHANNELS_ID,
    providerId = providerId,
    isVirtual = isVirtual,
    combinedProfileId = combinedProfileId,
    combinedSourceFilterProviderId = combinedSourceFilterProviderId,
    contentType = "LIVE",
    returnDestination = returnDestination
)
```

Add these three domain factories with exact mappings:

```kotlin
internal fun Movie.toPlayerNavigationRequest(
    returnDestination: AppDestination? = null
): PlayerNavigationRequest = playerNavigationRequest(
    streamUrl = streamUrl,
    title = name,
    internalId = id,
    categoryId = categoryId,
    providerId = providerId,
    contentType = "MOVIE",
    artworkUrl = posterUrl ?: backdropUrl,
    returnDestination = returnDestination
)

internal fun Episode.toPlayerNavigationRequest(
    returnDestination: AppDestination? = null
): PlayerNavigationRequest = playerNavigationRequest(
    streamUrl = streamUrl,
    title = "$title - S${seasonNumber}E${episodeNumber}",
    internalId = id,
    providerId = providerId,
    contentType = "SERIES_EPISODE",
    artworkUrl = coverUrl,
    returnDestination = returnDestination,
    seriesId = seriesId.takeIf { it > 0L },
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeId = episodeId.takeIf { it > 0L }
)

internal fun PlaybackHistory.toPlayerNavigationRequest(
    returnDestination: AppDestination? = null
): PlayerNavigationRequest = playerNavigationRequest(
    streamUrl = streamUrl,
    title = title,
    internalId = contentId,
    providerId = providerId,
    contentType = contentType.name,
    artworkUrl = posterUrl,
    returnDestination = returnDestination,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber
)
```

`playerNavigationRequest(...)` is the single generic/archive factory. Its parameters expose every existing primitive field, including `archiveStartMs`, `archiveEndMs`, `archiveTitle`, and `returnDestination`; it constructs the core request without deriving metadata.

- [ ] **Step 4: Move URL safety into a focused app policy file**

Move the existing scheme allowlist unchanged to `PlayerNavigationPolicy.kt` and import the core request type. Keep the accepted schemes:

```kotlin
setOf("http", "https", "rtsp", "rtmp", "rtsps", "mms", "xtream", "stalker", "content", "file")
```

- [ ] **Step 5: Migrate consumers mechanically**

Replace app-contract imports with `com.streamvault.core.navigation.PlayerNavigationRequest` in MainActivity, launcher recommendations, Watch Next, TV input, player screen/tests, and settings playback entry points. Replace `Routes.livePlayer/moviePlayer/episodePlayer/player` calls with the new adapters.

Change `PlayerScreen` parameters from:

```kotlin
returnRoute: String? = null,
onNavigate: ((String) -> Unit)? = null
```

to:

```kotlin
returnDestination: AppDestination? = null,
onNavigate: ((AppDestination) -> Unit)? = null
```

The guide-notice action invokes `onNavigate(returnDestination)` only when both are non-null.

- [ ] **Step 6: Remove player/domain content from `AppNavigationContracts.kt`**

Delete the old `PlayerNavigationRequest`, player factory methods, and URL policy. Leave only temporary route aliases not yet migrated by Task 8; delete the file completely once no declarations remain.

- [ ] **Step 7: Run player/navigation tests and compilation**

```powershell
.\gradlew.bat :core:navigation:test :app:testDebugUnitTest --tests "com.streamvault.app.navigation.*" :app:compileDebugKotlin --console=plain
```

Expected: all focused tests pass; player and external intent producers compile against the core request.

- [ ] **Step 8: Commit player request extraction**

```powershell
git add core/navigation app/src/main app/src/test
git commit -m "refactor: move player navigation contracts to core"
```

### Task 5: Centralize Android controller execution and typed return behavior

**Files:**

- Create: `app/src/main/java/com/streamvault/app/navigation/NavControllerNavigator.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/AppNavigationPayloads.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/CatalogDetailNavigationActions.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/ReturnNavigationPolicy.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/AppNavigationLifecycle.kt`
- Create: `app/src/test/java/com/streamvault/app/navigation/ReturnNavigationPolicyTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigationAdapters.kt`

**Interfaces:**

- Consumes: core `NavigationActions`, `NavigationCommand`, `NavigationOptions`, destinations, and requests; app `AppRouteCodec`.
- Produces: `NavControllerNavigator.execute(command): Boolean`, typed feature actions, and app-owned payload access for player/detail destinations.

The payload boundary exposed to graph registrations is:

```kotlin
internal interface AppNavigationPayloads {
    fun consumePlayerRequest(entry: NavBackStackEntry): PlayerNavigationRequest?
    fun consumeMoviePresentationHint(entry: NavBackStackEntry): MovieDetailPresentationHint?
    fun consumeSeriesPresentationHint(entry: NavBackStackEntry): SeriesDetailPresentationHint?
}
```

Keep domain presentation hints behind a separate app-owned action boundary:

```kotlin
internal interface CatalogDetailNavigationActions {
    fun openMovieDetail(movie: Movie, returnDestination: AppDestination? = null)
    fun openSeriesDetail(series: Series, returnDestination: AppDestination? = null)
}
```

`NavControllerNavigator` implements both `NavigationActions` and `CatalogDetailNavigationActions`. The catalog interface remains in `:app` for Phase 4 because `Movie` and `Series` are domain models; Phase 5 moves/refines it with the catalog feature rather than polluting `:core:navigation`.

- [ ] **Step 1: Write failing pure return-policy tests**

```kotlin
class ReturnNavigationPolicyTest {
    @Test
    fun existingTargetPopsBeforeNavigating() {
        assertThat(planReturnNavigation(hasTargetInBackStack = true, hasPreviousEntry = true))
            .isEqualTo(ReturnNavigationPlan.PopToTarget)
    }

    @Test
    fun missingTargetNavigatesAndClearsPlayer() {
        assertThat(planReturnNavigation(hasTargetInBackStack = false, hasPreviousEntry = true))
            .isEqualTo(ReturnNavigationPlan.NavigateToTarget)
    }

    @Test
    fun missingReturnAndBackStackFallsBackHome() {
        assertThat(planReturnNavigation(hasTargetInBackStack = false, hasPreviousEntry = false))
            .isEqualTo(ReturnNavigationPlan.NavigateHome)
    }
}
```

- [ ] **Step 2: Run the test and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.navigation.ReturnNavigationPolicyTest --console=plain
```

Expected: compilation fails because the policy types do not exist.

- [ ] **Step 3: Implement the pure return plan**

```kotlin
internal enum class ReturnNavigationPlan {
    PopToTarget,
    NavigateToTarget,
    PopPrevious,
    NavigateHome
}

internal fun planReturnNavigation(
    hasReturnTarget: Boolean,
    hasTargetInBackStack: Boolean,
    hasPreviousEntry: Boolean
): ReturnNavigationPlan = when {
    hasReturnTarget && hasTargetInBackStack -> ReturnNavigationPlan.PopToTarget
    hasReturnTarget -> ReturnNavigationPlan.NavigateToTarget
    hasPreviousEntry -> ReturnNavigationPlan.PopPrevious
    else -> ReturnNavigationPlan.NavigateHome
}
```

Update the first two tests to pass `hasReturnTarget = true` and the fallback test to pass `false`.

- [ ] **Step 4: Implement the only root-controller adapter**

`NavControllerNavigator` implements `NavigationActions`, `CatalogDetailNavigationActions`, and `AppNavigationPayloads`, and adds:

```kotlin
internal fun execute(command: NavigationCommand): Boolean
internal fun navigateIfResumed(
    destination: AppDestination,
    options: NavigationOptions = NavigationOptions()
): Boolean
internal fun consumePlayerRequest(entry: NavBackStackEntry): PlayerNavigationRequest?
internal fun consumeMoviePresentationHint(entry: NavBackStackEntry): MovieDetailPresentationHint?
internal fun consumeSeriesPresentationHint(entry: NavBackStackEntry): SeriesDetailPresentationHint?
```

All route conversion goes through `AppRouteCodec`. `openPlayer` writes `PLAYER_REQUEST_KEY` to the current entry before navigating to `AppDestination.Player`. Detail helpers keep the existing presentation-hint keys and previous-entry copy behavior inside this adapter.

- [ ] **Step 5: Replace old controller extensions**

Move `Lifecycle.awaitResumed` unchanged to `AppNavigationLifecycle.kt`. Remove `navigateToPlayer`, `navigateToExternalPlayer`, `navigateToMovieDetail`, and `navigateToSeriesDetail` controller extensions after their call sites move to typed actions. Keep domain presentation-hint conversion in `AppNavigationPayloads.kt`.

- [ ] **Step 6: Run policy tests and compile**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.navigation.ReturnNavigationPolicyTest :app:compileDebugKotlin --console=plain
```

Expected: pass.

- [ ] **Step 7: Commit the controller adapter**

```powershell
git add app/src/main/java/com/streamvault/app/navigation app/src/test/java/com/streamvault/app/navigation/ReturnNavigationPolicyTest.kt
git commit -m "refactor: centralize typed navigation execution"
```

### Task 6: Extract external intent parsing and pending command coordination

**Files:**

- Create: `app/src/main/java/com/streamvault/app/navigation/ExternalNavigationRequestParser.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/AppNavigationCoordinator.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/NavigationCoordinatorModule.kt`
- Create: `app/src/test/java/com/streamvault/app/navigation/ExternalNavigationRequestParserTest.kt`
- Create: `app/src/test/java/com/streamvault/app/navigation/AppNavigationCoordinatorTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/MainActivity.kt`
- Modify: `app/src/main/java/com/streamvault/app/tv/LauncherRecommendationsManager.kt`
- Modify: `app/src/main/java/com/streamvault/app/tvinput/TvInputSetupActivity.kt`
- Modify: `app/src/androidTest/java/com/streamvault/app/compat/PlatformCompatibilityMatrixTest.kt`
- Delete: `app/src/main/java/com/streamvault/app/navigation/AppNavigationExternalNavigation.kt`
- Delete: `app/src/main/java/com/streamvault/app/navigation/ExternalNavigationRequest.kt`

**Interfaces:**

- Consumes: core external requests/commands and app route codec.
- Produces: `ExternalNavigationRequestParser.parse(Intent): ExternalNavigationRequest?`, `PendingNavigationCommand`, and coordinator submit/acknowledge flows.

- [ ] **Step 1: Write failing intent parser tests with Robolectric**

Cover player, typed destination, legacy route, search, and malformed fallback:

```kotlin
@RunWith(RobolectricTestRunner::class)
class ExternalNavigationRequestParserTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val parser = ExternalNavigationRequestParser(context)

    @Test
    fun searchIntentBecomesTypedSearchRequest() {
        val intent = Intent(Intent.ACTION_SEARCH)
            .putExtra(SearchManager.QUERY, " sports ")
        assertThat(parser.parse(intent))
            .isEqualTo(ExternalNavigationRequest.Search("sports"))
    }

    @Test
    fun malformedLegacyRouteFallsBackHome() {
        val intent = Intent().putExtra(MainActivity.EXTRA_EXTERNAL_ROUTE, "bad/route")
        assertThat(parser.parse(intent)).isEqualTo(
            ExternalNavigationRequest.Destination(AppDestination.Home)
        )
    }
}
```

Add these concrete cases:

- `ACTION_VIEW` with `audio/x-mpegurl` and a content URI produces `ImportM3u`.
- `ACTION_VIEW` with an `.m3u8` path and no MIME type produces `ImportM3u`.
- `ACTION_SEND` with JSON MIME and `EXTRA_STREAM` produces `ImportBackup` using the URI returned by `BackupFileBridge`.
- `EXTRA_PLAYER_REQUEST` produces `Player` and takes precedence over all later inputs.
- `EXTRA_EXTERNAL_DESTINATION` produces the matching typed `Destination`.
- A generic `ACTION_VIEW` that is neither playlist nor backup produces `Destination(Home)`.
- A blank search/assistant/voice query produces no search request.

- [ ] **Step 2: Write failing coordinator idempotence tests**

```kotlin
@Test
fun commandIsClearedOnlyAfterMatchingAcknowledgement() = runTest {
    val ids = sequenceOf(10L, 11L).iterator()
    val coordinator = AppNavigationCoordinator(NavigationCommandIdSource { ids.next() })
    coordinator.submitExternalRequest(ExternalNavigationRequest.Search("news"))

    val pending = coordinator.pendingCommand.value
    assertThat(pending?.id).isEqualTo(10L)
    coordinator.acknowledge(11L)
    assertThat(coordinator.pendingCommand.value).isEqualTo(pending)
    coordinator.acknowledge(10L)
    assertThat(coordinator.pendingCommand.value).isNull()
}
```

- [ ] **Step 3: Run tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.navigation.ExternalNavigationRequestParserTest --tests com.streamvault.app.navigation.AppNavigationCoordinatorTest --console=plain
```

Expected: compilation fails because parser/coordinator types do not exist.

- [ ] **Step 4: Implement the app-owned Android parser**

Use this boundary:

```kotlin
class ExternalNavigationRequestParser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun parse(intent: Intent): ExternalNavigationRequest?
}
```

Move these exact operations from `MainActivity` into private parser helpers while preserving precedence and accepted inputs:

- `readPlayerRequestExtra`
- typed destination extra reading
- legacy route handling
- playlist URI validation
- backup JSON candidate validation and `BackupFileBridge.copyToImportInbox(context, uri)`
- search/assistant/voice query handling
- generic `ACTION_VIEW` Home fallback

Keep `EXTRA_PLAYER_REQUEST`, `EXTRA_EXTERNAL_DESTINATION`, and `EXTRA_EXTERNAL_ROUTE` in `MainActivity` for binary compatibility and preserve their exact string values.

Migrate `LauncherRecommendationsManager` and `TvInputSetupActivity` to serialize core `AppDestination` values under `EXTRA_EXTERNAL_DESTINATION`, and have the parser read that exact type. Update `PlatformCompatibilityMatrixTest` to exercise `AppRouteCodec.decodeLegacyExternalRoute`. Once those producers, parser tests, and compatibility tests compile, delete the app-owned `ExternalDestination` and duplicate `ExternalNavigationRequest` declarations together.

Add the pure conversion used by the coordinator:

```kotlin
internal fun ExternalNavigationRequest.toNavigationCommand(): NavigationCommand = when (this) {
    is ExternalNavigationRequest.Search -> NavigationCommand.Navigate(
        destination = AppDestination.Search(query),
        options = NavigationOptions(launchSingleTop = true)
    )
    is ExternalNavigationRequest.Player -> NavigationCommand.OpenPlayer(request)
    is ExternalNavigationRequest.Destination -> NavigationCommand.Navigate(
        destination = destination,
        options = NavigationOptions(launchSingleTop = true)
    )
    is ExternalNavigationRequest.ImportM3u -> NavigationCommand.Navigate(
        destination = AppDestination.ProviderSetup(importUri = uri),
        options = NavigationOptions(launchSingleTop = true)
    )
    is ExternalNavigationRequest.ImportBackup -> NavigationCommand.Navigate(
        destination = AppDestination.Settings(backupUri = uri),
        options = NavigationOptions(launchSingleTop = true)
    )
}
```

- [ ] **Step 5: Implement coordinator pending-command state**

```kotlin
internal data class PendingNavigationCommand(
    val id: Long,
    val command: NavigationCommand
)

@HiltViewModel
class AppNavigationCoordinator @Inject constructor(
    private val commandIds: NavigationCommandIdSource
) : ViewModel() {
    private val commandQueue = ArrayDeque<PendingNavigationCommand>()
    private val _pendingCommand = MutableStateFlow<PendingNavigationCommand?>(null)
    val pendingCommand: StateFlow<PendingNavigationCommand?> = _pendingCommand.asStateFlow()

    fun submitExternalRequest(request: ExternalNavigationRequest)
    fun submit(command: NavigationCommand)
    fun acknowledge(id: Long)
}

internal fun interface NavigationCommandIdSource {
    fun next(): Long
}

internal class AtomicNavigationCommandIdSource @Inject constructor() : NavigationCommandIdSource {
    private val nextId = AtomicLong(0L)
    override fun next(): Long = nextId.incrementAndGet()
}
```

`submit` appends to `commandQueue` and publishes the head only when no command is active. `acknowledge` removes only a matching head, then publishes the next queued command. This prevents a second intent from overwriting an unacknowledged first intent. Add a test that submits two searches, acknowledges the first, and observes the second.

Bind the ID source explicitly:

```kotlin
@Module
@InstallIn(ActivityRetainedComponent::class)
internal abstract class NavigationCoordinatorModule {
    @Binds
    @ActivityRetainedScoped
    abstract fun bindNavigationCommandIdSource(
        implementation: AtomicNavigationCommandIdSource
    ): NavigationCommandIdSource
}
```

- [ ] **Step 6: Wire MainActivity submission without changing Compose yet**

Inject `ExternalNavigationRequestParser`, obtain the coordinator with `private val appNavigationCoordinator by viewModels<AppNavigationCoordinator>()`, and replace `_externalNavigationRequestFlow` mutation with:

```kotlin
intent?.let(parser::parse)?.let(appNavigationCoordinator::submitExternalRequest)
```

Keep `openPlayer(request)` as a compatibility method that submits `ExternalNavigationRequest.Player(request)`. Remove `clearExternalNavigationRequest` and the Activity-owned request flow after `AppNavigation.kt` migrates in Task 9.

- [ ] **Step 7: Run parser/coordinator tests and app compilation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.navigation.ExternalNavigationRequestParserTest --tests com.streamvault.app.navigation.AppNavigationCoordinatorTest :app:compileDebugKotlin --console=plain
```

Expected: pass.

- [ ] **Step 8: Commit external orchestration**

```powershell
git add app/src/main app/src/test/java/com/streamvault/app/navigation app/src/androidTest/java/com/streamvault/app/compat/PlatformCompatibilityMatrixTest.kt
git commit -m "refactor: extract external navigation coordination"
```

### Task 7: Extract startup navigation resolution from the root composable

**Files:**

- Create: `app/src/main/java/com/streamvault/app/navigation/StartupNavigationResolver.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/AppNavigationState.kt`
- Create: `app/src/test/java/com/streamvault/app/navigation/StartupNavigationResolverTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigationCoordinator.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigationStartupPolicy.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigationPolicy.kt`

**Interfaces:**

- Consumes: existing repositories and landing/catalog domain preferences inside `:app`.
- Produces: `StartupNavigationResolver.resolve(AppLandingDestination): StartupNavigationTarget`, coordinator `state`, startup command idempotence, and typed catalog destination resolution.

- [ ] **Step 1: Write failing resolver tests**

Use Mockito Kotlin repositories and concrete channels/history. Start with this complete favorite fixture:

```kotlin
@Test
fun firstFavoriteProducesVirtualLiveRequestReturningToLive() = runTest {
    whenever(preferencesRepository.showFavoritesCategory).thenReturn(flowOf(true))
    whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
        flowOf(ActiveLiveSource.ProviderSource(7L))
    )
    whenever(favoriteRepository.getFavorites(7L, ContentType.LIVE)).thenReturn(
        flowOf(
            listOf(
                Favorite(
                    providerId = 7L,
                    contentId = 42L,
                    contentType = ContentType.LIVE
                )
            )
        )
    )
    whenever(preferencesRepository.getHiddenChannelIds(7L)).thenReturn(flowOf(emptySet()))
    whenever(channelRepository.getChannel(42L)).thenReturn(
        Channel(
            id = 42L,
            name = "News",
            streamUrl = "https://example.com/live.m3u8",
            providerId = 7L
        )
    )

    val result = resolver.resolve(AppLandingDestination.FIRST_FAVORITE_LIVE)

    assertThat(result.destination).isEqualTo(AppDestination.LiveTv())
    assertThat(result.playerRequest?.internalId).isEqualTo(42L)
    assertThat(result.playerRequest?.categoryId).isEqualTo(VirtualCategoryIds.FAVORITES)
    assertThat(result.playerRequest?.returnDestination).isEqualTo(AppDestination.LiveTv())
}
```

Add these exact resolver cases:

- `HOME` returns `StartupNavigationTarget(AppDestination.Home, playerRequest = null)`.
- Two Live playback-history entries with `lastWatchedAt` values `100L` and `200L` select the `200L` channel.
- A selected channel ID present in `getHiddenChannelIds(providerId)` is skipped and produces no player request when no visible candidate remains.
- A combined profile with enabled providers `7L` and `8L` sets `combinedProfileId`, accepts channels only from those enabled members, and ignores disabled/non-member providers.
- No active live source and no active provider produces the typed landing destination with no player request.
- A candidate ID whose `ChannelRepository.getChannel` result is `null` produces no player request.

- [ ] **Step 2: Run resolver test and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.navigation.StartupNavigationResolverTest --console=plain
```

Expected: compilation fails because the resolver does not exist.

- [ ] **Step 3: Implement explicit startup result and resolver**

```kotlin
internal data class StartupNavigationTarget(
    val destination: AppDestination,
    val playerRequest: PlayerNavigationRequest? = null
)
```

Move the repository logic from `resolveStartupPlayerRequest` into an injected resolver class. Preserve active provider/combined source selection, favorite ordering, playback-history ordering, hidden-channel filtering, virtual category IDs, and provider/profile IDs exactly.

- [ ] **Step 4: Add coordinator state and one-shot startup commands**

Expand the coordinator constructor in this task:

```kotlin
@HiltViewModel
class AppNavigationCoordinator @Inject constructor(
    private val commandIds: NavigationCommandIdSource,
    private val startupResolver: StartupNavigationResolver,
    private val preferencesRepository: PreferencesRepository,
    private val providerRepository: ProviderRepository
) : ViewModel()
```

```kotlin
internal data class AppNavigationState(
    val startupTarget: StartupNavigationTarget? = null,
    val topLevelDestinations: List<AppTopLevelDestination> = AppTopLevelDestination.defaultOrder,
    val catalogLayout: CatalogLayout? = null,
    val lastSplitCatalogType: ContentType = ContentType.MOVIE,
    val splitPreferenceReady: Boolean = false
)
```

The Hilt ViewModel coordinator observes the existing preference/provider flows in `viewModelScope`, resolves startup once per active provider/configuration, and exposes typed top-level/catalog navigation helpers. Implement the startup handshake explicitly:

```kotlin
fun requestStartupNavigation(popUpTo: AppDestination)
fun onDestinationResumed(destination: AppDestination)
```

`requestStartupNavigation` submits the resolved landing destination with `NavigationOptions(popUpTo = popUpTo, inclusive = true)` and remembers that startup transition. When its command is acknowledged, retain—not enqueue—the optional startup player request. `onDestinationResumed(AppDestination.LiveTv(...))` then enqueues that player request exactly once; any other destination leaves it pending. Extend `AppNavigationCoordinatorTest` with assertions for repeated requests, non-Live resumed destinations, matching Live resumption, acknowledgement, and reattaching collectors to the same coordinator without duplicate commands. The Hilt ViewModel ownership is what retains that coordinator across Activity recreation.

- [ ] **Step 5: Convert catalog policy to typed destinations**

Change `resolveCatalogRoute` into:

```kotlin
internal fun resolveCatalogDestination(
    layout: CatalogLayout?,
    requested: AppDestination,
    lastSplitCatalogType: ContentType,
    splitPreferenceReady: Boolean
): AppDestination
```

Preserve the SPLIT/UNIFIED/UNKNOWN behavior covered by `CatalogRouteResolverTest` and update the test to use `AppDestination.Movies`, `Series`, and `Vod`.

Expose `fun requestTopLevelNavigation(requested: AppDestination)` on the coordinator. It resolves through `resolveCatalogDestination`, persists `MOVIE`/`SERIES` as the provider's last split catalog type when applicable, and submits a `Navigate` command with `popUpTo = AppDestination.Welcome`, `saveState = true`, `launchSingleTop = true`, and `restoreState = true`. This replaces the old root `tabNavigate` branch.

- [ ] **Step 6: Run startup/catalog tests and compilation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.navigation.StartupNavigationResolverTest --tests com.streamvault.app.navigation.CatalogRouteResolverTest :app:compileDebugKotlin --console=plain
```

Expected: pass.

- [ ] **Step 7: Commit startup orchestration**

```powershell
git add app/src/main/java/com/streamvault/app/navigation app/src/test/java/com/streamvault/app/navigation
git commit -m "refactor: extract startup navigation coordination"
```

### Task 8: Split the graph into controller-free feature registration extensions

**Files:**

- Create: `app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/graph/WelcomeGraph.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/graph/ProviderGraph.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/graph/HomeGraph.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/graph/LiveGraph.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/graph/CatalogGraph.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/graph/PlayerGraph.kt`
- Create: `app/src/main/java/com/streamvault/app/navigation/graph/SystemGraph.kt`
- Create: `app/src/test/java/com/streamvault/app/navigation/FeatureGraphBoundaryTest.kt`
- Modify: `app/build.gradle.kts`
- Delete: `app/src/main/java/com/streamvault/app/navigation/AppNavigationGraph.kt`

**Interfaces:**

- Consumes: `NavigationActions`, app-owned `CatalogDetailNavigationActions`, `AppNavigationPayloads`, route patterns, and screen callbacks.
- Produces: `AppNavHost` as registry and seven `NavGraphBuilder.register*Graph` extensions with no root-controller parameter.

- [ ] **Step 1: Add a failing source-boundary test**

Create a JVM test that scans the graph source directory:

```kotlin
@Test
fun featureGraphsDoNotReferenceRootController() {
    val graphRoot = File("src/main/java/com/streamvault/app/navigation/graph")
    val files = graphRoot.walkTopDown()
        .filter { it.extension == "kt" }
        .toList()
    assertThat(files.map { it.name }).containsAtLeast(
        "WelcomeGraph.kt",
        "ProviderGraph.kt",
        "HomeGraph.kt",
        "LiveGraph.kt",
        "CatalogGraph.kt",
        "PlayerGraph.kt",
        "SystemGraph.kt"
    )
    val violations = files
        .filter { "NavHostController" in it.readText() || "NavController" in it.readText() }
        .map { it.name }
        .toList()
    assertThat(violations).isEmpty()
}
```

Also add a Gradle `verifyFeatureNavigationBoundary` task with the same source scan and make `check` depend on it, so the rule applies outside unit-test execution.

- [ ] **Step 2: Run the test and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.navigation.FeatureGraphBoundaryTest --console=plain
```

Expected: the test fails because the seven registration files do not exist yet.

- [ ] **Step 3: Create the registry-only host**

`AppNavHost` must contain one `NavHost` and registration calls only:

```kotlin
@Composable
internal fun AppNavHost(
    navController: NavHostController,
    actions: NavigationActions,
    catalogDetailActions: CatalogDetailNavigationActions,
    payloads: AppNavigationPayloads,
    startupReady: Boolean,
    onStartupNavigationRequested: (popUpTo: AppDestination) -> Unit,
    onTopLevelDestinationRequested: (AppDestination) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = AppRoutePatterns.WELCOME
    ) {
        registerWelcomeGraph(actions, startupReady, onStartupNavigationRequested)
        registerProviderGraph(actions, startupReady, onStartupNavigationRequested)
        registerHomeGraph(actions, catalogDetailActions, onTopLevelDestinationRequested)
        registerLiveGraph(actions)
        registerCatalogGraph(
            actions,
            catalogDetailActions,
            payloads,
            onTopLevelDestinationRequested
        )
        registerPlayerGraph(actions, payloads)
        registerSystemGraph(actions)
    }
}
```

No screen composable call belongs in `AppNavHost.kt`.

- [ ] **Step 4: Move each destination while preserving callbacks and arguments**

Move the existing `composable` blocks with this fixed ownership:

- `WelcomeGraph.kt`: `WELCOME`
- `ProviderGraph.kt`: `PROVIDER_SETUP`
- `HomeGraph.kt`: `HOME`
- `LiveGraph.kt`: `LIVE_TV_DESTINATION` and `EPG_DESTINATION`
- `CatalogGraph.kt`: `MOVIES`, `SERIES`, `VOD`, `SEARCH_DESTINATION`, `MOVIE_DETAIL`, and `SERIES_DETAIL`
- `PlayerGraph.kt`: `PLAYER` and `MULTI_VIEW`
- `SystemGraph.kt`: `DOWNLOADS`, `SETTINGS_DESTINATION`, `PLUGINS`, and `PARENTAL_CONTROL_GROUPS`

Within those blocks, replace navigation operations as follows:

```kotlin
navController.navigateToPlayer(request)
// becomes
actions.openPlayer(request)

navController.popBackStack()
// becomes
actions.back()

navController.navigate(route)
// becomes
actions.navigate(checkNotNull(AppRouteCodec.decode(route)))

navController.navigateToMovieDetail(movie, returnRoute)
// becomes
catalogDetailActions.openMovieDetail(movie, returnRoute?.let(AppRouteCodec::decode))
```

Route arguments continue to come from each destination's `NavBackStackEntry`. Player/detail payload reads use the app-owned `payloads` interface, not a controller parameter. Domain-to-request conversion uses Task 4 adapters.

Top-level shell callbacks decode their current app route once and call `onTopLevelDestinationRequested`; the coordinator applies SPLIT/UNIFIED correction, persists the last split catalog selection, and submits the resulting typed command. They do not call `actions.navigate` directly for tab changes.

- [ ] **Step 5: Keep typed return behavior in player/catalog graphs**

`registerPlayerGraph` passes `safePlayerRequest.returnDestination` to `PlayerScreen` and calls `actions.returnTo` on back. Movie/series detail destinations carry typed return targets and call `actions.returnTo` rather than navigating raw route strings.

- [ ] **Step 6: Run graph guard, compile, and targeted tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.navigation.FeatureGraphBoundaryTest verifyFeatureNavigationBoundary :app:compileDebugKotlin --console=plain
```

Expected: pass, and no file below `navigation/graph` references `NavController`.

- [ ] **Step 7: Commit the graph split**

```powershell
git add app/build.gradle.kts app/src/main/java/com/streamvault/app/navigation app/src/test/java/com/streamvault/app/navigation/FeatureGraphBoundaryTest.kt
git commit -m "refactor: split app navigation graph registry"
```

### Task 9: Reduce the root composable to coordinator/host wiring

**Files:**

- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/streamvault/app/MainActivity.kt`
- Modify: `app/src/main/java/com/streamvault/app/ui/components/shell/AppShellNavigation.kt`
- Delete when empty: `app/src/main/java/com/streamvault/app/navigation/AppNavigationContracts.kt`
- Delete when empty: `app/src/main/java/com/streamvault/app/navigation/AppNavigationAdapters.kt`
- Create: `app/src/androidTest/java/com/streamvault/app/ui/AppNavigationContractTest.kt`

**Interfaces:**

- Consumes: coordinator state/commands, `NavControllerNavigator`, and `AppNavHost`.
- Produces: root `AppNavigation` wiring with no startup/external/domain resolution and connected contract coverage.

- [ ] **Step 1: Add failing connected navigation contract tests**

Use `createComposeRule`, a `TestNavHostController` configured with `ComposeNavigator`, and the same internal `NavigationCommandEffect` used by production. Seed it with `MutableStateFlow<PendingNavigationCommand?>` and an acknowledgement recorder. Cover:

```kotlin
@Test
fun externalSearchCommandNavigatesOnceAndAcknowledges() {
    // Submit Search("news"), resume the host, assert route search?query=news,
    // recompose, then assert only one search destination exists.
}

@Test
fun playerBackReturnsToTypedGuideDestination() {
    // Navigate Guide -> Player(returnDestination = Guide), invoke back,
    // and assert the current typed destination is Guide with original arguments.
}
```

Add these connected cases so every Phase 4 exit path crosses the production command effect:

- a startup `OpenPlayer` command issued while Live TV is resumed opens Player once and Back returns to Live TV;
- a malformed external route converted to `Navigate(Home)` lands on Home and acknowledges once;
- legacy `movie_detail/42?returnRoute=home` parsing followed by command execution lands on Movie Detail with a typed Home return destination;
- recomposition after each successful acknowledgement does not add a duplicate back-stack entry.

- [ ] **Step 2: Run the connected class and verify failure**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.AppNavigationContractTest' --console=plain
```

Expected: the new tests fail until root command collection is wired.

- [ ] **Step 3: Rewrite `AppNavigation` as narrow wiring**

The final function should have this shape:

```kotlin
@Composable
fun AppNavigation(
    coordinator: AppNavigationCoordinator,
    navController: NavHostController = rememberNavController()
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val pending by coordinator.pendingCommand.collectAsStateWithLifecycle()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val navigator = remember(navController) {
        NavControllerNavigator(navController, AppRouteCodec)
    }

    NavigationCommandEffect(
        pending = pending,
        currentBackStackEntry = currentBackStackEntry,
        navigator = navigator,
        acknowledge = coordinator::acknowledge
    )

    DestinationResumedEffect(currentBackStackEntry) { route ->
        AppRouteCodec.decode(route)?.let(coordinator::onDestinationResumed)
    }

    AppNavHost(
        navController = navController,
        actions = navigator,
        catalogDetailActions = navigator,
        payloads = navigator,
        startupReady = state.startupTarget != null,
        onStartupNavigationRequested = coordinator::requestStartupNavigation,
        onTopLevelDestinationRequested = coordinator::requestTopLevelNavigation
    )
}
```

The file must not import domain models or repositories and must not contain route branches.

`NavigationCommandEffect` owns the lifecycle-resumed wait and acknowledges only when `navigator.execute(command.command)` returns `true`. `DestinationResumedEffect` waits for the current entry to reach `RESUMED` before reporting its route. Keeping both as separately testable internal composables gives the connected test the production lifecycle gates without constructing feature screens.

- [ ] **Step 4: Wire MainActivity directly to the coordinator**

Pass the Activity-owned Hilt ViewModel coordinator to `AppNavigation`. Remove repository exposure used only by the old root composable, remove the Activity-owned external request flow, and keep platform-only callbacks such as PiP/cast behavior unchanged.

- [ ] **Step 5: Finish typed consumer migration**

Confirm the Task 4/6 migration left `MainActivity`, `LauncherRecommendationsManager`, and `TvInputChannelSyncManager` on the core request type, then remove any now-empty app-owned compatibility declaration. Keep raw route strings only in `AppRoutePatterns`, `AppRouteCodec`, Android `composable` registrations, and shell selection identifiers. Confirm no feature implementation imports `NavHostController`:

```powershell
rg -n "NavHostController|NavController" app/src/main/java/com/streamvault/app/ui app/src/main/java/com/streamvault/app/navigation/graph --glob '*.kt'
```

Expected: no matches.

- [ ] **Step 6: Run unit, compile, and connected contract checks**

```powershell
.\gradlew.bat verifyCoreNavigationBoundary verifyFeatureNavigationBoundary :core:navigation:test :app:testDebugUnitTest :app:compileDebugKotlin --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.AppNavigationContractTest' --console=plain
```

Expected: all pass.

- [ ] **Step 7: Commit root wiring**

```powershell
git add app/src/main app/src/test app/src/androidTest core/navigation
git commit -m "refactor: coordinate typed app navigation"
```

### Task 10: Full Phase 4 verification, graph refresh, and report

**Files:**

- Create: `docs/COMPOSE_REDUCTION_PHASE4_REPORT.md`
- Modify: `docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md` to link the completed Phase 4 report after the Phase 4 exit criteria
- Update: `graphify-out/` through `graphify update .`

**Interfaces:**

- Consumes: all Phase 4 implementation slices.
- Produces: verification evidence and a Phase 5-ready navigation boundary report.

- [ ] **Step 1: Run static boundary and full JVM/build checks**

```powershell
.\gradlew.bat verifyCoreNavigationBoundary verifyFeatureNavigationBoundary verifyCoreUiBoundary :core:navigation:test :core:ui:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug --console=plain
```

Expected: all tasks pass. Record exact test counts and any configuration-cache warning separately.

- [ ] **Step 2: Run applicable connected coverage on the TV emulator**

```powershell
.\gradlew.bat :core:ui:connectedDebugAndroidTest --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.AppNavigationContractTest' --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.components.shell.ShellGoldenTest' --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.PremiumRouteGoldenTest' --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.PlayerSmokeTest' --console=plain
```

Expected: navigation contract tests, shell goldens, premium routes, and applicable player smoke tests pass. Record any known fixture failure precisely. These commands install the current checkout's debug APK over the same package on the emulator.

- [ ] **Step 3: Confirm the exit-condition source shape**

Run:

```powershell
rg -n "NavHostController|NavController" app/src/main/java/com/streamvault/app/ui app/src/main/java/com/streamvault/app/navigation/graph --glob '*.kt'
rg -n "com\.streamvault\.(app|data|domain|player)|androidx\.navigation|androidx\.compose|dagger\.hilt|NavController|NavHost" core/navigation/src/main --glob '*.kt'
```

Expected: both commands return no matches. Inspect `AppNavHost.kt` and confirm it contains the `NavHost` plus registration calls, not feature screen composables.

- [ ] **Step 4: Refresh the repository graph**

```powershell
graphify update .
```

Expected: graphify rebuilds successfully and the report contains `:core:navigation`, `AppNavigationCoordinator`, `AppNavHost`, and feature registration extensions. An HTML visualization may be skipped because the graph exceeds the configured node limit.

- [ ] **Step 5: Write the Phase 4 report**

Document:

- module/package structure and dependency guards;
- typed destination/player/external contracts;
- route compatibility and legacy parsing;
- coordinator and root-composable responsibilities;
- graph registration ownership and Phase 5 move units;
- startup, deep-link, player return, and external-navigation test evidence;
- unit/build/golden/player smoke results;
- warnings or unavailable gates;
- explicit confirmation that no player lifecycle/recovery behavior changed.

- [ ] **Step 6: Run final diff checks and commit the report**

```powershell
git diff --check
git status --short
git add docs/COMPOSE_REDUCTION_PHASE4_REPORT.md docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md graphify-out
git commit -m "docs: report phase 4 navigation architecture"
```

If `graphify-out` is ignored or unchanged, omit it from `git add`. Expected: clean worktree after the commit.
