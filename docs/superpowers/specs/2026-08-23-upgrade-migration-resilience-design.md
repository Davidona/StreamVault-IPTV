# Upgrade Migration Resilience Design

## Goal

Make upgrades from StreamVault 1.0.14 and later preserve all user data, tolerate legacy provider representations, and avoid a process crash loop when Room cannot open the database.

## Scope

This change hardens the existing 72-to-74 provider migration and the application database-opening boundary. It does not enable destructive migration, merge providers, redesign the Room schema, or replace the 73-to-74 provider-table rebuild.

## Provider identity collisions

Schema 72 allows provider rows that are distinct under the legacy raw-field index but equivalent under the schema-73 canonical identity algorithm. The migration must preserve every such provider and all of its dependent rows.

During the 72-to-73 backfill:

1. Compute the same canonical identity key used by `ProviderConfigurationCodec`.
2. Give the first provider in stable `id` order the canonical key.
3. If that key is already owned, derive a deterministic disambiguated key from the canonical key and provider ID.
4. Never delete or merge providers or provider-owned data.

The disambiguated key remains stable across retries because both inputs are durable. Runtime configuration commits must not allow Room's `REPLACE` behavior to remove another provider's configuration. If a legacy-disambiguated provider is updated without becoming unique, it retains its current disambiguated identity. A newly inserted provider whose canonical identity is already owned is rejected through the existing boolean commit result.

## Legacy provider types

Before snapshot backfill, schema-72 provider type strings are canonicalized using the same aliases recognized by `RoomEnumConverters`: `XTREAM` and `XTREAM_CODES_API` become `XTREAM_CODES`; `STALKER` and `STB` become `STALKER_PORTAL`; `PLAYLIST` becomes `M3U`.

Unknown values follow the existing converter behavior and become `M3U`. The canonical value is written back to `providers.type` so the stable provider row and typed configuration cannot disagree after 73-to-74.

## Foreign-key validation

The 73-to-74 migration continues to back up, clear, rebuild, and restore the provider-dependent graph. After restoration it validates only the tables it copied. It must not reject a pre-existing foreign-key problem in a table outside that graph.

The existing 37-table preservation mechanism remains unchanged in this patch. Its storage cost is a separate optimization problem; changing it without an equally safe SQLite parent-table rebuild would risk catalog loss.

## Startup database gate

Application startup gets one explicit database-readiness owner. It opens the Room writable database on the IO dispatcher before launching database-backed recovery work. State is exposed as `Opening`, `Ready`, or `Failed`.

- `Ready`: launch download, plugin, reminder, Stalker, pending-restore, and work-registration startup actions.
- `Failed`: retain the sanitized failure summary, do not launch database consumers, and keep the process alive.
- Retrying repeats the open attempt without deleting data.

`MainActivity` renders a small TV-safe database recovery screen until the gate is ready. Failure UI offers Retry and a path to share the already-sanitized crash/diagnostic report. It does not offer automatic database deletion.

Startup jobs also receive per-task exception containment so a later recovery defect cannot terminate the application process after the database is ready.

## Testing

Tests are added before production changes and must cover:

- schema 72 with two raw-distinct URLs that normalize to one identity;
- preservation of both providers and representative dependent catalog rows through the current schema;
- deterministic distinct identity keys across repeated construction;
- all supported provider-type aliases and unknown-type fallback;
- runtime commits that cannot replace another provider's configuration;
- foreign-key validation ignoring unrelated legacy damage while still detecting damage in a rebuilt table;
- database gate success, failure, retry, and one-time startup task admission;
- startup task exceptions being recorded without crashing or blocking sibling tasks.

Existing migration registry and full-chain tests remain required. Emulator/instrumentation execution must be coordinated with the other active worker; JVM tests and static checks may run independently.

## Non-goals

- No `fallbackToDestructiveMigration`.
- No automatic provider merge.
- No silent provider or catalog deletion.
- No database schema version bump unless Room entity shape changes.
- No attempt to optimize the 37-table rebuild in the same change.
