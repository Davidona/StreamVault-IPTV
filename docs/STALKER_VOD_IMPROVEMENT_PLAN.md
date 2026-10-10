# Stalker VOD ingestion improvement plan

Status: V1 and V2 are implemented; focused unit, Room, and app-build validation
passed. Real-provider browsing and playback remain unverified.
Prepared: 2026-10-10 against the current working tree.
Related: [Stalker live improvements](STALKER_SYNC_IMPROVEMENT_PLAN.md).
Implementation evidence: [V1/V2 implementation notes](STALKER_VOD_V1_V2_IMPLEMENTATION.md).

## Decision and boundaries

Keep paginated VOD loading as the default. Improve work around each page and
exceptional whole-category loading. Do not introduce a speculative single-request
bulk endpoint, request every page concurrently, or force full VOD ingestion into
Add Provider. An All/wildcard listing is still paginated.

Preserve the upfront resolver, authentication/profile checks, content detection,
split/unified layout decisions, movie/series classification, VOD-derived series,
playback descriptors, source order, and category protection. Preserve existing
interactive OPEN/NEXT_PAGE/COMPLETE behavior and background continuation policy.

No before/after measurements, benchmark suite, or numerical speedup gate is
required. Validate correctness and bounded processing; make no measured gain claims.

## Existing implementation to retain

- [x] Setup prepares category shells and defers item loading.
- [x] Interactive hydration commits page rows and checkpoints transactionally.
- [x] Successful-page checkpoints support continuation.
- [x] Existing page anomaly checks detect inconsistent totals/repeated pages.
- [x] Background indexing limits category concurrency and downgrades on stress.
- [x] Unified VOD preserves mixed movie/series classification and page ordering.
- [x] Page mapping uses the shared identity resolver improved by live work.
- [x] Limited wildcard strategy already exists with learned support.

These are existing behaviors observed in source, not new work completed by this
plan or a claim that all end-to-end acceptance has passed.

Relevant source:

- `data/src/main/java/com/streamvault/data/remote/stalker/OkHttpStalkerApiService.kt`
  (`getVodStreams`, `fetchPagedItems`, and single-page decoding).
- `data/src/main/java/com/streamvault/data/remote/stalker/StalkerProvider.kt`
  (page mapping, classified split/unified VOD, identities).
- `data/src/main/java/com/streamvault/data/sync/SyncManager.kt`
  (`fetchStalkerSummaryPageWithRecovery`, operations adapters).
- `data/src/main/java/com/streamvault/data/sync/VodCategoryHydrationCoordinator.kt`
  (interactive hydration and page transactions).
- `data/src/main/java/com/streamvault/data/sync/StalkerIncrementalIndexExecutor.kt`
  (background fetch windows, checkpointing, pruning, wildcard strategy).
- `data/src/main/java/com/streamvault/data/sync/StalkerIndexPolicy.kt`
  (pagination, deduplication, and retry decisions).

## V1 — Bound exceptional complete-category recovery

Priority: first. Status: implemented.

The normal page path is incremental. However, empty-first-page summary recovery
calls `getVodStreams` or `getSeriesList`. The VOD list API calls `fetchPagedItems`,
accumulates all pages, and recovery wraps the result as page 1 of 1. This loses
real page provenance and can retain a whole category during a background slice.

- [x] Trace production callers of complete-list APIs and separate required public
  list contracts from internal ingestion callers. Do not change unrelated providers.
- [x] Identify the compatibility difference that makes this recovery useful using
  existing fixtures/request traces. Both VOD routes ultimately use ordered-list
  requests; preserve necessary recovery instead of assuming it is redundant.
- [x] Give ingestion recovery a bounded page/batch consumption contract with
  explicit coverage and termination receipts. Reuse proven pagination checks.
- [x] Preserve real page provenance or isolate complete-category recovery staging
  from normal page checkpoints; do not label a partial prefix as page 1 of 1.
- [x] Remove complete-category recovery from summary ingestion, so no staging
  attempt or concurrent page work remains to join or clean. A retry is page-scoped;
  cancellation unwinds before the existing page commit.
- [x] Keep each recovery attempt to one paged response and preserve its coverage
  receipt. Existing anomaly, truncation, page-limit, and background-slice handling
  remain authoritative; no client-side byte cap was added.
- [x] Preserve classified split/unified behavior, including derived series, when
  replacing list-based recovery. Verify native-series fallback separately.

Acceptance: ordinary and exceptional ingestion do not retain a complete category
list; failed recovery cannot publish complete coverage or prune verified data.
Any remaining public list-returning API is explicitly documented and excluded
from claims of bounded internal ingestion.

## V2 — Reduce repeated page-local database work

Priority: second. Status: implemented.

Unified hydration reloads all VOD categories to find one category's protection
settings on each page. Split hydration already resolves category context before
its loop. Background indexing also performs repeated hydration reads; audit
their role before removing them because they may observe concurrent progress.

- [x] Resolve immutable mapping context once per safe hydration scope or use a
  targeted DAO lookup instead of loading all categories per page.
- [x] Establish how edits/protection changes become visible during a long request.
  Retain provider/category locks and generation checks; refresh mutable protection
  at commit if required by existing behavior. Do not cache it across requests.
- [x] Audit repeated hydration/count/category lookups and avoid only those that
  have equivalent authoritative in-memory state inside the same locked scope.
- [x] Keep row writes, catalog-entry ordering, and successful-page checkpoint in
  one transaction. For background operations, inspect transaction boundaries
  before changing batching; prevent checkpoints advancing ahead of durable rows.
- [x] Preserve stable IDs, favorites, watch progress, protection, and duplicate
  rules. Use batch DAO operations where existing page methods already support them.

Acceptance: equivalent persisted content and checkpoint behavior with fewer
redundant lookups, asserted through fake call counts and Room state checks.

## V3 — Evaluate bounded background fetch/write overlap

Priority: after V1/V2. Status: evaluated; retain the bounded category windows.

Background indexing currently awaits all results in a bounded category window,
then validates and persists successful pages. This is already bounded; replacing
it is justified only if a small pipeline simplifies useful overlap without
weakening ordering, scheduling, or retry behavior.

- [x] Document category/duplicate precedence and page transaction ownership first.
- [x] Decide whether to retain windows or use admitted fetches feeding a single
  writer through a bounded queue. Record the decision and reason in this document.
- [ ] If adopted, keep pages sequential within each category, preserve category
  precedence independently of completion order, and bound any reorder buffer.
- [ ] Include producer, queue, writer, mapped rows, and active request responses
  in retained-memory accounting; bound row/byte payloads explicitly.
- [ ] Release network permits before waiting on writer backpressure. Verify the
  single-permit case, stress downgrade, cancellation, and authentication retry.
- [ ] Advance each checkpoint only after its page commit. Respect slice deadlines,
  interactive priority, cooldowns, and durable continuation without losing pages.

Acceptance: failure in one category cannot corrupt another; cancellation joins
all work; committed pages resume correctly; queue pressure cannot block a portal
permit needed by the consumer. If retained windows are the better fit, mark this
stage complete with that decision rather than adding concurrency for its own sake.

## V4 — Conditional direct VOD page decoding

Priority: optional follow-up. Status: evaluated and deferred.

VOD pages already bound typical response size. A direct decoder may avoid trees,
but the live reader is not a drop-in replacement for movie and series metadata.

- [x] Audit all retained fields consumed by movie, native-series, and classified
  VOD mappers, including playback descriptors and mixed item classification.
- [x] Decide whether direct decoding is warranted after V1-V3; record the decision.
- [ ] If adopted, preserve pagination metadata, wrappers/keyed shapes, aliases,
  coercion, duplicate keys, portal errors, malformed/truncated-document semantics,
  and cancellation. Keep generic detail/episode/guide decoding outside this scope.
- [ ] Retain an internal legacy comparison path until differential fixtures pass;
  do not add a user-facing decoder switch.

Acceptance: equivalent item fields, classification, ordering, and pagination
evidence. A malformed response must never become complete coverage.

## Wildcard policy

Do not broaden wildcard use by default in this plan. Existing limited support
remains authoritative. Wider catalog-wide pagination needs separate evidence of
complete coverage, correct item categories, hidden/protected-scope behavior,
mixed movie/series classification, and safe pruning before replacing category
requests. A returned list alone is not proof of those properties.

## Validation and completion tracking

Extend focused tests using call counts, coroutine barriers, and persisted state.
Do not use elapsed-time performance assertions.

- [x] V1 empty-first-page retry call counts and real page receipt verified.
- [x] V2 targeted protection lookup and transaction placement verified.
- [x] V3 decision documented; bounded windows retained, no pipeline adopted.
- [x] V4 decision documented; direct decoder deferred.
- [ ] Split and unified VOD, mixed movie/series, native and derived series verified.
- [ ] Empty/repeated/changing-total/truncated pages and page limits verified.
- [ ] Retry after committed pages resumes correctly; failed attempts do not advance
  checkpoints or delete verified rows.
- [x] Room verifies page ordering, stable identity, favorites, protection, watch
  progress, and successful-page coverage checkpoints.
- [ ] Room rollback, search updates, and coverage-safe pruning verified.
- [ ] Add Provider deferral, interactive OPEN/NEXT_PAGE/COMPLETE, background
  continuation, edit/supersession, cancellation, restart, and deletion verified.
- [x] Focused unit/instrumentation checks and app build pass.
- [ ] Real-provider VOD browsing, continuation, and representative movie/series
  playback verified where changed; unavailable access recorded as a validation gap.
- [ ] If shared live playback behavior is touched, required sustained multi-channel
  validation from `AGENTS.md` completed; build/synthetic tests do not substitute.
- [x] Implementation notes list completed stages, evidence, and remaining gaps.

Implement V1, then V2. Resolve V3/V4 decisions before expanding scope. Update
checkboxes as work lands; preserve unrelated dirty changes and existing live work.
