# Phase 5 System extraction — profile validation

Date: 2026-09-04.

## Supported workflow

The implementation uses the existing app baseline-profile workflow. The
prescribed plan command was attempted but this checkout has no
`:benchmark:pixel2Api36Setup` project:

```text
./gradlew.bat :benchmark:pixel2Api36Setup:generateBaselineProfile :app:assembleRelease --no-daemon --console=plain --warning-mode=none
FAILURE: project ':benchmark:pixel2Api36Setup' not found in project ':'.
```

The current task is app-level `:app:generateBaselineProfile`. It built and
installed the non-minified release target and started the benchmark generator
on `Television_1080p(AVD) - 16`.

## Result

The `BaselineProfileGenerator.startup` method completed successfully and
emitted a fresh startup profile during the run. The captured output contained
nonzero System descriptors including:

```text
com/streamvault/feature/system/R$string
com/streamvault/feature/system/api/SystemWelcomePort
com/streamvault/feature/system/api/WelcomeDevProviderConfig
com/streamvault/feature/system/navigation/SystemGraphKt
com/streamvault/app/system/AppSystemWelcomeAdapter
```

The subsequent `criticalJourneys` method remained at `1/10` with no failure
diagnostic for the bounded collection window. It was stopped safely; the
overall generator process therefore ended with user-interrupt status and did
not complete the profile install/copy step. This benchmark journey exercises
seeded Home/Live/player navigation and does not cover the System routes.

The maintained files under `app/src/main/generated/baselineProfiles/` were
not manually edited. They still contain stale pre-extraction System entries,
including old app `WelcomeGraph`, `SystemGraph`, Welcome, Downloads, and
Plugins descriptors. The generated fresh startup output was inspected during
the run and had no old app System descriptors, but it was not promoted into
source because the supported generator did not finish its complete workflow.

## Interpretation

- Baseline-profile refresh is open and must be rerun when the seeded benchmark
  journey completes end-to-end.
- No claim is made that the stale checked-in generated profile is refreshed.
- No physical Android device was available; only the TV emulator was present.
- The System extraction does not alter player composition, stream preparation,
  recovery, lifecycle, surfaces, or playback routing. The critical-journey
  stall is consequently recorded as a benchmark/seeded-environment follow-up,
  not as evidence of a System presentation failure.
