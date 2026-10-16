# Xtream sync performance review

Inspected 2026-10-09 at commit `540ec30c`. Scope: adding a provider first,
then Settings sync and the background work that makes the catalog usable.
This is a source inspection, not a measured speedup or an implementation.

Scope clarification: the reported primary delay is the initial Live TV import,
before queued work. The EPG/continuation findings below concern later catalog
availability and do not explain that initial live phase. See
[the live-only import comparison](XTREAM_LIVE_IMPORT_COMPARISON.md) for the
revised priority and concrete reference-code comparison.

## Current execution

ProviderSetupViewModel → ValidateAndAddProvider → ProviderRepositoryImpl:
authenticate, save/stage configuration, run initial onboarding sync.
SyncManager/SyncCoordinator route into XtreamCatalogSyncExecutor.syncFull.

The foreground sequence is:

1. Fetch live categories.
2. Import the complete live lineup, with bulk or category requests; stage batches.
3. Commit live rows and rebuild channel FTS.
4. Fetch and commit movie category shells.
5. Fetch and commit series category shells.
6. Return continuation work for live index backfill, movie summaries, series
   summaries, and EPG. Provider setup can finish before movie/series items exist.

ProviderContinuationScheduler enqueues EPG first, then live, movie, and series
index workers. All use `provider-workflow-<id>` with APPEND_OR_REPLACE.
The EPG worker has a 30-second initial delay. Index workers behind it wait for
that delay and guide execution, including any WorkManager retries.

Settings Sync Now performs TV, Movies, Series, EPG section calls in sequence.
Xtream movie/series section calls update category shells and queue indexing;
they do not await the complete item catalog. Settings shares the executors, but
its operation sequence and live policy differ from initial onboarding.
Full refresh/rebuild routes use the full plan, whose non-onboarding live reason
is FOREGROUND; they should not be confused with the Settings TV section route.

## Findings supported by code

### 1. AUTO often deliberately chooses the expensive request topology

`data/.../sync/XtreamLiveSyncPolicy.kt:38` selects category loading for LOW tier,
current low-memory state, hidden categories, remembered stress/full avoidance,
MID onboarding, and MANUAL_SETTINGS/BACKGROUND_STALE.
`CatalogSyncRuntimeProfile.kt` defines LOW as memory class <=192 MB or low-RAM,
MID as <=320 MB. MID onboarding prefers categories and permits at most two
category requests; LOW permits one. HIGH may use bulk.

`XtreamAdaptiveSyncPolicy.kt` adds 250 ms between CATEGORY request starts even
on a healthy provider. For 200 category requests, the healthy start-spacing
floor alone is about 49.75 seconds, before the final request finishes. This is
a theoretical floor from policy, not a recorded provider timing. Concurrency
does not remove this global pacing floor.

`SyncManagerXtreamSupport.executeCategoryRecoveryPlan` processes small windows
with awaitAll before starting the next window. A slow category prevents idle
slots from taking new work. Concurrency is selected initially; the window loop
can downgrade to sequential but does not continuously refill or increase slots.

### 2. Guide scheduling blocks catalog availability

Sources: `ProviderContinuationScheduler.kt:18`, `ProviderWorkScheduling.kt`,
`BackgroundEpgSyncWorker.kt:131`, and `XtreamIndexWorker.enqueue`.
The 30-second EPG delay and serial queue are structural delays in the normal
onboarding continuation path. Their actual contribution to an observed run
still needs timing evidence. EPG SKIP bypasses this part.

This can explain a provider becoming active while movie/series categories stay
empty. First improvement: put catalog indexing before EPG in the existing
durable queue. A later scheduler can prioritize requested categories and guide
availability explicitly, while preserving generation/lease/commit ownership.
Creating independent queues alone would not address the provider-wide lock.

### 3. Streaming still parses every row twice

`remote/xtream/OkHttpXtreamApiService.kt:496` reads each row through Gson's
JsonParser, serializes the resulting tree via element.toString(), and parses
that string with kotlinx.serialization at line 504. This applies to streaming
live, movie, and series arrays. Thin live DTOs reduce retained fields but do
not eliminate the first parse, tree allocation, or second parse.

All streaming callbacks await mapping and DB writes before reading more rows.
This keeps memory bounded, but puts transfer/parse and persistence in sequence.
A bounded producer/consumer pipeline can overlap stages when their costs
justify it; it does not guarantee a win when the provider/network dominates.

### 4. Live import does substantial repeated and additional database work

`SyncCatalogStore.stageChannelBatch:371` enters a fenced transaction, counts
all staged channels for the session, deduplicates the batch, fingerprints it,
and inserts rows. LOW/MID/HIGH batches are only 100/300/500 rows. A 50,000-row
LOW import therefore performs approximately 500 staging transactions and
growing stage-count queries. The cost of those queries needs measurement.

`SyncCatalogStore.applyStagedLiveCatalog:163` applies categories/channels and
rebuilds channel FTS inside the commit transaction. The DAO rebuild is global
to channels_fts, so other providers contribute to its cost too.
Entities declare Fts4 contentEntity links; existing generated release Room code
confirms automatic channel insert/update/delete FTS triggers. This suggests
redundant work, but removing rebuilds requires proving search correctness for
every affected write path and supported DB upgrade path.

`SyncManager.backfillXtreamLiveIndex:2115` later reads every provider channel,
creates a second full list of index entities, deletes its previous Xtream live
index rows, and rewrites them. This also weakens the bounded-memory story of
the earlier streaming stage. Index maintenance could use SQL projection or
bounded changed-row updates during catalog promotion instead.

The live refresh SQL (`CatalogSyncDao.updateChangedChannelsFromStage`) uses a
separate correlated stage lookup for each field of a changed channel. It skips
matching fingerprints, so this primarily matters for changed rows; first-add
timings must be considered separately from refresh timings.

### 5. Movies/series already have a bulk path, with a costly fallback

`XtreamIncrementalIndexExecutor.processSummary` first attempts one full summary
stream for a fresh job. Therefore the whole Xtream engine is not universally
category-by-category. The existing bulk path is worth improving and reusing.

Full summaries use 500-row batches. Each batch reads existing rows, merges
metadata, writes catalog rows, rereads persisted IDs, writes Xtream index rows,
and updates the durable job. The path does not filter unchanged summaries
before writing all merged rows. Rich movie/series details remain separate from
this summary import; background "indexing" includes downloading basic catalog
items, not just constructing a search index.

When full streaming fails, summaries use a sequential category loop and the
worker limits each invocation to two categories (`CATEGORY_SLICE_SIZE = 2`).
Each slice schedules another durable worker. A 200-category fallback requires
about 100 worker invocations, plus the failed bulk attempt. Fresh/full streams
are not limited to two categories.

Large bulk bodies have ceilings: live 96 MiB, movies 80 MiB, series 100 MiB
(plus the configured headroom). A rejected oversized response can send work
into the slower fallback. Preserve limits, but measure whether wire-size caps
remain the right protection for a bounded streaming parser.

### 6. Legacy settings are misleading foundations for optimization

Provider setup and repository onboarding force xtreamFastSyncEnabled=false.
Current syncFull already queues movie/series summaries instead of loading
complete detail catalogs in the foreground. Toggling that legacy flag should
not be assumed to activate a faster onboarding engine.

## Proposed design

Use one catalog ingestion pipeline, with different fetch strategies feeding
it. Preserve existing configuration generations, commit fences, cancellation,
hidden/protected categories, stable IDs, favorites and watch progress.

- Fetch category metadata once per section and reuse it for classification.
  Independent metadata requests can overlap under a modest provider budget.
- Prefer streaming bulk for healthy providers when the new parser/pipeline has
  demonstrated bounded memory on LOW devices too. Learn provider capability
  and outcomes; retain explicit user modes and category fallback. Device tier
  should primarily select memory/batch budgets, rather than decide request
  count on its own.
- Parse directly into thin rows with one parser; tolerate provider field
  variants without reparsing whole responses. Map rows once.
- Feed a bounded queue to one DB writer. Tune batch size by measured throughput,
  row size, heap headroom and transaction duration. Maintain cheap incremental
  counts, with durable reconciliation on resume and existing size limits.
- Promote complete snapshots safely and avoid duplicate full index passes.
  Refresh writes should skip unchanged records. A partial import must preserve
  rows outside its confirmed scope; stale pruning follows complete generations.
- For category fallback use a continuously refilled, bounded worker pool.
  Adapt concurrency and pacing to observed server stress, including Retry-After
  when supplied, and retry only failed categories with durable checkpoints.
- Prioritize catalog availability before EPG and enrichment. Requested category
  work should outrank background completion. Worker slices should have measured
  time/row budgets rather than a universal two-category limit.
- Keep details/episodes/enrichment on demand or in lower-priority background
  work. Show separate live-ready, catalog-ready, search-ready and guide-ready
  states instead of treating queued category shells as completed item sync.

An optional small live preview could shorten first-playable time. Treat that
as a separate product choice; it is not proof of faster complete ingestion and
must not delete the rest of the lineup as if the preview were a full snapshot.

## Implementation and measurement order

1. Instrument baseline milestones and stage costs, then fix continuation order.
2. Benchmark and replace the double parse; add the bounded writer pipeline.
3. Reduce staging/query/index work and skip unchanged refresh writes.
4. Validate bulk on constrained devices, then change AUTO and category workers.
5. Align Settings completion/progress with the same readiness milestones.

Measure request counts, bytes, response-header latency, end-to-end transfer,
parse/map CPU, writer queue wait, staging, commit, FTS, Xtream index, scheduling
wait, peak heap/GC, retry/fallback counts, and total elapsed time. End-to-end
stream duration includes downstream callback work today; do not call that pure
network time. Correlate sanitized records by run/provider/section/strategy.

Compare both first usable live playback and full catalog/search completion.
Use identical provider snapshot, device, section counts, network, hidden-category
configuration, and metadata scope for this app and any reference implementation.
Repeat runs and report median and spread. A 10x target remains unproven.

Use synthetic small and large datasets (for example 50k live, 100k movies,
20k series; many tiny categories and uneven large categories), low/mid/high
device budgets, slow/rate-limited/bulk-rejecting servers, malformed rows,
interrupted imports, and refreshes with zero/small/large changes. Verify counts,
category coverage, playback URLs, search, preserved user state, process-death
resume, provider edits/deletion and failed-snapshot preservation.

The existing local catalog fixture is a correctness fixture: one live channel,
63 movies and 63 series. It cannot establish large-catalog throughput. An
emulator is connected, but its available filtered logcat contained no matching
Xtream sync timing records during inspection. No add-provider operation or
runtime benchmark was performed, and no playback validation is claimed.

## Reference repository comparison

Compare endpoint/request topology, parsing allocations, write and indexing
strategy, readiness criteria, metadata scope, and recovery behavior before
borrowing implementation ideas. Check its license before adapting source.
Use its results to revise this proposal rather than committing to a rewrite
based only on this initial inspection.
