# Phase 5 System — Task 9 structural verification

Date: 2026-09-04

## Ownership and legacy-path checks

The exact old paths are absent:

```text
app/src/main/java/com/streamvault/app/ui/screens/welcome = False
app/src/main/java/com/streamvault/app/ui/screens/downloads = False
app/src/main/java/com/streamvault/app/ui/screens/plugins = False
app/src/main/java/com/streamvault/app/navigation/graph/WelcomeGraph.kt = False
app/src/main/java/com/streamvault/app/navigation/graph/SystemGraph.kt = False
```

The source scan found no legacy app screen packages and no
`registerWelcomeGraph` definition. `:app:verifyFeatureNavigationBoundary`
passes with only `LiveGraph.kt` remaining under the app-local graph directory.
`SystemGraph.kt` is feature-owned and registered once by `AppNavHost`.

## Plugin runtime ownership

The app retains these runtime implementations:

```text
app/src/main/java/com/streamvault/app/plugins/PluginMessengerClient.kt
app/src/main/java/com/streamvault/app/plugins/PluginPlaybackRouting.kt
app/src/main/java/com/streamvault/app/plugins/PluginWorkCoordinator.kt
app/src/main/java/com/streamvault/app/plugins/StreamVaultPluginManager.kt
```

The feature source contains no `StreamVaultPluginManager`,
`PluginMessengerClient`, `PluginWorkCoordinator`, or `playbackCandidates`
definition. Plugin IPC, provider ownership, work coordination, and playback
routing remain app-owned; the feature owns only presentation and neutral API
models/contracts.

## Feature boundary

`feature/system/build/reports/feature-system-boundary/report.txt` records:

```text
projectDependencies=:core:navigation,:core:ui,:domain
mainSourceViolations=
```

The reported Kotlin/Java fixture violations are intentional negative fixtures
used by the boundary verifier. The production source scan also found no app,
data, player, or sibling-feature imports, `MainActivity`, `NavController`, or
`NavHostController` references.

## Resource ownership

The Task 0 inventory contains 33 System string references. The feature now
contains exactly those 33 keys in the default catalog and the same 26 locale
qualifiers as the app. The app/feature comparison used the pre-cleanup app
catalog so removed app definitions could still be checked.

```text
qualifiers_checked=26
duplicate_system_entries=0
missing_system_values=0
value_mismatches=0
placeholder_mismatches=0
feature_default_system_keys=33
```

The app catalog removed 30 feature-only keys from 51 locale resource files
(780 definitions). Three keys remain in app resources because they still have
non-System consumers:

| Key | Retained consumers |
|---|---|
| `app_name` | app manifest and `tv_input_service.xml` |
| `nav_downloads` | app shell and Settings navigation/state mapping |
| `settings_cancel` | app TV-input setup and Settings/Live/Catalog/Playback dialogs |

The remaining 30 keys have no app or sibling production consumer. The
`feature/provider` `app_name` boundary fixture is test-only and is not counted
as a production consumer.

## Gate results

Passed:

- `:feature:system:verifyFeatureSystemBoundary`
- `:feature:system:dependencies`
- `:feature:system:testDebugUnitTest`
- `:feature:system:lintDebug`
- `:feature:system:assembleDebug`
- `:app:verifyFeatureNavigationBoundary`
- `:app:compileDebugKotlin`
- `:core:navigation:test`
- `:core:ui:testDebugUnitTest`
- `:domain:test`
- `:app:testDebugUnitTest`
- `:app:assembleDebug`

The required `:app:lintDebug` check was run against the final resource state
but remains red from existing app-wide lint debt: 675 errors remain, with the
first error at unchanged
`app/src/main/java/com/streamvault/app/AppStartupCoordinator.kt:192`
(`Trace.beginAsyncSection`, `NewApi`). This slice does not update the app lint
baseline or modify that startup code.
