# Task 3 Report — Settings Operations Contract

## Scope completed

Introduced the `SettingsOperations` domain boundary and the three settings-facing snapshots. Added the data adapter and Hilt binding, then migrated settings operational reads and commands from Room DAOs and `ProviderSyncCommands` to the domain contract. `SettingsPreferences` was left as its existing binding.

The feature still retains its `:data` dependency and the Task 1 production source guard was not activated, as required for this phase.

## Red / green evidence

All commands ran from `tmp/phase7-provider-boundary`. `ANDROID_HOME` and `ANDROID_SDK_ROOT` were set to `E:\androidSdk` for Android Gradle commands only; no `local.properties` or build configuration was changed.

### Red

1. Added the domain contract test, feature snapshot-to-UI test, and data adapter tests before creating any Task 3 production source.
2. Initial combined Android command:

   ```powershell
   .\gradlew.bat :domain:test --tests com.streamvault.domain.settings.SettingsOperationsContractTest :feature:settings:testDebugUnitTest --tests com.streamvault.feature.settings.presentation.SettingsOperationalModelsTest :data:testDebugUnitTest --tests com.streamvault.data.settings.SettingsOperationsImplTest -x :feature:settings:verifyFeatureSettingsBoundary
   ```

   failed during Android configuration because the worktree had no SDK path, before test compilation.
3. Domain red command:

   ```powershell
   .\gradlew.bat :domain:test --tests com.streamvault.domain.settings.SettingsOperationsContractTest
   ```

   failed as expected at `:domain:compileTestKotlin` with unresolved `SettingsXtreamIndexJob`, `SettingsXtreamLiveOnboarding`, and `SettingsSyncSection` references (10 compiler diagnostics), proving the new domain contract was absent.

### Green

```powershell
.\gradlew.bat :domain:test --tests com.streamvault.domain.settings.SettingsOperationsContractTest
```

Passed: 1 test, 0 failures/errors.

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests com.streamvault.data.settings.SettingsOperationsImplTest
```

Passed: 7 tests, 0 failures/errors.

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest --tests com.streamvault.feature.settings.presentation.SettingsOperationalModelsTest --tests com.streamvault.feature.settings.presentation.SettingsDerivedStateObserversTest --tests com.streamvault.feature.settings.presentation.SettingsProviderActionsTest -x :feature:settings:verifyFeatureSettingsBoundary
```

Passed: 7 tests across 3 classes, 0 failures/errors.

```powershell
.\gradlew.bat :app:kspDebugKotlin -x :feature:settings:verifyFeatureSettingsBoundary
```

Passed: app Hilt/KSP compilation (155 actionable tasks; 17 executed, 138 up-to-date).

During green work, the first data test run exposed a test-double mismatch: Mockito `any()` does not match the adapter's nullable default `movieFastSyncOverride = null`. The test was corrected to match the actual `null`/`MANUAL_SETTINGS` defaults, then passed. No production behavior changed for that issue.

## Field mapping audit

| Domain field | Data source |
| --- | --- |
| `SettingsXtreamIndexJob.providerId` | `XtreamIndexJobEntity.providerId` |
| `section` | `section` |
| `state` | `state` |
| `indexedRows` | `indexedRows` |
| `lastError` | `lastError` |
| `SettingsXtreamLiveOnboarding.providerId` | `XtreamLiveOnboardingStateEntity.providerId` |
| `phase` | `phase` |
| `importStrategy` | `importStrategy` |
| `acceptedRowCount` | `acceptedRowCount` |
| `stagedFlushCount` | `stagedFlushCount` |
| `syncProfileTier` | `syncProfileTier` |
| `syncProfileBatchSize` | `syncProfileBatchSize` |
| `syncProfileStrategy` | `syncProfileStrategy` |
| `syncProfileLowMemory` | `syncProfileLowMemory` |
| `syncProfileMemoryClassMb` | `syncProfileMemoryClassMb` |
| `syncProfileAvailableMemMb` | `syncProfileAvailableMemMb` |
| `lastError` | `lastError` |
| `updatedAt` | `updatedAt` |

`SettingsSyncSection` is mapped explicitly in the adapter: `LIVE → LIVE`, `MOVIES → MOVIES`, `SERIES → SERIES`, and `EPG → EPG`. The retry adapter leaves existing `ProviderSyncCommands` defaults intact, including `MANUAL_SETTINGS`, and forwards the progress callback unchanged.

## Self-review

- `SettingsOperations` defines only the seven requested APIs and requested snapshots/section enum; it has no Room or data dependency.
- `SettingsOperationsImpl` is the only new operational data boundary and maps all requested snapshot fields explicitly.
- `SettingsDataModule` binds only `SettingsOperations`; it does not duplicate the existing `SettingsPreferences` binding.
- Settings operational consumers use `SettingsOperations`; a source audit found no DAO/entity/sync-command data references in `feature/settings/src/main`.
- Sync selection order, callbacks, UI strings, result handling, and defaults were retained. The existing domain `SyncProvider` use case remains for its richer settings flow; the former `ProviderSyncCommands` calls use the new operations adapter.
- `git diff --check` passed. `graphify update .` completed after the code changes.

## Concerns

- The first combined red command was blocked by the missing SDK path, so only the domain contract's missing-symbol red compilation was directly observed. The feature and data tests were nevertheless authored before their production types, then executed green once the existing SDK was supplied.
- The intentional Task 1 settings boundary failure was excluded from Android test and app KSP commands. It remains for Task 4; this task did not remove the feature `:data` dependency or activate that production source guard.
