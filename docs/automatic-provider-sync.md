# Automatic provider sync

Settings > Sources > Providers contains global automatic-sync defaults and per-provider overrides. Expand Configure automatic sync to change sections, catalog/guide intervals, connection and charging requirements, Android device idle, or a local preferred time window. Turn Customize this provider off to inherit the defaults. The global enable switch applies to every override.

Default behavior:

- Automatic sync enabled for all saved providers, including inactive providers.
- Live, Movies/VOD and Series catalogs due every 24 hours; guides due every six hours.
- Connected network and battery not low; charging, unmetered networking and device idle are optional.
- Automatic work waits while local playback (including paused/buffering players) or recording is active. An idle transition requests a catch-up check.
- No preferred time window by default. Windows can cross midnight and use device-local time.
- A durable hourly check plans due work; process startup and provider selection also request checks. Android decides actual execution times.

The shared ProviderRefreshPlanner selects supported sections using successful refresh timestamps. Attempt timestamps do not make failed work fresh. Explicit Settings refreshes share capability selection and the current catalog executors while ignoring automatic enablement and freshness. Interrupted provider setup/configuration recovery remains independent of automatic refresh settings.

Provider behavior:

- Xtream: current Live strategy plus resumable Movies/Series index jobs.
- Stalker: current Live executor and its own index-job store. On-demand VOD refreshes category shells; background-index mode queues resumable item indexing. Unified VOD does not request split Series.
- M3U: one playlist download applies the selected Live/Movie sections. Complete snapshots add, update and prune entries using existing import safety rules.
- Jellyfin: Movies and Series refresh only when due, including for inactive saved providers; there are no Live/guide requests.

Automatic index continuations retain their policy/provenance, recheck restrictions and section selections, and preserve pending progress. Explicit manual index work can replace pending automatic work with restrictive constraints. Permanent index failures require explicit retry. Scheduled refreshes do not change the selected provider or deactivate it after a refresh failure.

Settings displays per-section last successful timestamps, the next due time, and the last automatic-check result, distinguishing fresh data, queued indexing, running work, deferral, warnings and failure. Automatic policy defaults and overrides are included in backup/restore through portable provider identities; runtime status is not backed up.

## Validation

Full domain, data and Settings unit suites: **1,601 tests passed** (180 domain, 1,346 data, 75 Settings). A final targeted rerun covers the final worker changes and builds both debug APKs.

An opt-in real WorkManager probe creates and removes only a synthetic M3U provider. It verifies inactive-provider refresh, update/add/prune behavior, preservation of an existing row ID, freshness skips, automatic disablement and explicit manual refresh. Run after installing the debug application and its instrumentation APK:

```powershell
adb -s emulator-5558 shell am instrument -w -r -e class com.streamvault.app.benchmark.AutomaticProviderSyncRuntimeTest -e automaticSyncProbe true com.streamvault.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Final validation:

- Final targeted rerun: **498 tests passed** (180 domain, 243 affected data/sync tests, 75 Settings).
- Debug application and instrumentation APKs assembled successfully.
- `AutomaticProviderSyncRuntimeTest` passed on **emulator-5558** against the final APK (one real-worker integration test, 1.147 seconds). Its assertions cover inactive refresh, two-entry reconciliation, row-ID preservation, freshness, automatic disablement and manual refresh.
- Final APK Settings UI checked with DPAD navigation: automatic sync enabled, catalog default 24 hours, guide default six hours, expandable controls and a scrolling interval picker.
- No AndroidRuntime error was reported for the final running app process during the UI check.

Local evidence is retained in `tmp/automatic-sync/`: build logs, final instrumentation result, UI hierarchy captures and the interval-picker screenshot. Build artifacts are `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

This validation exercises scheduled catalog synchronization. It does not claim live playback, real-provider availability, or exact wall-clock background execution timing.
