# Trakt history synchronization

## Current implementation — October 12, 2026

The authenticated read API fetches one mixed history page at a time, with a limit of
100 rows. It shares the account mutex and single-use token rotation with playback
and profile requests. A 401 can trigger one refresh; a second 401 requires reconnect.
Rate-limited reads return a status without retrying. Missing pagination headers use
the raw row count, so skipped malformed rows do not prematurely end pagination.

Movie entries require a positive TMDB movie ID. Episode entries require a positive
parent-show TMDB ID and exact season/episode numbers. Unsupported or malformed rows
are counted and skipped. History IDs retain 64-bit integer precision. The endpoint
and response format were checked against the
[official Trakt sync contract](https://github.com/trakt/trakt-api/blob/master/projects/api/src/contracts/sync/index.ts).

The matching index deduplicates repeated history IDs across pages. Conflicting
identities for the same history ID match neither item. It reports the latest watch
and distinct play count for each exact media identity.

## Merge policy

- Import candidates must match a catalog-resolved media identity and content kind;
  episodes must also match the local season and episode numbers.
- Newer local rewatch progress and explicit manual completion are preserved.
- Imported completion is automatic completion, with the newer watch timestamp and
  the larger play count. Older progress advances to the known duration so existing
  library queries reflect completion. Newer local rewatch progress, metadata, and
  provider identity remain intact.
- Applying the same match twice produces no second candidate.
- An item absent from remote history produces no change. Nothing is deleted or
  marked unwatched automatically.

The policy returns candidates. A dedicated repository operation rechecks current
persisted/pending history under its lock and in a Room transaction before applying
one candidate and updating catalog projections. It assigns automatic completion,
preserves the remote watch timestamp, and never increments the play count merely
because a sync was repeated. Current incognito preferences suppress writes.
Completed episodes are excluded from unwatched counts even with unknown duration.
No real account history was fetched or changed by automated validation.

## Manual Sync Now

Settings → Trakt → Sync Now stages the entire bounded remote read first (up to 50
pages / 5,000 records). A failed page or exceeded limit produces no writes. Movie/show
catalog lookup uses bounded TMDB batches; episode matching uses cached exact
provider/series ownership and season/episode numbers. It does not fetch provider
catalogs or guess titles.

Account changes stop the run. Local import failure may leave earlier successful
imports; the UI reports that count, and retrying is idempotent. The result includes
imported, uploaded, unchanged, unmatched, and skipped counts. Outbound sync uses the
newest completed local event per exact media identity, skips events already present
in Trakt, and sends batches of at most 100. Repeated Sync Now runs reconcile against
Trakt history before another POST; uploads are never automatic. Incognito mode blocks
the operation. Ambiguous write failures stop the run, and the next manual run reads
history before deciding what remains. There is no automatic polling or history
deletion. Local history stores only each item's latest watch time, so older local
rewatches cannot be reconstructed as separate dated events.

## Remaining work

The authenticated empty-history Sync Now path passed on the isolated Codex_Trakt
emulator. The connected account returned a successful result with zero imported,
uploaded, unchanged, unmatched, or skipped items. The emulator had no provider/catalog,
so this did not exercise media matching or outbound upload and changed no Trakt history.
Still verify a non-empty account and library, including rewatch/manual completion
conflicts, repeated sync, and interruption. Larger histories need a bounded
continuation design before expanding the current read limit.

Trakt now normalizes `watched_at` to minute precision and its API team says the same
item plus minute timestamp deduplicates naturally, though the current history contract
still has an older warning assigning duplicate prevention to clients. StreamVault
therefore canonicalizes uploads to UTC minute timestamps and preflights all staged
history before writing. A capped or failed read still blocks every upload.

Local test/build/lint output: `build/trakt-history-import-final-validation.log`. Tests cover
mixed pages, pagination, malformed rows, duplicate/conflicting IDs, merge conflicts,
401/rate-limit handling, history/playback sharing one token rotation, staged-read
failures/limits, account switches, incognito, idempotent transactional persistence,
catalog progress, episode counts, and the manual settings action. The outbound follow-up
adds MockWebServer payload/response tests and service coverage for exact movie/episode
resolution, minute-precision reconciliation, the 100-event cap, incognito and account
changes, refresh retry, and ambiguous failure handling. All 52 Trakt data tests and 18
Trakt domain tests pass locally; these tests do not write to a real Trakt account.
Real-account sync/device acceptance remains open.
