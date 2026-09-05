# Compose Reduction Phase 5 — System feature extraction report

Date: 2026-09-05

## Outcome

Welcome, Downloads, and Plugins presentation is now owned by `:feature:system`.
The feature owns its controller-free graph registration, routes, ViewModels,
rendering, neutral plugin contracts/models, resources, unit tests, connected
tests, and reviewed goldens. `:app` remains the Android composition root and
retains startup orchestration, shell composition, platform adapters, plugin
discovery/IPC/work/provider/playback infrastructure, and Android entry points.

The extraction is structurally complete and the incremental feature-value
thresholds pass. The clean/warm build-overhead guardrail also passes in
steady-state. The supported seeded baseline/startup profile workflow now
completes and the generated sources have been promoted through Gradle. The
module remains provisional only for the explicitly unavailable physical-device,
accessibility-service, and authorized production-journey gates.

## Rollback and commit sequence

Rollback SHA: `cddef1526924634819c7fb27d15c8a49b3945a1d`.

The implementation was split into these focused commits:

```text
e9e89e65 docs(system): record phase 5 extraction baseline
b189b977 test(system): lock existing presentation behavior
c9f7b3cd build(system): add isolated feature module
fac29462 feat(system): define feature contracts and routes
6ce6517a refactor(system): bridge app startup and plugin services
541dd966 refactor(system): move welcome presentation
0c7d911d refactor(system): move downloads presentation
3ab9239b refactor(system): move plugins presentation
dcd74f59 refactor(system): register feature-owned navigation
35ff4f03 test(system): stabilize presentation semantics
57182dee test(system): verify ownership and resources
```

Connected acceptance and final documentation are intentionally separate
follow-up commits after the final evidence review.

## Ownership and size

The Task 0 baseline contained eight app production files and 2,120 physical
lines: the three screen/state/ViewModel groups plus the two old graph files.
The authoritative pre-extraction inventory is
`validation/phase5_system/task0-inventory.md`.

Current feature counts are:

| Area | Files | Physical lines / assets |
|---|---:|---:|
| `src/main/java` Kotlin | 13 | 2,459 |
| `src/test/java` Kotlin | 9 | 700 |
| boundary fixtures | 12 | 45 |
| `src/androidTest/java` Kotlin | 7 | 970 |
| `src/main/res` | 51 | 33 default System keys, 26 qualifiers |
| reviewed connected goldens | 6 | 1920×1080 PNG each |

The old app screen directories and app `WelcomeGraph.kt`/`SystemGraph.kt` are
absent. The feature has one `SystemGraph` registration and the app root
registers it once.

## Boundary and contracts

`:feature:system` has exactly these project dependencies:

```text
:core:navigation, :core:ui, :domain
```

The boundary verifier scans production Kotlin/Java and negative fixtures. The
production result is zero forbidden references for app/data/player/root
controllers/all sibling features; the fixtures detect app, data, player,
`MainActivity`, `NavController`, `NavHostController`, and each sibling feature
token as required. The report is in
`feature/system/build/reports/feature-system-boundary/report.txt`.

`SystemWelcomePort` exposes `Flow<WelcomeSyncProgress?>` and a
`WelcomeDevProviderConfig`. `AppSystemWelcomeAdapter` maps the app's
`SyncProgressBus` and `BuildConfig` values without moving either dependency.

`SystemPluginManagementPort` exposes discovery, provider-source lookup, APK
install, enable/disable, configuration load/save/refresh, and configuration
actions. `AppSystemPluginManagementAdapter` delegates directly to the existing
`StreamVaultPluginManager` and `ProviderSourceRegistry`.

The feature contains no plugin manager, messenger, work coordinator, provider
ownership, or playback-routing implementation. Those remain under
`app/src/main/java/com/streamvault/app/plugins` and related app infrastructure.

## Navigation and behavior preservation

The feature owns the exact `welcome`, `downloads`, and `plugins` route
patterns. `SystemGraph` is controller-free and receives navigation actions,
startup redirect callbacks, and a scaffold callback from `AppNavHost`. The app
retains `NavHostController`, `NavHost`, `AppRouteCodec`, startup policy, and
top-level shell adaptation.

Characterization tests were added before the moves. Feature ViewModel and
presentation tests preserve loading/error/result handling, download actions,
plugin configuration JSON/draft behavior, Welcome progress and setup/later
actions, semantics, focus/test tags, and callback delegation. The connected
graph test proves one registration for all three routes. Full authorized
production-provider/plugin journeys were unavailable and are not claimed as
passed; details are in `validation/phase5_system/task10-device-journeys.md`.

## Resources

The feature owns the exact 33 System string keys from the Task 0 inventory.
The default and all 25 localized qualifiers were compared (26 total):

```text
duplicate_system_entries=0
missing_system_values=0
value_mismatches=0
placeholder_mismatches=0
```

Thirty feature-only app definitions were removed. `app_name`, `nav_downloads`,
and `settings_cancel` remain app-owned because the manifest, TV input service,
app shell, Settings, Live, Catalog, and Playback still consume them. The full
audit is in `validation/phase5_system/task9-resource-cleanup.md`.

## Automated validation

Passing gates:

- `:feature:system:verifyFeatureSystemBoundary`
- `:feature:system:dependencies`
- `:feature:system:testDebugUnitTest`
- `:feature:system:lintDebug`
- `:feature:system:compileDebugAndroidTestKotlin`
- `:feature:system:assembleDebug`
- `:core:navigation:test`
- `:core:ui:testDebugUnitTest`
- `:domain:test`
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- `:app:assembleBeta`
- `:app:assembleRelease`
- root `:verifyBaselineProfileSources` (source shape valid)

The final focused matrix completed successfully in 1m29s with 324 actionable
tasks, including the System boundary, feature lint, connected-test compile,
feature assembly, app tests, and app debug assembly.

`:app:lintDebug` remains red from existing app-wide debt (675 errors); the
first unchanged error is `AppStartupCoordinator.kt:192`, `NewApi` on
`Trace.beginAsyncSection`. No app lint baseline or startup code was changed by
this extraction.

## Connected acceptance and visual review

On the only available device, `Television_1080p(AVD) - 16`, API 36,
1920×1080:

- six deterministic goldens were recorded, visually inspected, and compared
  pixel-for-pixel;
- the final System connected suite passed 19/19;
- the suite covers nine presentation tests, one graph test, six golden cases,
  and three RTL/1.3 font-scale cases;
- the reduced-motion variant passed with global animation scales set to zero
  and the original scales restored afterward;
- the retained log evidence reports no app fatal/runtime/resource/launcher
  match and no sensitive-pattern match.

The first 19-test attempt had two transient PixelCopy/window-capture failures;
the exact same cases passed in isolation. `GoldenCapture.kt` now retries
capture three times with a 250ms delay, while retaining exact bitmap
dimensions and pixel comparisons. The rerun passed 19/19.

No phone/tablet profile or TalkBack service was available. Semantics actions
and connected RTL/large-text/reduced-motion assertions pass, but touch/mouse
and service-mediated accessibility traversal remain unavailable gates.

The Live-TV long-duration protocol is waived for this slice because no player
composition, stream preparation, recovery, lifecycle, surfaces, overlays,
Cast URL rewriting, or plugin playback routing changed.

Evidence: `validation/phase5_system/task10-connected-validation.md`,
`task10-device-journeys.md`, `task10-screenshots/`, and `task10-logcat.txt`.

## Performance and isolation

The paired warm incremental medians pass the optional-module thresholds:

| Measurement | Before | After | Improvement | Threshold |
|---|---:|---:|---:|---:|
| source edit | 20.6311s | 7.3199s | 64.56% faster | ≥25% |
| unit-test compile | 18.1523s | 7.6457s | 57.88% faster | ≥20% |

All five raw samples per series, cache state, hashes, and build guardrails are
in `validation/phase5_system/performance-after.md`.

With a reversible System-only edit, the app dry run showed sibling Kotlin
compile tasks as graph entries marked `SKIPPED`; the actual `:app:assembleDebug`
run showed sibling Kotlin tasks `UP-TO-DATE` and did not execute sibling Kotlin
compilation. The System and required app integration/package tasks were the
only relevant work. Evidence is in
`validation/phase5_system/system-edit-dry-run.txt` and
`system-edit-task-output.txt`.

Clean debug, warm debug, Beta, and Release guardrails passed. The paired
rollback/current clean run is faster in the current checkout, and steady-state
warm medians are 11.707s rollback versus 11.955s current, a 2.12% slowdown;
the ≤10% build-overhead gate passes. Full raw samples and cache/task details
are in `validation/phase5_system/performance-after.md`.

## Profiles, graph, and remaining work

The supported profile command was corrected from the stale plan path
`:benchmark:pixel2Api36Setup` to the current app-level
`:app:generateBaselineProfile`. The release benchmark harness now waits for
UiAutomator idle after launching the target. The startup journey passed 1/1,
and the seeded critical journey subsequently completed successfully. The
supported `:app:copyBaselineProfileIntoSrc` workflow then passed its connected
run with zero failures and refreshed both generated sources. Each source has
nonzero `feature/system` descriptors and no old app Welcome/Downloads/Plugins,
`WelcomeGraph`, or `SystemGraph` descriptors. See
`validation/phase5_system/profile-validation.md` for the command and scan
evidence.

`graphify update .` completed with 16,550 nodes and 32,460 edges. The refreshed
graph resolves feature `SystemGraph` ownership and an `AppNavHost` to
`PluginsScreen` path through `AppNavigation`.

Open follow-ups are physical-device/touch validation, TalkBack service
traversal, and authorized production-provider/plugin journeys. This report does
not close
Playback, Provider, Settings, Live, Catalog, accessibility, or repository-wide
performance gates, and does not claim full Phase 5 modernization completion.
