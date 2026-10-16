# Xtream live parsing and group identity

Implemented after the attempt-isolation package on
`codex/xtream-live-sync-performance`, 2026-10-09.

## Changes

The thin live sync projection now reads tokens directly into its DTO. It skips
unknown fields without building their JSON trees and removes the Gson-tree to
JSON-text to Kotlin-serializer conversion for every row. The existing domain
mapper continues to decide channel admission, category identity, adult flags,
text compatibility, catch-up values, stream URLs and protection defaults.

Retained fields preserve lenient scalar/list behavior, missing/null defaults,
last-key-wins handling, including nested category objects, and text escaping.
The service honors strict unknown-key configuration. Category-list recursion has
an explicit depth limit. Truncated and malformed array syntax is classified as
parsing failure; transport failures and cancellation keep their own semantics.
The legacy decoder remains available through the existing compatibility path.

Group-ID generation now executes the existing canonical-name/key algorithm
directly. It avoids calculating playback resolution, codec, language, source,
transport and variant attributes that sync does not use. Playback classification
continues to calculate those attributes. Frequently used canonical separators
reuse compiled expressions. The group-ID format and canonicalization stay the
same, preserving grouping identity across refreshes and providers.

## Component measurements

API 36 Android TV x86_64 emulator, debug library instrumentation. Each scenario
uses 10,000 synthetic entries, two warmups per method and five measured samples,
alternating method order. Measurements use a monotonic wall clock and Android's
current-thread CPU clock. Every parser sample verifies row count and stream-ID
sum; every group-ID sample verifies the aggregate identity against classification.

| Component | Previous method median CPU | New method median CPU | Previous/new median wall | Previous/new median GC count |
| --- | ---: | ---: | ---: | ---: |
| Core live fields | 322.492 ms | 62.959 ms | 375.706 / 73.299 ms | 36 / 8 |
| Core fields plus ignored metadata | 480.483 ms | 62.703 ms | 534.320 / 69.136 ms | 30 / 4 |
| Group identity for tagged names | 7,138.095 ms | 1,335.489 ms | 8,101.808 / 1,513.209 ms | 505 / 117 |

The parser CPU reduction is about 80% for core fields and 87% with extra
metadata; group-ID CPU falls about 81% for these names. GC counts cover the
whole process and can include other work. These are component measurements,
excluding network, Room, the app graph, promotion and screen readiness. They
cannot establish a total-sync multiplier or release-device performance.

All 30 measured samples completed with matching fingerprints. Report:
`build/xtream-sync-performance/p2-components-20261009T174801Z/report.json`.
The synthetic instrumentation benchmark is
`XtreamLiveComponentBenchmarkTest#compareLiveDecoderAndGroupIdentity`.
It requires an explicit argument so routine correctness suites skip the timed
comparison:

```powershell
.\gradlew.bat :data:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.remote.xtream.XtreamLiveComponentBenchmarkTest#compareLiveDecoderAndGroupIdentity' -Pandroid.testInstrumentationRunnerArguments.xtreamLiveComponentBenchmark=true
```

## Correctness and integration

Differential decoder checks compare directly against the previous conversion,
covering every retained field across loose scalar types, nested category IDs,
duplicate keys, escaping and 1,000 deterministic mixed provider rows. Additional
checks cover strict unknown keys and the nesting bound. Group-ID equivalence
covers multilingual, accented, blank, punctuation-only, tagged and quality names
across multiple provider IDs, alongside existing expected-identity checks.

The final integration build passed 207 data and 16 domain tests and built both
isolated benchmark APKs. The instrumentation opt-in guard was also verified:
routine runs skip the component comparison.

Synthetic Add Provider and Settings Live refresh passed in all three requested
modes: six samples, 2,000 saved channels in every sample and successful cleanup.
The STREAM_ALL account issued two bulk requests and no category requests across
its initial/refresh lifecycle; its initial import took 1,137 ms and refresh
1,090 ms. These single samples are smoke evidence, not a performance median.
Report: `build/xtream-sync-performance/20261009T175109203489Z/report.json`.

A larger STREAM_ALL run used 10,000 rows/100 categories, one warmup and five
measured initial/refresh pairs. All 12 samples, including warmups, retained all
10,000 channels and passed cleanup. The completion marker and sample count
matched, instrumentation passed, and request history was not truncated.
Report: `build/xtream-sync-performance/20261009T175359801867Z/report.json`.

| Phase | All measured samples median | Maximum | Measured samples |
| --- | ---: | ---: | ---: |
| Initial import | 4,566.944 ms | 26,110.422 ms | 5 |
| Settings Live refresh | 4,579.747 ms | 27,734.189 ms | 5 |

These groups mix strategies. Two measured accounts used only bulk requests,
with initial/refresh medians of 4,488.678 / 4,566.173 ms (two samples per phase).
The other accounts mixed bulk with category requests. Logs confirmed three
low-memory aborts and one pre-request full-catalog skip; aborted attempts safely
fell back and retained complete counts. Maximum measured sampled Java heap was
15,331,632 bytes, but Android's system-memory signal changed independently.
Request high-water was one: this implementation has no new retrieval overlap.

The payload checksum matches the original 10,000-row baseline. That baseline
ran entirely through categories, so these results do not establish a before/after
bulk speedup or a reliable AUTO time. They demonstrate faster components, a
working bulk path and a remaining source of inconsistent import time.

AUTO strategy, request concurrency and healthy-provider pacing remain governed
by their existing rules. The bounded pipeline and category scheduling packages
follow before the default-strategy release gate.
