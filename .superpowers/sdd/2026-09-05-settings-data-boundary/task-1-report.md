# Task 1 Report: Enforce the final settings boundary

## Status

RED demonstrated. The final settings boundary is specified by tests and the existing Gradle guard, but the current production dependency intentionally remains, so the boundary task fails as required before production migration.

## Changes

- Removed `:data` from the settings boundary's approved project-dependency set.
- Added a fixture-only `com.streamvault.data` token so the boundary scan requires detection of data imports without rejecting current production data imports.
- Added `feature/settings/src/test/resources/boundary-fixtures/DataImport.kt` using `import com.streamvault.data.sync.SyncProgressBus`.
- Extended `SettingsModuleBoundaryTest` to require that the build file omit `project(":data")`.
- Preserved the production `implementation(project(":data"))` dependency and existing production source token list, as required for this RED task.

## Verification

Command:

```text
gradlew.bat :feature:settings:verifyFeatureSettingsBoundary --no-daemon
```

Result: expected failure, exit code 1.

Relevant failure:

```text
:feature:settings project dependencies must be exactly [:core:navigation, :core:ui, :domain, :player]; found
[:core:navigation, :core:ui, :data, :domain, :player]
```

This confirms the RED boundary is failing specifically because the existing `:data` project dependency is still present. The generated boundary report was not produced because the task stops at the dependency assertion.

Gradle also reported 28 configuration-cache problems, including two unique problems for this existing execution-time boundary task. These are ancillary warnings and are not the cause of the intended failure.

## Graph maintenance

Ran `graphify update .` successfully after modifying code files. Graphify rebuilt the code graph with 13,862 nodes, 24,410 edges, and 473 communities.

## Commit scope

The commit contains only the Task 1 boundary build/test/fixture changes and this report. The captured test log remains uncommitted.
