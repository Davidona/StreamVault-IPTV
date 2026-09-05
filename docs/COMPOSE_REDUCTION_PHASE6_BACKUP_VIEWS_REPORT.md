# Phase 6 Backup Import Views Experiment Report

## Environment

- Repository `HEAD`: `a223337b` (`feat: add views backup import preview experiment`); benchmark and paired-host changes were working-tree experiment changes on top of this commit.
- Device/API/resolution: `Television_1080p(AVD) - 16`, API 36, 1920x1080.
- APK/runner: `com.streamvault.app.debug` debug fixture launched by the Macrobenchmark APK; `StartupMode.WARM`; five iterations per mode.
- Runner caveat: Macrobenchmark warned that the device is an emulator and the target is debuggable, so results are directional rather than production-device estimates.

## Results

| Mode | Initial display median | Interaction `frameDurationCpuMs` P50 | Interaction `frameOverrunMs` P50 | Functional result |
|---|---:|---:|---:|---|
| Compose | 3,319.6 ms | 511.6 ms | 718.5 ms | Passed |
| Views | 3,531.1 ms | 567.0 ms | 789.0 ms | Passed |

Relative to Compose, Views was approximately 6.4% slower on initial display, 10.8% slower on interaction CPU frame duration, and 9.8% slower on frame overrun.

The focused connected presentation matrix passed for both modes (2 tests), the reviewed Views golden capture passed, and the focused Views controller unit test passed. Macrobenchmark result artifacts were written under `benchmark/build/outputs/connected_android_test_additional_output`.

PSS/memory was not collected because the candidate already failed the primary performance comparison; no memory conclusion is drawn from this report.

## Behavior and visual review

Both implementations supported the paired journey: initial focus, conflict-strategy change, provider toggle, and Back dismissal. The Views candidate rendered the required title, summaries, controls, footer, and focus state in the 1920x1080 capture.

## Decision

Drop the Views candidate. It did not produce the required repeatable performance win and was slower in both primary benchmark dimensions. Keep Compose as the production implementation. The candidate was never wired into the production Settings flow.

## Follow-up

Do not spend more time polishing this Views surface. Use the retained benchmark evidence to choose the next Phase 6 candidate or optimization target. If a future candidate is evaluated, repeat the same control/candidate protocol before visual polish.
