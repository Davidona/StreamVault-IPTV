# Xtream live attempt isolation and recovery

Implemented on `codex/xtream-live-sync-performance`, 2026-10-09, after the
initial backend baseline. This package supplies the safety boundary needed
before overlapping retrieval and database writes. Production AUTO selection,
request concurrency and healthy-provider spacing retain their existing behavior.

## Attempt boundaries

Every full response attempt owns its session, duplicate set, pending raw batch,
category collector and accepted-row count. Failure or cancellation clears that
session before retrying. A duplicate admitted by a failed attempt can therefore
be admitted correctly by the successful retry.

Every category attempt uses its own staging session. Only a completed category
is merged into the combined import session. Failed thin decoding, legacy
decoding and transport retries discard their own rows first. SQL merging ignores
channels already present in the completed session, preserving the first
completed category's row. A failed category cannot reserve a duplicate or
overwrite a successful category. Partial results use the existing upsert-only
promotion, preserving channels absent from the completed scopes.

Superseded workflows are cancellation control flow. They stop retry/decoder
fallback and return the workflow's superseded disposition. Provider-wide stage
cleanup and onboarding state writes now check ownership inside their database
transactions. Scoped cancellation cleanup remains possible for the old attempt
without touching a newer session.

HTTP cancellation remains connected throughout response-body reading, including
after headers arrive. A response delivered during a cancellation race is closed.
Streamed arrays must reach their closing token and end of document; extra JSON
after an otherwise valid array cannot establish completion. Alternate decoding
is reserved for parsing/compatibility errors instead of arbitrary writer or
transport failures.

## Durable completion receipt

Schema 79 adds nullable sealed session, configuration generation and mapping
profile columns, plus a sealed row count defaulting to zero. The 78-to-79
migration preserves existing imports while giving them no completion proof.

After a complete full-catalog response, onboarding checks the exact staged row
count and atomically writes category metadata and the receipt. The receipt binds
the session to the authoritative provider configuration generation and the text
classification/base64 mapping flags. Configuration is rechecked inside the
sealing transaction. The receipt contains no credentials or request URLs.

Recovery requires a matching full-catalog receipt, current configuration and
mapping profile, exact accepted/staged row counts, no mixed catalog rows, and
an unrestricted category scope. Anything else is discarded and fetched again.
Normal promotion remains transactional and clears the receipt on completion.

Category snapshots deliberately refetch after interruption. Durable per-category
coverage and resume are still work for the subsequent scheduling package. Until
that exists, a nonempty category stage is never treated as a complete snapshot.
Hidden-category imports also refetch rather than reuse an unproven scope.

## Validation

Focused checks exercise real Room staging, retries after emitted rows,
cross-category duplicates, partial promotion preserving the active library,
supersession, atomic sealing and database close/reopen before promotion.
Orchestration checks cover unsealed rows, missing rows, configuration/mapping
mismatch, category snapshots and valid sealed recovery. HTTP tests cover blocked
body cancellation and trailing documents.

Results:

- 177 focused JVM/Robolectric tests passed, including catalog-store and shared
  workflow/Jellyfin checkpoint regression checks.
- Android migration tests passed for a populated schema-78 onboarding record
  and the complete migration path from every exported historical schema.
- Benchmark debug application and instrumentation APKs built successfully.
  They use `com.streamvault.app.syncbenchmark`, separate from the user's app.
- Synthetic Add Provider and Settings Live refresh passed in all three requested
  modes: six samples, 2,000 channels in each, successful sync states and provider
  cleanup. No event-history truncation; instrumentation exited successfully.
  Report: `build/xtream-sync-performance/20261009T173718237727Z/report.json`.
  This is one sample per phase/mode with no warmups, a correctness smoke check.
  The device continued to select category requests, so it is not a bulk speed
  comparison. The fixture supplies no playable media.

Reproduction:

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests '*XtreamLiveAttemptIsolationTest' --tests '*SyncManagerXtreamLiveStrategyTest' --tests '*SyncManagerTest' --tests '*ProviderWorkflowRunnerTest' --tests '*ProviderWorkflowCommitFenceTest' --tests '*OkHttpXtreamApiServiceTest' --tests '*StreamVaultDatabaseMigrationRegistryTest' --tests '*SyncCatalogStoreTest' --tests '*JellyfinCatalogCheckpointTest'
.\gradlew.bat :data:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.local.StreamVaultDatabaseMigrationTest#migrate78To79_preservesSavedImportWithoutGrantingCompletionProof,com.streamvault.data.local.StreamVaultDatabaseMigrationTest#everyExportedHistoricalSchemaMigratesToCurrent'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PxtreamSyncBenchmark=true
python tools/xtream_sync_benchmark.py --channels 2000 --categories 10 --iterations 1 --warmups 0 --refresh
```

This package makes no speedup claim; parsing, pipelining,
database costs, category scheduling and the AUTO release gate remain ahead.
