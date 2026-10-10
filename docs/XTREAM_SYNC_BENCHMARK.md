# Xtream sync benchmark

This guide documents the benchmark harness for the
[Xtream sync improvement plan](XTREAM_SYNC_IMPROVEMENT_PLAN.md). Initial runs
captured the pre-change engine; the current branch's ordinary app now uses
bulk-first AUTO, while the isolated benchmark build keeps a policy override for
repeatable comparisons.

## Run on the connected emulator

From the repository root in PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PxtreamSyncBenchmark=true -PcompatAbi=x86_64 --console=plain
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python tools/xtream_sync_benchmark.py --channels 10000 --categories 100 --iterations 5 --warmups 1 --refresh
```

The build property creates `com.streamvault.app.syncbenchmark` with its own
storage and disables developer account seeding. Ordinary debug builds keep
their usual application ID and seeds. The instrumentation refuses to run
against another application ID or a benchmark database containing providers.
It creates synthetic accounts and removes only the exact account from each run.
It never clears app data or removes the existing debug app. For an interrupted
run, use `--cleanup-run <exact run token from report.json>`. The instrumentation
checks that token, normalized benchmark name and local endpoint before removing
that one provider; otherwise resolve it in the benchmark app before rerunning.

On this branch, ordinary `com.streamvault.app.debug` builds use bulk-first
`AUTO` by default; the isolated benchmark package retains its explicit policy
override for comparisons. To build the ordinary x86_64-compatible APK for
manual provider testing, use `:app:assembleDebug -PcompatAbi=x86_64` and install
it without clearing data. The exact saved APK, package verification, and
emulator install/launch record are in the
[P6 tuning report](XTREAM_SYNC_P6_TUNING_REPORT.md).

The Python runner starts a fixture bound to loopback, temporarily reverses its
port through adb, and removes that mapping when done. It refuses to reuse an
existing reverse mapping; choose another `--port` if necessary. APK installation
is explicit; the runner does not install, uninstall, or wipe an app.

Results go to an ignored, unique `build/xtream-sync-performance/<timestamp>/`
directory: `report.json`, structured `benchmark-log.txt`, and `requests.jsonl`.
The report requires both successful instrumentation and a completion marker.
An incomplete or failed run does not become a successful timing result.

## What is measured

The instrumentation invokes `ValidateAndAddProvider.loginXtream` using the
production dependency graph. Optional `--refresh` invokes `retrySection(LIVE)`,
the Settings live repair entry point. Runs cover AUTO, STREAM_ALL and
CATEGORY_BY_CATEGORY unless `--modes` limits the selection. The stored choice
is recorded alongside observed bulk/category request counts: STREAM_ALL can
still be overridden by existing safety policy, and AUTO may behave differently
for initial setup versus refresh.

Optional `--first-browsable-screen` launches the isolated app on the empty
Welcome screen, routes its startup landing destination to Live TV, and times
from the production setup use case starting until the first synthetic channel
row is accessible in the real Live screen. For a deterministic real-row target,
it temporarily hides the empty virtual Favorites and Recent categories, then
restores those preferences and the prior landing destination. The report
includes median and max for measured samples. It covers app navigation and row
readiness, but drives the production use case directly instead of interacting
with the provider form; it is emulator evidence, not a release-device
comparison. The option still deletes only its synthetic provider.

Optional `--verify-channel-fields` pages through the persisted live rows after
each setup and, with `--refresh`, after each Settings-style Live refresh. It
checks every row against the deterministic fixture: source order,
stream identity, name, category ID, the row's expected fallback category name,
EPG ID, archive fields, adult/protection state, logical group ID, fingerprint
presence, and the internal live URL token. Because fixture live rows omit
`category_name`, it separately checks the persisted category table against the
category-list names. The check uses 1,024-row pages and skips fixture rows
intentionally marked malformed. It runs after the relevant sync timer stops, so
its elapsed time is reported separately and is not included in import or
refresh latency.

Optional `--background-worker` marks the synthetic provider's last successful
Live timestamp stale, then queues a one-time no-ID `ProviderSyncWorker` through
WorkManager. It waits for terminal state and verifies the worker succeeded,
updated live-success metadata, and retained the exact channel count. This runs
the same provider-enumeration stale-refresh branch used by periodic and
launch-stale work; it does not wait for the periodic interval or validate its
network/battery scheduling constraints.

Each sample verifies the expected channel count after commit. Setup milestones
record live download start, save start, and the transition to movie category
shells after live commit. When synthetic movie/series summaries are configured,
the harness also waits for both durable index jobs to finish and checks Room
summary and active-index counts. The total setup time includes authentication,
initial workflow work, live import and existing category-shell completion;
summary indexing is reported separately. Refresh time ends when the existing
live repair command returns. Warmups are excluded from medians and maxima.
Request counts belong to the synthetic account's full lifecycle and include
both phases when refresh is enabled.

Optional `--repeat-vod-refresh` invokes the existing forced summary-index command
once for movies and then once for series after the initial jobs succeed. The
fixture and local rows are unchanged. It records per-section and combined
sequential elapsed time plus Java heap/ART GC samples for those direct refresh
calls, then verifies both Room and active-index counts again. This isolates an
unchanged summary refresh from setup and live import. It does not measure UI
readiness, playback, native RSS, or release-device performance. The P7 report
uses this option for its pre/post comparison.

When both movie and series fixtures are non-empty and at least one warmup is
configured, the first warmup seeds hydrated details, movie watch history,
protection flags and local playback metadata, then forces summary reindexing.
It checks that remote titles refresh while local IDs, hydrated fields, watch
state, protection, playback metadata and hydrated index timestamps survive.
The check is reported as `room_preservation_verified` and stays outside measured
samples when the configured warmup count is nonzero.

`XtreamSyncTiming` reports each streamed live response's wall duration, callback
duration, emitted row count and normal completion. Callback duration includes
downstream mapping and staging; remaining wall time includes network waits,
decode and request overhead. It is not parser CPU. Existing catalog timing
records capture stage batches, category commit, channel promotion and FTS
rebuild, now using a monotonic clock. The runner retains only structured
benchmark data and explicitly recognized numeric timing lines. It never
exports general provider logs, request URLs, response previews or passwords.

The P3 bulk pipeline adds numeric `live-pipeline` records for batch counts,
reader duration, final writer drain, send/backpressure time and estimated
outstanding raw batch bytes. See the
[pipeline report](XTREAM_SYNC_PIPELINE_IMPLEMENTATION.md) for resource accounting
and overlap semantics. These durations overlap and are not CPU measurements.

Java heap is sampled every 50 ms, with ART GC count deltas where available.
These are debug-emulator measurements, not release-device performance, native
RSS or an allocation profile. A short heap peak may fall between samples.
Production background jobs remain enabled and can contend with later samples.
Record environment, payload checksum and scenario with every comparison.

## Fixture scenarios

The fixture generates deterministic live, movie and series rows. Live JSON is
streamed without retaining the live list, and each catalog type can be
partitioned across categories. Metadata records seed, payload checksums, byte
sizes and expected counts. `--bulk-vod-status` and `--bulk-series-status` can
reject full responses while category requests succeed, exercising summary
fallback. EPG remains outside the benchmark. The fixture supplies no playable
media.

Examples (add normal iteration/mode options as needed):

```powershell
python tools/xtream_sync_benchmark.py --channels 50000 --categories 200 --header-delay-ms 100
python tools/xtream_sync_benchmark.py --channels 10000 --categories 100 --category-delay-ms 1500
python tools/xtream_sync_benchmark.py --channels 10000 --categories 100 --bytes-per-second 2000000
python tools/xtream_sync_benchmark.py --bulk-status 503 --modes STREAM_ALL
python tools/xtream_sync_benchmark.py --disconnect-after-rows 500 --modes STREAM_ALL
python tools/xtream_sync_benchmark.py --channels 1000 --categories 20 --disconnect-after-rows 500 --modes AUTO --experimental-auto-bulk --iterations 1 --warmups 0 --verify-channel-fields --settle-seconds 30
python tools/xtream_sync_benchmark.py --channels 1000 --categories 10 --movies 2000 --movie-categories 20 --series-items 1000 --series-categories 10 --bulk-vod-status 503 --bulk-series-status 503 --runtime-tier LOW --stage-batch-size 256 --modes STREAM_ALL --iterations 5 --warmups 1
python tools/xtream_sync_benchmark.py --channels 1000 --categories 10 --movies 2000 --movie-categories 20 --series-items 1000 --series-categories 10 --runtime-tier LOW --modes STREAM_ALL --iterations 5 --warmups 1 --repeat-vod-refresh
python tools/xtream_sync_benchmark.py --channels 1000 --categories 10 --movies 2000 --movie-categories 20 --series-items 1000 --series-categories 10 --bulk-vod-status 503 --bulk-series-status 503 --runtime-tier LOW --modes STREAM_ALL --iterations 5 --warmups 1 --repeat-vod-refresh
python tools/xtream_sync_benchmark.py --channels 1000 --categories 10 --movies 8000 --movie-categories 80 --series-items 4000 --series-categories 40 --runtime-tier DEVICE --stage-batch-size 256 --modes STREAM_ALL --iterations 3 --warmups 1
python tools/xtream_sync_benchmark.py --channels 1000 --categories 10 --movies 8000 --movie-categories 80 --series-items 4000 --series-categories 40 --bulk-vod-status 503 --bulk-series-status 503 --runtime-tier LOW --stage-batch-size 256 --modes STREAM_ALL --iterations 2 --warmups 1
python tools/xtream_sync_benchmark.py --channels 1000 --categories 10 --modes STREAM_ALL --iterations 1 --warmups 0 --first-browsable-screen
python tools/xtream_sync_benchmark.py --channels 10000 --categories 100 --modes STREAM_ALL --iterations 1 --warmups 1 --background-worker
```

These examples exercise synthetic category fallback and larger catalog sizes.
They are emulator evidence only; they do not measure playback, native RSS,
real-provider behavior, or physical-device performance. The P7 report records
the completed tier and route comparison matrix plus the unchanged VOD
pre-change comparison. That route-specific emulator comparison does not replace
the P0 release-device baseline or physical-device acceptance gate.

Also available: `--malformed-every`, `--throttle-every`, and `--seed`. Fault
scenarios are diagnostic checks: current retry/fallback behavior may prevent
completion, in which case the runner intentionally fails. A completed server
response alone never proves a complete client catalog.

Fixture/report tests:

```powershell
python -m unittest tools.tests.test_xtream_sync_perf_fixture tools.tests.test_xtream_sync_benchmark tools.tests.test_catalog_xtream_fixture
```

## Remaining measurement work

This package is an initial backend baseline, not the full P0 gate. Follow-up
work still needs precise headers/first-row and workflow-wait timings, attempt
and generation identifiers, row identity/field equivalence beyond counts,
skipped/duplicate/hidden coverage, parser CPU/allocation profiling, auxiliary
index readiness, release-build/device first-screen comparison, and full
WorkManager scheduling-constraint validation. Synthetic first-browsable and
no-ID background-worker measurements are available now. Pipeline byte high-water
and writer-drain timings now exist; actual heap/allocation attribution and queue
occupancy over time remain open. Run the full
five-sample scenario matrix and representative release-device measurements
before freezing production live-sync tuning. The P7 unchanged-summary result is
route-specific and stays separate from the release-device gate. Playback
validation is separate and follows AGENTS.md's sustained capture/log
requirements.
