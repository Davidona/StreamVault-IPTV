# P6 live strategy and tuning report

Status: bulk-first AUTO is enabled in normal app profiles on this branch for
direct testing. Stage-batch defaults remain unchanged. Real-provider and
physical-device acceptance for P6 remain open.

| State | Work |
| --- | --- |
| Done | Bulk-first AUTO policy, hidden-category routing, progress changes, and the synthetic 10k-row matrix across batch sizes and policy overrides |
| Done | Synthetic setup-to-first-browsable-row timing through the real Welcome-to-Live screen on API 36 emulator |
| Done | No-ID stale `ProviderSyncWorker` refresh through WorkManager with catalog and metadata assertions |
| Done | Fresh APK emulator check: 10k-channel AUTO bulk success and 503-to-category fallback with full field verification |
| Done | Normal app debug APK installed and launched on the API 36 x86_64 emulator with app data preserved |
| Done | Successful new-provider completion now routes all setup protocols to Home; edited providers retain their prior return behavior |
| Deferred | Physical LOW/MID/HIGH-device acceptance; no physical devices are available |
| Pending | User real-provider compatibility and sustained-playback run, release-device comparison, and release rollout evidence |

Date: 2026-10-10  
Branch: `codex/xtream-live-sync-performance`

## Implementation exercised

- Normal app packages now use bulk-first AUTO by default. The isolated
  `com.streamvault.app.syncbenchmark` package can opt into this policy for the
  benchmark run or retain the previous policy for comparison.
- Bulk-first AUTO tests remembered category stress independently from
  bulk failure, honors an active full-catalog cooldown, and considers known
  hidden-category coverage. With at least 60% of known live channels hidden, it
  avoids downloading a full list for a selected-category refresh.
- Bulk results are filtered before staging for hidden categories, while
  previously committed hidden rows and required hidden rows are retained.
- Add Provider and Settings Live refresh use the same live strategy. Progress
  labels now distinguish loading, saving/committing, and committed Live TV.
  Committed row counts are reported after catalog promotion.
- Newly added Xtream providers use the bulk-first strategy when live sync is
  left at the default `AUTO` mode.
- Explicit category mode and bulk-failure fallback remain available.

## Branch activation and fresh-build verification (2026-10-10)

The normal debug APK was built from this branch and verified as
`com.streamvault.app.debug`, version `1.0.19-debug` (version code 21). It was
built with `-PcompatAbi=x86_64`; package inspection confirmed native ABIs
`arm64-v8a`, `armeabi-v7a`, and `x86_64`. The saved APK is
`build/xtream-sync-performance/streamvault-bulk-first-debug.apk`. It was
installed on `emulator-5554` with `adb install -r --no-streaming`, preserving
app data; `pm path` confirmed the installed package, and
`monkey -p com.streamvault.app.debug 1` launched it successfully. This is the
branch's ordinary debug app, not the isolated benchmark package or a Play Store
release. It is ready for the user to enter a real provider. No real-provider
credentials or provider requests were used in this validation.

The focused `XtreamLiveSyncPolicyTest` and `CatalogSyncRuntimeProfileTest`
passed, including the normal-package bulk-first default. The isolated benchmark
app and test APKs were then rebuilt, installed without clearing app data, and
rerun against the deterministic local fixture using AUTO with the LOW tier
profile simulated on the API 36 Android TV emulator (192 MB memory class).
Existing per-tier stage-batch sizes were kept.

The provider-completion navigation follow-up uses the shared setup route, so
Xtream, M3U, Stalker, Jellyfin, QR pairing, and backup import all send a newly
created provider to Home. Editing an existing provider still returns to its
launching screen. `ProviderGraphNavigationTest` covers the add-versus-edit
navigation rule. The ordinary debug APK was rebuilt and reinstalled after this
change; the install preserved app data. Real-provider behavior has not yet been
tested.

Build and launch the ordinary branch APK with:

```powershell
.\gradlew.bat :app:assembleDebug -PcompatAbi=x86_64 --console=plain
New-Item -ItemType Directory -Force build/xtream-sync-performance | Out-Null
Copy-Item app/build/outputs/apk/debug/app-debug.apk build/xtream-sync-performance/streamvault-bulk-first-debug.apk -Force
adb -s emulator-5554 install -r --no-streaming build/xtream-sync-performance/streamvault-bulk-first-debug.apk
adb -s emulator-5554 shell monkey -p com.streamvault.app.debug 1
```

| Scenario | State | Coverage | Live requests | Setup elapsed |
| --- | --- | --- | --- | ---: |
| Bulk succeeds | Success | 10,000/10,000 channels and all fixture fields verified; 100 categories | 1 bulk, 0 category | 7.00 s ([report](../build/xtream-sync-performance/20261010T142614124918Z/report.json)) |
| Bulk HTTP 503, then fallback | Partial after complete live fallback | 10,000/10,000 channels and all fixture fields verified; 100 categories | 3 bulk attempts (503), 100 category successes, no post-fallback bulk retries | 16.77 s ([report](../build/xtream-sync-performance/20261010T142632162841Z/report.json)) |

Both runs completed instrumentation successfully. The second run also logged
that queued onboarding recovery was coalesced after the category fallback.
These are single-run synthetic diagnostics, not a performance comparison or a
real-provider/playback acceptance result. The fixture contains no playable
media; sustained playback validation is still deferred.

## Benchmark setup

The isolated debug and Android test APKs were built with:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest `
  -PxtreamSyncBenchmark=true -PcompatAbi=x86_64
```

The generated application IDs were `com.streamvault.app.syncbenchmark` and
`com.streamvault.app.syncbenchmark.test`. The APKs were installed with
`adb install -r --no-streaming`; application data was not cleared. The device
was the API 36 Android TV emulator (`sdk_google_atv64_x86_64`), with a 192 MB
memory class and a 192 MiB maximum Java heap. All measurements used the debug
benchmark package, a local synthetic provider, 10,000 channels, 100 categories,
and the same 2,426,989-byte fixture payload (`4dfbcaf7…31d63a3`). The fixture
contains no playable media.

Each table row below has one warm-up and five measured setup/refresh pairs.
Request counts are per synthetic account and include both setup and Settings
refresh. Timings cover backend setup or refresh completion; they do not measure
the first usable screen. Java heap is sampled by instrumentation and excludes
native RSS.

## Stage batch exploration

These runs used explicit `STREAM_ALL`. Tier names are policy overrides on one
emulator, not measurements from physical LOW/MID/HIGH devices.

| Tier override | Batch rows | Add Provider median / max (ms) | Settings refresh median / max (ms) | Max sampled Java heap (MB) | Evidence |
| --- | ---: | ---: | ---: | ---: | --- |
| LOW | 100 | 5,726 / 5,777 | 5,541 / 5,657 | 11.3 | [report](../build/xtream-sync-performance/20261010T093957897893Z/report.json) |
| LOW | 128 | 5,518 / 5,529 | 5,223 / 5,393 | 11.6 | [report](../build/xtream-sync-performance/20261010T094107248725Z/report.json) |
| LOW | 256 | 5,255 / 5,446 | 5,092 / 5,137 | 11.6 | [report](../build/xtream-sync-performance/20261010T094217729971Z/report.json) |
| MID | 300 | 5,279 / 5,317 | 5,057 / 5,121 | 12.7 | [report](../build/xtream-sync-performance/20261010T094326043505Z/report.json) |
| MID | 512 | 5,156 / 5,311 | 4,985 / 5,037 | 11.9 | [report](../build/xtream-sync-performance/20261010T094434065503Z/report.json) |
| HIGH | 500 | 4,964 / 5,424 | 4,896 / 5,021 | 13.9 | [report](../build/xtream-sync-performance/20261010T094540490695Z/report.json) |
| HIGH | 1,024 | 3,877 / 3,987 | 3,656 / 3,776 | 12.9 | [report](../build/xtream-sync-performance/20261010T094643512690Z/report.json) |

Every measured operation retained all 10,000 channels and used two bulk live
requests per account, with no category requests. Larger batches were faster in
these sequential runs. The emulator, run order, and synthetic provider limit
that observation; no production batch size is promoted from this result.

## Candidate AUTO and behavior checks

| Scenario | Tier / batch | Add Provider median (ms) | Settings refresh median (ms) | Live requests per account | Evidence |
| --- | --- | ---: | ---: | --- | --- |
| Candidate AUTO | LOW / 256 | 5,043 | 4,932 | 2 bulk, 0 category | [report](../build/xtream-sync-performance/20261010T094742058234Z/report.json) |
| Candidate AUTO | MID / 512 | 5,200 | 4,945 | 2 bulk, 0 category | [report](../build/xtream-sync-performance/20261010T094841875839Z/report.json) |
| Candidate AUTO | HIGH / 1,024 | 5,159 | 4,911 | 2 bulk, 0 category | [report](../build/xtream-sync-performance/20261010T094948164463Z/report.json) |
| 80% hidden before refresh | LOW / 256 | 3,856 | 2,263 | 1 bulk, 20 category | [report](../build/xtream-sync-performance/20261010T095100069509Z/report.json) |
| 80% hidden before refresh | MID / 512 | 3,833 | 1,983 | 1 bulk, 20 category | [report](../build/xtream-sync-performance/20261010T095141776371Z/report.json) |
| 80% hidden before refresh | HIGH / 1,024 | 5,114 | 2,506 | 1 bulk, 20 category | [report](../build/xtream-sync-performance/20261010T095221680576Z/report.json) |
| Remembered category stress | HIGH / 1,024 | 3,663 | 3,516 | 2 bulk, 0 category | [report](../build/xtream-sync-performance/20261010T095319396060Z/report.json) |
| Full-catalog cooldown active | HIGH / 1,024 | 5,053 | 4,676 | 1 bulk, 100 category | [report](../build/xtream-sync-performance/20261010T095408941106Z/report.json) |

Every operation in these groups recorded the expected 10,000-channel count.
The hidden-category runs retained hidden rows while using the 20 visible
categories for the Settings refresh. Remembered category stress did not force
the candidate into category mode. An active bulk cooldown did select category
mode for refresh.

### Bulk HTTP 503 fallback

The 10,000-channel HIGH candidate run with bulk HTTP 503 completed all sample
iterations and preserved the 10,000-row count. Per account, instrumentation
recorded six bulk attempts and 200 category requests across setup and refresh;
the initial phase retried/recovered through category requests, and the active
cooldown kept refresh on categories. The resulting sync state was `Partial`
because the failed bulk attempt remained a warning, despite complete row-count
assertions. Treat this as evidence of data retention and fallback execution,
not a clean-success status.

[Bulk 503 report](../build/xtream-sync-performance/20261010T095515495918Z/report.json)

All reports have `complete=true`, `request_events_truncated=false`, and five
measured samples per phase. The benchmark runner checked instrumentation exit
status and exact row counts. These checks establish the synthetic backend path;
they do not establish provider compatibility or production-device readiness.

## Invalid preliminary batch

The first set of batch measurements ran before the newly built isolated APK was
installed, so its runtime tier and batch overrides were not active. Reports
from `20261010T092817951398Z` through `20261010T093826139108Z` were discarded
and are not used in the tables. The APK was then installed without a data wipe,
and a smoke run confirmed the HIGH candidate settings and two-request route
before collecting the reported measurements.

## Synthetic first-browsable-screen check

The opt-in UI probe launched the isolated app on Welcome, ran the production
`ValidateAndAddProvider.loginXtream` use case against the local fixture, and
waited for the first channel row to become accessible in Live TV. It temporarily
hid empty virtual Favorites and Recent categories so the initial selection was
All Channels, then restored the prior landing and category preferences. This
drives the real navigation and screen but does not interact with the provider
form.

| Fixture / device | Runs | Setup median / max | First browsable row median / max | Channels retained |
| --- | ---: | ---: | ---: | ---: |
| 10,000 live rows / 100 categories; API 36 Android TV emulator; DEVICE policy | 1 warmup + 5 measured | 5.71 / 5.77 s | 6.71 / 6.87 s | 10,000 / 10,000 |

The run peaked at 36,060,832 sampled Java-heap bytes, observed at most two
simultaneous synthetic HTTP requests, recorded all expected samples, and had no
truncated request events. The setup median is backend setup completion; the
first-row median includes navigation and screen readiness. These are debug
emulator measurements, not a physical-device or release-build comparison, and
do not validate media playback.

[First-browsable report](../build/xtream-sync-performance/20261010T113111995992Z/report.json)

## Synthetic background-worker check

The opt-in `--background-worker` probe created one active synthetic provider,
set only its `lastLiveSuccess` metadata stale, and enqueued an actual
no-provider-ID `ProviderSyncWorker` request through WorkManager. This exercises
the provider-enumeration stale-refresh branch used by periodic and launch-stale
work. It waited for the work's terminal state, required `SUCCEEDED`, checked
that all 10,000 channels remained, and required `lastLiveSuccess` to advance.

| Fixture / device | Runs | Worker median / max | Work state | Channels retained |
| --- | ---: | ---: | --- | ---: |
| 10,000 live rows / 100 categories; API 36 Android TV emulator; DEVICE policy | 1 warmup + 1 measured | 5.80 / 5.80 s | SUCCEEDED for both | 10,000 / 10,000 |

The measured synthetic account made two bulk live-list requests (initial setup
and worker refresh), no category-scoped live-list requests, and the fixture
recorded no truncated request events. This validates the worker execution path
and catalog preservation on the debug emulator; it does not validate WorkManager
six-hour scheduling constraints, real-provider behavior, physical devices, or
playback.

[Background-worker report](../build/xtream-sync-performance/20261010T113351914960Z/report.json)

## Direct-test status and release gate

The exploratory data does not meet the P6 release gate. It is from one debug
emulator with simulated policy tiers and a synthetic provider. Bulk-first AUTO
is active in normal app packages on this branch for direct testing.
An opt-in
setup-to-first-browsable-row check now exercises the real Welcome-to-Live UI on
that emulator, but physical LOW, MID, and HIGH device measurements,
release-build memory and first-screen readiness comparison, the user's
real-provider compatibility run, and WorkManager scheduling-constraint
validation remain open. The required two-channel sustained
playback validation also remains open: the fixture has no media, and no real
provider has yet been tested. Follow the repository live-TV validation
instructions for that playback run. Stage-batch defaults stay unchanged pending
broader device evidence.
