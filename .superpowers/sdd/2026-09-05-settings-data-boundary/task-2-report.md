# Task 2 report: Settings preferences contract

## Status and scope

Implemented Task 2 in `C:\Users\david\.gemini\antigravity\scratch\iptv-player\tmp\phase7-provider-boundary` on 2026-09-05. Read the exact Task 2 brief before implementation. No subagents were dispatched. The initial worktree was clean.

The requested compile/test gate is green. The final supplemental gate is also green, including all 37 settings tests outside the deliberately red Task 1 module-boundary test, compilation of data tests, and the app's Hilt component compilation.

Task 2 is a type extraction: settings consumers now use the domain contract, and the original repository remains the persistence implementation. Tasks 3–4 still own operational dependency migration and removal of the settings `:data` dependency.

## Implementation

- Added `SettingsPreferences` with exactly 176 members referenced by `feature/settings/src/main`: 86 Flow properties, 88 suspend functions, and two ordinary functions returning Flow (`getHiddenCategoryIds` and `getCategorySortMode`).
- Preserved existing property types, function parameter names, nullability, generic arguments, suspend modifiers, and return types. Existing Boolean/Long-returning commands retain their return types. The contract has no DataStore/Room types or untyped command map.
- Moved `DatabaseMaintenanceSnapshot` to `com.streamvault.domain.settings`, preserving all 17 fields, their order and types, and the absence of constructor defaults. Updated every affected Kotlin source/test import across the repository.
- Made `PreferencesRepository` implement `SettingsPreferences`, adding 175 new `override` modifiers; `verifyParentalPin` already had one through `ParentalPinVerifier`. Existing parental interfaces remain implemented.
- Migrated all eight settings production files consuming `PreferencesRepository`, including fully qualified helper parameter types, and all three existing settings test consumers. Existing test assertions were preserved.
- Added a fake-backed contract test that passes an interface-typed preference value into the real `SettingsAppUpdateActions` constructor and `observeCategoryManagement` helper. The tests also verify failed-update state/persistence and hidden-category derivation.
- Added the interface's Hilt binding in the existing app `RepositoryModule`. This small supporting change is required because settings view models now inject the interface; it resolves to the same singleton repository. It does not introduce the Task 3 operations module or change persistence.

## Files

- `domain/src/main/java/com/streamvault/domain/settings/SettingsPreferences.kt`
- `domain/src/main/java/com/streamvault/domain/settings/DatabaseMaintenanceSnapshot.kt`
- `data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt`
- `app/src/main/java/com/streamvault/app/di/RepositoryModule.kt`
- `data/src/main/java/com/streamvault/data/sync/SyncWorker.kt`
- `data/src/test/java/com/streamvault/data/sync/SyncWorkerPolicyTest.kt`
- `feature/settings/src/main/java/com/streamvault/feature/settings/parental/ParentalControlGroupViewModel.kt`
- `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsAppUpdateActions.kt`
- `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsDerivedStateObservers.kt`
- `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsGuideDefaultCategoryBindings.kt`
- `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsObserverRegistrations.kt`
- `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsOperationalModels.kt`
- `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsProviderActions.kt`
- `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsStateBindings.kt`
- `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsViewModel.kt`
- `feature/settings/src/test/java/com/streamvault/feature/settings/parental/ParentalControlGroupViewModelTest.kt`
- `feature/settings/src/test/java/com/streamvault/feature/settings/presentation/SettingsAppUpdateActionsTest.kt`
- `feature/settings/src/test/java/com/streamvault/feature/settings/presentation/SettingsProviderActionsTest.kt`
- `feature/settings/src/test/java/com/streamvault/feature/settings/presentation/SettingsPreferencesContractTest.kt`
- `.superpowers/sdd/2026-09-05-settings-data-boundary/task-2-report.md` (this report).

All listed source changes belong to Task 2. Generated graph files and raw command logs remain local ignored artifacts.

## TDD: RED before implementation

First added only `SettingsPreferencesContractTest.kt`; production code had not been changed.

Environment setup used for all Gradle commands:

```powershell
$env:ANDROID_HOME = Split-Path (Split-Path (Get-Command adb).Source)
```

`adb` resolved to `E:\androidSdk\platform-tools\adb.exe`, so `ANDROID_HOME` was `E:\androidSdk`. No local SDK configuration file was changed.

Ran:

```powershell
./gradlew.bat :feature:settings:compileDebugUnitTestKotlin --console=plain
```

Result: expected RED, exit 1, `BUILD FAILED in 14s`; 52 actionable tasks (3 executed, 1 from cache, 48 up-to-date).

The compiler reached the new test and reported:
- Unresolved domain `settings` package / `SettingsPreferences` import.
- `Unresolved reference 'SettingsPreferences'` on the interface-typed values and fake.
- The fake's five overrides reported `overrides nothing`.

This is the requested missing-contract failure, not an SDK or unrelated build failure. The test exercises two real settings entry points: restoring either parameter type to the concrete repository would make its interface-typed argument fail compilation.

Raw log: `task-2-red.log`, beside this report.

## GREEN: required gate

After extracting the contract, moving the snapshot, and changing consumer types, ran exactly the required tasks:

```powershell
./gradlew.bat :domain:test :data:compileDebugKotlin :feature:settings:compileDebugKotlin :feature:settings:compileDebugUnitTestKotlin --console=plain
```

Result: exit 0, `BUILD SUCCESSFUL in 1m 32s`; 55 actionable tasks (21 executed, 34 up-to-date). No contract-shape compilation errors occurred. Domain XML results: 145 tests, zero failures/errors/skips.

Raw log: `task-2-green.log`.

## Supplemental verification and the existing boundary RED

Ran the broader check:

```powershell
./gradlew.bat :feature:settings:testDebugUnitTest :data:compileDebugUnitTestKotlin :app:hiltJavaCompileDebug -x :feature:settings:verifyFeatureSettingsBoundary --console=plain
```

Result: exit 1, `BUILD FAILED in 1m 11s`; 38 settings tests completed, 37 passed and one failed. The sole failure was the unchanged Task 1 `SettingsModuleBoundaryTest.settingsModuleDeclaresExpectedBoundary`, line 16: settings still declares `project(":data")`. Both new contract tests and all migrated settings tests passed. Data unit-test compilation completed. The app Hilt check had not completed when this invocation stopped.

The Gradle boundary task was explicitly excluded to reach unit tests; the unit-level boundary assertion still demonstrated the expected red state. The Task 1 report and task ledger explicitly defer removing the production dependency and enabling the final policy until Tasks 3–4. Neither boundary test nor build policy was modified.

Raw log: `task-2-supplemental.log`.

## Final verification

Ran:

```powershell
./gradlew.bat :domain:test :data:compileDebugKotlin :feature:settings:compileDebugKotlin :feature:settings:compileDebugUnitTestKotlin :feature:settings:testDebugUnitTest --tests 'com.streamvault.feature.settings.presentation.*' --tests 'com.streamvault.feature.settings.parental.*' --tests 'com.streamvault.feature.settings.navigation.*' :data:compileDebugUnitTestKotlin :app:hiltJavaCompileDebug -x :feature:settings:verifyFeatureSettingsBoundary --console=plain
```

Result: exit 0, `BUILD SUCCESSFUL in 22s`; 185 actionable tasks (5 executed, 180 up-to-date). Final settings XML results: 37 tests, zero failures/errors/skips, including both contract tests. These filters include every existing settings test except the known Task 1 root-package module-boundary test. The domain gate remained green with its 145-test result up-to-date.

The app Kotlin/Java compilation and `:app:hiltJavaCompileDebug` completed successfully, validating the new binding and affected production constructor call sites. `:data:compileDebugUnitTestKotlin` passed, covering the moved snapshot's data test import.

Raw log: `task-2-final-verification.log`.

Additional checks:

```powershell
git -c core.safecrlf=false diff --check
rg -n 'PreferencesRepository|com.streamvault.data.preferences' feature/settings/src/main feature/settings/src/test -g '*.kt'
rg -n 'com.streamvault.data.preferences.DatabaseMaintenanceSnapshot' --glob '*.kt' --glob '!tmp/**'
graphify update .
```

- Diff whitespace check: exit 0. Removed the empty trailing line left by moving the snapshot before the final check.
- Both obsolete preference/snapshot reference searches: no matches (normal `rg` exit 1).
- Enumerated actual settings preference member references and compared against the extracted signature inventory: exactly 176 distinct members; no extra convenience APIs.
- Compared the repository before/after in memory, normalizing CRLF and undoing only the new imports/interface/override modifiers and snapshot move: identical content. This verifies that all method bodies, property initializers, preference keys/defaults/encodings, and persistence logic remain unchanged.
- Compared all 14 pre-existing affected consumer/import files to their original versions with only the specified type/import replacements applied: every comparison passed.
- `graphify update .` ran after source changes and again after the final binding/test annotation change: exit 0. Final graph: 1,328 files, 13,968 nodes, 24,519 edges, 479 communities. `graph.json` and `GRAPH_REPORT.md` refreshed locally. HTML visualization was skipped by graphify because node count exceeds its 5,000-node limit. Raw log: `task-2-graphify.log`.

## Self-review

Used the TDD and verification-before-completion skill workflows, with the user's exact compile-time RED requirement taking precedence over generic runtime-test guidance. Performed review locally without subagents.

- The interface exposes only settings-used members, including the two provider-scoped Flow methods and typed setters that return values.
- Existing repository behavior is mechanically unchanged, as confirmed by the normalized source comparison.
- The moved snapshot retains its full immutable data shape. Both worker persistence callbacks and settings UI conversion retain their original code.
- Existing tests now mock the interface without dropping assertions. The new partial fake delegates unused contract members to an interface mock while recording the exercised writes and supplying actual flows.
- Constructor/helper compilation protects the intended domain boundary. Runtime assertions exercise actual update-action and category-observer behavior.
- Hilt still supplies the existing singleton repository, and generated component compilation is green.
- No preference migration, provider/sync behavior change, production dependency enforcement change, or unrelated code cleanup was included.

## Concerns and limitations

- Known, intentional checkpoint concern: the full unfiltered settings suite remains red only on the Task 1 module-boundary assertion until Tasks 3–4 remove `:data` and remaining DAO/entity/sync references. This report does not claim that the complete Phase 7 boundary is finished.
- Existing Kotlin warnings (annotation targets, unchecked casts, coroutine opt-ins, and duplicate module-name flag) and the JVM class-sharing warning appeared during compilation/tests. They did not fail the requested/final gates.
- No emulator playback validation or APK assemble/install was performed; no playback behavior was changed. The app was verified through Kotlin/Java/Hilt compilation.
- No additional Task 2 implementation concerns found. Avoid adding another `SettingsPreferences` binding in Task 3: this task already binds it in the app's `RepositoryModule`.
