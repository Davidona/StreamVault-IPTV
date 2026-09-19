# Phase 5 System — Task 10 connected validation

Date: 2026-09-04

## Device and test scope

Connected validation ran on the single available device:

```text
emulator-5554 — Television_1080p(AVD) - 16
API 36, 1920x1080
```

The six golden assets were recorded from deterministic fake states, visually
inspected at full resolution, and then compared pixel-for-pixel. The reviewed
captures are also retained under `task10-screenshots/`.

## Results

Recording mode completed successfully:

```text
./gradlew.bat :feature:system:connectedDebugAndroidTest '-PsystemGoldens.record=true' --no-daemon --no-configuration-cache --console=plain --warning-mode=none
BUILD SUCCESSFUL — 16/16 tests
```

Comparison mode completed successfully after the baselines were pulled and
reviewed:

```text
./gradlew.bat :feature:system:connectedDebugAndroidTest --no-daemon --no-configuration-cache --console=plain --warning-mode=none
BUILD SUCCESSFUL — 16/16 tests
```

After adding the explicit RTL/large-font/paused-clock variant coverage, the
variant class passed independently:

```text
./gradlew.bat :feature:system:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.feature.system.SystemPresentationVariantTest' --no-daemon --no-configuration-cache --console=plain --warning-mode=none
BUILD SUCCESSFUL — 3/3 tests
```

The final full-suite result includes the variant tests and the bounded
PixelCopy retry in `GoldenCapture.kt`:

```text
./gradlew.bat :feature:system:connectedDebugAndroidTest --no-daemon --no-configuration-cache --console=plain --warning-mode=none
BUILD SUCCESSFUL — 19/19 tests on Television_1080p(AVD) - 16
```

The first 19-test attempt exposed two transient PixelCopy/window-capture
failures while the same cases passed when isolated. The helper now retries
capture up to three times with a 250 ms delay; bitmap dimensions and every
pixel are still compared exactly. The rerun passed all 19 tests.

Reduced-motion variant validation also passed with all three emulator global
animation scales temporarily set to `0`; the original values were restored
(`animator_duration_scale=1`, `transition_animation_scale=1.0`, and
`window_animation_scale=1.0`).

The connected suite covers:

- 9 Welcome, Downloads, and Plugins presentation behavior tests;
- 1 controller-free System graph route test;
- 6 reviewed golden cases: Welcome no-provider/syncing, Downloads
  empty/completed, and Plugins empty/configuration;
- 3 RTL/1.3 font-scale tests with the Compose test clock paused.

The golden comparison helper fails when any named baseline is missing and
checks exact bitmap dimensions and pixels against the reviewed assets.

## Runtime and log result

The connected variant run was performed after clearing logcat. The captured
log was filtered to remove unrelated platform Cast, WindowManager, loader, and
device-identifier diagnostics before it was retained as evidence in
`task10-logcat.txt`.

```text
NO_APP_FATAL_RUNTIME_MATCHES
NO_SENSITIVE_PATTERNS_FOUND
```

No System-caused fatal exception, resource lookup failure, launcher failure,
or app-tagged runtime error was found.

## Live-TV validation decision

The long-duration Live-TV protocol is waived for this slice. The extraction
does not change player composition, stream preparation, playback candidate
selection, Cast URL rewriting, lifecycle handling, surfaces, overlays,
recovery, or plugin playback routing. Plugin IPC, provider ownership, work
coordination, and playback infrastructure remain in `:app`. If a later review
finds a playback-affecting change in this slice, this waiver is invalid and the
change must be split and validated with the repository's two-channel protocol.
