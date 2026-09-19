# Catalog extraction post-measurement

Date: 2026-09-02
Post-extraction reference: `5f1e615e` plus the app navigation-boundary fix in
the final Catalog documentation commit
Toolchain: Java 21.0.5, Gradle 8.12, Windows 11 amd64, `--no-daemon`

## Feature source-edit samples

Each run used a reversible comment-only edit in
`feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/dashboard/DashboardHomeShelves.kt`.
The file was restored and its normalized Git hash matched `HEAD` afterward.

| Sample | Command | Duration | Result | Profile |
|---:|---|---:|---|---|
| 1 | `:feature:catalog:compileDebugKotlin --profile` | 109.4s | PASS | `profile-2026-09-02-23-39-00.html` |
| 2 | same | 8.3s | PASS | `profile-2026-09-02-23-40-45.html` |
| 3 | same | 8.5s | PASS | `profile-2026-09-02-23-40-54.html` |
| 4 | same | 8.2s | PASS | `profile-2026-09-02-23-41-02.html` |
| 5 | same | 8.3s | PASS | `profile-2026-09-02-23-41-10.html` |

The first run includes post-clean/configuration overhead. Warm median: 8.3s;
warm range: 8.2–8.5s.

## Feature test-compile samples

Each run used a reversible comment-only edit in
`feature/catalog/src/test/java/com/streamvault/feature/catalog/presentation/dashboard/DashboardHomeShelvesTest.kt`.

| Sample | Command | Duration | Result | Profile |
|---:|---|---:|---|---|
| 1 | `:feature:catalog:compileDebugUnitTestKotlin --profile` | 30.8s | PASS | `profile-2026-09-02-23-41-37.html` |
| 2 | same | 8.9s | PASS | `profile-2026-09-02-23-42-05.html` |
| 3 | same | 8.7s | PASS | `profile-2026-09-02-23-42-14.html` |
| 4 | same | 8.7s | PASS | `profile-2026-09-02-23-42-22.html` |
| 5 | same | 9.0s | PASS | `profile-2026-09-02-23-42-31.html` |

Warm median: 8.7s; warm range: 8.7–9.0s. Both temporary edits were removed
before the final validation commit.

## Isolation and packaging

An actual Catalog-only edit followed by `:app:assembleDebug` executed
`:feature:catalog:compileDebugKotlin`; sibling feature Kotlin tasks were
reported `UP-TO-DATE` and did not execute. The unfiltered Gradle dry-run lists
all dependency tasks by design, so the actual execution result is the
authoritative isolation check.

Additional guardrails:

- `clean :app:assembleDebug`: PASS, 80.7s.
- warm `:app:assembleDebug`: PASS, 14.9s.
- `:app:assembleBeta :app:assembleRelease`: PASS, 332.3s.

These are current-checkout samples, not a cache-equivalent five-run clean-build
comparison.

## Dashboard macrobenchmark diagnostic run

The focused `dashboardVerticalScroll` benchmark was rerun against the same API
36 TV emulator after installing the benchmark and seeded debug artifacts:

```text
./gradlew.bat :benchmark:connectedBenchmarkBenchmarkAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.benchmark.StreamVaultMacrobenchmark#dashboardVerticalScroll' `
  --no-daemon --console=plain --warning-mode=none
```

Before this run, the seeded debug app had accumulated recent-channel data by
opening public-M3U live channels. That made the Home `LazyColumn` scrollable;
the earlier no-data fixture had no scrollable content and produced
`IllegalStateException: Observed no renderthread slices in trace` before
metrics. The rerun passed 1/1 test with five warm iterations and emitted:

| Metric | Result |
|---|---|
| `frameCount` | min 88, median 91, max 98 |
| `frameDurationCpuMs` | P50 60.0, P90 70.7, P95 78.7, P99 92.7 |
| `frameOverrunMs` | P50 63.1, P90 78.9, P95 89.7, P99 106.1 |

The five Perfetto traces and the raw metric message are retained in the
Gradle-connected-test output directory. This is actionable after-run evidence,
but not a cache-equivalent paired before/after comparison: no equivalent
pre-extraction run was captured in this continuation, and the public M3U
fixture still lacks VOD/series content. Keep the formal paired performance gate
open.
