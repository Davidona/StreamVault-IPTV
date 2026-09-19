# Settings extraction — Task 6 evidence

Date: 2026-08-27

## Scope

Task 6 moved the pure settings presentation state/action/model layer and its
focused unit tests into `:feature:settings`. App-owned platform adapters and
the remaining Settings UI/ViewModel files intentionally remain in `:app` for
the next tasks.

## Verification

Commands were run from the repository root with the existing Gradle daemon and
cache:

```text
gradlew.bat :feature:settings:check --console=plain --warning-mode=none
EXIT=0
BUILD SUCCESSFUL in 31s

gradlew.bat :feature:settings:verifyFeatureSettingsBoundary \
  :feature:settings:testDebugUnitTest \
  :app:compileDebugKotlin \
  :app:compileDebugUnitTestKotlin \
  --console=plain --warning-mode=none
EXIT=0
BUILD SUCCESSFUL in 5s
```

The full feature check includes debug/release unit tests, lint, Kover, and the
boundary task. The focused command verifies feature boundary fixtures and app
source/test compilation against the transitional contracts.

## Evidence interpretation

These are structural/compile/unit-test gates only. Runtime playback/provider
acceptance, settings connected tests, physical-TV focus journeys, account and
document chooser flows, and paired incremental-performance measurements remain
open and are not represented as passing here.

