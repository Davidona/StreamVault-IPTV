# Phase 5 Feature Module Extraction Design

Date: 2026-08-24

## Status

Approved design for Phase 5 of the Compose reduction and UI architecture plan.
Phase 5 is split into independently mergeable feature extractions. Playback is
first and establishes the module, navigation, dependency, testing, measurement,
and reporting pattern used by the remaining features.

## Goal

Move feature presentation out of :app so a change within one feature does not
compile unrelated feature source, feature tests run independently, and :app
becomes the Android platform and navigation composition root.

This phase changes module and package ownership. It does not redesign the UI,
convert screens to Views, or change playback preparation, recovery, timeshift,
focus, input, lifecycle, or navigation behavior.

## Accepted scope and sequencing

The extraction order is:

1. :feature:playback
2. :feature:provider
3. :feature:settings
4. :feature:live
5. :feature:catalog
6. :feature:system only when measured source/build impact justifies it

Each extraction has its own implementation plan, review boundary, build
measurement, verification report, and rollback point. Playback is the only
detailed implementation plan produced with this design. Later feature plans
reuse its proven conventions and gates.

Temporary feature dependencies on :data are allowed during Phase 5 when needed
to preserve behavior. Every direct implementation import is recorded in
docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md and removed or
justified in Phase 7. No feature may depend on :app.

## Target dependency direction

~~~text
:app
  -> :feature:playback
  -> later feature modules
  -> :core:navigation / :core:ui / :domain / :data / :player

:feature:playback
  -> :core:navigation
  -> :core:ui
  -> :domain
  -> :player
  -> :data                 # temporary imports recorded in the ledger

:player -> :domain
:core:ui                   # no project dependencies
:core:navigation           # no project dependencies
~~~

Dependencies between feature implementation modules are prohibited. Shared
behavior moves to an existing contract/core module only when it is genuinely
shared and fits that module's responsibility.

## Playback ownership

### :feature:playback owns

- registerPlaybackGraph and playback route patterns.
- Player route/screen, ViewModel, state, policies, coordinators, overlays, and
  feature-specific dialogs.
- MultiView screen, ViewModel, state, manager, planner, and control HUD.
- Playback Cast models, request factory, manager, coordinator, and bindings.
- Live preview handoff and live translation presentation services.
- Playback-specific strings, drawables, tests, and golden fixtures.
- Feature boundary verification and Compose compiler diagnostics.

### :app retains

- MainActivity, root NavHostController, external intent parsing, route
  compatibility decoding, and saved-state payload transport.
- AppNavHost, which imports and calls registerPlaybackGraph.
- Android implementation of the playback host contract for PiP,
  keep-screen-on, and Cast route chooser actions.
- Watch Next and launcher recommendation implementations.
- StreamVaultPluginManager, adapted to feature-owned playback preparation and
  Cast URL rewrite ports through app-owned Hilt bindings.
- Platform services and manifest entry points that are not feature-owned.

### :player owns

- PlayerEngine, Media3PlayerEngine, playback/timeshift policy, render-view
  creation/binding, and engine qualifiers.
- PlayerRenderView, because Live/EPG previews and playback all consume it.
- API methods for primary/MultiView engine configuration. Feature presentation
  must not cast PlayerEngine to Media3PlayerEngine.

### Shared supporting ownership

- Generic image wrappers and dialog visuals move to :core:ui only when their
  API contains rendering inputs rather than app resources or navigation.
- Shared time-format rendering uses a core UI value type and composition local;
  :app maps the persisted domain preference into that value.
- Pure archive capability and channel raw-identity policies move to :domain.
- PlayerTransparentGuideOverlay.kt is excluded from playback. It is currently
  unreferenced and depends on EPG implementation types, so it moves under the
  app EPG package and is reconsidered with :feature:live.

## Playback navigation seam

AppRouteCodec continues to own compatibility encoding/decoding. Playback owns
the stable route patterns and graph registration:

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

AppNavHost passes payloads::consumePlayerRequest. The feature never imports
AppNavigationPayloads, AppRouteCodec, MainActivity, or a root controller. The
existing supported-scheme check moves with the graph and retains the current
fallback: Back first, then Home with Player removed from the stack.

## Platform and service contracts

The playback feature defines these narrow contracts:

~~~kotlin
data class PlaybackPictureInPictureState(
    val enabled: Boolean,
    val isPlaying: Boolean,
    val videoWidth: Int,
    val videoHeight: Int,
    val pixelWidthHeightRatio: Float
)

interface PlaybackPlatformHost {
    val pictureInPictureMode: StateFlow<Boolean>
    fun updatePictureInPictureState(state: PlaybackPictureInPictureState)
    fun clearPictureInPictureState()
    fun enterPictureInPicture(): Boolean
    fun openCastRouteChooser()
    fun setKeepScreenOn(enabled: Boolean)
}

interface PlaybackStreamPreparer {
    suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo>
}

interface CastUrlRewriter {
    suspend fun rewrite(request: CastMediaRequest): String?
}

interface PlaybackSurfaceRefreshPort {
    suspend fun updateWatchNextProgress(history: PlaybackHistory)
    suspend fun refreshWatchNext()
    suspend fun refreshRecommendations()
}
~~~

App adapters delegate to the existing MainActivity, plugin, Watch Next, and
launcher implementations. They add no policy. Player lifecycle effect keys and
callback order remain unchanged when calls are redirected through these ports.

## Source and resource migration

Production and test files move with history where possible. Mechanical moves,
package/import changes, contract changes, and behavior changes use separate
commits. No behavior change is authorized by this design.

Playback source uses com.streamvault.feature.playback.*. Resources referenced
by moved code are copied into the feature for compilation, then removed from
:app only when no remaining app source references the key. Shared keys may
remain duplicated during transition and are recorded for the later owning
feature. Localization values remain equivalent in every existing locale.

Player and MultiView unit tests move into the feature. Navigation compatibility
tests remain in :app. Playback request safety and graph callback tests move into
the feature. Whole-app entry, deep-link, and return tests remain app tests.
Playback overlay goldens move to the feature module.

## Error handling and rollback

- Invalid player requests keep the existing warning and Back/Home fallback.
- Missing platform capability is a nullable host and preserves current no-op
  behavior outside MainActivity.
- Existing plugin preparation, Cast rewrite, Watch Next, and recommendation
  failures retain their results and logging.
- The app route remains wired until feature graph, integration, unit,
  connected, and live playback gates pass.
- Every commit compiles independently. The original implementation is removed
  only after the feature destination is running through AppNavHost.

## Build and runtime validation

For every feature extraction:

- Capture five incremental build runs before and after a representative
  feature-only source edit.
- Capture five incremental unit-test compile runs before and after extraction.
- Record clean debug and warm no-change guardrails.
- Verify a feature-only edit does not compile unrelated feature source.
- Run feature tests independently, then app integration tests and assemble.
- Run boundary guards and scan feature source for app/controller imports.
- Refresh graph output after code changes with graphify update .

Playback additionally requires the repository live-TV protocol on at least two
channels: 61 screenshots per channel at two-second cadence, changing hashes for
the full window, media session PLAYING with error=null, healthy HLS evidence,
and no fatal error, stuck timeout, or unintended MPEG-TS fallback. Player focus,
Back, controls, seeking, overlays, PiP, Cast, repeated zapping, and MultiView
are included in manual/connected validation.

Module/package moves invalidate generated profile descriptors, so profile
generation is rerun from the existing :benchmark producer. Generated output is
not hand-edited or release-approved without the existing physical-device gate.

## Phase 5 exit criteria

- :app contains orchestration and platform entry points, not extracted feature
  presentation implementations.
- No feature implementation depends on :app or a root navigation controller.
- Each feature exposes graph registration and small contracts only.
- Each feature's tests run independently.
- A feature-only edit does not compile unrelated feature source.
- Temporary data dependencies are complete in the transitional ledger.
- Playback behavior and live stability are equivalent or better.
- Build/runtime measurements and a phase report exist for every extraction.
- :feature:system is extracted only when measurements justify it.
