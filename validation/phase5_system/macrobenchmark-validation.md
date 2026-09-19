# Phase 5 macrobenchmark validation

Captured 2026-09-05 on the API 36 `Television_1080p(AVD) - 16` emulator
(`emulator-5554`) after the System extraction and benchmark-harness fix.

## Command

```text
gradlew.bat :benchmark:connectedNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
  -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.benchmark.StreamVaultMacrobenchmark \
  --no-daemon --console=plain --warning-mode=none
```

The explicit `Macrobenchmark` selector is required because the baseline-profile
plugin's aggregate task otherwise selects only the profile generator rules.
The cold-start journeys now force-stop `com.streamvault.app` in their
per-iteration setup, so independent startup tests satisfy Macrobenchmark's
cold-start precondition even when another test method ran immediately before
them.

## Result

- 8 tests passed, 0 failures, 0 errors, and 0 skipped.
- Instrumentation result: `OK (8 tests)`; measured time `1,381.073 s`.
- Gradle result: `BUILD SUCCESSFUL in 24m 10s`.
- The two release cold-start methods each completed all 10 iterations.
- The six seeded interaction methods each completed all 5 iterations.

| Journey | Iterations | Result | Key metric |
|---|---:|---|---|
| `coldStartupNoCompilation` | 10 | pass | TTID median 2,416.7 ms |
| `coldStartupWithBaselineProfile` | 10 | pass | TTID median 2,224.2 ms |
| `dashboardVerticalScroll` | 5 | pass | frame-count median 76 |
| `epgHorizontalAndVerticalNavigation` | 5 | pass | frame-count median 44 |
| `liveTvCategoryAndChannelNavigation` | 5 | pass | frame-count median 39 |
| `liveTvCategoryReentryCategoryBuild` | 5 | pass | frame-count median 61 |
| `playerControlsOpenAndNavigate` | 5 | pass | frame-count median 49 |
| `settingsScrollAndDialogNavigation` | 5 | pass | frame-count median 72 |

## Gate interpretation

This closes the Phase 5 emulator execution gate for the explicit macrobenchmark
suite and confirms the release cold-start harness is isolated between methods.
The results are diagnostic: the run uses an emulator and the seeded interaction
journeys target the debuggable `com.streamvault.app.debug` fixture. A matched
pre/post performance comparison, physical-device measurements, and the other
feature-specific acceptance gates remain open.
