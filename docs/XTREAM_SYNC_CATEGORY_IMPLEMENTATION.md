# Xtream live category scheduling

P5 on `codex/xtream-live-sync-performance`.

Live category requests use a bounded refilled coordinator: completion frees a
slot immediately instead of waiting for the slowest category in a fixed window.
Outcomes retain provider category order, while progress reports completion order.
Runtime profile caps and the remembered sequential preference remain. Completed
attempts merge under the P1 fence; failed or cancelled attempts are discarded.

Stress downgrade stops refilling until already running attempts drain, then
admits one at a time. Repeated sequential stress stops further admissions.
Authentication failures stop admissions and are excluded from failed-category
continuation retries. Unexpected worker failure cancels siblings; worker
CancellationException explicitly cancels the coordinator too, including workflow
supersession. Scope exit joins workers before staging cleanup.

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

AUTO selection remains unchanged until P6. Bulk rejection, broader runtime-tier
acceptance and redundant background imports require the following gates. These
fixtures contain no playable media and do not validate playback.

The bulk-HTTP-503 fallback fixture also passed both operations at 2,000 rows:
`build/xtream-sync-performance/20261010T080443490570Z/report.json`.
Initial completion took 10,102 ms and refresh 7,587 ms. Existing bulk retries
and stressed-provider backoff remain costly; faster healthy category pacing
does not bypass those failure safeguards.
