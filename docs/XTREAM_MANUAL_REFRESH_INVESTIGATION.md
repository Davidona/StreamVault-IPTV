# Xtream manual refresh waiting at zero

Validated on October 23, 2026.

## Confirmed blocking condition

Settings calls `syncSection` for a manual Movies or Series refresh. The section
executor queues catalog indexing, and Settings waits for its persisted job to
finish. XtreamIndexWorker appended this work to `provider-workflow-{providerId}`,
the same WorkManager chain used by provider recovery and configuration work.
A recovery request waiting for its delay therefore blocks the catalog request
before it can fetch titles. The persisted index stays QUEUED with zero rows.

## Controlled emulator reproduction

The opt-in `XtreamMovieRefreshRuntimeTest` uses a local HTTP fixture and the same
section command invoked by Settings. It does not automate tapping the dialog.

1. Add a fixture provider and successfully import one movie and one series.
2. Insert a provider recovery request with a ten-minute delay in the provider
   workflow chain, modelling a pending recovery/backoff prerequisite.
3. Make a second movie and series available at the HTTP endpoints.
4. Invoke manual Movies and Series refreshes on the existing provider.

Before the fix, both jobs remained QUEUED with zero rows. WorkManager reported
the two index requests as BLOCKED. Waiting for the updated catalog timed out
after 90 seconds; the complete test failed after 105.862 seconds.

After the fix, the five-second sample showed SUCCESS with two rows for each
section. The recovery request was still ENQUEUED, verified by assertion. Existing
movie and series IDs were preserved. The subsequent automatic movie catch-up
also succeeded. The complete instrumentation test passed in 64.412 seconds.

Sanitized local evidence is in the ignored directory
`artifacts/xtream-manual-refresh/`: `before-instrumentation.txt`,
`after-instrumentation.txt`, and `probe-comparison.txt`.

## Change and verification

Manual Xtream indexing now uses `provider-xtream-index-{providerId}` independently
of provider recovery. Movie and series index slices retain their ordering within
that queue. Automatic work keeps its existing queue. Provider workflow leases
and locks still govern execution. Existing persisted requests are not cancelled.

Both debug APKs built successfully. The scheduler integration, work scheduling,
Xtream worker, and sync manager suites passed: 132 tests, zero failures. The
emulator instrumentation test passed with the delayed recovery still pending.

## History and limits

Commit `e6a715f5` on July 30, 2026 changed Xtream indexing from separate index
work names to the shared provider workflow name. Commit `470d49d7` on October 19
added the Settings display of queued state and indexed row count, shipped in
1.2.1. This blocking condition predates that display.

This is a reproduced scheduling defect under a specific pending-recovery
condition. No logs from the reporting users were supplied, so it does not prove
their devices had that same predecessor. It provides no evidence for the
previous empty-response hypothesis, whose speculative patch was reverted.
