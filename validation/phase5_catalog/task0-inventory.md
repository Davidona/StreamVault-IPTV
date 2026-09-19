# Phase 5 Catalog Task 0 Inventory

Date: 2026-09-02
Baseline commit: `d303e2aaf090843ff51081687c7d4fa4d7af6a7c`
Branch: `feature/improveCompose`
Working tree: clean before measurement

## Ownership baseline

The Catalog surface contains 21 production Kotlin files under:

- `ui/screens/dashboard` (3 files)
- `ui/screens/movies` (4 files)
- `ui/screens/series` (4 files)
- `ui/screens/vod` (7 files)
- `ui/screens/favorites` (2 files)
- `ui/screens/search` (1 file)

The exact list is in `source-inventory.txt`. The import, resource, test, and
consumer scans are in `project-imports.txt`, `resource-inventory.txt`,
`test-inventory.txt`, and `consumer-inventory.txt`.

Observed coupling matches the design spec: app shell/navigation/resources and
device/time helpers; Dashboard Settings and app-update implementations;
Movie/Series detail Playback Cast, plugin, and download implementations; and
temporary `PreferencesRepository`/`ProviderSyncStateSource` data imports.
Favorites remains unregistered. The mixed app golden suite contains the four
Catalog cases: dashboard default, movies landing, series detail, and search
results.

## Baseline toolchain

- Java: 21.0.5 (Oracle), build `21.0.5+9-LTS-239`
- Gradle: 8.12
- Kotlin: 2.0.21
- OS: Windows 11 amd64
- Gradle mode: `--no-daemon`; configuration cache was stored by the run

## Focused baseline command

```powershell
./gradlew.bat :app:testDebugUnitTest --tests 'com.streamvault.app.ui.screens.search.SearchViewModelTest' --tests 'com.streamvault.app.ui.screens.movies.MovieDetailViewModelCastingTest' --tests 'com.streamvault.app.ui.screens.series.SeriesDetailViewModelCastingTest' --tests 'com.streamvault.app.ui.screens.dashboard.DashboardHomeShelvesTest' --tests 'com.streamvault.app.navigation.CatalogRouteResolverTest' :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none
```

Result: `BUILD SUCCESSFUL` in 3m 32s; 153 actionable tasks (2 executed,
151 up-to-date). The only JVM diagnostic was the standard class-data-sharing
warning from the test JVM. No test or compile failure was observed.

## Measurement status

The five source-edit and five test-compile samples are recorded separately in
`performance-before.md`. The temporary edits are restored and verified with
`git diff --check` before Task 1 begins.
