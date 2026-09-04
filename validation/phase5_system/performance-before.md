# Phase 5 System extraction — pre-extraction performance baseline

Captured: 2026-09-04 on `feature/improveCompose`, rollback SHA
`cddef1526924634819c7fb27d15c8a49b3945a1d`.

## Environment and protocol

- Gradle 8.12; Oracle JVM 21.0.5; Windows 11 amd64.
- All runs used `--no-daemon --console=plain --warning-mode=none` and
  `--profile`; the Gradle wrapper starts a single-use daemon for each run.
- The first source run refreshed configuration state; subsequent runs reused
  configuration cache. Each run followed a reversible one-blank-line edit in
  the named source and the file was restored before the next run.
- `WelcomeScreen.kt` and `StreamVaultPluginOwnerTest.kt` have the same Git blob
  hashes as `HEAD` after restoration (`fc8ea1e6...` and `62933d46...`).
- The focused pre-baseline build completed successfully in 3m 30s before these
  samples; its detailed task result is in `task0-inventory.md`.

## `:app:compileDebugKotlin` source-edit samples

| Sample | Duration | Build result | Task result | Cache state |
|---|---:|---|---|---|
| source-1 | 72.7s | success | 141 actionable; 3 executed; 138 up-to-date | configuration cache unavailable/rebuilt |
| source-2 | 19.8s | success | 141 actionable; 1 executed; 140 up-to-date | reused |
| source-3 | 20.6s | success | 141 actionable; 1 executed; 140 up-to-date | reused |
| source-4 | 20.7s | success | 141 actionable; 1 executed; 140 up-to-date | reused |
| source-5 | 21.1s | success | 141 actionable; 1 executed; 140 up-to-date | reused |

Raw durations: 72.7065s, 19.7733s, 20.6311s, 20.7051s, 21.0920s.
Min 19.8s; median 20.6s; max 72.7s. The first run is a cache/configuration
outlier and must not be compared with a post-extraction warm median without
matching cache state.

## `:app:compileDebugUnitTestKotlin` test-edit samples

| Sample | Duration | Build result | Task result | Cache state |
|---|---:|---|---|---|
| test-1 | 62.4s | success | 153 actionable; 4 executed; 1 from cache; 148 up-to-date | configuration cache unavailable/rebuilt |
| test-2 | 18.2s | success | 153 actionable; 1 executed; 152 up-to-date | reused |
| test-3 | 16.7s | success | 153 actionable; 1 executed; 152 up-to-date | reused |
| test-4 | 17.6s | success | 153 actionable; 1 executed; 152 up-to-date | reused |
| test-5 | 20.1s | success | 153 actionable; 1 executed; 152 up-to-date | reused |

Raw durations: 62.4346s, 18.1523s, 16.6664s, 17.5945s, 20.1057s.
Min 16.7s; median 18.2s; max 62.4s. The first run is a cache/configuration
outlier; the warm median is the paired comparison baseline.

## Baseline interpretation

These are pre-extraction measurements only. They establish paired warm medians
for the later System-only edit and feature test-compile measurements; they do
not yet demonstrate the Phase 5 gates of 25% source improvement, 20% unit-test
compile improvement, sibling Kotlin compile absence, clean/warm no-regression,
or startup no-regression.
