# Settings extraction — Task 7 evidence

Date: 2026-08-27

## Scope

`SettingsViewModel`, its settings action/observer helpers, and the focused
action/observer tests now live in `:feature:settings`. App-only implementations
are reached through `SettingsDiagnosticsPort`, `SettingsSurfaceRefreshPort`,
and `SettingsAppUpdatePort`; domain repositories, managers, and the explicitly
ledgered `:data` types remain unchanged.

## Verification

The following checks completed successfully with exit code 0:

```text
gradlew.bat :feature:settings:compileDebugKotlin --console=plain --warning-mode=none
gradlew.bat :feature:settings:compileDebugUnitTestKotlin --console=plain --warning-mode=none
gradlew.bat :feature:settings:testDebugUnitTest --console=plain --warning-mode=none
gradlew.bat :feature:settings:verifyFeatureSettingsBoundary --console=plain --warning-mode=none
gradlew.bat :app:compileDebugKotlin --console=plain --warning-mode=none
gradlew.bat :app:compileDebugUnitTestKotlin --console=plain --warning-mode=none
gradlew.bat :feature:settings:check --console=plain --warning-mode=none
```

The feature boundary scan reports approved project dependencies, zero forbidden
source references, and detected Kotlin/Java fixtures. Feature tests include the
ViewModel source-boundary test and the moved action/observer tests.

## Evidence interpretation

The checks establish compile, unit-test, lint, Kover, and structural boundary
evidence. They do not close connected-TV, manual focus, backup chooser/Drive,
update-install, or paired performance gates. Remaining Settings UI/resources,
graph ownership, and runtime acceptance are still open.

