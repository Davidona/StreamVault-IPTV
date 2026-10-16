# Stalker S4-S6 follow-up implementation

Implemented 2026-10-10. This report extends the initial
[S3/S4 implementation snapshot](STALKER_SYNC_S3_S4_IMPLEMENTATION.md) and
records current status for the [improvement plan](STALKER_SYNC_IMPROVEMENT_PLAN.md).

## S4: eligible category and bulk overlap

The live category request starts before the bulk request. When the provider's
adaptive metadata limit permits two requests, bulk HTTP can run while categories
load; the single bulk consumer waits for the complete category context before
mapping any row. When stress state lowers admission to one, categories finish
before bulk starts, so bulk cannot hold the only slot while waiting on its own
consumer. Known category-only portals continue to use category fallback.

The coordinator retains request priority, rate-limit cooldown, provider stress,
and cancellation behavior. Existing bounded pipeline rules still own raw batches,
mapping, staging, authorization retry, and reader/writer cleanup.

## S5: incremental category fallback

Category fallback uses the validated paged `get_ordered_list` reader and bounded
mapped batches. Every full category attempt has a separate staging session. A
transport/server retry starts with a fresh session after the previous pipeline
drains; failed attempts are discarded. Completed categories merge into the
catalog stage in provider order, so concurrent completion order does not choose
the duplicate row winner.

Complete refreshes prune only successfully fetched category scopes. Failed or
unvisited scopes retain their verified channels. A partial category result does
not advance `lastLiveSuccess`. Failed category scopes are persisted in the Room
`stalker_live_category_retries` table with their category ID/name, configuration
generation, attempt count, and sanitized error. The next ordinary live/provider
sync fetches only queued, still-visible scopes; successful scope promotion and
queue deletion happen in the same Room transaction. A verified full bulk refresh
clears the queue. Entries for changed provider configurations, removed categories,
and intentionally hidden categories are discarded. Portal-wide authorization,
account, rate-limit, or transport-consent failures stop further category
scheduling and are not recorded as independent category retries.

Bootstrap fallback is sequential and stops requesting later categories once the
200-row accepted prefix is reached. The prefix uses upsert-only promotion, does
not certify bulk fidelity, and retains the existing durable full-provider
continuation.

## S6: readiness and continuation

Live readiness is published after staged promotion returns; category readiness
follows category-shell commits. The coordinator passes bootstrap's typed
`FULL_CATALOG` receipt to `ProviderContinuationScheduler`, which schedules the
existing provider-resume work. The focused bootstrap test verifies readiness,
the 200-row limit, stale full-catalog metadata, and the durable resume call.
Existing repository coverage continues to check save-with-verification-pending
and provider configuration revision behavior.

## Validation

- Focused unit run passed: 361 tests across 26 suites, no failures, errors, or
  skips. Command:
  `:data:testDebugUnitTest --tests '*Stalker*Test' --tests '*SyncManagerTest' --tests '*ProviderRepositoryImplTest'`.
- New `SyncManagerTest` cases cover overlap while category loading is blocked,
  fallback bootstrap stopping before the next category, and preserving a stale
  live-success timestamp after a category failure.
- `StalkerLiveIngestionIntegrationTest` passed on `emulator-5554`, covering Room
  promotion, partial-scope pruning, stable IDs/favorites/search, bounded category
  pages, and rejection of trailing/truncated catalogs.
- `git diff --check` passed on the final working tree.
- Durable per-category retry tracking was added after that validation run; it has
  not yet been covered by a test run in this follow-up. Production and unit-test
  Kotlin compilation passed, the app/Hilt graph compiled, and KSP generated the
  version 80 Room schema.
- The emulator has the sync benchmark packages but not the StreamVault app. No
  real provider credentials/session were available. Two-channel sustained live
  playback and real-provider onboarding/continuation therefore remain unverified.
- No before/after timing comparison was run; no numerical speedup is claimed.
