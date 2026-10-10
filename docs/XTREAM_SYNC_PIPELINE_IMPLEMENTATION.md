# Xtream live import pipeline

P3 implementation on `codex/xtream-live-sync-performance`, following the
[improvement plan](XTREAM_SYNC_IMPROVEMENT_PLAN.md). This package changes bulk
live ingestion used by provider setup and Settings Live refresh. AUTO selection
remains behind the P6 gate. Category mode and automatic fallback remain available;
their worker scheduling and pacing improvements belong to P5.

## Retrieval and ownership

For a healthy provider, live category metadata and one bulk live response start
concurrently. Both use the existing provider backoff/retry policy. A provider
with remembered request stress gets one local request slot: metadata resolves
first, then bulk retrieval begins. There are no parallel downloads of the same
bulk endpoint.

The reader sends typed batches through a bounded coroutine channel. A single
consumer on `Dispatchers.Default` waits for category context once, then maps and
stages rows while the HTTP reader continues. Metadata failure explicitly primes
empty adult-category context and retains fallback category semantics. Mapping
does not issue another category request for each batch. Authoritative category
shells, adult flags, hidden/required scope and stream ordering retain their
existing semantics.

Only the consumer owns the staging session, duplicate set, fallback categories,
accepted count and write timings. Reader success requires valid END_DOCUMENT.
Import success also requires channel drain and completion of the final write;
the P1 completion receipt is sealed only after that return.

Reader failure cancels and joins the writer before discarding its attempt.
Writer failure cancels the reader and its HTTP call. Workflow supersession is a
CancellationException, which normally would only cancel the child; the consumer
explicitly cancels its enclosing pipeline scope too. Parent cancellation closes
the queue/response and joins children. Retry gets a fresh queue and the existing
fresh attempt state. No late writer can write into an already discarded session.

## Resource limits

| Tier | Existing row batch limit | Estimated raw batch bytes | Queued batches |
| --- | ---: | ---: | ---: |
| LOW | 100 | 256 KiB | 1 |
| MID | 300 | 512 KiB | 2 |
| HIGH | 500 | 1 MiB | 2 |

These preserve the current row limits; larger tuning candidates remain unselected.
A byte threshold can flush earlier. A row larger than its batch budget fails
explicitly instead of being trimmed or admitted into an oversized queue.
The estimate includes UTF-16 text, category-ID strings and conservative fixed
object/list overhead. It is an accounting limit, not an exact ART heap reading.

At most `queue capacity + 2` full batches are retained by the writer, queue and
suspended sender. While assembling a batch, the producer may also hold its next
decoded row: use `(queue capacity + 3) * batch bytes` as a conservative raw-row
budget. Mapped channels/entities, parser buffers, metadata, duplicate tracking,
Room and indexes are additional allocations.

Live streamed JSON rows also have a 1,048,576-character token-read budget, plus
up to JsonReader's 1,024-character read-ahead. This applies to thin and legacy
streamed live decoders, including category responses, and includes skipped
metadata. It rejects pathological fields before building an entire huge token
or JSON tree. This is a new conservative resource admission rule: large ignored
metadata can also exceed it. No string is silently truncated. Resource failures
do not trigger the more expensive legacy decoder.

The existing 100,000-channel store cap bounds duplicate tracking to that count
plus one incoming batch before rejection. Existing HTTP response caps bound
metadata/response input. Fallback category accumulation is bounded by admitted
rows. P4 will evaluate stage uniqueness instead of the in-memory duplicate set.

The shared policy in singleton SyncManager permits one bulk pipeline across all
providers, so simultaneous imports cannot multiply these queues. Category and
other section jobs still follow their existing concurrency rules; this is not
a general app-wide network or memory scheduler. Live low-memory admission and
runtime abort signals remain active.

## Evidence

Barrier-based tests demonstrate metadata/bulk overlap, adult-context readiness,
one metadata resolution after failure, byte flushing, bounded queue backpressure,
final writer drain and parent/reader/writer cancellation. A transport test
supersedes the writer while the HTTP body is blocked after the first row and
observes underlying Call cancellation. Room attempt-isolation, retry, partial
fallback, supersession and sealed snapshot tests remain part of the regression
suite. Request-stress serialization and shared bulk permits are tested too.

Final regression run: 232 data tests and 16 domain identity tests passed;
both isolated benchmark APKs built. The fixture/report suite passed 14 tests.
The benchmark package is `com.streamvault.app.syncbenchmark`, separate from
ordinary app storage. No user app data was cleared.

Android TV API 36 x86_64 emulator: memory class 192 MB, max Java heap
201,326,592 bytes. Production LOW row batches (100) and one queued batch were
used. Background work stayed enabled. All 24 setup/refresh samples below
(including two warmup samples) retained their full expected channel count and
passed account cleanup, instrumentation, completion/sample-count checks and
untruncated request history.

| Scenario | Initial | Settings Live refresh | Evidence |
| --- | ---: | ---: | --- |
| 2k rows, 10 categories, all modes | STREAM_ALL 1,018 ms | STREAM_ALL 937 ms | Six samples; AUTO/category about 2.5 s |
| 10k rows, 100 categories, STREAM_ALL | Median 4,246 ms; max 4,289 ms | Median 4,306 ms; max 8,290 ms | One warmup + five measured pairs |
| 2k rows, category metadata delayed 1.5 s | 2,725 ms | 2,509 ms | Single pair; bulk begins during metadata wait |
| 2k rows, bulk returns HTTP 503 | 10,523 ms | 16,805 ms | Single pair; complete category fallback |
| 50k rows, 200 categories, STREAM_ALL | 21,090 ms | 40,451 ms | Single pair; three completed bulk imports |

Reports under ignored `build/xtream-sync-performance/`:

- All-mode smoke: `20261010T063839794250Z/report.json`.
- Repeated 10k: `20261010T063927249264Z/report.json`.
- Delayed metadata: `20261010T064037889878Z/report.json`.
- Bulk rejection: `20261010T064106880909Z/report.json`.
- Large catalog: `20261010T064201982860Z/report.json`.

The delayed-metadata request log proves bulk started 2.994 ms after metadata
started, before its 1,500.719 ms response completed. Request high-water was two.
Each synthetic account in the healthy small run issued two bulk requests
(setup + refresh) and no category stream requests. AUTO and explicit category
mode still issued 20 category requests across each account's two phases.

For the repeated 10k run, maximum estimated outstanding batch bytes were
178,154; maximum final drain was 86 ms. Median send time was 3,797 ms: the
reader mostly waited for mapping/staging. Maximum measured sampled Java heap
was 12,964,864 bytes. Thirteen completed bulk responses were observed for
twelve setup/refresh samples; one measured account incurred an extra full
import during its refresh interval, explaining the approximately doubled tail.
The 50k run similarly had three completed responses for two samples, with
maximum outstanding batch bytes 179,184 and drain 85 ms. Peak sampled Java
heap was 59,484,816 bytes on its cold initial sample, falling to 14,574,624
on refresh. These samples do not isolate all sources of heap allocation.

The extra completed imports are not evidence of a partial-body transport retry.
Background scheduling/coalescing needs investigation under P6/P7 before making
latency guarantees. The bulk-rejection account also executed additional import
work: nine rejected bulk requests and 30 category requests across its lifecycle.
Fallback is correct but still slow; P5 pacing and duplicate-work handling remain
necessary.

The 10k payload checksum matches P0/P2. P2's nominal bulk-only initial/refresh
medians were 4,489/4,566 ms (only two samples per phase); its mixed-strategy tail
included low-memory category fallback. P3 shows a modest nominal reduction,
not a controlled speedup ratio. The emulator restarted before P3 and background
timing differed. No category fallback appeared in the healthy P3 bulk runs,
which does not prove that memory pressure was fixed. AUTO's release gate
remains unmet.

The evidence prioritizes P4 writer/mapping and database cost, followed by P5
category fallback. Include redundant completed live imports in the P6/P7
scheduling audit. The fixture supplies no playable media. Playback acceptance,
screen readiness and release-device results remain P8/P0 gates.

## Timing interpretation

`XtreamSyncTiming phase=live-pipeline` records rows, batches, reader elapsed time,
post-reader drain, time spent sending batches, maximum estimated outstanding
batch bytes, queue capacity and byte limit. Outstanding bytes include the
consumer and suspended sender, not just the queue. They exclude the next row
and a producer batch that has not reached send.

Send time includes scheduling/channel overhead as well as backpressure. Reader
wall time includes that waiting. Mapping/staging and response durations overlap;
do not add them together or interpret them as CPU time. The benchmark exporter
accepts only the exact numeric format and discards general logs.
