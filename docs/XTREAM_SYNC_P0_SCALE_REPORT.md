# Xtream sync 50k scale and batch sweep

Status: **Done** for an exploratory synthetic emulator sweep. This does not
close P0 or establish a release performance result.

Measured 2026-10-10 on the API 36 Android TV x86_64 emulator using the isolated
`com.streamvault.app.syncbenchmark` package. Each profile used one warmup and
three measured imports of the same deterministic 50,000-channel, 200-category
fixture (seed 1). The live payload was 12,250,789 bytes with SHA-256
`fb0b72467c66305c3780a9ff1b23fe5d4bd0ad3d8a03b0d34e8082558e02e073`.
Every measured import committed exactly 50,000 rows through one complete live
bulk request and made zero per-category live requests. The API 36 emulator had
a 192 MiB memory class and 201,326,592-byte maximum Java heap. Tier and batch
settings below are benchmark overrides on this one emulator; they do not
represent physical LOW/MID/HIGH devices.

## Results

| Profile | Stage batch / queue | Staged batches | Setup median / max | Live commit median | Max sampled Java heap | Measured GC delta range |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| LOW / 100 | 100 / 1 | 500 | 19.446 / 20.781 s | 19.364 s | 13.21 MiB | 431–436 |
| LOW / 256 | 256 / 1 | 196 | 20.339 / 20.817 s | 20.247 s | 13.25 MiB | 367–372 |
| LOW / 512 | 512 / 1 | 114 | 20.175 / 25.303 s | 20.072 s | 13.93 MiB | 321–328 |
| MID / 512 | 512 / 2 | 98 | 18.627 / 23.591 s | 18.544 s | 15.96 MiB | 308–318 |
| HIGH / 500 | 500 / 2 | 100 | 21.030 / 24.338 s | 20.927 s | 14.21 MiB | 311–320 |
| HIGH / 1,024 | 1,024 / 2 | 49 | 24.470 / 24.614 s | 24.357 s | 16.38 MiB | 293–296 |

LOW/256, LOW/512 and HIGH/500 were respectively 4.6%, 3.8% and 8.1% slower
by median setup time than LOW/100. MID/512 was 4.2% faster by median, but its
23.591 s maximum was 13.5% above the LOW/100 maximum and its 15.96 MiB sampled
heap was 20.8% higher. That three-sample, single-emulator median does not
establish a safe improvement. HIGH/1,024 was 25.8% slower by median than
LOW/100 and had a 16.38 MiB sampled peak. Larger batches reduced staging batch
and sampled GC counts, but did not provide a repeatable elapsed-time gain;
several profiles had higher maxima. Sampled heap is process telemetry, not an
allocation profile or device memory-pressure measurement. The one-off 50k
smoke run without warmup took 29.515 s and is excluded from the controlled
comparison as an outlier.

## Matched bulk and category route comparison

The same fixture, emulator build, LOW runtime override and batch size 100 were
also measured using explicit `CATEGORY_BY_CATEGORY` mode (one warmup plus three
measured imports). Both runs used the same payload hash, committed exactly
50,000 rows, completed every live response, and retained full request logs.

| Live route | Live requests per import | Rows per response | Setup median / max | Live commit median | Max sampled Java heap |
| --- | ---: | ---: | ---: | ---: | ---: |
| STREAM_ALL bulk | 1 | 50,000 | 19.446 / 20.781 s | 19.364 s | 13.21 MiB |
| CATEGORY_BY_CATEGORY | 200 | 250 | 25.950 / 28.739 s | 25.878 s | 9.62 MiB |

On this synthetic LOW profile, category mode was 33.5% slower by median setup
time and had a 38.3% higher maximum. It had a 27.2% lower maximum sampled heap.
The request trace showed a maximum of one active server request during category
mode. This supports the bulk route for this healthy, zero-delay fixture, while
also showing the category route's lower sampled heap in this run. It does not
establish behavior for real providers, different category distributions,
throttling, physical devices or release builds. The run order was not
randomized, so treat the comparison as exploratory rather than a frozen release
gate.

### Repeated alternating-order 10k follow-up

To reduce simple run-order bias, the same API 36 emulator and LOW/100 profile
ran three paired blocks against a deterministic 10,000-channel, 100-category
fixture. Each route had one warmup and one measured import per block. Route
order alternated bulk-first, category-first, bulk-first. All reports used the
same payload SHA-256 (`4dfbcaf7e39ce168eb4cd58e34b5d8c78dddf4592d6638cb46c76b0fb31d63a3`),
recorded all expected samples, passed instrumentation, and retained complete
request-event logs. Every measured import committed all 10,000 rows.

| Paired block | Order | STREAM_ALL setup | CATEGORY_BY_CATEGORY setup | Bulk faster in pair |
| --- | --- | ---: | ---: | ---: |
| 1 | Bulk first | 5.482 s | 6.971 s | 21.4% |
| 2 | Category first | 5.181 s | 8.847 s | 41.4% |
| 3 | Bulk first | 4.534 s | 6.709 s | 32.4% |

Across the three measured imports per route, `STREAM_ALL` had a 5.181 s median
and 5.482 s maximum setup time; `CATEGORY_BY_CATEGORY` had a 6.971 s median
and 8.847 s maximum. Bulk was faster in all three pairs. Maximum sampled Java
heap was 11.08 MiB for bulk and 9.93 MiB for category mode. Each bulk import
made one live request; each category import made 100. These samples support a
bulk latency advantage on this specific synthetic fixture, with lower sampled
heap for category mode. The sequence was alternating rather than randomized,
and the category-first block had the slowest category-mode sample; bulk ranged
from 4.534 to 5.482 s. Run-to-run and ordering effects remain plausible. This
exploratory result alone did not justify a production AUTO/default decision or
replace measurements on other fixtures, release builds, physical devices or
real providers. Bulk-first AUTO was subsequently enabled on this branch for
direct testing; the activation and fresh synthetic checks are recorded in the
[P6 tuning report](XTREAM_SYNC_P6_TUNING_REPORT.md).

### Seeded randomized comparisons across simulated tiers

A follow-up used the same 10k/100 fixture, payload hash, API 36 Android TV
x86_64 emulator and isolated benchmark package. We ran three paired blocks per
simulated tier with one warmup and one measured import per route in every
block. Both tier and route order were shuffled with seed `20261010`; each tier
had two bulk-first and one category-first pair. Tier overrides select the
application policy only: these are not physical LOW/MID/HIGH devices. The
profile's default stage batch sizes were LOW/100, MID/300 and HIGH/500. All
reports were complete, instrumentation passed, measured rows were exactly
10,000, hashes matched, and request-event logs were not truncated.

Positive paired advantage means bulk had lower setup time; a negative value
means category mode was faster.

| Run order | Tier / pair | First route | STREAM_ALL setup | CATEGORY_BY_CATEGORY setup | Bulk paired advantage |
| ---: | --- | --- | ---: | ---: | ---: |
| 1 | LOW / 1 | Bulk | 6.308 s | 8.685 s | +27.4% |
| 2 | MID / 1 | Bulk | 5.651 s | 6.269 s | +9.9% |
| 3 | HIGH / 1 | Category | 5.345 s | 5.352 s | +0.1% |
| 4 | LOW / 3 | Bulk | 4.559 s | 6.814 s | +33.1% |
| 5 | MID / 2 | Category | 5.468 s | 6.540 s | +16.4% |
| 6 | MID / 3 | Bulk | 5.499 s | 6.224 s | +11.7% |
| 7 | HIGH / 2 | Bulk | 5.622 s | 5.217 s | -7.8% |
| 8 | HIGH / 3 | Bulk | 4.436 s | 5.042 s | +12.0% |
| 9 | LOW / 2 | Category | 5.756 s | 8.618 s | +33.2% |

| Simulated tier / default batch | Route | Setup median / max | Faster in paired blocks | Max sampled Java heap |
| --- | --- | ---: | ---: | ---: |
| LOW / 100 | STREAM_ALL | 5.756 / 6.308 s | 3 / 3 | 11.71 MiB |
| LOW / 100 | CATEGORY_BY_CATEGORY | 8.618 / 8.685 s | — | 9.66 MiB |
| MID / 300 | STREAM_ALL | 5.499 / 5.651 s | 3 / 3 | 12.19 MiB |
| MID / 300 | CATEGORY_BY_CATEGORY | 6.269 / 6.540 s | — | 13.92 MiB |
| HIGH / 500 | STREAM_ALL | 5.345 / 5.622 s | 2 / 3 | 12.45 MiB |
| HIGH / 500 | CATEGORY_BY_CATEGORY | 5.217 / 5.352 s | — | 21.21 MiB |

Bulk won all six LOW and MID paired blocks. At HIGH, category mode won one
pair and the median route times were close (bulk 5.345 s; category 5.217 s).
Bulk made one live request per import; category mode made 100. The measured
heap direction varied by tier, with the category route's HIGH-profile maximum
the largest sample in this sweep. This matrix suggests the route advantage can
depend on the simulated policy tier and does not support a universal AUTO
choice. All runs used one emulator with the same 192 MiB memory class; results
do not establish behavior on physical devices, release builds or real providers.

The bulk per-run request trace confirms one complete `get_live_streams`
response, zero per-category live requests, and exact row counts. The request
also fetches authentication/category metadata and empty movie/series category
shells. One representative LOW/100 measured run spent 14.2 s in the live HTTP
response and 19.8 s in the instrumented streaming callback; these overlap with
decode, mapping, and staging and must not be summed as separate wall-time
costs. The pipeline trace records substantial producer send-wait, but this
sweep alone does not isolate a safe optimization target.

## Persisted row and field equivalence

The benchmark now has an opt-in `--verify-channel-fields` pass. After setup
timing stops, it walks Room in 1,024-row pages and checks fixture-derived source
order, stream IDs, names, category IDs and the row fallback category name, EPG
IDs, archive support/duration, provider/adult/protection fields, logical group
IDs, internal URL tokens, and required local/fingerprint fields. It separately
checks the persisted category table against the category-list names. The channel
rows intentionally omit `category_name`, so their expected row value is the
existing `Category N` fallback; the authoritative `Benchmark Group NNNN`
values are verified in category records.

| Route / fixture | Persisted rows checked | Category records checked | Verification time (outside setup timing) | Result |
| --- | ---: | ---: | ---: | --- |
| STREAM_ALL / 50k | 50,000 | 200 | 10.883 s | Pass |
| CATEGORY_BY_CATEGORY / 50k | 50,000 | 200 | 14.756 s | Pass |
| STREAM_ALL / 103 rows, every tenth malformed | 93 | 12 | 0.038 s | Pass |
| STREAM_ALL / 10k initial + hidden 20% refresh | 10,000 after each phase | 100 after each phase | 2.581 / 2.464 s | Pass |
| CATEGORY_BY_CATEGORY / 10k initial + hidden 20% refresh | 10,000 after each phase | 100 after each phase | 2.750 / 2.595 s | Pass |
| STREAM_ALL / HTTP 503 then category fallback | 10,000 | 100 | 2.693 s initial / 3.449 s follow-up | Fields pass; follow-up settled Partial with no post-fallback bulk retry |
| STREAM_ALL / truncated body then category fallback | 1,000 | 20 | 0.293 s | Fields pass; final state Partial |

Each 50k route check was one no-warmup correctness run, not a timing sample. The
bulk run made one complete live request; the category run made 200 complete
category requests. All live response rows summed to 50,000 and request-event
logs were not truncated. The malformed smoke confirms the checker skips the
fixture's invalid stream-ID rows and covers the remaining 93 persisted rows.
The 10k refresh probe hid 20% of categories before the Settings-style live
refresh; both phases retained all expected rows and categories, and the verifier
matched every row after refresh. Each phase made one complete bulk request and
zero category-stream requests. This is one synthetic correctness sample per
phase, not refresh performance evidence.

The explicit category-mode refresh retained the same 10,000 rows and 100
category records after hiding 20% of categories; fixture comparison passed
after both setup and refresh. It made 100 category requests for setup and 80
visible-category requests for refresh. The original 10k HTTP 503 run retried
the bulk endpoint, then completed all 100 category responses and verified every
persisted field, but its state snapshot was `Syncing` and three later bulk
requests followed the fallback. This exposed a queued onboarding recovery that
could rerun completed primary work when fallback left the overall sync
`PARTIAL`.

After updating `resumePrimaryCatalog` to coalesce a queued initial-onboarding
resume when onboarding completed and the durable live snapshot is fresh and
complete, a follow-up 10k HTTP 503 run passed instrumentation and verified all
10,000 rows and 100 categories. It made three bulk attempts, all HTTP 503,
followed by 100 successful category requests; there were zero bulk requests
after fallback. The sampled sync state was `Partial`, and logcat recorded
`Xtream primary resume coalesced ... onboarding completed while queued.` The
request trace was complete. Setup took 17.841 s and the post-setup field check
took 3.449 s; this is one emulator verification run, not evidence for other
failed-response shapes. The 1k truncated-body run made four
incomplete 500-row bulk attempts, completed 20 category responses, and verified
all 1,000 persisted fields; its state snapshot was `Partial`. It has not been
rerun after the coalescing change.

## Decision and limits

Follow-up on 2026-10-10: the corrected bulk-first truncated-response scenario
and an HTTP 503 scenario with VOD/series fallback now pass a 30-second terminal
state/count observation with zero bulk Live requests after category fallback.
See [failure handling and continuation queues](XTREAM_SYNC_FAILURE_HARDENING.md).
This closes that bounded truncated-response observation; other failures and
longer retry windows remain open. The earlier evidence below stays historical.

- **Done:** Synthetic 50k-row scale smoke and six-profile batch-size comparison;
  all measured samples completed with exact fixture counts.
- **No default change:** Existing tier-specific production batch defaults stay
  unchanged. The larger candidates did not show a repeatable end-to-end gain.
- **Done:** Three paired, alternating-order synthetic LOW/100 comparisons and
  three seeded randomized pairs per simulated LOW/MID/HIGH policy at 10k rows.
  Bulk won all LOW/MID pairs; HIGH was mixed and close by median. The separate
  50k comparison also favored bulk by median.
- **Done:** All-row field equivalence for both initial-import routes on the
  50k fixture, a malformed-row smoke check, and a 10k hidden-category refresh
  check on both live routes, plus field checks after 503 and truncated-body
  category fallback.
- **Still open:** Route comparisons across other fixture/provider profiles;
  terminal-state/retry-quiescence behavior for truncated-body and other failure
  paths; broader refresh/failure cases; attributable
  allocation/CPU profiling; release-build and representative device
  measurements; finalized P0 gates.
- **Deferred:** Physical LOW/MID/HIGH tuning and real-provider/playback checks.
  No physical devices are available. The synthetic fixture has no playable
  media; a branch debug APK is now installed for the user to run against their
  provider, but that validation has not yet been performed.

Raw local reports (ignored build artifacts):

- `build/xtream-sync-performance/20261010T140737945520Z/report.json`
- `build/xtream-sync-performance/20261010T140737945520Z/requests.jsonl`
- `build/xtream-sync-performance/p0-scale-50k-low-batch100-3x1/report.json`
- `build/xtream-sync-performance/p0-scale-50k-low-batch256-3x1/report.json`
- `build/xtream-sync-performance/p0-scale-50k-low-batch512-3x1/report.json`
- `build/xtream-sync-performance/p0-scale-50k-mid-batch512-3x1/report.json`
- `build/xtream-sync-performance/p0-scale-50k-high-batch500-3x1/report.json`
- `build/xtream-sync-performance/p0-scale-50k-high-batch1024-3x1/report.json`
- `build/xtream-sync-performance/p0-scale-50k-low-category-3x1/report.json`
- `build/xtream-sync-performance/p0-field-equivalence-50k-verified/report.json`
- `build/xtream-sync-performance/p0-field-equivalence-50k-category-verified/report.json`
- `build/xtream-sync-performance/p0-field-equivalence-malformed-103/report.json`
- `build/xtream-sync-performance/p0-field-equivalence-10k-hidden-refresh/report.json`
- `build/xtream-sync-performance/p0-field-equivalence-10k-category-hidden-refresh/report.json`
- `build/xtream-sync-performance/p0-field-equivalence-10k-bulk-503-fallback/report.json`
- `build/xtream-sync-performance/p0-field-equivalence-1k-partial-disconnect-fallback/report.json`
- `build/xtream-sync-performance/p0-route-pair1-bulkfirst/report.json`
- `build/xtream-sync-performance/p0-route-pair2-categoryfirst/report.json`
- `build/xtream-sync-performance/p0-route-pair3-bulkfirst/report.json`
- `build/xtream-sync-performance/p0-route-randomized-LOW-pair1-bulkfirst/report.json`
- `build/xtream-sync-performance/p0-route-randomized-LOW-pair2-categoryfirst/report.json`
- `build/xtream-sync-performance/p0-route-randomized-LOW-pair3-bulkfirst/report.json`
- `build/xtream-sync-performance/p0-route-randomized-MID-pair1-bulkfirst/report.json`
- `build/xtream-sync-performance/p0-route-randomized-MID-pair2-categoryfirst/report.json`
- `build/xtream-sync-performance/p0-route-randomized-MID-pair3-bulkfirst/report.json`
- `build/xtream-sync-performance/p0-route-randomized-HIGH-pair1-categoryfirst/report.json`
- `build/xtream-sync-performance/p0-route-randomized-HIGH-pair2-bulkfirst/report.json`
- `build/xtream-sync-performance/p0-route-randomized-HIGH-pair3-bulkfirst/report.json`

Reproduction uses the commands documented in
[`XTREAM_SYNC_BENCHMARK.md`](XTREAM_SYNC_BENCHMARK.md), with
`--channels 50000 --categories 200 --modes STREAM_ALL --iterations 3
--warmups 1` and the recorded tier/batch overrides.
