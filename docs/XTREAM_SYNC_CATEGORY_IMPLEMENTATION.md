# Xtream live category scheduling

P5 on `codex/xtream-live-sync-performance`.

Live category requests use a bounded refilled coordinator: completion frees a
slot immediately instead of waiting for the slowest category in a fixed window.
Outcomes retain provider category order, while progress reports completion order.
Runtime profile caps and the remembered sequential preference remain. Completed
attempts merge under the P1 fence; failed or cancelled attempts are discarded.

Stress downgrade stops refilling until already running attempts drain, then
admits one at a time. Repeated sequential stress stops further admissions.
Authentication failures stop admissions and block provider continuation, including
when authentication fails during a sequential continuation request. The coordinator
returns a structured authentication/stress stop reason so continuation cannot undo
a terminal stop. Completed outcomes remain available for partial promotion.

Local staging and attempt-reset exceptions bypass transport retries and provider
health accounting, then escape the fetcher with their original failure type and
message. This also applies to legacy decoder fallback. Unexpected worker failure
cancels siblings; worker
CancellationException explicitly cancels the coordinator too, including workflow
supersession. Scope exit joins workers before staging cleanup.

Regression tests use the real HTTP fetcher, mapper, staging writer, coordinator
and Room store to verify local-failure propagation, sibling cancellation,
discarded staging and preservation of the active catalog. Strategy tests also
verify that stress/authentication stops suppress continuation, and continuation
tests preserve completed and unattempted outcomes when a later request loses
authentication.

Healthy live-category requests no longer have the mandatory 250 ms start gap.
Remembered stress retains pacing and backoff. Movie/series category requests
retain their existing pacing and fixed-window behavior.

HTTP 429 and server failures retain their status and Retry-After in the existing
network exception type. Both regular and streamed responses preserve these
fields instead of wrapping the exception again. Retry-After accepts seconds or
an HTTP date. Provider-wide Retry-After deadlines survive successful concurrent
requests and stress recovery; the next request waits for the later of that
deadline and adaptive backoff. Authentication is not classified as throttling.
Waiting requests recheck the deadline after waking, including when another
response extends Retry-After while they are asleep; a deterministic test covers
that extension.

## Evidence

After the failure-handling review fixes, 314 sync/HTTP Kotlin unit tests passed
with zero failures or errors. Nine new regression tests cover local writer and
attempt-reset failures, real-fetcher sibling cancellation and staging cleanup,
legacy fallback, authentication before/during continuation, and terminal stress
on both an intermediate and the final category. Existing shared-helper and
other-protocol sync tests remain in that passing set.

42 focused Kotlin tests passed, including refilled slots with a slow category,
bounded concurrency, stable result order, stress downgrade/abort, authentication,
sibling cancellation, real attempt supersession, failed-scope isolation, HTTP
status/header propagation and deterministic Retry-After waiting.

The isolated API 36 emulator retained all expected channels and passed cleanup:

| Fixture | Measured pairs | Add Provider median | Live refresh median |
| --- | ---: | ---: | ---: |
| 2,000 rows / 10 categories | 5 plus 1 warmup | 1,277 ms | 1,199 ms |
| 10,000 rows / 100 categories | 2 plus 1 warmup | 8,059 ms | 7,766 ms |

Reports:
- `build/xtream-sync-performance/20261010T080155937054Z/report.json`
- `build/xtream-sync-performance/20261010T080311051489Z/report.json`

Earlier 2,000-row category checks took roughly 2.5–2.8 seconds. Their sample
counts and device states differ, so this comparison establishes removal of the
observed pacing cost rather than a controlled release-device speedup ratio.
This emulator uses LOW profile category concurrency one: parallel refill itself
is verified by deterministic tests, not a device concurrency measurement.

At the time of this P5 report, production AUTO selection was unchanged. P6 later
enabled bulk-first AUTO in normal app profiles on this branch for direct testing.
The release gate remains open because tier overrides ran on one emulator and did
not measure physical devices or first-screen readiness. See the
[P6 tuning report](XTREAM_SYNC_P6_TUNING_REPORT.md).
The synthetic fixtures contain no playable media and do not validate playback.

The bulk-HTTP-503 fallback fixture also passed both operations at 2,000 rows:
`build/xtream-sync-performance/20261010T080443490570Z/report.json`.
Initial completion took 10,102 ms and refresh 7,587 ms. Existing bulk retries
and stressed-provider backoff remain costly; faster healthy category pacing
does not bypass those failure safeguards.
