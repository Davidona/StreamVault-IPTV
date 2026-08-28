# Upgrade Migration Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve every valid legacy provider during Room upgrades and keep database-open failures from crashing the application at startup.

**Architecture:** Harden migration 72-to-74 with canonical type conversion, deterministic collision-safe identities, and scoped integrity validation. Add a single startup database coordinator that opens Room before admitting database consumers and exposes readiness to a TV-safe recovery UI.

**Tech Stack:** Kotlin, Room, Hilt, coroutines/StateFlow, Jetpack Compose, JUnit, Robolectric, AndroidX MigrationTestHelper.

**Spec:** `docs/superpowers/specs/2026-08-23-upgrade-migration-resilience-design.md`

## Global Constraints

- Preserve all providers and provider-owned data; never merge or delete normalized duplicates.
- Keep `fallbackToDestructiveMigration` disabled.
- Do not change Room schema version or entity shape.
- Do not redesign the 37-table provider rebuild in this change.
- Do not use the emulator until its other active worker has finished.

---

### Task 1: Collision-safe migration identities and legacy types

**Files:**
- Modify: `data/src/main/java/com/streamvault/data/local/MigrationSupport.kt`
- Modify: `data/src/main/java/com/streamvault/data/local/FeatureMigrationsV49To75.kt`
- Create: `data/src/test/java/com/streamvault/data/local/MigrationSupportTest.kt`
- Modify: `data/src/androidTest/java/com/streamvault/data/local/StreamVaultDatabaseMigrationTest.kt`

**Interfaces:**
- Produces: `canonicalLegacyProviderType(String): String`
- Produces: `migrationIdentityKey(List<String>): String`
- Produces: `disambiguatedMigrationIdentityKey(String, Long): String`

- [ ] Write JVM tests proving all aliases canonicalize, unknown types follow the existing M3U fallback, canonical hashes are stable, and disambiguated hashes differ by provider ID.
- [ ] Run `:data:testDebugUnitTest --tests com.streamvault.data.local.MigrationSupportTest` and verify failures because the helpers do not exist.
- [ ] Implement the three minimal helpers in `MigrationSupport.kt`.
- [ ] Re-run the targeted JVM test and verify it passes.
- [ ] Add a migration test starting from schema 72 with raw-distinct but canonically equivalent providers and dependent channel rows; assert both reach the current schema with distinct identity keys and preserved rows.
- [ ] Add migration fixtures for every supported alias and an unknown value; assert canonical stable provider/configuration types after migration.
- [ ] Update migration 72-to-73 to canonicalize `providers.type`, compute the canonical key through the shared helper, and use the deterministic disambiguated key only when the canonical key is already present.

### Task 2: Prevent runtime configuration replacement across providers

**Files:**
- Modify: `data/src/main/java/com/streamvault/data/local/dao/ProviderSnapshotDao.kt`
- Modify: `data/src/test/java/com/streamvault/data/local/ProviderSnapshotDaoTest.kt`

**Interfaces:**
- Consumes: persisted disambiguated keys created by Task 1.
- Produces: `commitConfiguration(ProviderConfigEntity): Boolean` that cannot replace another provider's configuration.

- [ ] Add Robolectric tests with two providers proving a colliding new insert is rejected and an existing disambiguated provider retains its key during a colliding update.
- [ ] Run the targeted DAO test and verify the current `REPLACE` implementation fails by removing or overwriting the other configuration.
- [ ] Add DAO queries for the current config and identity owner, then make `commitConfiguration` retain the current provider's key on collision or reject a new colliding provider.
- [ ] Re-run the targeted test and the existing DAO tests.

### Task 3: Scope migration foreign-key validation

**Files:**
- Modify: `data/src/main/java/com/streamvault/data/local/FeatureMigrationsV49To75.kt`
- Modify: `data/src/androidTest/java/com/streamvault/data/local/StreamVaultDatabaseMigrationTest.kt`

**Interfaces:**
- Consumes: `ProviderDependentBackup.table` from the existing rebuild.
- Produces: migration 73-to-74 validation limited to restored provider-dependent tables.

- [ ] Add a migration test with an unrelated pre-existing foreign-key violation and prove 73-to-74 currently rejects it.
- [ ] Add a companion test proving a violation in a rebuilt provider-dependent table is still rejected.
- [ ] Replace the global validation call with `validateForeignKeys` over the restored backup table names.
- [ ] Run the migration test class when the shared emulator becomes available; otherwise leave the exact command and unverified status in the handoff.

### Task 4: Gate startup on database readiness

**Files:**
- Create: `app/src/main/java/com/streamvault/app/startup/DatabaseStartupCoordinator.kt`
- Create: `app/src/main/java/com/streamvault/app/startup/DatabaseStartupScreen.kt`
- Create: `app/src/test/java/com/streamvault/app/startup/DatabaseStartupCoordinatorTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/StreamVaultApp.kt`
- Modify: `app/src/main/java/com/streamvault/app/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `DatabaseStartupState` with `Opening`, `Ready`, and sanitized `Failed` states.
- Produces: `DatabaseStartupCoordinator.state: StateFlow<DatabaseStartupState>` and idempotent `start()` / `retry()`.
- Consumes: `StreamVaultDatabase.openHelper.writableDatabase` and the existing startup actions.

- [ ] Write coordinator tests for success, failure, retry, one-time admission, and isolated startup-action failures.
- [ ] Run the targeted app JVM test and verify failure because the coordinator does not exist.
- [ ] Implement the coordinator with one mutex-protected open attempt, sanitized failure state, and per-action exception containment.
- [ ] Re-run coordinator tests.
- [ ] Route all current `StreamVaultApp` database-backed startup work and `StartupWorkRegistry.register()` through the coordinator.
- [ ] Render navigation only in `Ready`; render a focused TV-safe retry/share screen in `Opening` and `Failed` without collecting database-backed flows.
- [ ] Run targeted app tests and compile the debug app.

### Task 5: Verification and graph refresh

**Files:**
- Update generated graph under `graphify-out/` through the required command; generated output remains ignored unless already tracked.

- [ ] Run targeted data and app JVM tests.
- [ ] Run `:data:testDebugUnitTest` and `:app:testDebugUnitTest`.
- [ ] Coordinate and run the Room migration instrumentation class when the emulator is free.
- [ ] Run `git diff --check` and inspect `git diff` for unintended schema/entity changes.
- [ ] Run `graphify update .` because code files changed.
- [ ] Commit independently reviewable migration and startup changes.
