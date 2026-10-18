# Database migration invariants

Every schema change must satisfy this checklist before the database version is advanced:

- Add exactly one adjacent migration to the appropriate version-group file and keep the registry contiguous.
- Export and commit the new Room schema JSON; its version must equal `STREAM_VAULT_DATABASE_VERSION`.
- Preserve provider identity, encrypted typed configuration, configuration generation, and active-source references.
- Preserve foreign keys and indexes for catalog, EPG assignment, recording, history, backup/workflow, and plugin ownership tables.
- Backfills must be deterministic, idempotent within the transaction, and explicit about defaults and nullability.
- Add `MigrationTestHelper` coverage for the new adjacent hop and at least one populated supported multi-hop origin.
- Run every committed historical schema artifact through the exact production registry to the current version; document intentionally absent exports.
- Verify `PRAGMA foreign_key_check` for every table written by the migration.
- Downgrades remain unsupported and must fail explicitly; destructive fallback is prohibited.

## Current verification requirements

- Current schema: v86.
- Exported historical origins: v1 and v3-v85; v2 has no committed Room schema export.
- Required migration coverage: `StreamVaultDatabaseMigrationTest` must validate exported origins through v86 plus populated direct/multi-hop preservation fixtures and the legacy Stremio schema fixture described below.
- Migration 78 → 79 scopes episode uniqueness to the owning series and expires hydrated series detail timestamps.
- Migration 79 → 80 adds the four Xtream live receipt columns; migration 80 → 81 creates the durable Stalker live-category retry table and its provider index.
- Migration 82 → 83 stores playback-quality preferences. Migration 83 → 84 repairs legacy Stremio schema variants and adds the recording network profile. Migration 84 → 85 adds Stremio add-on/history tables and download request metadata. Migration 85 → 86 normalizes databases upgraded from either branch lineage.
- The pre-merge Stremio branch independently exported schema v80 with the same version number but no Xtream receipt columns, the old `(provider_id, episode_id)` episode index, and Stremio add-on/history tables. The migration path repairs that variant, preserving Stremio rows and invalidating affected hydrated-series cache timestamps; keep its populated migration fixture.
## Validation (2026-10-10)

- All 45 `StreamVaultDatabaseMigrationTest` tests passed on `Television_4K_2(AVD) - 16` using `:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.local.StreamVaultDatabaseMigrationTest` on 2026-10-10, against schema v81.
- Coverage includes every committed historical schema to v81, populated v78 → v80 → v81 receipt preservation, and direct v80 → v81 Stalker retry creation with episode/timestamp preservation and provider-delete cascade checks.
- Current Stremio worktree validation on 2026-10-16: all 47 `StreamVaultDatabaseMigrationTest` tests passed on `emulator-5558` against schema v84, including a populated synthetic fixture for the pre-merge Stremio v80 schema and preservation of provider, add-on, playback-history, and episode data.
