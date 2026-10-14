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

## Current verification

- Current schema: v79.
- Exported historical origins: v1 and v3-v78; v2 has no committed Room schema export.
- `StreamVaultDatabaseMigrationTest` validates all exported origins to v79 plus populated direct/multi-hop preservation fixtures.
- Device result (2026-09-25): 43/43 passed on `Television_1080p(AVD) - 16` (Android TV, API 36, arm64-v8a).
