# Phase 5 System extraction — post-extraction performance

Captured: 2026-09-04 on `feature/improveCompose`, after the System
presentation extraction. The paired pre-extraction baseline is recorded in
`performance-before.md`.

## Protocol

- Gradle 8.12; Oracle JVM 21.0.5; Windows 11 amd64.
- Each sample used `--profile --no-daemon --console=plain --warning-mode=none`.
- The source and test measurements used reversible one-blank-line edits and
  restored the files byte-for-byte after each series.
- Source hash after restoration:
  `WelcomeScreen.kt = ca1c597ed3c8a3c4bfa47ce9749bd1c4e9e21ad5`.
- Test hash after restoration:
  `WelcomeViewModelTest.kt = 928b17738c55a0035b1adb8aacf6b031b4800ce8`.

## `:feature:system:compileDebugKotlin` source-edit samples

| Sample | Duration | Result |
|---|---:|---|
| source-1 | 22.9892s | success |
| source-2 | 7.2796s | success |
| source-3 | 7.3984s | success |
| source-4 | 7.3602s | success |
| source-5 | 7.2687s | success |

Raw durations: 22.9892s, 7.2796s, 7.3984s, 7.3602s, 7.2687s.
All-sample min/median/max: 7.2687s / 7.3602s / 22.9892s. The first sample
is the configuration/cache warm-up outlier. Warm samples 2–5 have a median of
7.3199s.

## `:feature:system:compileDebugUnitTestKotlin` test-edit samples

| Sample | Duration | Result |
|---|---:|---|
| test-1 | 25.1138s | success |
| test-2 | 7.6541s | success |
| test-3 | 7.5883s | success |
| test-4 | 7.7130s | success |
| test-5 | 7.6373s | success |

Raw durations: 25.1138s, 7.6541s, 7.5883s, 7.7130s, 7.6373s.
All-sample min/median/max: 7.5883s / 7.6373s / 25.1138s. The first sample
is the configuration/cache warm-up outlier. Warm samples 2–5 have a median of
7.6457s.

## Paired value gate

Compared with the pre-extraction warm medians in `performance-before.md`:

| Measurement | Before | After | Change | Gate |
|---|---:|---:|---:|---|
| System source edit | 20.6311s | 7.3199s | 64.56% faster | pass (≥25%) |
| System unit-test compile | 18.1523s | 7.6457s | 57.88% faster | pass (≥20%) |

The results are comparable warm paired measurements. The first sample in each
series is reported, but excluded from the paired median because it includes
configuration/cache initialization.

## Build guardrails

- `clean :app:assembleDebug`: passed; `BUILD SUCCESSFUL in 1m 9s`, 255
  actionable tasks, 113 executed, 142 from cache. This was a clean invocation
  with remote build-cache reuse.
- Warm `:app:assembleDebug`: passed; `BUILD SUCCESSFUL in 14s`, 242
  actionable tasks, 1 executed, 241 up-to-date.
- `:app:assembleBeta :app:assembleRelease`: passed; `BUILD SUCCESSFUL in
  18m35s`, 464 actionable tasks, 276 executed, 149 from cache, 39 up-to-date.
- The app lint task remains red on pre-existing app-wide debt and is tracked
  separately in the final System report; feature lint passes.

The source-edit and unit-test thresholds pass. Sibling Kotlin isolation,
startup/profile status, and the remaining verification matrix are recorded in
the System report rather than inferred from these two measurements.
