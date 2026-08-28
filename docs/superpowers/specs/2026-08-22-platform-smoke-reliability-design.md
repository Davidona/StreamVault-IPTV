# Platform Smoke Reliability Design

## Problem

The release workflow is currently the only practical place to exercise the full Android API matrix. That makes `master` the feedback loop for CI experiments. The latest workflow also prebuilds instrumentation APKs in a separate Gradle invocation before starting the emulator. After that change, API 33 and API 35 regressed from passing to dying before test discovery, while API 36 remained unresolved. Failed jobs preserve neither device crash diagnostics nor connected-test reports.

## Design

Move the platform matrix into a reusable workflow that runs on pushes to `develop` and supports both `workflow_call` and manual execution. The release workflow will call it after the host build, so the same gate is exercised before merging and during release.

Each matrix entry will build, install, and execute from the emulator runner's script, matching the last known-good API 25–35 execution path. API 36 remains an explicitly isolated Android TV x86 configuration. There will be no separate precompile invocation.

The smoke runner will register failure handling before its first Gradle command. On failure it will capture the active suite, device properties, instrumentation/package state, the Android crash log buffer, recent logcat, and DropBox app-crash records. The workflow will always upload these diagnostics together with app/data connected-test outputs and reports, with a unique artifact name per API.

## Safety and Release Behavior

- Manual execution of the reusable smoke workflow never publishes an APK or release.
- Release publication still requires both the host build and the reusable platform matrix to pass.
- Matrix jobs remain independent with `fail-fast: false`.
- Diagnostics commands are best-effort and cannot replace the original test exit status.
- Existing API coverage and API 35/36 foreground-service suites remain unchanged.

## Verification

Shell tests will use fake `adb` and `gradlew` executables to prove that failure diagnostics run after the first suite fails, preserve the failing status, and record the suite name. Static workflow tests will prove that release delegates to the reusable workflow, the reusable workflow can run manually, connected-test artifacts are uploaded, and the removed precompile stage cannot return unnoticed.
