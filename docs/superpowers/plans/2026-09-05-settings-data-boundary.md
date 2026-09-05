# Settings Data Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all direct `:data` dependencies and imports from `:feature:settings` without changing settings behavior.

**Architecture:** Settings consumes focused domain contracts. The existing preferences implementation adopts the preference contract, while a data adapter maps Room/sync implementation types into domain operational snapshots.

**Tech Stack:** Kotlin, Coroutines Flow, Hilt, Room, DataStore, JUnit, Truth, Mockito-Kotlin, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-09-05-settings-data-boundary-design.md`

## Global Constraints

- Preserve preference keys, defaults, persisted encodings, and all user-facing settings behavior.
- `:feature:settings` may depend on `:domain`, `:core:ui`, `:core:navigation`, and `:player`, but not `:data`.
- Presentation sources must not reference `com.streamvault.data`.
- Room, DataStore, and concrete provider sync types remain in `:data`.

---

### Task 1: Enforce the final settings boundary

**Files:**
- Modify: `feature/settings/build.gradle.kts`
- Create: `feature/settings/src/test/resources/boundary-fixtures/DataImport.kt`
- Modify: `feature/settings/src/test/java/com/streamvault/feature/settings/SettingsModuleBoundaryTest.kt`

**Interfaces:**
- Consumes: existing `verifyFeatureSettingsBoundary` task.
- Produces: failing tests/fixtures that define the final build guard before production migration begins.

- [ ] Add a failing fixture/assertion requiring detection of `com.streamvault.data` and requiring the build file to omit `project(":data")`.
- [ ] Run `:feature:settings:verifyFeatureSettingsBoundary` and confirm it fails on the current dependency.
- [ ] Leave enforcement changes staged for Task 4, where the migrated production sources can satisfy them.

### Task 2: Introduce the settings preferences contract

**Files:**
- Create: `domain/src/main/java/com/streamvault/domain/settings/SettingsPreferences.kt`
- Create: `domain/src/main/java/com/streamvault/domain/settings/DatabaseMaintenanceSnapshot.kt`
- Modify: `data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt`
- Modify: settings production files importing `PreferencesRepository`
- Modify: settings tests importing `PreferencesRepository`

**Interfaces:**
- Consumes: existing domain preference value types and existing `PreferencesRepository` behavior.
- Produces: `SettingsPreferences`, implemented by `PreferencesRepository`, with every stream/command actually consumed by settings.

- [ ] Add a compile-time contract test that assigns a settings preference fake to constructors/helpers currently typed as `PreferencesRepository`; confirm compilation fails because `SettingsPreferences` does not exist.
- [ ] Declare `SettingsPreferences` with the exact Flow properties and suspend functions referenced under `feature/settings/src/main`.
- [ ] Move `DatabaseMaintenanceSnapshot` to `domain.settings` and make `PreferencesRepository : SettingsPreferences`, adding `override` to implemented members.
- [ ] Replace production and test constructor/helper types with `SettingsPreferences`.
- [ ] Run `:domain:test :data:compileDebugKotlin :feature:settings:compileDebugKotlin :feature:settings:compileDebugUnitTestKotlin` and fix contract-shape mismatches without changing behavior.

### Task 3: Introduce the settings operations contract

**Files:**
- Create: `domain/src/main/java/com/streamvault/domain/settings/SettingsOperations.kt`
- Create: `data/src/main/java/com/streamvault/data/settings/SettingsOperationsImpl.kt`
- Create: `data/src/main/java/com/streamvault/data/di/SettingsDataModule.kt`
- Modify: `feature/settings/.../SettingsViewModel.kt`
- Modify: `feature/settings/.../SettingsDerivedStateObservers.kt`
- Modify: `feature/settings/.../SettingsObserverRegistrations.kt`
- Modify: `feature/settings/.../SettingsSyncActions.kt`
- Modify: `feature/settings/.../SettingsOperationalModels.kt`
- Modify: affected settings tests

**Interfaces:**
- Produces: `SettingsOperations.observeProgramCount(providerId): Flow<Int>`, `observeXtreamIndexJobs(providerId)`, `observeXtreamLiveOnboarding(providerId)`, and `retryProviderSection(providerId, section, onProgress)` using `SettingsSyncSection` and immutable domain snapshots.

- [ ] Add failing domain/feature tests for operational snapshot mapping and sync-section delegation.
- [ ] Define domain snapshots and `SettingsSyncSection` without Room annotations or data imports.
- [ ] Implement `SettingsOperationsImpl` by delegating to the existing DAOs and `ProviderSyncCommands`, mapping every field explicitly.
- [ ] Bind the implementation with Hilt and replace DAO/sync injections and helper parameters in settings.
- [ ] Move entity-to-UI conversion to domain-snapshot-to-UI conversion and migrate tests.
- [ ] Run focused settings and data adapter tests until green.

### Task 4: Remove the data dependency and verify the checkpoint

**Files:**
- Modify: `feature/settings/build.gradle.kts`
- Modify: `docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`
- Update: `graphify-out/`

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: final dependency direction `:feature:settings -> :domain <- :data`.

- [ ] Remove `implementation(project(":data"))` and enable source-level data-package rejection.
- [ ] Confirm `rg -n "com\\.streamvault\\.data" feature/settings/src/main` returns no matches.
- [ ] Run `:domain:test :data:testDebugUnitTest :feature:settings:testDebugUnitTest :app:assembleDebug` and confirm exit code 0.
- [ ] Run `git diff --check` and independently review the boundary, mappings, and behavior compatibility.
- [ ] Update the Phase 7 execution state and run `graphify update .`.
