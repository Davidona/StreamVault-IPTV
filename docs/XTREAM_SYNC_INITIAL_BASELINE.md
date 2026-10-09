# Initial Xtream live backend baseline

Measured on 2026-10-09 using the first P0 implementation on
`codex/xtream-live-sync-performance`, based on `develop` at `540ec30c`.
This is the current importer with timing hooks, not an optimized implementation.
Reproduction and limitations: [benchmark guide](XTREAM_SYNC_BENCHMARK.md).

## Environment and dataset

- Debug APK, Android TV x86_64 emulator, API 36,
  model `sdk_google_atv64_x86_64`, memory class 192 MB, low-RAM flag false.
- Maximum Java heap 201,326,592 bytes. Android's live low-memory signal can
  change independently of heap occupancy or the low-RAM device flag.
- 10,000 live rows, 100 categories, seed 1, no artificial network delay,
  bandwidth cap, throttling, malformed rows or disconnects.
- Live payload SHA-256:
  `4dfbcaf7e39ce168eb4cd58e34b5d8c78dddf4592d6638cb46c76b0fb31d63a3`.
- EPG skipped; movie/series shells empty. Production background jobs enabled.
- One warmup and five measured initial imports per requested mode.

```powershell
python tools/xtream_sync_benchmark.py --channels 10000 --categories 100 --iterations 5 --warmups 1
```

## Results

| Requested mode | Setup median | Setup max | Live-committed median | Saved channels | Observed measured live requests |
| --- | ---: | ---: | ---: | ---: | --- |
| AUTO | 25.300 s | 25.334 s | 25.240 s | 10,000 | 100 categories/import; zero bulk |
| STREAM_ALL | 25.286 s | 25.293 s | 25.228 s | 10,000 | 100 categories/import; zero bulk |
| CATEGORY_BY_CATEGORY | 25.282 s | 25.305 s | 25.224 s | 10,000 | 100 categories/import; zero bulk |

All 18 imports, including warmups, passed expected channel counts and provider
cleanup. Instrumentation passed, the completion marker was captured, all samples
were recorded, and the bounded request-event history was not truncated. Global
server request high-water was two, including background traffic; it is not proof
of parallel live category downloads for an individual import.

**This is not a bulk-versus-category performance comparison.** The current
engine's strategy/safety checks redirected the requested STREAM_ALL runs to
categories before a bulk request. Logs confirmed the full-catalog skip. AUTO
also selects categories for this device's LOW tier. A valid bulk comparison
requires an environment where the current guard permits a complete bulk attempt.

Maximum sampled Java heap among measured samples: AUTO 14,443,440 bytes,
STREAM_ALL 16,101,696 bytes, categories 12,169,536 bytes. ART GC count deltas
ranged from 374–504, 359–370 and 356–361 respectively. These are process-wide
debug values, including potential background work, not allocation measurements
or evidence that Android's system-wide low-memory signal must be false.

One representative measured import had 2,028 ms summed staging time across 100
100-row batches. Final category staging/commit took 10/1 ms; channel promotion
took 47 ms and FTS rebuild 8 ms. Most wall time precedes final promotion.
The healthy category request spacing of 250 ms imposes a roughly 24.75-second
floor for 100 sequential starts. Mapping and staging also occupy that interval;
these overlapping costs must not be added as independent delays. Profile mapping
and decoding further before assigning CPU percentages.

## Settings refresh smoke check and recovery

A separate 2,000-row/10-category run passed initial import and live Settings
repair for every mode, with exact counts and cleanup. It is one sample per
phase/mode with no warmup, so it is a functional smoke check, not a refresh
performance gate. That run showed interrupted bulk attempts due to
`LowMemoryCatalogAbortException` followed by category recovery. One completed
bulk response also appeared in logs; concurrent follow-up work prevents using
it as an isolated bulk latency sample.

Queued follow-up work for a removed synthetic provider was observed trying
categories after its workflow generation was superseded. Commit fencing rejected
its writes, but the continuing request/fallback work needs investigation in P1.
Superseded workflow ownership should terminate work rather than initiate decoder
or category recovery. Background requests can affect account-level counts and
later process-wide heap/GC samples.

## Artifacts and remaining gates

Local baseline artifacts are in:
`build/xtream-sync-performance/20261009T165800195284Z/`.
The successful refresh smoke check is in:
`build/xtream-sync-performance/20261009T165718649672Z/`.
Raw general logcat and provider credentials are not exported.

Installed APK SHA-256 values for these successful runs:

- App: `852b8b19bb3c108191a7fed204cea19708173ca988e758c054b2a83b74b24307`.
- Instrumentation: `570084179468ca74554398f8d22b9e275f220ddc19f71c71ac511a19079fc06a`.

Builds passed; nine Xtream HTTP tests and thirteen fixture/report tests passed.
Provider accounts were synthetic and the existing debug application was retained.
The comparator notes use a neutral filename and contain no reference app name.

P0 remains open for an actual bulk comparison, representative device/release
measurements, repeated refresh scenarios, row identity/field equivalence,
allocation profiling, precise attempt/coverage diagnostics and UI readiness.
No default, parser, worker-pool, snapshot receipt, or import semantics changed
in this package. P1 correctness work precedes the planned P2–P6 performance
changes. Performance gates are not frozen from this limited environment.
