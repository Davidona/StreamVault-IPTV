# Settings Data Boundary Design

## Context

Phase 7 requires presentation modules to stop importing persistence and remote implementation types. `:feature:settings` currently depends directly on `:data` for `PreferencesRepository`, `ProgramDao`, Xtream indexing/onboarding DAOs and entities, and provider sync commands.

## Decision

Expose settings-required persistence through focused domain contracts:

- `SettingsPreferences` contains only preference streams and commands consumed by settings UI and parental settings.
- `SettingsOperations` exposes program counts, Xtream indexing/onboarding state, and repair-section sync commands using domain models.
- `PreferencesRepository` implements `SettingsPreferences`; a data-layer adapter implements `SettingsOperations` by delegating to existing DAOs and sync commands.
- Feature-owned UI mapping remains in `:feature:settings`, but it consumes domain models only.

The contracts use existing domain enums and models wherever possible. Database maintenance and Xtream operational records become immutable domain snapshots. Android DataStore, Room DAOs/entities, and data sync enums remain private to `:data`.

## Dependency direction

```text
:feature:settings ---> :domain <--- :data
        |                            |
        +----> :core:ui              +----> DataStore / Room / sync implementations
        +----> :core:navigation
        +----> :player
```

## Compatibility

- Preference keys, defaults, serialization, and setter behavior do not change.
- Sync section ordering, progress callbacks, cancellation behavior, and user-facing messages do not change.
- Xtream onboarding/index data is mapped field-for-field before entering presentation code.
- Existing Hilt construction remains automatic through interface bindings.

## Verification

- Boundary tests must fail on either a Gradle `:data` dependency or a `com.streamvault.data` source reference.
- Contract shape and data adapter mapping receive focused unit tests.
- Existing settings tests are migrated to contract fakes/mocks without weakening assertions.
- `:domain:test`, `:data:testDebugUnitTest`, `:feature:settings:testDebugUnitTest`, and `:app:assembleDebug` validate the completed checkpoint.

## Out of scope

- UI redesign or Compose-to-Views conversion.
- Preference key/schema migration.
- Changes to sync scheduling or provider behavior.
- Removing the separate `:player` dependency; that belongs to the player-capability checkpoint.
