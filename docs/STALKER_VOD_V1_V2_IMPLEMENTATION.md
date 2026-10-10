# Stalker VOD V1/V2 implementation

Implemented 2026-10-10. Covers V1/V2 and the conditional V3/V4 decisions in
[the VOD improvement plan](STALKER_VOD_IMPROVEMENT_PLAN.md).

## V1: bounded empty-first-page recovery

Production background indexing now retries only the requested first page when
that page succeeds with no items. The retry uses the existing paged provider
route and returns its original page number, total-page count, advertised totals,
truncation state, and termination reason. The old recovery called
`getVodStreams`/`getSeriesList`, which used the same `get_ordered_list` requests
but accumulated a complete category and then represented it as page 1 of 1.

The retained compatibility behavior is the extra request after an empty first
response, which can recover a transient empty response. For Series, each paged
attempt still prefers the native series route and falls back to one VOD page
filtered by `is_series`; split VOD still uses the classified page path, preserving
movies and VOD-derived series. Existing provider tests cover mixed classification
and page order.

An error from the retry now propagates to the index executor, so it cannot turn
the original empty response into successful coverage. Cancellation unwinds
before any row write or successful-page checkpoint. The page result is committed
through the existing page flow; subsequent page errors preserve already committed
rows and resume from their durable checkpoint. Public list-returning provider
contracts remain available, but summary ingestion no longer calls them.

No category staging layer was needed: this recovery no longer performs a
complete-category attempt or emits rows while a request is in flight. It consumes
one normal paged response, so existing page anomaly checks and background slice
continuation remain authoritative.

## V2: page-local category work

Unified hydration no longer loads every VOD category to find one category's
protection bit. `CategoryDao` now has a targeted provider/type/category lookup.
Hydration reads that mutable flag inside the same Room transaction that writes
movie/series rows, replaces ordered catalog entries, counts persisted entries,
and writes the successful-page checkpoint. A protection edit made while the
network request is running is therefore read at commit time; protection is not
cached across pages or requests.

Split hydration already resolves the movie category before its page loop. Its
series/category counts and page checkpoint reads still affect eligibility,
native-series precedence, or persisted progress, so they remain. Background
indexing reloads hydration at window admission and after the slice to account for
durable progress and concurrent workers. Summary rows commit before their
successful-page checkpoint is advanced; a crash in between replays an idempotent
page rather than skipping uncommitted data.

The interactive coordinator now compares each response fingerprint with both
pages seen during the current COMPLETE request and the last committed page
fingerprint. This catches a portal repeating the last page across separate
NEXT_PAGE calls without replacing the durable successful-page checkpoint. Native
Series-page fallback to VOD is explicitly marked `VOD_DERIVED` in both paged and
legacy list APIs; focused tests cover split mixed-order mapping, unified mixed
mapping, and the fallback classification.

Stalker background index mutations now assert the active provider-workflow lease
inside their Room transactions. This covers summary row/index writes, hydration
attempt and success/failure checkpoints, job state, summary metadata, watch-progress
restoration, and stale-row pruning. A focused test lets a page fetch finish, marks
the lease superseded before its row transaction, and verifies that rows and the
successful-page checkpoint remain unchanged.

## V3/V4 decisions

V3 keeps the existing bounded fetch windows. `awaitAll` preserves the requested
category order, pages remain sequential per category, and the writer processes
the validated window in that order. This is the current duplicate-precedence and
checkpoint ownership rule. A queue pipeline would add retained payloads,
backpressure, and portal-permit coordination without a correctness need, so no
fetch/write overlap was added.

V4 defers direct VOD decoding. Movie, native-series, and classified VOD mapping
consume different metadata and playback descriptors, and the live decoder cannot
stand in for those field contracts. V1 no longer needs a whole-category list, so
the separate decoder work is not required for this change.

## Validation

- Focused unit run passed:
  `:data:testDebugUnitTest --tests '*SyncManagerTest' --tests '*VodCategoryHydrationCoordinatorTest' --tests '*StalkerProviderTest'`.
  It covers empty-first-page retry call counts, actual page provenance, no
  whole-list fallback call, and targeted protection lookup inside the commit
  transaction.
- The focused tests also cover resume after a failed NEXT_PAGE, a repeated page
  across calls, a changed advertised page total, successful sequential COMPLETE,
  and the 200-page COMPLETE limit remaining `TRUNCATED` and incomplete. A failed
  empty-first-page retry also leaves the successful checkpoint unchanged, avoids
  movie row writes, and does not run stale-row pruning.
- Room instrumentation run passed:
  `:data:connectedDebugAndroidTest` with
  `-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.local.StalkerVodHydrationIntegrationTest`
  passed on the `Television_4K_2(AVD) - 16` emulator against the current database
  schema. It covers mixed movie/series page order, category protection, stable
  movie IDs, favorites, watch progress, updated movie FTS results, the
  completed-page checkpoint, and transaction rollback after a simulated failure.
- A Room lifecycle instrumentation case now closes and reopens the database,
  verifies the committed VOD page checkpoint and partial Stalker index job are
  still available for continuation, then deletes the provider and verifies Room
  cascades clear its hydration, catalog entries, movie rows, and index job.
- Focused SyncManager tests cover resuming a fresh RUNNING Stalker job from its
  next persisted page. Shared provider workflow integration tests cover lease
  supersession. A Stalker page test also checks lease validity at the page commit
  boundary after the response has been fetched. There is not yet one combined
  scenario spanning an actual configuration edit, active background write, and
  process restart.
- The supersession case passed with:
  `:data:testDebugUnitTest --tests '*SyncManagerTest.superseded Stalker movie index cannot commit fetched page rows or checkpoint'`.
  The full `*SyncManagerTest` class also passed after adding transaction fences.
- A focused initial-sync test verifies Add Provider stores the VOD and Series
  category shells but does not request VOD or Series item pages. Indexing remains
  deferred.
- App debug build and `git diff --check` passed. The diff check emitted only
  existing working-tree LF/CRLF normalization warnings.
- Real-provider VOD browsing and representative playback: not available in this
  workspace; record as a validation gap.
- Background continuation, persisted restart state, stale-lease write fencing,
  and provider deletion now have focused component/instrumentation coverage. A
  combined provider-edit/supersession/restart scenario and real-provider behavior
  remain unverified end to end. Unit checks verify pruning occurs after full
  coverage and is skipped after failed first-page recovery.
- No live playback path was changed. No before/after measurements or performance
  benchmark suite was run.
