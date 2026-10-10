# Stalker direct live decoding and bounded ingestion

This is the initial S3/S4 implementation snapshot. The follow-up completed the
remaining S4 overlap work and implemented S5/S6; see
[the follow-up report](STALKER_SYNC_S4_S6_IMPLEMENTATION.md) for current status.

Implemented 2026-10-10. Completes S3 and implements the ingestion/retry portion of
S4 in [the improvement plan](STALKER_SYNC_IMPROVEMENT_PLAN.md).

## Direct decoding

The bulk live response reader now consumes mapped scalar fields and command
variants directly from `JsonReader`. Unknown nested metadata is skipped without
building Gson trees. The direct and retained legacy readers share the existing
record mapper, preserving category/EPG fields, adult flags, command descriptors,
temporary-link and archive/PVR capabilities, extensions, and other mapped
metadata. Generic VOD, series, detail, guide, and upfront resolver decoding are
unchanged.

Scalar coercion, aliases, last-key-wins behavior, command-array indexes, malformed
command shapes, and missing-row identity/name admission are preserved. The
complete-response path now also checks `END_DOCUMENT`; extra JSON after a valid
catalog is a failure rather than a completed snapshot. Intentional bootstrap
prefix termination remains distinct from full-response completion.

## Bounded pipeline and attempt ownership

Bulk live loading now uses one raw-row producer, a one-batch queue, and one
consumer that owns identity mapping and staging callbacks. The consumer uses
the existing runtime row budgets, with estimated raw batch byte budgets of
256/512/1024 KiB for LOW/MID/HIGH. Wide rows exceeding the batch byte budget fail
explicitly. Direct callers without a runtime profile retain a 500-row limit and
a 1 MiB byte budget.

Memory accounting includes the producer's current batch, a queued batch, the
consumer's batch, and the separate mapped staging batch. The queue does not
remove the existing admitted-ID deduplication set, which remains governed by
the provider catalog row limit. The byte estimate limits retained batches; it
is not a measured total heap bound or a token-level scalar allocation limit.

Reader failure, writer failure, and cancellation close the queue and join the
consumer before retry or attempt cleanup. Normal writer cleanup does not turn
a returned portal error into cancellation. Authorization retry invokes an
attempt-start callback that discards the previous session, counters, duplicate
state, buffered rows, and fallback categories. It reloads category context after
reauthentication before starting the replacement stream.

Staging uses admitted-count accounting for fresh attempts and materializes
fallback categories at completion. The bulk stall watchdog distinguishes local
mapping/writes and queue backpressure from network inactivity. Cancelled staging
cleanup runs in a noncancellable context after the pipeline has stopped.

The 200-row bootstrap cap is preserved. A successful prefix no longer records
a positive bulk category-fidelity verdict; that evidence requires a completed
full response. Durable full sync still follows bootstrap through existing
continuation behavior.

## Initial follow-up status (superseded)

Live categories still load before bulk HTTP. Concurrent category/bulk requests
require explicit admission ordering so a bulk response holding the only portal
permit cannot fill its queue while its consumer waits for queued category work.
That ordering has not been implemented; S4 remains partially complete.

Incremental category fallback (S5), remaining readiness/scheduling integration
(S6), and final end-to-end provider/playback acceptance are also pending.

## Validation

- 356 unit tests across 26 suites passed, with no failures, errors, or skips:
  `:data:testDebugUnitTest --tests '*Stalker*Test' --tests '*SyncManagerTest' --tests '*ProviderRepositoryImplTest'`.
- Decoder differential tests compare the retained legacy path across complete
  mapped fields/descriptors, 1,000 mixed scalar/alias rows, duplicate keys,
  command shapes, and truncation.
- Coroutine-barrier tests verify reader backpressure, ordered drain, reader error,
  writer failure, full-queue cancellation, joined cleanup, and wide-row rejection.
- SyncManager tests force an authorization failure after actual staged writes,
  then verify a fresh session is promoted and the failed session is discarded.
  Bootstrap tests verify 200 staged rows without a positive fidelity verdict.
- Two `StalkerLiveIngestionIntegrationTest` tests passed on `emulator-5554`
  (`Television_4K_2(AVD) - 16`). They use the real HTTP decoder, Stalker mapper,
  identity resolver, pipeline, staging helpers, and Room promotion. Only initial
  authentication is supplied by the fixture; production discovery is unchanged.
- The Room tests verify additions/deletions, stable local IDs, retained favorites,
  EPG/archive fields, stream descriptor equivalence, and FTS rename behavior.
  Trailing and truncated documents leave the verified catalog intact and clear
  failed staging.
- `:app:assembleDebug -PcompatAbi=x86_64` passed. The APK was built, not installed.
- `git diff --check` passed.

Logs: ignored `build/stalker-s3-s4-validation.log`,
`build/stalker-s3-s4-room.log`, and `build/stalker-s3-s4-app-build.log`.
No before/after speed measurements were run and no measured speedup is claimed.
Real-provider setup and the two-channel sustained playback acceptance were not
run; synthetic import/Room checks do not count as playback validation.
