# Task 4 report: Settings provider/data boundary

## Outcome

Removed the direct `:feature:settings -> :data` Gradle dependency. Settings now
depends on `:domain`, `:core:ui`, `:core:navigation`, and `:player`. The boundary
guard now rejects `com.streamvault.data` in main Kotlin and Java sources, including
both import and fully-qualified forms. The existing Kotlin/Java fixtures remain in
place and are verified by the same broad source-token list.

No preference keys, defaults, persisted encodings, sync behavior, settings UI, or
player dependency were changed.

## Commands and results

`adb` resolved to `E:\androidSdk\platform-tools\adb.exe`; every Gradle command
below derived and set `ANDROID_HOME=E:\androidSdk` from that executable:

```powershell
$adbPath = (Get-Command adb -ErrorAction Stop).Source
$env:ANDROID_HOME = Split-Path (Split-Path $adbPath -Parent) -Parent
```

1. Baseline red check, before the dependency removal:

```powershell
.\gradlew.bat :feature:settings:verifyFeatureSettingsBoundary --console=plain --no-daemon
```

Result: exit 1, as intentionally left by Task 1. The guard found
`[:core:navigation, :core:ui, :data, :domain, :player]` where `:data` was not
allowed.

2. Focused boundary guard after the change:

```powershell
.\gradlew.bat :feature:settings:verifyFeatureSettingsBoundary --console=plain --no-daemon
```

Result: exit 0 in 21s. The task reported approved dependencies, no forbidden main
source references, and Kotlin/Java fixture coverage.

3. A first unfiltered settings-suite run exposed stale partial ASM test output:

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest --console=plain --no-daemon
```

Result: 28 tests, 4 `NoClassDefFoundError` failures in
`SettingsDriveBackupActionsTest`. Investigation found all five coroutine lambda
classes in Kotlin output but only the first lambda in the ASM-transformed test
output. No source change was made for this unrelated stale-output issue. The
transform was regenerated with:

```powershell
.\gradlew.bat :feature:settings:transformDebugUnitTestClassesWithAsm --rerun-tasks --console=plain --no-daemon
```

Result: exit 0 in 1m 10s; all five lambda classes were present in transformed
output.

4. Required unfiltered settings suite after regeneration:

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest --console=plain --no-daemon
```

Result: exit 0 in 26s; 39 tests, 0 failures, 0 errors.

5. Required full verification matrix:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :feature:settings:testDebugUnitTest :app:assembleDebug --console=plain --no-daemon
```

Result: exit 0 in 3m 41s; 264 actionable tasks (16 executed, 248 up-to-date).
JUnit XML totals were:

| Module/task | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `:domain:test` | 146 | 0 | 0 | 0 |
| `:data:testDebugUnitTest` | 1082 | 0 | 0 | 0 |
| `:feature:settings:testDebugUnitTest` | 39 | 0 | 0 | 0 |

No documented pre-existing data-suite failure occurred, so no focused data-test
fallback was needed.

6. Dependency and production-source scans:

```powershell
rg -n 'com\.streamvault\.data' feature/settings/src/main
Get-Content feature/settings/build/reports/feature-settings-boundary/report.txt
```

Result: the `rg` command returned exit 1 with 0 matches, which is the expected
no-match result. The generated boundary report recorded:

```text
projectDependencies=:core:navigation,:core:ui,:domain,:player
mainSourceViolations=
```

It also detected the retained fixtures for both required data forms:
`DataImport.kt:3: import com.streamvault.data` and
`FullyQualifiedDataReference.kt:3: com.streamvault.data` (alongside the other
existing boundary fixtures).

7. Diff formatting check:

```powershell
git diff --check
```

Result: exit 0.

8. Required graph refresh:

```powershell
graphify update .
```

Result: exit 0. Graphify re-extracted 1,334 code files and rebuilt
`graphify-out/graph.json` and `graphify-out/GRAPH_REPORT.md` with 14,009 nodes,
24,567 edges, and 479 communities. It skipped `graph.html` because the graph
exceeds the 5,000-node visualization limit.

## Architecture state

`docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md` now marks only Phase 7
checkpoint 2 (settings boundary) complete. It explicitly leaves the player
capability API and final dependency/package cleanup outstanding.

## Warnings

- The pre-existing settings boundary Gradle task emitted 24 configuration-cache
  problems (2 unique): it captures Gradle script objects and accesses
  `Task.project` during execution. This task passed; its configuration-cache
  compatibility is deferred cleanup and was not changed here.
- Gradle emitted the existing JVM dynamic-agent warning while running unit tests.
- Graphify skipped HTML visualization because of the graph-size limit; graph JSON
  and report refresh completed.
