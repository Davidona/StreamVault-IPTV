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

The isolated startup command was:

```text
./gradlew.bat :benchmark:connectedNonMinifiedReleaseAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.benchmark.BaselineProfileGenerator#startup' --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 4m 59s — 1/1 test
```

The fresh artifact was emitted under
`benchmark/build/outputs/connected_android_test_additional_output/nonMinifiedRelease/connected/Television_1080p(AVD) - 16/`;
the timestamped startup profile was 3,240,532 bytes. Its scan returned no old
app Welcome/Downloads/Plugins or old app graph descriptors and returned
nonzero `feature/system` descriptors.

The subsequent independent `criticalJourneys` run started 1 test, remained at
`0/1`, and produced no failure diagnostic during the bounded collection
window. It was stopped safely. The earlier full generator run showed the same
behavior as `1/10`. This benchmark journey exercises seeded Home/Live/player
navigation and does not cover the System routes.

The first direct `:app:copyBaselineProfileIntoSrc --dry-run` exposed a cycle in
the existing app profile-task wiring. The build guard now treats direct
`copyBaselineProfileIntoSrc` and `mergeBaselineProfile` requests as profile
generation requests, and the same dry run completes successfully. An actual
copy still requires a completed general baseline collection.

The maintained files under `app/src/main/generated/baselineProfiles/` were
not manually edited. They still contain stale pre-extraction System entries,
including old app `WelcomeGraph`, `SystemGraph`, Welcome, Downloads, and
Plugins descriptors. The generated fresh startup output was inspected during
the run and had no old app System descriptors, but it was not promoted into
source because the supported generator did not finish its complete workflow.

## Interpretation

- Baseline-profile refresh is open and must be rerun when the seeded benchmark
  journey completes end-to-end.
- The direct copy-task dependency cycle is fixed and covered by a successful
  dry run.
- No claim is made that the stale checked-in generated profile is refreshed.
- No physical Android device was available; only the TV emulator was present.
- The System extraction does not alter player composition, stream preparation,
  recovery, lifecycle, surfaces, or playback routing. The critical-journey
  stall is consequently recorded as a benchmark/seeded-environment follow-up,
  not as evidence of a System presentation failure.
