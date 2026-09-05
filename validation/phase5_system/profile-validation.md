# Phase 5 System extraction — profile validation

Date: 2026-09-05 (updated after supported profile promotion).

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

A later retry used the available TV emulator through
`E:\\androidSdk\\platform-tools\\adb.exe` (`emulator-5554`, 1920x1080) and
force-stopped both app packages before starting the isolated
`criticalJourneys` test. The release activity launched and the benchmark
process remained alive, but the test stayed at `0/1`; logcat repeatedly
reported `UiDevice` `Active window root not found` while searching for the
`streamvault.destination:live_tv` route. The retry was stopped safely and the
benchmark package was force-stopped. Afterward, a direct ADB launch and D-pad
sequence exposed the release Home route, focused Live TV navigation, and the
seeded Live surface with `All Channels` containing 1,321 channels. This
separates the app's reachable seeded surface from the benchmark's lost-window
condition; it does not close the general profile gate.

A minimal benchmark-harness synchronization change was then made in
`benchmark/src/main/java/com/streamvault/benchmark/BenchmarkConfig.kt`: the
release target now waits for UiAutomator idle after `startActivityAndWait()`.
With the same `E:\\androidSdk\\platform-tools\\adb.exe` TV emulator, the
isolated `criticalJourneys` retry progressed beyond the initial
`Active window root not found` condition through seeded Home/Live navigation,
channel selection, and player setup. The profile collector remained unstable
through iteration 4, and the later player-preview step again lost the active
window root; the run was stopped safely. This improves benchmark
synchronization but does not produce a complete general profile.

The isolated `startup` retry completed successfully after the harness change:

```text
./gradlew.bat :benchmark:connectedNonMinifiedReleaseAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.benchmark.BaselineProfileGenerator#startup' --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 8m 24s — 1/1 test
```

The startup result confirms that the synchronization change does not regress
the startup profile journey. It does not change the status of the general
baseline-profile refresh at that point in the retry sequence: the checked-in
generated profiles were still stale and were not manually edited.

The successful critical journey was then followed by the supported promotion
workflow:

```text
./gradlew.bat :app:copyBaselineProfileIntoSrc --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 26m 29s
```

The connected task reported 0 failures (18/10 completed with eight skipped
entries). Gradle generated and copied the beta sources with 49,059 baseline
rules and 30,237 startup rules, then merged the startup rules into the
baseline source. The checked-in source files are now:

```text
app/src/main/generated/baselineProfiles/baseline-prof.txt — 5,652,478 bytes
app/src/main/generated/baselineProfiles/startup-prof.txt  — 3,221,129 bytes
```

The source scan found 126 `feature/system` descriptors and 15
`AppSystem` adapter descriptors in each file, with no old app Welcome,
Downloads, Plugins, `WelcomeGraph`, or `SystemGraph` descriptors. The files
were produced by Gradle; no generated profile rules were hand-edited.

## Interpretation

- Baseline/startup profile refresh is complete through the supported Gradle
  workflow, including successful seeded critical-journey collection and
  source promotion.
- The direct copy-task dependency cycle is fixed and covered by a successful
  dry run.
- The refreshed source contains feature-owned System descriptors and removes
  the old app-owned System screen/graph descriptors.
- No physical Android device was available; only the TV emulator was present.
- The System extraction does not alter player composition, stream preparation,
  recovery, lifecycle, surfaces, or playback routing. The critical-journey
  retries and successful final run are consequently recorded as benchmark
  evidence, not as evidence of a System presentation failure.
