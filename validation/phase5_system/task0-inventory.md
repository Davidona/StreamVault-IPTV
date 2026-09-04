# Phase 5 System extraction — Task 0 inventory

Captured: 2026-09-04

## Rollback point and working tree

- Branch: `feature/improveCompose`
- Rollback SHA: `cddef1526924634819c7fb27d15c8a49b3945a1d`
- Pre-existing dirty paths, preserved: `docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`,
  `docs/COMPOSE_REDUCTION_PHASE5_ROADMAP.md`, and the untracked approved plan
  `docs/superpowers/plans/2026-09-04-system-feature-extraction.md`.
- No production or test code was modified for Task 0.

## Source baseline

| File | Physical lines |
|---|---:|
| `WelcomeScreen.kt` | 310 |
| `DownloadsUiState.kt` | 13 |
| `DownloadsViewModel.kt` | 146 |
| `DownloadsScreen.kt` | 439 |
| `PluginsViewModel.kt` | 342 |
| `PluginsScreen.kt` | 811 |
| `WelcomeGraph.kt` | 29 |
| `SystemGraph.kt` | 30 |
| **Total** | **2,120** |

The planning estimate of 2,248 lines was stale; these fresh counts are the
authoritative pre-extraction baseline.

## Boundary findings

- Welcome directly imports app `BuildConfig`/`R`, `SyncProgressBus`, and provider
  setup/repository types.
- Downloads directly imports app `R` and `AppScreenScaffold`; its domain
  dependency is the existing `DownloadManager`.
- Plugins directly imports app shell/routes, plugin presentation models, and
  `StreamVaultPluginManager`; runtime discovery, IPC, provider ownership, and
  playback routing remain app-owned.
- The System module has no approved direct `:data` dependency. Sync progress
  and development seeding cross `SystemWelcomePort`; plugin operations cross
  `SystemPluginManagementPort`.
- Exactly 33 app string references are used by the eight production files.

## Focused pre-extraction build

Command:

```text
./gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.plugins.StreamVaultPluginOwnerTest --tests com.streamvault.app.plugins.PluginPlaybackRoutingTest :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Result: `BUILD SUCCESSFUL in 3m 30s`; 170 actionable tasks, 10 executed,
1 from cache, 159 up-to-date. Gradle 8.12, JVM 21.0.5 (Oracle), Windows 11
amd64. Configuration cache was absent at graph calculation and stored after
the build. Existing compiler warnings were emitted (duplicate `-module-name`,
deprecated memory-trim overrides/constants, and the Kotlin annotation target
warning); none were introduced by this slice.

The five source-edit and five unit-test-compile samples are recorded separately
in `performance-before.md` once the reversible-edit runs complete.
