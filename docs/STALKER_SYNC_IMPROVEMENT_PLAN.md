# Stalker Add Provider and catalog ingestion improvement plan

Status: S1-S6 implementation is applied and focused correctness/recovery checks
pass. Real-provider onboarding and sustained live playback acceptance remain
unverified because this workspace has no installed app/provider session.
Prepared: 2026-10-10 against the current working tree, including the uncommitted
Xtream sync improvements.

Implementation evidence: [S1/S2 implementation](STALKER_SYNC_S1_S2_IMPLEMENTATION.md).
S3/S4 evidence: [S3/S4 implementation](STALKER_SYNC_S3_S4_IMPLEMENTATION.md).
S4-S6 follow-up: [overlap, category coverage, readiness, and continuation](STALKER_SYNC_S4_S6_IMPLEMENTATION.md).

## Goal and boundaries

VOD follow-up: [Stalker VOD ingestion improvement plan](STALKER_VOD_IMPROVEMENT_PLAN.md).
It retains pagination and tracks bounded recovery, repeated page work, and
conditional pipeline/decoder improvements separately from S1-S6.

Reduce avoidable work when adding a Stalker provider and when completing its
live catalog in the background. Apply the same ingestion improvements to
Settings live refresh where the existing shared path permits it.

Preserve the existing upfront resolver/discovery flow. Its portal selection,
authentication, compatibility/profile checks, capability validation, content
type detection, and split versus unified catalog decisions are working behavior
to retain. Do not bypass checks, weaken verification, reorder discovery recipes,
or change classification heuristics as part of this plan.

Optimize how successful resolved results are reused and how accepted catalog
rows are decoded, mapped, identified, and persisted after resolution. The
remote-ID database resolver discussed below is a separate persistence concern;
its identity rules also remain unchanged.

No baseline, before/after timing comparison, performance benchmark suite,
physical-device tuning campaign, or numerical speedup gate is required. Validate
correctness, compatibility, bounded resource use, and successful completion.
Do not claim a measured speedup without measurements.

## Current behavior to retain

- New-provider onboarding uses bootstrap sync: at most 200 live rows are read,
  category shells are prepared, and durable full-catalog work is queued.
  This is initial availability, not a complete lineup.
- Provider edits validate and promote the replacement catalog through the
  existing staged configuration and commit callback.
- Bulk live loading already precedes category fallback, with learned capability
  state, a stall watchdog, and provider-aware request admission.
- Movie/series item indexing and guide work already have continuation paths.
- Authentication/session caches, custom headers, transport configuration,
  request priority, throttling, and stress cooldown remain authoritative.
- Shared Xtream work already supplies the cheaper logical group-ID calculation
  and staged live promotion using Room FTS content-sync triggers. Reuse those
  changes; do not implement duplicates or reintroduce global FTS rebuilds.

Relevant source:

- `data/.../repository/ProviderRepositoryImpl.kt`: discovery and onboarding.
- `data/.../remote/stalker/StalkerDiscoveryCoordinator.kt`: upfront discovery.
- `data/.../remote/stalker/StalkerProvider.kt`: layout detection, mapping, caches.
- `data/.../remote/stalker/OkHttpStalkerApiService.kt`: streaming row parsing.
- `data/.../remote/stalker/StalkerRemoteIdentityResolver.kt`: persistent IDs.
- `data/.../sync/StalkerCatalogSyncExecutor.kt`: live staging and fallback.
- `data/.../sync/SyncCatalogStore.kt`: transactional catalog promotion.

## S1 — Reuse successful discovery results

Status: **Implemented and correctness validated** (all five implementation items).

Keep every required discovery request and decision. Retain successful category
responses already obtained during layout detection so the subsequent setup flow
does not fetch the identical categories again.

Implementation:

1. Introduce an internal resolved-category snapshot containing the raw category
   records, endpoint/content type, resolved layout, and the session/configuration
   identity needed to establish that it belongs to the current provider.
2. Retain successful empty responses as successful empty responses. A failure,
   timeout, or inconclusive result must not become an authoritative empty list.
3. Hand the snapshot from the discovery client to the sync client through an
   explicit, short-lived in-memory handoff. Account for the new provider ID
   assigned after discovery; map remote IDs only in the final provider scope.
   Do not put session tokens or raw category payloads in sanitized discovery
   summaries or persist them as new credential copies.
4. Consume it through the existing category mapping and classification path,
   including adult flags, wildcard category behavior, and unified/split routing.
5. Invalidate on configuration changes, authentication invalidation/session
   replacement, incompatible layout, deletion, or expiry. A process restart or
   cache miss uses the current network path normally.

Acceptance: setup consumes matching successful discovery categories without a
duplicate request; misses and stale snapshots still fetch normally. Existing
resolver outcomes and error behavior remain equivalent.

## S2 — Reduce per-row database and category lookup work

Status: **Implemented and correctness validated** (all five implementation items).

Optimize identity persistence without changing assigned IDs or collision rules.

Implementation:

1. Add chunked DAO queries for existing raw IDs and occupied candidate surrogate
   IDs. Respect SQLite bind limits; never build an unbounded `IN` query.
2. Under the existing provider/content-type lock and transaction, reuse known
   identities and assign missing identities in the current sorted order.
   Preserve preferred category IDs, numeric candidates, legacy hash candidates,
   and high-surrogate fallback precedence.
3. Track occupied/new candidates within the batch, batch-insert new records, and
   retain conflict recovery for external writers. Avoid `REPLACE`, which could
   silently change an established mapping. Read high-surrogate allocation state
   once when needed and advance it safely within the transaction.
4. Keep memory bounded to the current identity batch. Preserve reverse lookup
   and category migration/name reconciliation behavior.
5. Build immutable category lookup maps when category seeds change. Preserve
   the existing first-match behavior when raw ID and name matches disagree or
   duplicate names exist, case-insensitive name matching, fallback names, and
   category ordering. Rebuild maps when projection adds category seeds.

Acceptance: numeric, nonnumeric, duplicate, colliding, and preexisting identities
resolve identically; refresh preserves local IDs and user state; category mapping
matches the existing list-based implementation.

## S3 — Add a direct live-row decoder

Status: **Implemented and correctness validated** (all five implementation items).

Replace per-row Gson trees only on the bulk live ingestion path. Keep generic
VOD, series, detail, and guide decoders outside this change.

Implementation:

1. Read retained live fields directly from `JsonReader` into a live record or
   compatible `StalkerItemRecord`; skip irrelevant nested payloads without
   allocating their trees.
2. Preserve field aliases, scalar coercion, null/default behavior, duplicate-key
   handling, text decoding, and supported array/wrapper/object-keyed shapes.
3. Preserve all fields needed for channel admission and playback: raw identity,
   name/order, category, logo, EPG ID, adult status, command/direct URL, extension,
   temporary-link/playback descriptors, archive and timeshift/PVR capabilities.
   Audit `toItemRecord` and `toChannel` together before selecting retained fields.
4. Preserve portal error and invalid-token detection. A truncated document or
   transport failure cannot be accepted as a complete catalog. Separate invalid
   rows from invalid document structure and retain current admission limits.
5. Keep the legacy reader available internally until differential compatibility
   checks pass. Do not add user-facing experimental decoder options.

Acceptance: fixture rows produce equivalent accepted channel fields and playback
descriptors; malformed/error/truncated responses retain failure semantics.

## S4 — Overlap live metadata and bounded ingestion

Status: **Implemented; focused concurrency and recovery checks pass**.

- [x] One producer, one queued raw batch, and one mapping/staging consumer.
- [x] Runtime row budgets and estimated retained-byte batch limits.
- [x] Consumer-owned counters, deduplication, and deferred fallback-category snapshots.
- [x] Fresh staging state and refreshed category context on authorization retry.
- [x] Joined reader/writer cancellation and failed-attempt cleanup.
- [x] Preserve the 200-row bootstrap prefix without recording full fidelity.
- [x] Watchdog distinguishes local work/backpressure from network stalls.
- [x] Overlap eligible live-category and bulk HTTP requests.
- [x] Keep limit-one/stressed portals sequential to avoid holding the only permit
  while the bulk consumer waits for category context.
- [x] Exercise overlap with a blocked category response and verify the bulk HTTP
  request starts before categories complete.

After upfront resolution completes, fetch live categories and the selected bulk
live response concurrently when admitted by the existing Stalker coordinator.
Use one bulk request, a bounded queue of raw live-row batches, and one consumer
that resolves identities, maps channels, and writes staging batches.

Implementation:

1. Reuse Xtream pipeline concepts without forcing Xtream DTOs or category rules
   onto Stalker. Resolve immutable category context once before mapping rows.
2. Preserve learned bulk support/fidelity selection. Known category-only portals
   enter fallback directly. When category discovery is needed for route selection,
   retain that dependency; overlap only where the bulk choice is already valid.
3. Respect existing metadata concurrency, cooldowns, priorities, and network
   permits. Category retrieval must remain able to finish while the bounded
   reader queue is full, including when the provider limit has dropped to one;
   never create a request-permit/queue deadlock.
4. Start conservatively with one queued batch and existing row batch sizes.
   Count reader, queued, and writer batches in retained memory. Add a retained-byte
   bound for wide records using the existing admission policy.
5. Keep counters, deduplication, category collection, and staging-session ownership
   in the single consumer. Materialize fallback categories at completion rather
   than copying their growing snapshot after every batch.
6. Give each bulk attempt a fresh staging session. Automatic authorization
   retry must restart the whole attempt after cleanup, rather than replay rows
   into the previous attempt's writer state. Join cancelled work before fallback.
7. Cancellation or writer failure closes the response and queue and discards
   staging. Promotion requires a valid completed response and drained writes.
8. Treat the existing 200-row bootstrap limit as an intentional partial result.
   Preserve its current admission/count semantics and durable full-sync handoff;
   never mark the partial bootstrap as a complete catalog or bulk-fidelity proof.
9. Distinguish network read progress from local writer/backpressure time so the
   bulk stall watchdog does not misclassify healthy database work as a stalled
   portal. Retain prompt timeout and cancellation for actual network stalls.

Acceptance: concurrency limits hold, slow categories and a full queue terminate,
writer/reader failures cannot promote partial attempts, and bootstrap still queues
full sync. No unbounded lineup list is introduced on the bulk route.

## S5 — Stage category fallback as categories finish

Status: **Implemented; bounded streaming and durable failed-scope retries are wired**.

Keep the current category concurrency cap and stress-based downgrade policy.
Replace retained whole-lineup outcomes with bounded category staging.

Implementation:

1. Stage each category attempt separately. Merge its rows into the import only
   after that attempt succeeds; discard a failed attempt before retrying it.
2. Retain compact coverage/count/error receipts instead of every completed
   category's channel list. Bound the remaining current-response memory; where
   the paged API still materializes a list, stream or consume bounded pages.
3. Preserve deterministic ordering and duplicate admission across categories.
   Concurrent completion order must not decide which duplicate row wins.
4. For bootstrap, stop after the current accepted prefix limit is satisfied;
   do not request later categories merely to truncate the combined list to 200.
   The full provider continuation remains durable.
5. For complete refresh, prune only confirmed complete scopes. Failed categories
   preserve existing verified rows, hidden categories remain preserved, and
   required hidden rows still participate under current rules.
6. Persist failed category IDs, names, configuration generation, attempt count,
   and sanitized error in the Room live-category retry queue. On the next ordinary
   sync, fetch only those still-visible scopes. Commit retry-queue updates in the
   same transaction as channel promotion; a successful full bulk refresh clears
   the queue. Keep `lastLiveSuccess` stale until all retry scopes complete.
   Portal-wide authentication/account/rate-limit failures are not stored as
   independent category retries.

Acceptance: fallback imports all successful scopes, partial failure preserves
verified data, retries do not retain failed rows, and bootstrap avoids unnecessary
remaining category downloads without claiming full coverage.

## S6 — Integrate readiness and durable follow-up

Status: **Readiness and durable bootstrap handoff verified in focused tests**;
the real-provider lifecycle acceptance below remains pending.

Preserve the current bootstrap/full-sync product behavior. Do not enlarge or
remove the 200-row cap as an incidental optimization.

- Publish readiness only after the relevant transaction commits. Distinguish
  initial availability, full live completion, category shells, item indexing,
  and guide completion using existing state models.
- Keep pending full live work ahead of optional item indexing and guide work.
  Use the current workflow locks, generations, and continuation scheduler;
  avoid competing work chains or duplicate scheduling.
- Ensure cancel, process restart, provider edit, and deletion cannot publish
  stale results or lose the bootstrap continuation.
- Keep setup completion/navigation and explicit save-with-verification-pending
  behavior intact. Reuse existing localized messages where possible.

## Correctness validation

Extend existing focused tests rather than introducing a performance harness.
Use fake request counts and coroutine barriers for reuse/concurrency assertions;
do not use elapsed-time speed assertions.

| Area | Required checks |
| --- | --- |
| Upfront resolver | Existing portal/profile/capability/type/layout fixtures retain their outcomes; no checks are skipped |
| Snapshot reuse | Split/unified layouts, successful empty response, error, session/configuration mismatch, new ID assignment, restart/cache miss |
| Identity persistence | Numeric/string IDs, collisions within/across batches, existing mappings, reverse lookup, provider/type isolation, transaction rollback |
| Decoder | Differential field equivalence, aliases/coercion, wrappers/keyed objects, descriptors, unknown nesting, errors, truncation, cancellation |
| Pipeline | Slow categories/writer, full queue, limit-one permits, authorization retry after staged rows, stall timeout, bounded wide rows |
| Fallback | Bulk unsupported/error, category retry/downgrade, deterministic duplicates, partial coverage, bootstrap early stop, hidden scopes |
| Catalog | Insert/update/delete/unchanged search, stable IDs/favorites/protection, provider isolation, atomic promotion |
| Lifecycle | Add Provider, staged edit, Settings refresh, full continuation, cancel, restart, supersession, deletion |

Run affected data/domain/provider unit tests and Room instrumentation for actual
identity constraints and promotion/search behavior. Exercise onboarding through
its real sync entry point and verify persisted rows and continuation state.
Build success alone is insufficient correctness evidence.

When ingestion or mapping changes affect playable channel fields, validate two
representative live channels in the emulator under `AGENTS.md`: preferably 61
screenshots per channel at 2-second intervals, progressing frames, media session
`PLAYING` with `error=null`, and sanitized logs with no fatal/stuck error or
unintended MPEG-TS fallback. Record channel names, capture counts/intervals,
unique hashes, session results, and log findings. This is playback correctness
validation, not a before/after speed comparison. Missing provider access remains
an explicit validation gap rather than a passing result.

## Implementation order and completion

S1-S6 are implemented in the current working tree. Keep changes independently
reviewable and preserve unrelated dirty Xtream work.
Prefer DAO/code changes without schema migration where current tables suffice.

- [x] S1 resolved category reuse and invalidation verified.
- [x] S2 batched identities and category lookup equivalence verified.
- [x] S3 live decoder compatibility verified.
- [x] S4 bounded ingestion, retry isolation, bootstrap prefix, and eligible
  category/bulk overlap verified; single-slot admission remains sequential.
- [x] S5 incremental category streaming, provider-order duplicate precedence,
  bootstrap early stop, and partial-scope preservation implemented.
- [x] S6 readiness milestones and durable bootstrap full-sync handoff verified.
- [x] Focused Stalker, SyncManager, and provider repository unit tests pass.
- [x] Implemented identity/import/promotion behavior passes Room integration checks.
- [ ] Real-provider onboarding and continuation acceptance verified.
- [ ] Playback correctness verified where playable imported fields changed.
- [x] Current validation gaps documented; no unsupported speedup claims.

Last updated: 2026-10-10. Checked items describe completed work in the current
working tree, not a claim that all provider/playback acceptance has passed.
