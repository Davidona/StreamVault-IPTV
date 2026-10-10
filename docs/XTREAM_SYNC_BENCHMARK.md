# Xtream live sync benchmark

This is the first implementation package of the
[improvement plan](XTREAM_SYNC_IMPROVEMENT_PLAN.md). It measures the current
engine before changing its defaults, decoding, concurrency or commit behavior.

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

Each sample verifies the expected channel count after commit. Setup milestones
record live download start, save start, and the transition to movie category
shells after live commit. The total setup time includes authentication, initial
workflow work, live import and existing category-shell completion. Refresh time
ends when the existing live repair command returns. Warmups are excluded from
medians and maxima. Request counts belong to the synthetic account's full
lifecycle and include both phases when refresh is enabled.

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

The fixture generates JSON incrementally without retaining the live list. The
same deterministic live rows are partitioned across categories. Metadata records
seed, payload checksum, byte size and expected admitted channel count. Empty
VOD/series shells and EPG skip keep this measurement focused on live ingestion.
The fixture supplies no playable media.

Examples (add normal iteration/mode options as needed):

```powershell
python tools/xtream_sync_benchmark.py --channels 50000 --categories 200 --header-delay-ms 100
python tools/xtream_sync_benchmark.py --channels 10000 --categories 100 --category-delay-ms 1500
python tools/xtream_sync_benchmark.py --channels 10000 --categories 100 --bytes-per-second 2000000
python tools/xtream_sync_benchmark.py --bulk-status 503 --modes STREAM_ALL
python tools/xtream_sync_benchmark.py --disconnect-after-rows 500 --modes STREAM_ALL
```

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
index readiness, and a first-browsable-screen measurement. Pipeline byte
high-water and writer-drain timings now exist; actual heap/allocation attribution
and queue occupancy over time remain open. Run the full
five-sample scenario matrix and representative release-device measurements
before freezing production tuning or claiming a speedup. Playback validation
is separate and follows AGENTS.md's sustained capture/log requirements.
