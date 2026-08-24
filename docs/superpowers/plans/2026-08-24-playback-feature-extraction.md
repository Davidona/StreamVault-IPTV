# Playback Feature Module Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Extract Player and MultiView presentation, state, navigation registration, resources, and tests into :feature:playback while preserving every playback, focus, lifecycle, recovery, and navigation behavior.

**Architecture:** :app remains the navigation/platform composition root and supplies saved-state payload plus Android service adapters. :feature:playback owns its graph, Player/MultiView presentation, ViewModels, coordinators, Cast implementation, resources, and tests; :player owns engine capabilities and render binding; shared generic UI and pure policies move to :core:ui and :domain only where ownership is already cross-feature.

**Tech Stack:** Kotlin 2.2/JVM 17, Android compile SDK 36/min SDK 25, Compose/TV Compose, AndroidX Navigation Compose 2.9.7, Hilt 2.56.2/KSP, Media3 through :player, coroutines/StateFlow, Coil 3, JUnit 4, Truth, Mockito Kotlin, Robolectric, Compose instrumentation, Gradle boundary tasks, Macrobenchmark/Baseline Profile tooling, ADB, and graphify.

**Spec:** docs/superpowers/specs/2026-08-24-phase-5-feature-module-extraction-design.md

## Global Constraints

- Preserve Player and MultiView visuals, routes, arguments, payload behavior,
  callback order, focus order, semantics/test tags, Back/input policy, effect
  keys, ViewModel scopes, PiP, Cast, permission, recovery, timeshift, and stream
  preparation behavior.
- :feature:playback must not depend on or import :app, MainActivity,
  NavController, or NavHostController.
- Allowed project dependencies are :core:navigation, :core:ui, :domain,
  :player, and temporary :data imports listed in the Phase 5 ledger.
- Do not introduce dependencies between feature implementation modules.
- Keep PlayerTransparentGuideOverlay.kt with app EPG code; it is unreferenced
  and is not part of playback.
- Presentation code must not cast PlayerEngine to Media3PlayerEngine.
- Mechanical moves, package/import changes, contract changes, and separately
  authorized behavior fixes use separate commits.
- Use history-preserving moves. Do not remove the app destination until the
  feature destination passes scoped checks.
- Run two-channel live validation after final integration: 61 screenshots per
  channel, two-second cadence, changing hashes, media session PLAYING with
  error=null, healthy HLS evidence, and no fatal/stuck/MPEG-TS fallback.
- Run graphify update . after code changes.

## Planned file structure

~~~text
feature/playback/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/java/com/streamvault/feature/playback/
    api/PlaybackPlatformHost.kt
    api/PlaybackServicePorts.kt
    navigation/PlaybackGraph.kt
    navigation/PlaybackNavigationPolicy.kt
    navigation/PlaybackRoutePatterns.kt
    cast/
    player/
    player/overlay/
    multiview/
    preview/
    translation/
  src/main/res/values*/strings.xml
  src/test/java/com/streamvault/feature/playback/
  src/androidTest/java/com/streamvault/feature/playback/

app/src/main/java/com/streamvault/app/playback/
  MainActivityPlaybackPlatformHost.kt
  AppPlaybackServiceAdapters.kt
  PlaybackIntegrationModule.kt

player/src/main/java/com/streamvault/player/
  di/PlayerEngineQualifiers.kt
  ui/PlayerRenderView.kt
~~~

---

### Task 1: Record baseline and transitional dependencies

**Files:**

- Create: docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md
- Create: validation/phase5_playback/build-baseline/README.md
- Inspect: app/src/main/java/com/streamvault/app/ui/screens/player/
- Inspect: app/src/main/java/com/streamvault/app/ui/screens/multiview/
- Inspect: app/src/main/java/com/streamvault/app/navigation/graph/PlayerGraph.kt

**Interfaces:**

- Consumes: Phase 0 build procedure and accepted Phase 5 design.
- Produces: source/import/resource/test inventory, temporary dependency list,
  and before-extraction build samples.

- [ ] **Step 1: Capture source/import/resource ownership**

Run:

~~~powershell
$playbackFiles = rg --files app/src/main/java/com/streamvault/app/ui/screens/player app/src/main/java/com/streamvault/app/ui/screens/multiview
$playbackFiles | Set-Content validation/phase5_playback/build-baseline/source-files.txt
rg -n "^import com\.streamvault\.(app|data|domain|player|core)" $playbackFiles | Set-Content validation/phase5_playback/build-baseline/project-imports.txt
rg -n "R\.(string|drawable|plurals|array)\." $playbackFiles | Set-Content validation/phase5_playback/build-baseline/resources.txt
~~~

Expected: both source roots are inventoried and every direct app/data import is
visible before any move.

- [ ] **Step 2: Create the dependency ledger**

Create these initial entries with owner Phase 7:

~~~markdown
# Phase 5 Transitional Dependency Ledger

| Feature | Imported implementation | Current consumer | Removal direction | Owner |
|---|---|---|---|---|
| playback | data.preferences.PreferencesRepository | PlayerPreferencesCoordinator | domain preference contract | Phase 7 |
| playback | data.remote.stalker.StalkerPlaybackResolutionException | PlayerContentResolver | player/domain resolution error | Phase 7 |
| playback | data.remote.xtream.ProviderPlaybackResolver | PlayerContentResolver | domain playback resolver | Phase 7 |
| playback | data.remote.xtream.XtreamStreamKind | alternate stream support | domain/player stream kind | Phase 7 |
| playback | data.remote.xtream.XtreamUrlFactory | alternate stream support | domain/player URL factory | Phase 7 |
| playback | data.security.CredentialDecryptionException | content/provider resolution | domain provider-access error | Phase 7 |
~~~

- [ ] **Step 3: Capture five incremental Player UI runs**

Make and restore a comment-only edit in PlayerOverlayItemKeys.kt. For each
sample run:

~~~powershell
.\gradlew.bat :app:compileDebugKotlin --profile --console=plain
~~~

Record elapsed time and executed tasks in the baseline README.

- [ ] **Step 4: Capture five Player unit-test compile runs**

Make and restore a comment-only edit in PlayerInputPolicyTest.kt. Run:

~~~powershell
.\gradlew.bat :app:compileDebugUnitTestKotlin --profile --console=plain
~~~

- [ ] **Step 5: Commit baseline evidence**

~~~powershell
git add docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md validation/phase5_playback/build-baseline
git commit -m "docs: record playback extraction baseline"
~~~

### Task 2: Move shared playback foundations to stable owners

**Files:**

- Modify: player/build.gradle.kts
- Modify: player/src/main/java/com/streamvault/player/PlayerEngine.kt
- Modify: player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt
- Modify: core/ui/build.gradle.kts
- Move: app/src/main/java/com/streamvault/app/ui/components/PlayerRenderView.kt to player/src/main/java/com/streamvault/player/ui/PlayerRenderView.kt
- Move: app/src/main/java/com/streamvault/app/di/PlayerEngineQualifiers.kt to player/src/main/java/com/streamvault/player/di/PlayerEngineQualifiers.kt
- Move: app/src/main/java/com/streamvault/app/ui/model/ArchivePlayback.kt to domain/src/main/java/com/streamvault/domain/playback/ArchivePlayback.kt
- Move: app/src/main/java/com/streamvault/app/ui/model/ChannelVariantUiHelpers.kt to domain/src/main/java/com/streamvault/domain/playback/ChannelIdentity.kt
- Move: app/src/main/java/com/streamvault/app/util/PlaybackCompletion.kt to domain/src/main/java/com/streamvault/domain/playback/PlaybackCompletion.kt
- Move: app/src/main/java/com/streamvault/app/ui/accessibility/MotionPreferences.kt to core/ui/src/main/java/com/streamvault/core/ui/accessibility/MotionPreferences.kt
- Move: app/src/main/java/com/streamvault/app/ui/components/AsyncImageModels.kt to core/ui/src/main/java/com/streamvault/core/ui/image/AsyncImageModels.kt
- Move: app/src/main/java/com/streamvault/app/ui/components/ChannelLogo.kt to core/ui/src/main/java/com/streamvault/core/ui/image/ChannelLogoBadge.kt
- Move: app/src/main/java/com/streamvault/app/ui/components/dialogs/PremiumDialog.kt to core/ui/src/main/java/com/streamvault/core/ui/components/dialogs/PremiumDialog.kt
- Move: app/src/main/java/com/streamvault/app/ui/notifications/NotificationPermissionGate.kt to core/ui/src/main/java/com/streamvault/core/ui/platform/NotificationPermissionGate.kt
- Create: core/ui/src/main/java/com/streamvault/core/ui/components/dialogs/PinEntryDialog.kt
- Create: core/ui/src/main/java/com/streamvault/core/ui/time/UiTimeFormat.kt
- Create: app/src/main/java/com/streamvault/app/ui/time/UiTimeFormatAdapter.kt
- Modify: app/src/main/java/com/streamvault/app/MainActivity.kt
- Test: player/src/test/java/com/streamvault/player/PlayerEngineFeatureConfigurationTest.kt
- Test: core/ui/src/test/java/com/streamvault/core/ui/time/UiTimeFormatTest.kt
- Move: app/src/test/java/com/streamvault/app/ui/model/ArchivePlaybackTest.kt to domain/src/test/java/com/streamvault/domain/playback/ArchivePlaybackTest.kt
- Move: app/src/test/java/com/streamvault/app/ui/model/ChannelVariantUiHelpersTest.kt to domain/src/test/java/com/streamvault/domain/playback/ChannelIdentityTest.kt

**Interfaces:**

- Produces: app-independent render binding, engine qualifiers, pure
  archive/identity policies, and explicit engine configuration APIs.

- [ ] **Step 1: Add failing engine capability tests**

Tests compile against these new PlayerEngine methods:

~~~kotlin
fun setAudioFocusBypassed(bypassed: Boolean)
fun setResolutionConstrainedForMultiView(constrained: Boolean)
~~~

Run:

~~~powershell
.\gradlew.bat :player:testDebugUnitTest --tests "com.streamvault.player.PlayerEngineFeatureConfigurationTest" --console=plain
~~~

Expected: compilation fails because the methods do not exist.

- [ ] **Step 2: Add API and Media3 implementation**

Add default no-op interface methods for test/fallback engines. Map them in
Media3PlayerEngine:

~~~kotlin
override fun setAudioFocusBypassed(bypassed: Boolean) {
    bypassAudioFocus = bypassed
}

override fun setResolutionConstrainedForMultiView(constrained: Boolean) {
    constrainResolutionForMultiView = constrained
}
~~~

Existing setMediaSessionEnabled remains the media-session contract.

- [ ] **Step 3: Move PlayerRenderView into :player**

Apply the Kotlin Compose plugin, enable Compose, and add Compose BOM/UI
dependencies to :player. Move PlayerRenderView to com.streamvault.player.ui
without changing key, AndroidView, bind, release, or modifier behavior. Update
Home, EPG, Player, and MultiView imports.

- [ ] **Step 4: Move qualifiers and pure policies**

Move MainPlayerEngine and AuxiliaryPlayerEngine to com.streamvault.player.di.
Move archive and channel identity helpers to com.streamvault.domain.playback.
Update imports mechanically without changing branches, constants, defaults, or
ordering.

- [ ] **Step 5: Move generic image/dialog primitives into :core:ui**

Move reduced-motion detection, crossfade image model, ChannelLogoBadge,
PremiumDialog, and NotificationPermissionGate to core packages. Add Coil
Compose and Coil network dependencies to :core:ui. Replace app device imports with
com.streamvault.core.ui.device.rememberIsTelevisionDevice.

Create PinEntryDialog as the current PinDialog implementation with localized
text supplied by callers:

~~~kotlin
@Composable
fun PinEntryDialog(
    title: String,
    cancelLabel: String,
    onDismissRequest: () -> Unit,
    onPinEntered: (String) -> Unit,
    error: String? = null
)
~~~

Keep the app PinDialog as a compatibility wrapper that passes
R.string.pin_dialog_title and R.string.pin_dialog_cancel. MultiView calls
PinEntryDialog with playback-module localized strings.

- [ ] **Step 6: Add the app-independent time rendering contract**

Create:

~~~kotlin
enum class UiTimeFormat {
    SYSTEM,
    TWELVE_HOUR,
    TWENTY_FOUR_HOUR
}

val LocalUiTimeFormat = compositionLocalOf { UiTimeFormat.SYSTEM }

fun UiTimeFormat.createTimeFormat(locale: Locale = Locale.getDefault()): DateFormat
fun UiTimeFormat.createDateTimeFormat(locale: Locale = Locale.getDefault()): DateFormat
fun UiTimeFormat.createTimeFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter
~~~

The app adapter maps each domain AppTimeFormat value one-to-one. MainActivity
provides LocalUiTimeFormat beside the existing LocalAppTimeFormat so unmoved app
consumers remain unchanged. Moved playback code uses only the core local.

- [ ] **Step 7: Run focused/integration checks**

~~~powershell
.\gradlew.bat :domain:test :core:ui:testDebugUnitTest :player:testDebugUnitTest :player:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugKotlin --console=plain
~~~

- [ ] **Step 8: Commit stable-owner moves**

~~~powershell
git add domain core/ui player app/src/main app/src/test
git commit -m "refactor: move playback foundations to stable modules"
~~~

### Task 3: Create :feature:playback and enforce its boundary

**Files:**

- Modify: settings.gradle.kts
- Modify: build.gradle.kts
- Modify: app/build.gradle.kts
- Create: feature/playback/build.gradle.kts
- Create: feature/playback/src/main/AndroidManifest.xml
- Create: feature/playback/src/test/java/com/streamvault/feature/playback/PlaybackModuleBoundaryTest.kt

**Interfaces:**

- Produces: Android library :feature:playback, root/module boundary tasks, app
  dependency, and independent test source sets.

- [ ] **Step 1: Register module and app dependency**

Add:

~~~kotlin
include(":feature:playback")
~~~

Add to :app:

~~~kotlin
implementation(project(":feature:playback"))
~~~

- [ ] **Step 2: Create Android library configuration**

Use namespace com.streamvault.feature.playback, compile SDK 36, min SDK 25,
JVM 17, Compose enabled, Hilt/KSP, and dependencies on the five approved
project modules. Add existing aliases used by moved source: Compose
BOM/UI/Material3/icons/TV, activity-compose, lifecycle runtime/ViewModel
Compose, navigation-compose, Hilt navigation, coroutines, Coil, core-ktx,
MediaRouter/Cast, OkHttp, and current test aliases.

- [ ] **Step 3: Add the boundary task**

Permit only:

~~~kotlin
val allowedProjectDependencies = setOf(
    ":core:navigation",
    ":core:ui",
    ":domain",
    ":player",
    ":data"
)
~~~

Scan src/main and reject import com.streamvault.app, NavHostController, and
NavController. Register root verifyFeaturePlaybackBoundary and attach the
module guard to check.

- [ ] **Step 4: Verify empty module**

~~~powershell
.\gradlew.bat verifyFeaturePlaybackBoundary :feature:playback:compileDebugKotlin :app:compileDebugKotlin --console=plain
~~~

- [ ] **Step 5: Commit module boundary**

~~~powershell
git add settings.gradle.kts build.gradle.kts app/build.gradle.kts feature/playback
git commit -m "build: add playback feature module"
~~~

### Task 4: Extract playback navigation registration

**Files:**

- Create: feature/playback/src/main/java/com/streamvault/feature/playback/navigation/PlaybackRoutePatterns.kt
- Move: app/src/main/java/com/streamvault/app/navigation/graph/PlayerGraph.kt to feature/playback/src/main/java/com/streamvault/feature/playback/navigation/PlaybackGraph.kt
- Move: app/src/main/java/com/streamvault/app/navigation/PlayerNavigationPolicy.kt to feature/playback/src/main/java/com/streamvault/feature/playback/navigation/PlaybackNavigationPolicy.kt
- Modify: app/src/main/java/com/streamvault/app/navigation/AppRouteCodec.kt
- Modify: app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt
- Test: feature/playback/src/test/java/com/streamvault/feature/playback/navigation/PlaybackNavigationPolicyTest.kt
- Test: app/src/test/java/com/streamvault/app/navigation/FeatureGraphBoundaryTest.kt

**Interfaces:**

- Produces: PlaybackRoutePatterns and controller-free registerPlaybackGraph.

- [ ] **Step 1: Move request safety tests**

Verify blank/unsupported requests return null while HTTP, HTTPS, RTSP, RTMP,
RTSPS, MMS, Xtream, Stalker, content, and file requests retain every field.

- [ ] **Step 2: Add route/registration API**

~~~kotlin
object PlaybackRoutePatterns {
    const val PLAYER = "player"
    const val MULTI_VIEW = "multi_view"
}

fun NavGraphBuilder.registerPlaybackGraph(
    actions: NavigationActions,
    platformHost: PlaybackPlatformHost?,
    consumePlayerRequest: (NavBackStackEntry) -> PlayerNavigationRequest?
)
~~~

Keep missing-request LaunchedEffect, Back-first fallback, Home options, Player
argument mapping, return callback, and MultiView replacement options unchanged.

- [ ] **Step 3: Wire app adapter**

Alias AppRoutePatterns.PLAYER and MULTI_VIEW to feature constants. Change
AppNavHost to pass actions, playbackPlatformHost, and
payloads::consumePlayerRequest. The feature does not receive
AppNavigationPayloads.

- [ ] **Step 4: Run navigation checks**

~~~powershell
.\gradlew.bat :feature:playback:testDebugUnitTest :app:testDebugUnitTest --tests "com.streamvault.app.navigation.*" verifyFeaturePlaybackBoundary :app:compileDebugKotlin --console=plain
~~~

- [ ] **Step 5: Commit navigation seam**

~~~powershell
git add feature/playback app/src/main/java/com/streamvault/app/navigation app/src/test/java/com/streamvault/app/navigation
git commit -m "refactor: move playback graph registration to feature"
~~~

### Task 5: Introduce platform and service ports

**Files:**

- Create: feature/playback/src/main/java/com/streamvault/feature/playback/api/PlaybackPlatformHost.kt
- Create: feature/playback/src/main/java/com/streamvault/feature/playback/api/PlaybackServicePorts.kt
- Create: app/src/main/java/com/streamvault/app/playback/MainActivityPlaybackPlatformHost.kt
- Create: app/src/main/java/com/streamvault/app/playback/AppPlaybackServiceAdapters.kt
- Create: app/src/main/java/com/streamvault/app/playback/PlaybackIntegrationModule.kt
- Modify: app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt
- Modify: app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt
- Test: app/src/test/java/com/streamvault/app/playback/MainActivityPlaybackPlatformHostTest.kt
- Test: app/src/test/java/com/streamvault/app/playback/AppPlaybackServiceAdaptersTest.kt

**Interfaces:**

- Produces: PlaybackPlatformHost, PlaybackStreamPreparer, CastUrlRewriter, and
  PlaybackSurfaceRefreshPort with app implementations.

- [ ] **Step 1: Add exact contracts**

Use the signatures in the design spec. PlaybackPlatformHost exposes PiP state
and operations plus setKeepScreenOn. Service ports expose plugin preparation,
Cast URL rewrite, Watch Next progress/refresh, and launcher refresh.

- [ ] **Step 2: Write delegation tests**

Use fakes to verify each call delegates once with unchanged arguments and
returns the underlying result. Include keep-screen-on true/false, PiP state,
plugin error/success, Watch Next progress, and both refresh calls.

- [ ] **Step 3: Implement MainActivity adapter**

Delegate PiP operations to existing Activity methods, Cast route chooser to
openCastRouteChooser, and keep-screen-on to existing window flags. This adapter
is the only playback path that knows MainActivity.

- [ ] **Step 4: Implement service adapters and Hilt bindings**

Adapt StreamVaultPluginManager to PlaybackStreamPreparer and CastUrlRewriter.
Bind PlaybackSurfaceRefreshPort to WatchNextManager and
LauncherRecommendationsManager. Add no policy.

- [ ] **Step 5: Supply host from app composition**

Create/remember the host at AppNavigation's Activity boundary and pass it
through AppNavHost. Preserve null/no-op behavior when context is not
MainActivity.

- [ ] **Step 6: Run adapter checks**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.playback.*" :feature:playback:compileDebugKotlin :app:compileDebugKotlin --console=plain
~~~

- [ ] **Step 7: Commit adapter boundary**

~~~powershell
git add feature/playback/src/main/java/com/streamvault/feature/playback/api app/src/main/java/com/streamvault/app/playback app/src/main/java/com/streamvault/app/navigation app/src/test/java/com/streamvault/app/playback
git commit -m "refactor: add playback platform and service ports"
~~~

### Task 6: Move feature-owned Cast, preview, translation, and UI support

**Files:**

- Move: app/src/main/java/com/streamvault/app/cast/CastModels.kt
- Move: app/src/main/java/com/streamvault/app/cast/CastMediaRequestFactory.kt
- Move: app/src/main/java/com/streamvault/app/cast/CastManager.kt
- Move: app/src/main/java/com/streamvault/app/cast/CastPlaybackCoordinator.kt
- Move: app/src/main/java/com/streamvault/app/di/CastModule.kt
- Move: app/src/main/java/com/streamvault/app/player/LivePreviewHandoffManager.kt
- Move: app/src/main/java/com/streamvault/app/player/LiveTranslationClient.kt
- Move: app/src/main/java/com/streamvault/app/player/LiveTranslationSession.kt
- Move: app/src/main/java/com/streamvault/app/ui/components/dialogs/ProgramHistoryDialog.kt
- Move: app/src/main/java/com/streamvault/app/cast/CastUiMessages.kt
- Move: app/src/test/java/com/streamvault/app/cast/CastMediaRequestFactoryTest.kt to feature/playback/src/test/java/com/streamvault/feature/playback/cast/CastMediaRequestFactoryTest.kt
- Move: app/src/test/java/com/streamvault/app/player/LivePreviewHandoffManagerTest.kt to feature/playback/src/test/java/com/streamvault/feature/playback/preview/LivePreviewHandoffManagerTest.kt
- Move: app/src/test/java/com/streamvault/app/player/LiveTranslationClientTest.kt to feature/playback/src/test/java/com/streamvault/feature/playback/translation/LiveTranslationClientTest.kt
- Move: app/src/test/java/com/streamvault/app/player/LiveTranslationCaptionPacingTest.kt to feature/playback/src/test/java/com/streamvault/feature/playback/translation/LiveTranslationCaptionPacingTest.kt
- Create: feature/playback/src/test/java/com/streamvault/feature/playback/cast/CastUiMessagesTest.kt
- Modify: app/src/main/java/com/streamvault/app/plugins/StreamVaultPluginManager.kt
- Modify: app/src/main/java/com/streamvault/app/MainActivity.kt

**Interfaces:**

- Consumes: Task 5 ports and player/domain/core contracts.
- Produces: feature-owned support implementations with no app imports.

- [ ] **Step 1: Move Cast values and request tests**

Change packages to com.streamvault.feature.playback.cast, update app plugin and
Activity imports, and run Cast request/factory tests before moving the manager.

- [ ] **Step 2: Move Cast manager/coordinator**

Replace CastManager's direct StreamVaultPluginManager dependency with
CastUrlRewriter. Keep initialization, session listener, route selection,
pending request, load, and delayed cancellation behavior unchanged.

- [ ] **Step 3: Move preview and translation services**

Use com.streamvault.feature.playback.preview and
com.streamvault.feature.playback.translation. Update Home/app callers to import
feature services. Preserve scopes, timing constants, and callback order.

- [ ] **Step 4: Move player-specific dialog and Cast message mapping**

Move ProgramHistoryDialog and CastUiMessages. Replace app device/time imports
with stable core equivalents and move referenced Cast message strings into the
feature resources without changing the enum-to-message mapping.

- [ ] **Step 5: Run focused tests and boundary scan**

~~~powershell
.\gradlew.bat :feature:playback:testDebugUnitTest :app:testDebugUnitTest verifyFeaturePlaybackBoundary :app:compileDebugKotlin --console=plain
~~~

- [ ] **Step 6: Commit feature-owned support**

~~~powershell
git add feature/playback app/src/main app/src/test
git commit -m "refactor: move playback support services to feature"
~~~

### Task 7: Move Player presentation, coordinators, resources, and tests

**Files:**

- Move: app/src/main/java/com/streamvault/app/ui/screens/player/*.kt to feature/playback/src/main/java/com/streamvault/feature/playback/player/
- Move: app/src/main/java/com/streamvault/app/ui/screens/player/overlay/*.kt except PlayerTransparentGuideOverlay.kt to feature/playback/src/main/java/com/streamvault/feature/playback/player/overlay/
- Move: app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerTransparentGuideOverlay.kt to app/src/main/java/com/streamvault/app/ui/screens/epg/PlayerTransparentGuideOverlay.kt
- Move: app/src/test/java/com/streamvault/app/ui/screens/player/*.kt to feature/playback/src/test/java/com/streamvault/feature/playback/player/
- Move: app/src/androidTest/java/com/streamvault/app/ui/screens/player/overlay/PlayerOverlayGoldenTest.kt to feature/playback/src/androidTest/java/com/streamvault/feature/playback/player/overlay/PlayerOverlayGoldenTest.kt
- Create/modify: feature/playback/src/main/res/values*/strings.xml
- Modify: app/src/main/res/values*/strings.xml only for playback-exclusive keys with no app consumer

**Interfaces:**

- Consumes: navigation/platform/service ports and stable player/domain/core APIs.
- Produces: independently compiling/testing Player feature with no app imports.

- [ ] **Step 1: Move tests and update packages**

Move Player policy/coordinator tests first, use
com.streamvault.feature.playback.player, and run them to expose remaining
production ownership dependencies.

- [ ] **Step 2: Move production mechanically**

Use history-preserving moves and update package/import names only. Exclude the
transparent EPG guide. Keep branches, constants, coroutine scopes, effect keys,
callback order, state collection, and modifier order unchanged.

- [ ] **Step 3: Replace app dependencies with approved owners/ports**

- PlaybackPlatformHost replaces MainActivity discovery/casts.
- PlaybackStreamPreparer replaces StreamVaultPluginManager.
- PlaybackSurfaceRefreshPort replaces Watch Next/launcher managers.
- com.streamvault.player.di.MainPlayerEngine replaces the app qualifier.
- PlayerEngine capability methods replace Media3PlayerEngine casts.
- Six ledgered data imports remain; no new unrecorded data import is allowed.

- [ ] **Step 4: Copy required resources**

Generate referenced keys:

~~~powershell
rg -o "R\.(string|drawable|plurals|array)\.[A-Za-z0-9_]+" feature/playback/src/main/java | Sort-Object -Unique | Set-Content validation/phase5_playback/playback-resource-keys.txt
~~~

Copy matching values for every existing values-* locale. Remove an app key only
after an rg scan finds no remaining app source/test reference.

- [ ] **Step 5: Run Player feature checks**

~~~powershell
.\gradlew.bat verifyFeaturePlaybackBoundary :feature:playback:compileDebugKotlin :feature:playback:testDebugUnitTest :feature:playback:compileDebugAndroidTestKotlin :app:compileDebugKotlin --console=plain
~~~

- [ ] **Step 6: Confirm forbidden imports are absent**

~~~powershell
rg -n "com\.streamvault\.app|MainActivity|NavController|NavHostController|Media3PlayerEngine" feature/playback/src/main --glob '*.kt'
~~~

Expected: no matches.

- [ ] **Step 7: Commit Player move**

~~~powershell
git add feature/playback app/src/main app/src/test app/src/androidTest validation/phase5_playback/playback-resource-keys.txt
git commit -m "refactor: move player presentation to playback feature"
~~~

### Task 8: Move MultiView and finish graph integration

**Files:**

- Move: app/src/main/java/com/streamvault/app/ui/screens/multiview/*.kt to feature/playback/src/main/java/com/streamvault/feature/playback/multiview/
- Create: feature/playback/src/test/java/com/streamvault/feature/playback/multiview/MultiViewManagerTest.kt
- Create: feature/playback/src/test/java/com/streamvault/feature/playback/multiview/MultiViewViewModelTest.kt
- Modify: feature/playback/src/main/java/com/streamvault/feature/playback/navigation/PlaybackGraph.kt
- Modify: app/src/androidTest/java/com/streamvault/app/ui/PlayerSmokeTest.kt

**Interfaces:**

- Produces: playback graph whose Player and MultiView destinations render
  feature-owned screens.

- [ ] **Step 1: Add MultiView tests before production move**

Cover slot replacement, maximum slots, generation cancellation, blocked
performance policy, audio selection, channel identity lookup, and empty slots.

- [ ] **Step 2: Move MultiView production**

Use feature Cast/dialog/player imports. Inject
@AuxiliaryPlayerEngine Provider<PlayerEngine> from :player. Replace the
concrete Media3 cast with PlayerEngine configuration methods while preserving
stagger delay, generation checks, cleanup, and focus.

- [ ] **Step 3: Wire feature graph**

Import the feature MultiView screen. Preserve Player-to-MultiView inclusive
replacement and Back behavior.

- [ ] **Step 4: Run independent and app compilation**

~~~powershell
.\gradlew.bat :feature:playback:testDebugUnitTest :feature:playback:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug --console=plain
~~~

- [ ] **Step 5: Commit MultiView**

~~~powershell
git add feature/playback app/src/main app/src/test app/src/androidTest
git commit -m "refactor: move multiview to playback feature"
~~~

### Task 9: Remove old app presentation and measure build isolation

**Files:**

- Modify: app/build.gradle.kts
- Modify: build.gradle.kts
- Modify: app/src/test/java/com/streamvault/app/player/PlaybackProgressGuardrailTest.kt
- Modify: docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md
- Create: validation/phase5_playback/build-after/README.md
- Delete: empty old Player/MultiView source/test directories after verification

**Interfaces:**

- Produces: app composition root without Player/MultiView implementation and
  measured independent feature compilation.

- [ ] **Step 1: Run source-shape scans**

~~~powershell
rg -n "ui\.screens\.(player|multiview)" app/src/main/java --glob '*.kt'
rg -n "com\.streamvault\.app|NavHostController|NavController" feature/playback/src/main --glob '*.kt'
~~~

Expected: app matches only documented compatibility adapters; feature has no
matches.

Update PlaybackProgressGuardrailTest so its direct progress-API scan covers
feature/playback/src/main in addition to app/src/main and player/src/main.

- [ ] **Step 2: Run feature-only gate**

~~~powershell
.\gradlew.bat verifyFeaturePlaybackBoundary :feature:playback:testDebugUnitTest :feature:playback:compileDebugAndroidTestKotlin --console=plain
~~~

- [ ] **Step 3: Run app integration gates**

~~~powershell
.\gradlew.bat verifyCoreNavigationBoundary verifyCoreUiBoundary :core:navigation:test :core:ui:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug --console=plain
~~~

- [ ] **Step 4: Capture five after-extraction feature edit runs**

Repeat Task 1's reversible edits against the moved PlayerOverlayItemKeys.kt.
Run five feature-edit and five feature-test samples:

~~~powershell
.\gradlew.bat :feature:playback:compileDebugKotlin :app:compileDebugKotlin --profile --console=plain
.\gradlew.bat :feature:playback:compileDebugUnitTestKotlin --profile --console=plain
~~~

- [ ] **Step 5: Compare guardrails**

Record median/min/max against Task 1 plus clean debug and warm no-change builds.
Do not claim improvement when measurement noise exceeds the difference.

- [ ] **Step 6: Commit cleanup/evidence**

~~~powershell
git add app build.gradle.kts feature/playback docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md validation/phase5_playback/build-after
git commit -m "refactor: complete playback module integration"
~~~

### Task 10: Connected behavior, live playback, profiles, graph, and report

**Files:**

- Create: docs/COMPOSE_REDUCTION_PHASE5_PLAYBACK_REPORT.md
- Modify: docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md
- Update: validation/phase5_playback/
- Update: graphify-out/ through graphify update .
- Regenerate: app/src/main/generated/baselineProfiles/ only through :benchmark

**Interfaces:**

- Produces: final behavior, stability, build-isolation, profile, and graph
  evidence for playback extraction.

- [ ] **Step 1: Run connected feature/app coverage**

Run playback overlay goldens from :feature:playback, app navigation contracts,
and Player smoke coverage on Television_1080p(AVD) - 16. Record every case,
including existing fixture failures, without counting failures as passes.

- [ ] **Step 2: Run manual TV behavior coverage**

Cover player launch/return, remote/Back priority, controls auto-hide, seeking,
modal actions, channel/category/EPG overlays, numeric entry, repeated zapping,
PiP, Cast chooser, MultiView open/focus/slot replacement, mouse/touch, RTL, and
reduced motion where available.

- [ ] **Step 3: Run two-channel long-duration validation**

For each channel capture 61 screenshots at two-second cadence. Record channel
name, screenshot count, unique hashes, final media session, HLS
prepare/read/first-frame or recovery evidence, and prohibited log matches using
AGENTS.md commands.

- [ ] **Step 4: Regenerate and verify profiles**

Use existing :benchmark startup and critical-journey producers. Verify source
separation/subset and beta/release packaging. Keep generated output uncommitted
when the physical-device gate remains open.

- [ ] **Step 5: Refresh graph output**

~~~powershell
graphify update .
~~~

Confirm graph output contains :feature:playback and Player/MultiView source no
longer resides in :app.

- [ ] **Step 6: Write playback report**

Record module ownership, public API, app adapters, moved
files/resources/tests, data ledger, exact checks/results, build measurements,
connected/manual/live evidence, profile status, existing failures, unavailable
gates, and rollback notes. Update the main plan only with evidence.

- [ ] **Step 7: Run final repository checks**

~~~powershell
git diff --check
.\gradlew.bat verifyFeaturePlaybackBoundary verifyCoreNavigationBoundary verifyCoreUiBoundary :feature:playback:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --console=plain
~~~

- [ ] **Step 8: Commit verified report/evidence**

Stage only approved report, plan status, validation metadata, and graph outputs.
Do not stage secrets, provider credentials, or unapproved generated profiles.

~~~powershell
git add docs/COMPOSE_REDUCTION_PHASE5_PLAYBACK_REPORT.md docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md validation/phase5_playback graphify-out
git commit -m "docs: report playback feature extraction"
~~~
