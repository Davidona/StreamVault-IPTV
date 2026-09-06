# Playback data-boundary cleanup implementation plan

> Execute this plan task-by-task with test-first changes and a verification checkpoint after each boundary seam.

## Task 1: Make the playback boundary red

Files:

- `feature/playback/build.gradle.kts`
- `feature/playback/src/test/resources/boundary-fixtures/*`

Changes:

- remove `:data` from the approved project dependency set and implementation dependencies;
- add forbidden data-package tokens and Kotlin/Java fixtures;
- require the new fixture violations in the boundary assertion;
- keep the typed configuration-cache-safe task shape.

Test first:

- run `:feature:playback:verifyFeaturePlaybackBoundary`; it must fail against the current dependency/import state.

## Task 2: Introduce the player-preferences domain seam

Files:

- `domain/src/main/java/com/streamvault/domain/settings/PlayerPreferences.kt`
- `data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt`
- `app/src/main/java/com/streamvault/app/di/RepositoryModule.kt`
- playback preference consumers and their tests

Changes:

- declare the missing player/multiview API on `PlayerPreferences`;
- implement/bind it from `PreferencesRepository`;
- migrate playback consumers and tests from the concrete repository to the interface.

Verification:

- `:feature:playback:testDebugUnitTest` and the focused multiview/player tests pass;
- `rg` finds no `PreferencesRepository` import in playback main or test sources.

## Task 3: Introduce the provider playback domain seam

Files:

- domain provider contract and failure types;
- app playback adapter and Hilt binding;
- `PlayerContentResolver`, `PlayerProviderCoordinator`, and related tests.

Changes:

- expose `ResolvedPlayback` through a domain `PlayerPlaybackResolver` result API;
- translate data resolver metadata and known data exceptions in the app adapter;
- preserve content-resolution success, credential-failure, Stalker-resolution-failure, internal-URL, and direct-URL paths.

Verification:

- focused resolver/coordinator tests pass;
- playback main/test sources contain no data-package imports.

## Task 4: Move the fallback URL token seam to domain

Files:

- domain internal playback URL codec;
- `PlayerAlternateStreamSupport` and its tests.

Changes:

- replace direct Xtream factory/token imports with the domain codec;
- preserve all existing fallback URL selection behavior and expected URL strings.

Verification:

- alternate-stream tests pass;
- no `XtreamUrlFactory` or `XtreamStreamKind` imports remain in playback.

## Task 5: Boundary and repository verification

Commands:

```text
graphify update .
./gradlew :feature:playback:verifyFeaturePlaybackBoundary --configuration-cache
./gradlew :feature:playback:testDebugUnitTest :domain:test :data:testDebugUnitTest :feature:settings:testDebugUnitTest :player:testDebugUnitTest :feature:live:testDebugUnitTest :app:assembleDebug --console=plain --no-daemon
git diff --check
```

Acceptance:

- playback project dependencies are exactly the four approved modules;
- playback source has no `com.streamvault.data` imports;
- focused and full verification pass;
- Phase 7 plan documents the removed transitional edge.
