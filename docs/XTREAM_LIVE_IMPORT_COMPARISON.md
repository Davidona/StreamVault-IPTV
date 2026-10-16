# Initial Xtream Live TV import comparison

Inspected 2026-10-09. StreamVault source: `540ec30c`. Reference implementation
revision: `b5271300b74eb6706293ceb8f3e70b62589e03ad`, inspected in a separate checkout.
No application code was changed and no runtime speed comparison was performed.

The user's priority is the initial live import on Add Provider. Work queued
afterward, including EPG and movie/series indexing, is outside that slow phase.
The earlier broad review remains useful for later work, but scheduling EPG
after indexing cannot fix the time spent importing live channels first.

## What the reference implementation actually does

- `MainActivity.fetchXtreamChannels` authenticates, then launches live category
  and full live stream requests concurrently. It calls getLiveStreams without
  a category ID. When movies/series are enabled, their bulk lists and category
  requests launch alongside these too.
- XtreamClient.streamBulkArray uses Android JsonReader on the response stream.
  It reads scalar fields into a small per-row map and immediately builds a
  Channel. It does not turn every row into JSON text and decode that text again.
- The reader accepts a root array and selected object-wrapped arrays. It maps
  IDs represented as numbers or strings, handles category_ids fallback and
  catch-up flags, and skips nested structures it does not consume.
- Parsed Channels accumulate in an ArrayList proportional to catalog size.
  Input JSON is streamed, but the entire output catalog is retained in memory.
  Its OOM handling explicitly acknowledges that this list can still be too large.
- The Xtream fetch returns the full enabled live/movie/series result. It does
  not deliver a capped live preview or incremental live row callbacks to the UI.
  The separate early live callback in this file belongs to Stalker.
- On a successful provider fetch, loadAllConfiguredProviders merges the result
  and calls renderLivePartial. That starts the live derivation/render path before
  the final persistCatalog call. The catalog cache is a delimited text file,
  written with a temporary file followed by rename; channel rows are not being
  staged and indexed in Room during the download.
- It still performs live filtering and grouping in deriveLiveHalf and caches
  derived results. The absence of import-time Room writes does not mean it
  performs no classification or caching work at all.

Reference implementation inspected at the revision above; relevant components are the Xtream request orchestrator, direct streaming parser, catalog cache, and live rendering path.

## Where StreamVault spends extra initial-live work

| Stage | Reference implementation | StreamVault |
| --- | --- | --- |
| Live requests | One full stream-list request, categories overlap | AUTO chooses category requests on LOW and MID onboarding; categories precede stream loading |
| Category path | Normal loader uses full list | Globally spaced request starts, 250 ms on healthy server; limited window concurrency |
| Parsing | JsonReader fields → Channel | Gson tree → JSON text → kotlinx DTO → Channel |
| Download/write overlap | Builds an in-memory output list | Mapping/staging callbacks suspend further reading |
| Persistence before availability | Starts live rendering before final flat-file persistence | Stages the lineup and commits Room catalog plus FTS before provider setup finishes |
| Import-time CPU | Basic row mapping; grouping performed in live derivation | Field cleanup, internal URL creation, full grouping classification, stage fingerprints |

The largest structural distinction is the request topology. For 200 categories,
our 250 ms request-start spacing alone implies about 49.75 seconds before the
last request starts, even with a fast provider. LOW devices run one category
request at a time; MID devices permit at most two. Grouped awaitAll windows
prevent free slots from picking up more work while a slow request finishes.
These are policy-derived costs, not an observed timing for the user's account.

Our existing STREAM_ALL route already uses a thin live DTO and bounded staging.
It is the foundation to improve; a new live sync engine from scratch is not
required to adopt the useful ideas in the reference implementation.

Our per-row getLogicalGroupId calls ChannelNormalizer.classify, calculating
language, resolution, codec, transport, source tags and variant attributes in
addition to the canonical group key. The import only retains the group ID.
Consider a canonical/group-key-only method after checking equivalence with the
existing classifier. Measure its CPU cost before deciding its priority.

Staging batches are 100/300/500 by device tier. Each batch runs a transaction,
checks workflow ownership, counts staged rows, deduplicates/fingerprints and
writes them. Full live promotion rebuilds global channels_fts inside its
transaction, although generated Room code has maintenance triggers. Avoiding
the rebuild needs search-correctness validation; it is not safe to delete this
call merely because it looks redundant.

## Revised implementation proposal, live first

1. Establish a live-only baseline: authentication → live categories → first
   stream headers/row → completed transfer/staging → commit → live screen
   ready. Compare AUTO and explicit STREAM_ALL on the same full channel lineup.
   This separates request topology from parsing, mapping and persistence.
2. Implement direct streaming field decoding into thin live rows. Preserve
   StreamVault's current field semantics, lenient IDs/flags, provider headers,
   category_ids, EPG IDs, channel numbers, catch-up and playback URL behavior.
   Consume supported response shapes explicitly; report truncated/malformed
   responses so they cannot be mistaken for complete deletion snapshots.
3. Use a small bounded batch queue between the reader and a single DB writer.
   Keep row/admission budgets and commit ownership. Tune batch sizes by measured
   transaction duration and memory headroom; reduce growing COUNT queries with
   safe incremental accounting and reconciliation on resume.
4. Validate bulk ingestion under constrained heaps, then make AUTO prefer the
   proven bulk path for healthy providers on LOW/MID too. Actual pressure and
   observed bulk failures still trigger fallback. Keep explicit user modes.
   Hidden categories can be filtered during bulk mapping, but that trades
   network bytes against request count and must preserve required hidden rows.
5. Reduce commit cost, including proving whether FTS trigger maintenance makes
   the global rebuild unnecessary. Retain atomic promotion, stable local IDs,
   favorites/protection and partial-import preservation.
6. Improve category fallback with continuously filled slots and pacing driven
   by server stress rather than a universal delay. Compare a new-provider fast
   path and changed-row refresh path separately; neither should discard the
   workflow/generation guarantees.

Keep the initial live catalog persistent and queryable through existing Room
interfaces. Adopting the reference implementation's all-in-memory catalog and file storage would change
too many browse/search/state contracts and increase peak memory. Borrow its
bulk request and single-parse approach while keeping bounded ingestion.

A full-catalog-ready milestone should remain distinct from any future small
preview milestone. Improving real complete-live throughput is the requested
goal; changing the completion label is insufficient.

## Evidence needed before calling a fix faster

Use identical provider data, device, network and channel coverage. Record
request count/bytes, end-to-end ingest, parse/map CPU, writer wait, stage totals,
commit/FTS time, first live-screen readiness and peak heap/GC. Current full-path
logs already report total ingest, mapping and staging time; commit logs report
category staging/commit, channel apply and FTS rebuild. Add missing parser,
request/header and readiness measurements. Current ingest elapsed includes
callback DB work, so it is not pure network time.

Test small/large lineups, many tiny categories, slow/throttled/bulk-rejecting
providers, malformed rows, process-death/cancellation, and hidden categories.
Verify complete channel counts, EPG IDs/numbers/catch-up flags, search,
stable favorites/protection and playback URLs. No speed ratio is established
by this source comparison. Playback bug validation, if a change affects it,
must follow the repository's sustained screenshot/log instructions.

The the reference implementation checkout was reviewed as a reference. No source was copied into
StreamVault; the proposed changes are implementation ideas for our existing
pipeline.
