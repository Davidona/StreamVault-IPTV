# P7 Xtream summary and background work

Status: P7 implementation, the synthetic emulator matrix, the pre-change
unchanged-refresh comparison, and the fingerprint-allocation follow-up are
complete. Physical-device tuning is deferred; full release validation remains
pending.

| Work item | Status | Evidence / remaining |
| --- | --- | --- |
| P7 implementation | Done | Summary diffing/fingerprints, local and hydrated state preservation, checkpointed tier slices, catalog-before-guide scheduling, focused tests, and APK builds |
| Small-catalog bulk-fallback tier sweep | Done | 2,000 movies / 1,000 series; LOW, MID, HIGH, and DEVICE profiles; five measured runs per profile |
| Bulk-success and larger-catalog comparisons | Done | 2k/1k DEVICE bulk success (five runs), 8k/4k DEVICE bulk success (three runs), and 8k/4k LOW bulk fallback (two runs) |
| Emulator Room preservation check | Done | Each run's warmup refreshed stale titles while retaining local IDs, hydrated fields, watch history, protection flags, playback metadata, and hydrated index timestamps |
| Fingerprint allocation follow-up | Done | Replaced per-byte formatter and per-field ByteBuffer allocations without changing digest bytes; sampled unchanged-refresh heap fell on both tested routes |
| Physical-device tuning | Deferred | No physical devices are available |
| Before/after unchanged VOD refresh comparison | Done | Ten measured bulk-success runs and five measured fallback runs per build on the same API 36 emulator; bulk success was about 60% faster, category fallback was effectively unchanged, and sampled Java heap increased |
| P8 end-to-end release validation | Pending | Real-provider validation and sustained live playback remain outstanding |

Date: 2026-10-10  
Branch: `codex/xtream-live-sync-performance`

## Summary indexing

The existing summary executor continues to stream full movie/series responses
first and fall back to category work when full streaming fails or a priority
category is requested. Category work now receives a device-tier slice budget:

| Tier | Categories per slice | Target rows | Target duration |
| --- | ---: | ---: | ---: |
| LOW | 4 | 1,000 | 10 s |
| MID | 8 | 3,000 | 20 s |
| HIGH | 16 | 8,000 | 30 s |

The executor checkpoints `nextCategoryIndex` after each completed category and
schedules continuation work when categories remain. A fetch is atomic at the
category boundary, so one unusually large or slow category can exceed its row
or time target before the next checkpoint. The budgets are conservative initial
values, not measured production tuning.

Summary persistence computes stable remote-field fingerprints, compares
existing movie/series rows, and writes only changed summaries. Index projections
are compared without the changing `indexedAt` field, so unchanged refreshes do
not rewrite the same index rows. Existing local IDs, movie watch state,
protection, TMDB identity, hydrated detail fields and local playback metadata
are preserved. The summary fingerprint excludes local watch, protection,
hydration and playback-metadata state.

## Scheduling and guide handoff

Xtream movie and series indexing continuations are scheduled before guide work.
Guide work uses a separate unique WorkManager chain and defers while any Xtream
LIVE, MOVIE or SERIES index job is queued or running. This keeps guide retries
from occupying the provider catalog continuation chain. Settings can continue
to report the durable live/movie/series index state separately from EPG state.

## Verification completed

- `XtreamIncrementalIndexExecutorTest`: row and duration budget checkpoint
  coverage passed.
- `XtreamSummaryFingerprintTest`: remote summary changes affect fingerprints;
  local watch, hydration, protection and playback-metadata changes do not.
- `ProviderContinuationSchedulerTest`: catalog continuations precede guide
  work.
- Focused `SyncManagerTest` passed all 98 tests, including changed-row and
  index-projection cases. `SyncManagerXtreamLiveStrategyTest` also passed.
- The app and benchmark debug APKs assembled successfully with the new worker
  interfaces and instrumentation sources.
- The synthetic performance fixture now supports deterministic movie and
  series summaries, independent category counts and payload hashes, and
  configurable bulk VOD/series rejection with category fallback.
- Fixture and report tests passed: `python -m unittest
  tools.tests.test_xtream_sync_perf_fixture
  tools.tests.test_xtream_sync_benchmark` (13 tests).
- The API 36 Android TV emulator completed a LOW-tier scenario with 1,000 live
  channels, 2,000 movies across 20 categories, and 1,000 series across 10
  categories. Bulk movie and series requests returned 503, then category
  fallback completed all sections. One warmup plus five measured runs were
  recorded; all six instrumentation samples completed and request events were
  not truncated.
- On each measured run, live import committed 1,000 rows, movie and series
  jobs ended in `SUCCESS`, and both Room entities and active index projections
  matched the expected 2,000 movie / 1,000 series counts. Median index
  completion was 8.593 s for movies and 8.594 s for series from setup start;
  median completion after Live TV commit was 7.930 s and 7.931 s respectively.
  Each measured account used 1 bulk plus 20 category movie requests, and 1 bulk
  plus 10 category series requests.
- The warmup Room check deliberately seeded an old title, hydrated detail,
  protection state, playback metadata, and playback-history progress, then
  forced both summary sections to reindex. The remote titles refreshed while
  stable IDs and local/hydrated fields remained intact. Both Xtream content
  index rows retained the hydrated timestamp. This check does not use playable
  media.
- The harness sampled Java heap and ART GC during summary indexing; aggregate
  maxima across all profiles and catalog sizes are recorded below.

## Emulator matrix results

All runs used the API 36 Android TV x86_64 emulator (192 MB memory class,
201,326,592-byte Java max heap), synthetic data, and a stage batch override of
256. LOW/MID/HIGH are simulated policy budgets on that same emulator. Times are
from setup start; the after-Live column subtracts the recorded Live commit
milestone. Each report completed every expected instrumentation sample without
truncated request events. Every measured sample reached `SUCCESS` for both
summary jobs and had Room row counts matching its fixture. Each configured
warmup reported `room_preservation_verified=true`.

Small catalog, category fallback (one bulk request rejected with 503, then one
request per category):

| Profile | Measured runs | Movie median (s) | Movie after Live (s) | Series median (s) | Series after Live (s) | Max sampled summary heap (MB) | Max GC delta |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| LOW | 5 | 8.593 | 7.930 | 8.594 | 7.931 | 12.78 | 35 |
| DEVICE | 5 | 7.961 | 7.507 | 7.963 | 7.509 | 10.70 | 35 |
| MID | 5 | 7.946 | 7.500 | 7.947 | 7.502 | 11.29 | 34 |
| HIGH | 5 | 7.961 | 7.509 | 7.962 | 7.510 | 10.02 | 32 |

Bulk-success and larger-catalog comparisons:

| Scenario | Measured runs | Movies / series | Movie median (s) | Series median (s) | Requests per measured sample (movie / series) |
| --- | ---: | ---: | ---: | ---: | --- |
| DEVICE, bulk success | 5 | 2,000 / 1,000 | 1.856 | 1.857 | 1 full + 0 category / 1 full + 0 category |
| DEVICE, bulk success | 3 | 8,000 / 4,000 | 5.241 | 5.243 | 1 full + 0 category / 1 full + 0 category |
| LOW, category fallback | 2 | 8,000 / 4,000 | 30.596 | 30.598 | 1 rejected full + 80 category / 1 rejected full + 40 category |

For the small fixture, the synthetic bulk-success path completed sooner than
fallback: about 1.86 s versus 7.95–8.59 s across the tested fallback profiles.
For the larger fixture, LOW-tier fallback completed all rows and checkpoints
in about 30.6 s. These are path and policy observations on one debug emulator;
they are not a before/after optimization result or production-device targets.
The largest measured summary heap sample was 12.89 MB, and the largest ART GC
count delta was 148 in the 8k/4k LOW fallback run. Heap sampling was every 50 ms
and does not include native RSS or allocation attribution.

## Pre-change comparison: unchanged VOD refresh

The probe first imports one deterministic catalog and waits for successful
movie/series jobs. It then invokes the existing forced summary-index command
once for movies and once for series, sequentially, without changing the fixture
or local rows. The reported combined time is the per-sample sum of those two
calls; it excludes setup, authentication and live import. Each sample verifies
the movie/series Room row counts and active search-index counts afterward.

Both builds used the API 36 Android TV x86_64 emulator (192 MB memory class,
201,326,592-byte Java max heap), LOW policy and 100-row stage batches. The
pre-change build is commit `2af03f5149e888d25307457d4cce82fab3de74d9`; P7 was
built from the same base commit plus the current uncommitted working-tree
changes. Each run used one warmup and five measured accounts, the same seed,
the same 1,000-channel / 2,000-movie / 1,000-series fixture, identical movie
and series payload hashes, and successful instrumentation. Bulk success used
three warmups and ten measured accounts per build. Category fallback used one
warmup and five measured accounts per build. All samples completed; medians
below exclude the warmups. Bulk success returned HTTP 200. Fallback returned
HTTP 503 for both full summary requests, then served the categories.

| Route | Build | Movie refresh median (s) | Series refresh median (s) | Sequential VOD median (s) | Max sampled heap (MiB) | Max ART GC delta |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Bulk success, 10 measured | Pre-P7 | 0.886 | 0.883 | 1.769 | 10.09 | 23 |
| Bulk success, 10 measured | P7 | 0.478 | 0.234 | 0.713 | 17.19 | 24 |
| Category fallback, 5 measured | Pre-P7 | 5.232 | 2.688 | 7.906 | 9.01 | 21 |
| Category fallback, 5 measured | P7 | 5.212 | 2.628 | 7.836 | 10.83 | 31 |

On bulk success, the unchanged sequential refresh median fell by 59.69% (2.48x
faster). The full ten-sample series is retained because baseline refresh times
settled during the measured run even after three warmups; no measured samples
were trimmed. Category fallback fell by 0.89%, which is effectively unchanged
at this sample size and should not be presented as a fallback speedup. Request
counts matched between builds: bulk success used two full movie and two full
series requests per account; fallback used two rejected full requests plus 40
movie-category and 20 series-category requests per account. Those totals cover
initial indexing plus the explicit repeated refresh.

The refresh-path maximum sampled Java heap rose from 10.09 to 17.19 MiB on bulk
success and from 9.01 to 10.83 MiB on fallback; maximum ART GC deltas were 24
and 31 for P7. This is an observed emulator memory tradeoff to carry into
physical-device tuning. Java heap is sampled every 50 ms and is not native RSS
or allocation attribution. These results support a route-specific bulk-refresh
gain, not a general VOD speedup claim or a release-device conclusion.

All four reports show successful instrumentation, complete request events and
exact Room/index coverage. The P7 working-tree build is identified by app APK
SHA-256 `FA643853B4CA8008F3FB68B61A7C7D1EC41D76B21145AF7D6022D8F296FA07E7`;
the pre-change app APK is `29E1238BEE4AFAF70CE161A18296AD0AF55E32574BF094413646DF9B468438C9`.
The baseline probe source is retained in the temporary baseline checkout at
`build/xtream-sync-p7-baseline/app/src/androidTest/java/com/streamvault/app/benchmark/XtreamLiveSyncBenchmarkTest.kt`.

## Fingerprint allocation follow-up

Inspection of `XtreamSummaryFingerprint` found repeated allocation in the hot
path: each non-null field created a `ByteBuffer` for its four-byte length
prefix, and every output digest byte called `String.format`. The implementation
now writes the same big-endian length prefix directly and encodes the digest
with a fixed lowercase hexadecimal lookup table. Field order, null markers,
UTF-8 field bytes, SHA-256, and persisted fingerprint values remain unchanged.

The optimized build was compared with the recorded P7 build using the same API
36 Android TV x86_64 emulator, LOW policy, 100-row stage batch, seed, and
2,000-movie / 1,000-series payload hashes. Bulk success used three warmups and
ten measured runs; fallback used one warmup and five measured runs. All
instrumentation samples completed, request events were not truncated, and
Room plus active-index counts matched the fixture.

| Route | Build | Movie median (s) | Series median (s) | Sequential VOD median (s) | Max sampled heap (MiB) | Max ART GC delta |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Bulk success, 10 measured | P7 | 0.478 | 0.234 | 0.713 | 17.19 | 24 |
| Bulk success, 10 measured | Fingerprint allocation follow-up | 0.447 | 0.206 | 0.652 | 11.61 | 15 |
| Category fallback, 5 measured | P7 | 5.212 | 2.628 | 7.836 | 10.83 | 31 |
| Category fallback, 5 measured | Fingerprint allocation follow-up | 5.232 | 2.629 | 7.857 | 8.07 | 18 |

The sampled refresh peak fell by 32.4% on bulk success and 25.5% on fallback
relative to P7. Bulk refresh time was 8.5% lower in this follow-up run; the
fallback time was 0.27% higher and remains effectively unchanged. Treat these
time differences as emulator observations, not a new speed claim. Compared
with the pre-P7 build, bulk sampled peak remains 1.52 MiB higher while fallback
is 0.94 MiB lower. Heap is sampled every 50 ms and this does not attribute
allocations or measure native RSS.

The optimized app APK SHA-256 is
`A77C0E63F0172C6C91EE91DEEE57B61C3A6BEEB46821E68196EB8C2B7D18E783`. Reports:

- `build/xtream-sync-performance/p7-post-fingerprint-optimized-bulk-2k-1k-low-10x3/report.json`
- `build/xtream-sync-performance/p7-post-fingerprint-optimized-fallback-2k-1k-low-5x1/report.json`

Local full reports are stored under the following ignored build directories:

- `build/xtream-sync-performance/20261010T111134999266Z/report.json` — LOW fallback.
- `build/xtream-sync-performance/20261010T111449834886Z/report.json` — DEVICE fallback.
- `build/xtream-sync-performance/20261010T111551636742Z/report.json` — MID fallback.
- `build/xtream-sync-performance/20261010T111652926306Z/report.json` — HIGH fallback.
- `build/xtream-sync-performance/20261010T111807126816Z/report.json` — DEVICE bulk success.
- `build/xtream-sync-performance/20261010T111829207899Z/report.json` — 8k/4k DEVICE bulk success.
- `build/xtream-sync-performance/20261010T111935389752Z/report.json` — 8k/4k LOW fallback.
- `build/xtream-sync-performance/p7-pre-change-phase-aligned-bulk-2k-1k-10x3/report.json` — pre-P7 unchanged bulk refresh, ten measured runs.
- `build/xtream-sync-performance/p7-post-change-refresh-bulk-2k-1k-low-10x3/report.json` — P7 unchanged bulk refresh, ten measured runs.
- `build/xtream-sync-performance/p7-pre-change-refresh-fallback-2k-1k/report.json` — pre-P7 unchanged fallback refresh.
- `build/xtream-sync-performance/p7-post-change-refresh-fallback-2k-1k-low/report.json` — P7 unchanged fallback refresh.

These synthetic runs used no real provider or playable media. Physical-device
tuning is deferred because no physical devices are available. The branch debug
APK is now installed for user-side provider testing, but live playback has not
yet been validated. Follow the sustained capture and log requirements in
`AGENTS.md` when recording that run.
