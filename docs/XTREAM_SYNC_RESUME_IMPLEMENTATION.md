# Coalescing completed Xtream primary recovery work

Targeted recovery previously called full sync unconditionally. Periodic work
also called full sync when its earlier read found incomplete onboarding. A job
queued while foreground catalog work was running could acquire the provider
lock after completion and download the same live lineup again. Measured refresh
accounts sometimes made three complete bulk requests instead of the expected
two for initial import plus refresh, roughly doubling refresh latency.

Both recovery routes now call `resumePrimaryCatalog`. SyncManager rechecks persisted
state after acquiring its provider lock. An Xtream provider can skip
redundant primary work only when:

- onboarding has no incomplete receipt;
- the provider is ACTIVE or in the transitional PARTIAL state;
- the last sync status is SUCCESS;
- the last live success is fresh under the existing catalog TTL;
- no newer unsuccessful live attempt is recorded;
- the actual persisted live count matches metadata.

Failed, partial, stale and incomplete catalogs still run recovery. Initial setup
can still have an inactive PARTIAL provider row while its successful catalog is
already committed and the repository is finalizing activation. The metadata
and onboarding proofs distinguish that transition from an actual partial sync;
provider activation flags alone do not determine catalog completion.
Candidate configuration workflows retain their separate generation-aware path.
Manual full sync and Settings Live refresh retain explicit request semantics,
including nonforced manual full sync with fresh metadata. Other protocols retain
their existing full recovery behavior. Successful foreground work already owns
its durable movie/series/EPG continuations; skipping its duplicate primary import
does not cancel those jobs.

Regression tests exercise a resume queued behind foreground sync, fresh catalog
coalescing, incomplete-count recovery, failed-attempt recovery and existing
manual/worker/workflow behavior. The queued test confirms one live retrieval
across both callers after the foreground transaction completes.
The initial-activation test explicitly uses an inactive PARTIAL provider with
successful complete catalog metadata.

The 10,000-row/100-category emulator run passed all 12 requested samples and
made exactly 12 complete bulk responses, with exactly two requests per account.
Report: `build/xtream-sync-performance/20261010T080831597314Z/report.json`.
Initial median was 5,525 ms and refresh median 5,067 ms. Host unit-test execution
overlapped part of this run, so these times are not a controlled comparison with
P4 medians. No duplicate-import refresh tail occurred in the measured accounts.
This proves the tested path and coverage, not all possible background overlap.

The larger fixture reproduced another necessary handoff: initial provider
activation had not finalized when the periodic resume acquired the lock.
The intermediate runs still made three bulk imports, despite fixing the targeted
route. Their reports are `20261010T081002546566Z`, `20261010T081445540937Z` and
`20261010T081842264251Z` under the same benchmark directory. Recognizing complete
catalog metadata while that provider row is transitional fixes the reproduced
handoff.

Final 50,000-row/200-category report:
`build/xtream-sync-performance/20261010T082158044977Z/report.json`.
Exactly two complete bulk responses occurred for initial setup plus refresh;
both retained 50,000 channels, and cleanup/count assertions passed. Initial setup
took 25,863 ms and refresh 26,765 ms. The preceding run with the redundant import
took 25,213/53,129 ms. These are single-sample diagnostics, not release-device
performance medians. The fixed-format numeric/enum log evidence in
`resume-proof.log` confirms an inactive PARTIAL provider was coalesced. Setup's
final sync state was Success.

Large-catalog writer cost remains substantial. This work removes duplicate
primary retrieval; it does not claim to meet the complete latency target.

Normal app builds on this branch now use bulk-first AUTO. The policy change and
fresh synthetic rechecks are documented in the [P6 tuning report](XTREAM_SYNC_P6_TUNING_REPORT.md);
physical-device and first-screen acceptance remain open.
