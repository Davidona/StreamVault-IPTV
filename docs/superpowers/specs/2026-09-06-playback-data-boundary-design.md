# Playback data-boundary cleanup

## Context

Phase 7 has already removed concrete `:data` dependencies from provider and settings features and from the player capability surface. `:feature:playback` still has a transitional `:data` edge. Its main source currently reaches into the data layer for preferences, provider playback resolution, credential/provider-specific exceptions, and the Xtream internal URL codec.

The feature should own playback policy and presentation behavior. Persistence, provider selection, credential handling, and provider URL encoding belong behind domain contracts implemented by the application/data composition root.

## Goal

Remove `implementation(project(":data"))` and all `com.streamvault.data` imports from `:feature:playback` while preserving current playback resolution, credential-error messaging, multiview preference behavior, and live HLS-to-MPEG-TS recovery behavior.

## Design

### Player preferences contract

Add `domain.settings.PlayerPreferences`, extending `SettingsPreferences` for the settings-owned preference surface and declaring the additional player/multiview reads and commands currently used by playback:

- player mute state;
- observed live/VOD variant maps and their recording commands;
- per-channel aspect ratio and audio/video offset reads/writes;
- last-live-category lookup and preferred-live-variant updates;
- active-provider and multiview preset/performance-mode state.

`PreferencesRepository` implements `PlayerPreferences`. The app Hilt module binds it to the domain contract. `PlayerPreferencesCoordinator` and `MultiViewViewModel` depend only on `PlayerPreferences`.

### Playback resolution contract

Add `domain.provider.PlayerPlaybackResolver` with:

- `isInternalStreamUrl(String?)`;
- `resolveAndCommitMetadata(...) : Result<ResolvedPlayback?>` using the existing domain `ResolvedPlayback` model.

The result form keeps provider/data exceptions out of the feature API. The domain contract also defines stable player-facing failure types for credential access and provider resolution. An app adapter wraps `ProviderPlaybackResolver`, maps `ResolvedStreamUrl` to `ResolvedPlayback`, and translates the two data exceptions into those domain failures. Unknown failures retain their existing propagation behavior.

`PlayerContentResolver` consumes only this contract and preserves separate credential and resolution messages. `PlayerProviderCoordinator` removes its data exception import and normalizes unexpected repository failures through the existing domain `Result` wrapper.

### Internal playback URL codec

The live recovery policy needs only the internal provider URL identity (provider id, live/movie/series kind, stream id, and extension), not the full data-layer Xtream factory. Add a small domain-owned `ProviderInternalStreamUrl` codec for that stable internal playback token. `PlayerAlternateStreamSupport` and its tests use this codec; data keeps its richer `XtreamUrlFactory` for data-layer URL construction and parsing.

### Boundary enforcement

Update the playback boundary task to:

- approve only `:core:navigation`, `:core:ui`, `:domain`, and `:player` project dependencies;
- reject `com.streamvault.data` in main source;
- include Kotlin and Java data-import fixtures so the scanner cannot regress;
- retain the existing app/navigation and direct Media3 guards.

The task remains typed and configuration-cache compatible.

## Compatibility and testing

- Existing player content, provider coordinator, multiview, and alternate-stream tests are migrated to domain contracts/fakes.
- Add focused tests for the app resolver adapter’s domain mapping and failure translation.
- Run the playback boundary task, playback unit tests, domain/data/settings/player/live tests, and debug assembly.
- Run `graphify update .` after source changes.
- Update the Phase 7 plan only after the `:data` edge and source imports are absent and verification is green.

## Out of scope

- Reworking provider implementations or the existing data-layer resolver registry.
- Changing playback retry policy, player capability APIs, or live validation behavior.
- Removing other app/data dependencies outside `:feature:playback`.
