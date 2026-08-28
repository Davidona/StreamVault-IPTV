# Settings Feature Module Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move Settings, parental controls, backup/restore presentation, state, dialogs, resources, tests, and graph registration from `:app` into independently compiled `:feature:settings` without changing behavior.

**Architecture:** `:app` remains the root navigation and Android platform composition root. `:feature:settings` owns presentation and controller-free graph registration, consumes neutral core/domain contracts, and reaches app-only operations through feature-defined ports implemented by app adapters. Temporary `:data` imports are fail-closed and ledgered; the existing provider backup-preview request remains unchanged and is adapted by `AppNavHost`.

**Tech Stack:** Kotlin, Android library modules, Jetpack Compose/TV Material, Navigation Compose, Hilt/KSP, coroutines/StateFlow, JUnit4, Truth, Mockito-Kotlin, Robolectric, Compose UI tests, Kover, Gradle boundary tasks, Graphify.

**Spec:** `docs/superpowers/specs/2026-08-27-phase-5-settings-feature-extraction-design.md`

## Global Constraints

- Preserve the intentional working tree; never reset, clean, delete, or rewrite unrelated changes.
- This is a behavior-preserving Phase 5 extraction: preserve routes, arguments, focus, semantics, callbacks, persistence, launcher order, UI output, and error text.
- `:app` remains the root `NavHostController`, external-intent, route-codec, payload, manifest, worker, and platform composition root.
- `:feature:settings` may depend only on `:core:navigation`, `:core:ui`, `:domain`, temporary ledgered `:data`, and the audited `:player` dependency.
- `:feature:settings` must never depend on `:app`, `:feature:provider`, `:feature:playback`, `NavController`, `NavHostController`, or `MainActivity`.
- Keep `ProviderBackupPreviewContent` and `ProviderBackupPreviewRequest` source- and behavior-compatible; `:app` maps them to the settings-owned dialog.
- Do not begin Live, Catalog, or System extraction.
- Move files with `git mv` where practical; keep contract changes, mechanical moves, graph wiring, and cleanup in separate commits.
- Every direct settings `:data` implementation import must appear in `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`.
- Record unavailable runtime, credential, hardware, provider, account, performance, and physical-device gates as open rather than passing.
- After code changes, run `graphify update .` before reporting completion.

---

### Task 0: Freeze inventory, rollback point, and baseline obligations

**Files:**
- Create: `validation/phase5_settings/task0-inventory.md`
- Create: `validation/phase5_settings/source-inventory.txt`
- Create: `validation/phase5_settings/test-inventory.txt`
- Create: `validation/phase5_settings/resource-inventory.txt`
- Create: `validation/phase5_settings/dependency-inventory.txt`
- Create: `validation/phase5_settings/performance-protocol.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`

**Interfaces:**
- Consumes: the approved spec, current branch status, current settings source/tests, Phase 5 roadmap, playback/provider reports.
- Produces: exact move inventory, pre-extraction SHA, dirty-tree record, nine-type `:data` allowlist, `AudioCompatibilityMemoryStore` audit entry, and reproducible measurement protocol.

- [ ] **Step 1: Capture the tree and exact source/test inventory**

Run:

```powershell
git status --short --branch
git rev-parse HEAD
rg --files app/src/main/java/com/streamvault/app/ui/screens/settings -g '*.kt' | Sort-Object
rg --files app/src/test/java/com/streamvault/app/ui/screens/settings -g '*.kt' | Sort-Object
```

Expected: 71 production Kotlin paths and 12 unit-test paths. Use `apply_patch` to record the exact command output in the inventory files and to record the SHA and every existing working-tree path in `task0-inventory.md`; do not modify or stage unrelated paths.

- [ ] **Step 2: Capture app/data/player/resource imports**

Run:

```powershell
rg '^import com\.streamvault\.(app|data|player)\.' app/src/main/java/com/streamvault/app/ui/screens/settings -g '*.kt' | Sort-Object
rg -o 'R\.(string|plurals|drawable)\.[A-Za-z0-9_]+' app/src/main/java/com/streamvault/app/ui/screens/settings -g '*.kt' | ForEach-Object { ($_ -split ':')[-1] } | Sort-Object -Unique
```

Expected: nine distinct `com.streamvault.data` types, one `com.streamvault.player.AudioCompatibilityMemoryStore`, and 665 unique resource references (660 strings, five plurals). Use `apply_patch` to save the outputs. If counts differ, record the fresh counts and use the saved inventories as source of truth.

- [ ] **Step 3: Add the settings ledger section**

Append explicit rows for `ProgramDao`, `XtreamIndexJobDao`, `XtreamLiveOnboardingDao`, `XtreamIndexJobEntity`, `XtreamLiveOnboardingStateEntity`, `DatabaseMaintenanceSnapshot`, `PreferencesRepository`, `ProviderSyncCommands`, and `SyncRepairSection`, with the removal directions from the spec. Add a separate audit note for `AudioCompatibilityMemoryStore`; do not describe it as a `:data` import.

- [ ] **Step 4: Write the paired measurement protocol**

Record five pre/post runs for a reversible settings source edit and five pre/post runs for a reversible settings test edit. Use the same Gradle daemon/cache state and commands on both refs:

```powershell
.\gradlew.bat :app:compileDebugKotlin --profile --console=plain --warning-mode=none
.\gradlew.bat :app:compileDebugUnitTestKotlin --profile --console=plain --warning-mode=none
.\gradlew.bat :app:assembleDebug --profile --console=plain --warning-mode=none
```

The post-extraction equivalents are `:feature:settings:compileDebugKotlin` and `:feature:settings:compileDebugUnitTestKotlin`. State that mixed dirty-tree timings are diagnostic only.

- [ ] **Step 5: Verify and commit only inventory artifacts**

Run:

```powershell
git diff --check
git diff -- docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md validation/phase5_settings
git add docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md validation/phase5_settings
git commit -m "docs: inventory settings feature extraction"
```

Expected: the commit contains documentation/evidence only.

### Task 1: Create `:feature:settings` and prove a fail-closed boundary

**Files:**
- Create: `feature/settings/build.gradle.kts`
- Create: `feature/settings/src/main/AndroidManifest.xml`
- Create: `feature/settings/src/test/java/com/streamvault/feature/settings/SettingsModuleBoundaryTest.kt`
- Create: `feature/settings/src/test/resources/boundary-fixtures/AppPackageImport.kt`
- Create: `feature/settings/src/test/resources/boundary-fixtures/FullyQualifiedAppReference.kt`
- Create: `feature/settings/src/test/resources/boundary-fixtures/MainActivityReference.java`
- Create: `feature/settings/src/test/resources/boundary-fixtures/RootNavigation.kt`
- Create: `feature/settings/src/test/resources/boundary-fixtures/RootNavigation.java`
- Create: `feature/settings/src/test/resources/boundary-fixtures/ProviderFeatureImport.kt`
- Create: `feature/settings/src/test/resources/boundary-fixtures/PlaybackFeatureImport.java`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: exact allowed project dependency set from the spec.
- Produces: registered `:feature:settings`, `verifyFeatureSettingsBoundary`, and fixture-tested rejection of app/root-controller/feature-implementation imports.

- [ ] **Step 1: Write the failing app dependency assertion**

Add to the existing app Gradle/module structure test or create a focused assertion in `SettingsModuleBoundaryTest.kt` that resolves the module directory and requires the settings build script to exist:

```kotlin
@Test
fun settingsModuleDeclaresExpectedBoundary() {
    val buildFile = File("build.gradle.kts")
    assertThat(buildFile.isFile).isTrue()
    val text = buildFile.readText()
    assertThat(text).contains("verifyFeatureSettingsBoundary")
    assertThat(text).contains(":core:navigation")
    assertThat(text).doesNotContain("project(\":app\")")
}
```

- [ ] **Step 2: Run the test to verify red**

Run:

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest --tests "com.streamvault.feature.settings.SettingsModuleBoundaryTest" --console=plain
```

Expected: Gradle fails because project `:feature:settings` is not registered.

- [ ] **Step 3: Add module registration and library configuration**

Add `include(":feature:settings")` to `settings.gradle.kts`, add `implementation(project(":feature:settings"))` to `:app`, and model `feature/settings/build.gradle.kts` on `feature/provider/build.gradle.kts` with namespace `com.streamvault.feature.settings`. The exact allowed project dependency set is:

```kotlin
val allowedProjectDependencies = setOf(
    ":core:navigation",
    ":core:ui",
    ":domain",
    ":data",
    ":player",
)
```

Configure Compose, Hilt/KSP, Kover, Java/Kotlin 17, minSdk 25, compileSdk 36, unit-test dependencies, connected-test dependencies, `documentfile`, OkHttp, and the same Compose BOM used by provider.

- [ ] **Step 4: Add the boundary scan and fixtures**

Use the provider boundary implementation with these forbidden tokens:

```kotlin
val forbiddenFeatureSettingsSourceTokens = listOf(
    "import com.streamvault.app",
    "com.streamvault.app",
    "import com.streamvault.feature.provider",
    "import com.streamvault.feature.playback",
    "MainActivity",
    "NavHostController",
    "NavController",
)
```

Require every Kotlin and Java fixture above to be detected. Make `check` and all test tasks depend on `verifyFeatureSettingsBoundary`.

- [ ] **Step 5: Verify green**

Run:

```powershell
.\gradlew.bat :feature:settings:verifyFeatureSettingsBoundary :feature:settings:testDebugUnitTest :feature:settings:assembleDebug --console=plain --warning-mode=none
```

Expected: boundary fixtures are detected, main source has zero violations, and the empty module assembles.

- [ ] **Step 6: Commit the module shell**

```powershell
git add settings.gradle.kts app/build.gradle.kts feature/settings
git commit -m "build: add settings feature module boundary"
```

### Task 2: Neutralize shared UI and persisted presentation contracts

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/components/SearchInput.kt` -> `core/ui/src/main/java/com/streamvault/core/ui/components/SearchInput.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/components/TvEmptyState.kt` -> `core/ui/src/main/java/com/streamvault/core/ui/components/TvEmptyState.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/components/dialogs/PinDialog.kt` -> `core/ui/src/main/java/com/streamvault/core/ui/components/dialogs/PinDialog.kt`
- Move: `app/src/main/java/com/streamvault/app/localization/AppLocaleSupport.kt` -> `core/ui/src/main/java/com/streamvault/core/ui/localization/AppLocaleSupport.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/model/LiveTvChannelMode.kt` -> `domain/src/main/java/com/streamvault/domain/model/LiveTvChannelMode.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/model/LiveTvQuickFilterVisibilityMode.kt` -> `domain/src/main/java/com/streamvault/domain/model/LiveTvQuickFilterVisibilityMode.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/model/VodViewMode.kt` -> `domain/src/main/java/com/streamvault/domain/model/VodViewMode.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/model/CategoryDisplayPreferences.kt` -> `domain/src/main/java/com/streamvault/domain/policy/CategoryDisplayPreferences.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/dashboard/DashboardShelfCustomizationDialog.kt` -> `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/DashboardShelfCustomizationDialog.kt`
- Modify: every current importer returned by `rg -l 'com.streamvault.app.ui.(components.SearchInput|components.TvEmptyState|components.dialogs.PinDialog|model.LiveTvChannelMode|model.LiveTvQuickFilterVisibilityMode|model.VodViewMode|model.applyProviderCategoryDisplayPreferences)' app feature core domain -g '*.kt'`
- Test: existing `:core:ui`, `:domain`, and app model/policy tests discovered by `rg -l 'LiveTvChannelMode|LiveTvQuickFilterVisibilityMode|VodViewMode|applyProviderCategoryDisplayPreferences|PinDialog|SearchInput|TvEmptyState' --glob '*Test.kt'`

**Interfaces:**
- Consumes: existing component signatures and enum storage values.
- Produces: app-independent imports usable by settings, live, and catalog; no new feature-to-feature dependency.

- [ ] **Step 1: Move the existing model tests first**

For every existing parsing/policy test found by the command above, move it to the owning module and update only its package/import. Add this assertion if no enum test currently covers storage compatibility:

```kotlin
@Test
fun persistedDisplayModesKeepExistingStorageValues() {
    assertThat(VodViewMode.fromStorage("classic")).isEqualTo(VodViewMode.CLASSIC)
    assertThat(LiveTvChannelMode.fromStorage("PRO")).isEqualTo(LiveTvChannelMode.PRO)
    assertThat(LiveTvQuickFilterVisibilityMode.fromStorage("always"))
        .isEqualTo(LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE)
}
```

- [ ] **Step 2: Verify red after changing test imports**

Run the moved test through `:domain:test` or `:core:ui:testDebugUnitTest` as appropriate.

Expected: compilation fails because the new package does not exist.

- [ ] **Step 3: Move production files mechanically and update all importers**

Use `git mv`. Change package declarations and imports only. Preserve enum members, storage strings, supported language tags and `iw`/`he` plus `in`/`id` canonicalization, component parameters, semantics, focus behavior, and rendering. Use existing `CoreAppScreenScaffold`, `NavigationChrome`, `UiDestination`, `LocalUiTimeFormat`, and `UiTimeFormat.createDateTimeFormat` rather than moving app shell/time adapters into the feature.

- [ ] **Step 4: Verify shared and app consumers**

Run:

```powershell
.\gradlew.bat :domain:test :core:ui:testDebugUnitTest :app:compileDebugKotlin :feature:provider:compileDebugKotlin :feature:playback:compileDebugKotlin --console=plain --warning-mode=none
```

Expected: all tasks pass and `rg` finds no old package import.

- [ ] **Step 5: Commit shared contract moves**

```powershell
git add core/ui domain app feature/settings
git commit -m "refactor: expose settings shared presentation contracts"
```

### Task 3: Define settings ports and app adapters

**Files:**
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/api/SettingsPlatformHost.kt`
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/api/SettingsBackupFileHost.kt`
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/api/SettingsSurfaceRefreshPort.kt`
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/api/SettingsDiagnosticsPort.kt`
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/api/SettingsAppUpdatePort.kt`
- Create: `app/src/main/java/com/streamvault/app/settings/AppSettingsPlatformHost.kt`
- Create: `app/src/main/java/com/streamvault/app/settings/AppSettingsBackupFileAdapter.kt`
- Create: `app/src/main/java/com/streamvault/app/settings/AppSettingsSurfaceRefreshAdapter.kt`
- Create: `app/src/main/java/com/streamvault/app/settings/AppSettingsDiagnosticsAdapter.kt`
- Create: `app/src/main/java/com/streamvault/app/settings/AppSettingsUpdateAdapter.kt`
- Create: `app/src/main/java/com/streamvault/app/settings/AppSettingsBindings.kt`
- Test: `app/src/test/java/com/streamvault/app/settings/AppSettingsAdaptersTest.kt`

**Interfaces:**
- Consumes: `BackupFileBridge`, `CrashReportStore`, app update services, Watch Next, recommendations, TV-input manager/worker, official-build verifier, and recording player handoff.
- Produces: the exact feature interfaces defined in the spec and app Hilt bindings for ViewModel-injected ports.

- [ ] **Step 1: Write failing adapter delegation tests**

Use internal operation interfaces in the app adapter file so tests assert delegation without Android singletons. Cover at least backup candidate mapping, surface refresh ordering, diagnostics mapping, release/download mapping, and recording playback field preservation:

```kotlin
@Test
fun recordingPlaybackAdapterPreservesRequestFields() {
    val request = SettingsRecordingPlaybackRequest(
        streamUrl = "content://recording/7",
        title = "News",
        internalId = 7L,
        providerId = 3L,
        contentType = "MOVIE",
    )
    platformHost.playRecording(request)
    assertThat(playbackOperations.requests).containsExactly(request)
}
```

- [ ] **Step 2: Run adapter tests to verify red**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.settings.AppSettingsAdaptersTest" --console=plain
```

Expected: compilation fails because settings API types/adapters do not exist.

- [ ] **Step 3: Define immutable feature value types and interfaces**

Implement the spec signatures. `SettingsBackupFileHost` must cover the current bridge behavior without exposing app types:

```kotlin
interface SettingsBackupFileHost {
    val jsonMimeType: String
    fun rememberManagedExport(uri: Uri)
    fun listManagedBackups(): List<SettingsBackupFileCandidate>
    fun listPickerFreeBackups(): List<SettingsBackupFileCandidate>
    fun listBackups(directory: File): List<SettingsBackupFileCandidate>
    fun createShareExportFile(): File
    fun createPickerFreeExportUri(): Uri?
    fun finishPickerFreeExport(uri: Uri, success: Boolean): Boolean
    fun createExportFile(directory: File): File
    fun delete(candidate: SettingsBackupFileCandidate): Boolean
    fun providerUri(file: File): Uri
}
```

`SettingsBackupFileCandidate` contains `uri`, `displayName`, `lastModifiedMs`, and `sizeBytes`. Update types mirror every field consumed by `SettingsAppUpdateActions`. `SettingsAppUpdatePort` also exposes
`fun isRemoteVersionNewer(remoteVersionCode: Int?, remoteVersionName: String, remotePublishedAt: String?): Boolean`; the app adapter delegates to the existing build/channel-aware policy. Feature-owned `SettingsUpdateCheckPolicy` preserves the current 24-hour success interval and 15-minute failure backoff, while feature-owned `SettingsUpdateActionState` and action selection preserve the current download/install decisions.

- [ ] **Step 4: Implement app adapters with no new policy**

Map app values field-for-field. `AppSettingsSurfaceRefreshAdapter` delegates to current Watch Next, recommendation, and TV-input operations. `AppSettingsUpdateAdapter` delegates to current checker/installer and retains their exact errors. Bind `SettingsSurfaceRefreshPort`, `SettingsDiagnosticsPort`, and `SettingsAppUpdatePort` from an app Hilt module; pass `SettingsPlatformHost` from `AppNavHost` because it performs route-owned Activity operations.

- [ ] **Step 5: Verify green and boundary safety**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.settings.AppSettingsAdaptersTest" :feature:settings:verifyFeatureSettingsBoundary :app:compileDebugKotlin --console=plain --warning-mode=none
```

Expected: adapter tests pass and feature source contains no app reference.

- [ ] **Step 6: Commit contracts and adapters**

```powershell
git add feature/settings/src/main/java/com/streamvault/feature/settings/api app/src/main/java/com/streamvault/app/settings app/src/test/java/com/streamvault/app/settings
git commit -m "refactor: add settings platform service ports"
```

### Task 4: Transfer backup-preview ownership without changing provider API

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsBackupImportPreviewDialog.kt` -> `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsBackupImportPreviewDialog.kt`
- Create: `feature/settings/src/androidTest/java/com/streamvault/feature/settings/presentation/SettingsBackupPreviewTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt`
- Do not modify: `feature/provider/src/main/java/com/streamvault/feature/provider/api/ProviderBackupPreviewContent.kt`

**Interfaces:**
- Consumes: `BackupPreview`, `BackupImportPlan`, conflict strategy, import toggles, busy state, dismiss and confirm callbacks.
- Produces: public settings-owned `BackupImportPreviewDialog(...)` invoked by app composition; provider request remains byte-for-byte unchanged.

- [ ] **Step 1: Add the connected callback/semantics test first**

Render the dialog with a populated preview and assert section labels, strategy selection, one import toggle, dismiss, and confirm callbacks. Name the root semantics tag `settings.backup.preview` only if the existing dialog already has an equivalent stable tag; otherwise assert existing text/content descriptions without adding semantics.

- [ ] **Step 2: Run the test to verify red**

```powershell
.\gradlew.bat :feature:settings:compileDebugAndroidTestKotlin --console=plain
```

Expected: compilation fails because the dialog is not feature-owned.

- [ ] **Step 3: Move the dialog and its exact resources**

Use `git mv`, update package/R/device imports, and preserve layout, focus, row order, callbacks, and strings. Use `core.ui.device.rememberIsTelevisionDevice`.

- [ ] **Step 4: Rewire only the app renderer**

Keep the current `ProviderBackupPreviewRequest` mapping in `AppNavHost`; change only the dialog import to `com.streamvault.feature.settings.presentation.BackupImportPreviewDialog`. Confirm all request fields remain mapped exactly once.

- [ ] **Step 5: Verify provider compatibility**

```powershell
.\gradlew.bat :feature:provider:testDebugUnitTest :feature:provider:compileDebugKotlin :feature:settings:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests "com.streamvault.app.navigation.*" --console=plain --warning-mode=none
```

Expected: provider code compiles unchanged and app navigation tests pass.

- [ ] **Step 6: Commit the seam**

```powershell
git add app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt feature/settings app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsBackupImportPreviewDialog.kt
git commit -m "refactor: move backup preview rendering to settings feature"
```

### Task 5: Move parental-control presentation and route state

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/screens/settings/parental/ParentalControlGroupViewModel.kt` -> `feature/settings/src/main/java/com/streamvault/feature/settings/parental/ParentalControlGroupViewModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/settings/parental/ParentalControlGroupScreen.kt` -> `feature/settings/src/main/java/com/streamvault/feature/settings/parental/ParentalControlGroupScreen.kt`
- Create: `feature/settings/src/test/java/com/streamvault/feature/settings/parental/ParentalControlGroupViewModelTest.kt`

**Interfaces:**
- Consumes: `SavedStateHandle["providerId"]`, `CategoryRepository`, temporary `PreferencesRepository`, core search/PIN/shell components.
- Produces: feature-owned parental state/ViewModel/screen with unchanged category keys, pending changes, hide/show behavior, messages, focus, and callbacks.

- [ ] **Step 1: Write focused ViewModel policy tests before the move**

Cover adult categories remaining protected, non-adult pending toggles, reset, search, hidden counts, save failure message, and provider ID preservation. The adult test must assert real state:

```kotlin
@Test
fun adultCategoryCannotBeUnprotected() = runTest {
    val adult = category(id = 9L, isAdult = true, isUserProtected = false)
    repository.categories.value = listOf(adult)
    viewModel.toggleCategoryProtection(adult)
    assertThat(viewModel.uiState.first { !it.isLoading }.categories.single().isProtected).isTrue()
}
```

- [ ] **Step 2: Run the feature test to verify red**

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest --tests "com.streamvault.feature.settings.parental.ParentalControlGroupViewModelTest" --console=plain
```

Expected: compilation fails because the parental classes remain in app.

- [ ] **Step 3: Move the two files mechanically**

Use core components and feature resources. Do not change string text, content descriptions, lazy-list keys, focus requesters, current mode/type state, or Hilt/SavedState behavior.

- [ ] **Step 4: Verify green**

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest --tests "com.streamvault.feature.settings.parental.ParentalControlGroupViewModelTest" :feature:settings:compileDebugKotlin --console=plain --warning-mode=none
```

- [ ] **Step 5: Commit parental ownership**

```powershell
git add app/src/main/java/com/streamvault/app/ui/screens/settings/parental feature/settings/src/main/java/com/streamvault/feature/settings/parental feature/settings/src/test/java/com/streamvault/feature/settings/parental
git commit -m "refactor: move parental controls to settings feature"
```

### Task 6: Move settings state, policies, actions, observers, and unit tests

**Files:**
- Move: all non-composable action/model/observer/formatter files listed in `validation/phase5_settings/source-inventory.txt`, including `SettingsUiStateModel.kt`, `SettingsOperationalModels.kt`, `SettingsPreferenceModels.kt`, `SettingsPreferenceSnapshotMapper.kt`, `RemoteShortcutSettingsModels.kt`, `RemoteShortcutSettingsSupport.kt`, `SettingsBackupActions.kt`, `SettingsDriveBackupActions.kt`, `SettingsProviderActions.kt`, `SettingsRecordingActions.kt`, `SettingsSyncActions.kt`, `SettingsEpgActions.kt`, `SettingsObserverRegistrations.kt`, `SettingsDerivedStateObservers.kt`, `SettingsGuideDefaultCategoryBindings.kt`, `SettingsStateBindings.kt`, `SettingsAppUpdateActions.kt`, `SettingsAppUpdateModels.kt`, `SettingsAppUpdateFormatting.kt`, `SettingsFormatting.kt`, and `InternetSpeedTestRunner.kt` into `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/`.
- Move: all 12 paths in `validation/phase5_settings/test-inventory.txt` into `feature/settings/src/test/java/com/streamvault/feature/settings/presentation/`.
- Modify: moved sources to consume Task 2 contracts and Task 3 ports.

**Interfaces:**
- Consumes: unchanged domain repositories/managers/use cases, ledgered `:data` types, settings ports, shared domain display enums.
- Produces: independently testable settings state/action/observer layer with the existing method and state-field surface.

- [ ] **Step 1: Move one unit-test cluster first and verify red**

Start with `SettingsBackupActionsTest.kt`, `SettingsDriveBackupActionsTest.kt`, and `SettingsAppUpdateActionsTest.kt`. Change packages/imports only, then run:

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest --tests "com.streamvault.feature.settings.presentation.SettingsBackupActionsTest" --tests "com.streamvault.feature.settings.presentation.SettingsDriveBackupActionsTest" --tests "com.streamvault.feature.settings.presentation.SettingsAppUpdateActionsTest" --console=plain
```

Expected: compilation fails because production actions are still app-owned.

- [ ] **Step 2: Move the matching production cluster and verify green**

Use `git mv`; replace app update classes with `SettingsAppUpdatePort` value types and replace TV managers with `SettingsSurfaceRefreshPort`. Preserve state updates, coroutine launch order, partial-import checkpoint behavior, and messages.

Run the same focused command. Expected: all focused tests pass.

- [ ] **Step 3: Repeat test-first for remaining inventory tests**

Move the remaining nine test files, run `:feature:settings:testDebugUnitTest` to observe missing production symbols, then move the corresponding production helpers. Do not weaken assertions or replace real state assertions with mock-interaction-only assertions.

- [ ] **Step 4: Run the complete moved unit suite and app compile**

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest :app:compileDebugUnitTestKotlin :feature:settings:verifyFeatureSettingsBoundary --console=plain --warning-mode=none
```

Expected: all 12 moved test classes pass from the feature; boundary scan reports only ledger-approved module dependencies and zero forbidden source imports.

- [ ] **Step 5: Commit state/action ownership**

```powershell
git add app/src/main/java/com/streamvault/app/ui/screens/settings app/src/test/java/com/streamvault/app/ui/screens/settings feature/settings
git commit -m "refactor: move settings state and actions to feature"
```

### Task 7: Move `SettingsViewModel` and connect operational adapters

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsViewModel.kt` -> `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/streamvault/app/settings/AppSettingsBindings.kt`
- Test: `feature/settings/src/test/java/com/streamvault/feature/settings/presentation/SettingsViewModelBoundaryTest.kt`

**Interfaces:**
- Consumes: Task 3 ports, Task 6 state/actions, existing Hilt bindings for domain/data/player dependencies.
- Produces: feature-owned Hilt ViewModel with the same public actions and `StateFlow<SettingsUiState>`.

- [ ] **Step 1: Write the constructor-boundary source test**

```kotlin
@Test
fun viewModelUsesPortsInsteadOfAppImplementations() {
    val source = File("src/main/java/com/streamvault/feature/settings/presentation/SettingsViewModel.kt")
    assertThat(source.isFile).isTrue()
    val text = source.readText()
    assertThat(text).contains("SettingsSurfaceRefreshPort")
    assertThat(text).contains("SettingsDiagnosticsPort")
    assertThat(text).contains("SettingsAppUpdatePort")
    assertThat(text).doesNotContain("com.streamvault.app")
}
```

- [ ] **Step 2: Run to verify red**

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest --tests "com.streamvault.feature.settings.presentation.SettingsViewModelBoundaryTest" --console=plain
```

Expected: failure because the feature ViewModel file does not exist.

- [ ] **Step 3: Move the ViewModel mechanically**

Preserve all methods, observer registration order, StateFlow sharing policies, initial values, coroutine scopes, and injected domain/data/player dependencies. Replace only app implementations with Task 3 ports and feature R/build-info values.

- [ ] **Step 4: Verify Hilt and unit compilation**

```powershell
.\gradlew.bat :feature:settings:kspDebugKotlin :feature:settings:testDebugUnitTest :app:compileDebugKotlin --console=plain --warning-mode=none
```

Expected: Hilt resolves app adapter bindings at app assembly and the feature ViewModel compiles independently against interfaces.

- [ ] **Step 5: Commit ViewModel ownership**

```powershell
git add app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsViewModel.kt app/src/main/java/com/streamvault/app/settings feature/settings
git commit -m "refactor: move settings view model to feature"
```

### Task 8: Move remaining Settings UI and locale resources

**Files:**
- Move: every remaining production path under `app/src/main/java/com/streamvault/app/ui/screens/settings/` listed in `validation/phase5_settings/source-inventory.txt` into `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/`, preserving the `parental/` destination already moved.
- Create/Modify: `feature/settings/src/main/res/values/strings.xml`
- Create/Modify: `feature/settings/src/main/res/values-ar/strings.xml`, `values-cs/strings.xml`, `values-da/strings.xml`, `values-de/strings.xml`, `values-el/strings.xml`, `values-es/strings.xml`, `values-fi/strings.xml`, `values-fr/strings.xml`, `values-hu/strings.xml`, `values-in/strings.xml`, `values-it/strings.xml`, `values-iw/strings.xml`, `values-ja/strings.xml`, `values-ko/strings.xml`, `values-nb/strings.xml`, `values-nl/strings.xml`, `values-pl/strings.xml`, `values-pt/strings.xml`, `values-ro/strings.xml`, `values-ru/strings.xml`, `values-sv/strings.xml`, `values-tr/strings.xml`, `values-uk/strings.xml`, `values-vi/strings.xml`, `values-zh/strings.xml`
- Modify: corresponding app locale files only after reference scans prove each moved key is feature-exclusive.
- Create: `feature/settings/src/androidTest/java/com/streamvault/feature/settings/presentation/SettingsNavigationAndDialogTest.kt`
- Modify/Split: `app/src/androidTest/java/com/streamvault/app/ui/PremiumRouteGoldenTest.kt`

**Interfaces:**
- Consumes: feature ViewModel/state/actions, core shell/UI primitives, platform host, app-supplied `List<UiDestination>` and route callback.
- Produces: complete feature-owned Settings UI with unchanged focus, semantics, rows, dialogs, launchers, and visual resources.

- [ ] **Step 1: Add a focused connected test before moving the root screen**

Cover the Settings destination semantic, selected section focus, navigation between at least two sections, opening/dismissing one value dialog, and Back restoration. Run compile first:

```powershell
.\gradlew.bat :feature:settings:compileDebugAndroidTestKotlin --console=plain
```

Expected: compilation fails because `SettingsScreen` is not in the feature.

- [ ] **Step 2: Move UI files mechanically**

Use `git mv`. Replace app shell with `CoreAppScreenScaffold`, passed `UiDestination` values, and `onTopLevelRouteRequested`. Replace app Search/PIN/empty/time/device imports with Task 2/core equivalents. Pass `SettingsPlatformHost` for recording playback, backup files/share, crash sharing, removable storage, build info, and official-build status. Preserve every Activity Result contract and callback order.

- [ ] **Step 3: Move resources locale by locale**

Use `validation/phase5_settings/resource-inventory.txt` as the candidate set. For each key, scan all non-settings source/tests/resources before removing it from app:

```powershell
rg -n "R\.(string|plurals)\.<key>|name=\"<key>\"" app feature core benchmark -g '*.kt' -g '*.xml'
```

Copy the exact default and translated values. Keep shared keys in app or duplicate them when another owner remains. Do not rename keys or rewrite translations.

- [ ] **Step 4: Move/split connected golden ownership**

Move the Settings route portion of `saved_guide_and_settings_routes_matchGolden` into the feature connected test while leaving Guide coverage in app. Reuse the checked-in Settings baseline rather than recording a new self-baseline. Keep the recording-disabled missing-golden guard.

- [ ] **Step 5: Compile and test the full UI**

```powershell
.\gradlew.bat :feature:settings:compileDebugKotlin :feature:settings:compileDebugAndroidTestKotlin :feature:settings:testDebugUnitTest :app:compileDebugKotlin --console=plain --warning-mode=none
```

Expected: all moved sources/resources compile and no `com.streamvault.app` import appears under feature main.

- [ ] **Step 6: Commit UI/resources separately**

```powershell
git add app/src/main app/src/androidTest feature/settings/src/main feature/settings/src/androidTest
git commit -m "refactor: move settings presentation to feature"
```

### Task 9: Move Settings graph registration and preserve route compatibility

**Files:**
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/navigation/SettingsRoutePatterns.kt`
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/navigation/SettingsGraph.kt`
- Create: `feature/settings/src/test/java/com/streamvault/feature/settings/navigation/SettingsRoutePatternsTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/graph/SystemGraph.kt`
- Modify: `app/src/test/java/com/streamvault/app/navigation/FeatureGraphBoundaryTest.kt`
- Test: existing `AppRouteCodecTest.kt` and `ExternalDestinationTest.kt`

**Interfaces:**
- Consumes: Task 3 platform host, Task 8 screens, `NavigationActions`, `AppDestination`, `UiDestination` list, app top-level route callback.
- Produces: `SettingsRoutePatterns` and `registerSettingsGraph(actions, platformHost, navigationDestinations, onTopLevelRouteRequested, onTopLevelDestinationRequested)`.

- [ ] **Step 1: Write route-pattern tests first**

```kotlin
@Test
fun routePatternsRemainCompatible() {
    assertThat(SettingsRoutePatterns.SETTINGS).isEqualTo("settings")
    assertThat(SettingsRoutePatterns.SETTINGS_DESTINATION)
        .isEqualTo("settings?backupUri={backupUri}")
    assertThat(SettingsRoutePatterns.PARENTAL_CONTROL_GROUPS)
        .isEqualTo("parental_control_groups/{providerId}")
}
```

- [ ] **Step 2: Run to verify red**

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest --tests "com.streamvault.feature.settings.navigation.SettingsRoutePatternsTest" --console=plain
```

Expected: unresolved `SettingsRoutePatterns`.

- [ ] **Step 3: Implement route constants and controller-free graph**

Copy the existing `SystemGraph` Settings and parental composable blocks. Preserve `backupUri` blank filtering, provider ID type, `dropUnlessResumed`, `launchSingleTop`, current route, add/edit callbacks, parental callbacks, and Back. Do not copy Downloads or Plugins.

- [ ] **Step 4: Register the feature from `AppNavHost`**

Build/pass current shell `UiDestination` values in app, supply `AppSettingsPlatformHost`, and call `registerSettingsGraph`. Keep the provider preview adapter pointing to the settings dialog. Remove only Settings and parental blocks/imports from `SystemGraph` after app compilation succeeds.

- [ ] **Step 5: Extend graph-boundary coverage and verify**

Add the settings navigation directory to `FeatureGraphBoundaryTest` and require `SettingsGraph.kt`. Run:

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest :app:testDebugUnitTest --tests "com.streamvault.app.navigation.AppRouteCodecTest" --tests "com.streamvault.app.navigation.ExternalDestinationTest" --tests "com.streamvault.app.navigation.FeatureGraphBoundaryTest" :app:assembleDebug --console=plain --warning-mode=none
```

Expected: route compatibility and feature graph tests pass; the app assembles with one Settings route owner.

- [ ] **Step 6: Commit graph ownership**

```powershell
git add feature/settings/src/main/java/com/streamvault/feature/settings/navigation feature/settings/src/test/java/com/streamvault/feature/settings/navigation app/src/main/java/com/streamvault/app/navigation app/src/test/java/com/streamvault/app/navigation
git commit -m "refactor: register settings graph from feature"
```

### Task 10: Remove legacy ownership and run automated structural gates

**Files:**
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`
- Create: `validation/phase5_settings/task10-automated-validation.md`
- Modify: `app/build.gradle.kts` only for confirmed-obsolete dependencies/tasks.
- Modify: `feature/settings/build.gradle.kts` for final exact dependency/runtime report wiring.

**Interfaces:**
- Consumes: completed feature ownership and inventory.
- Produces: one production owner, exact transitional dependency evidence, source-count evidence, independent checks, app integration checks, and refreshed Graphify output.

- [ ] **Step 1: Fail on legacy or forbidden ownership**

Run:

```powershell
rg -n "com\.streamvault\.app\.ui\.screens\.settings" app feature core domain -g '*.kt' -g '*.java'
rg -n "com\.streamvault\.app|NavHostController|NavController|MainActivity|com\.streamvault\.feature\.(provider|playback)" feature/settings/src/main -g '*.kt' -g '*.java'
rg -n '^import com\.streamvault\.data\.' feature/settings/src/main -g '*.kt' | Sort-Object
```

Expected: first two scans return no results; every third-scan type matches the settings ledger exactly.

- [ ] **Step 2: Remove only confirmed-obsolete app resources/dependencies**

Check every candidate with `rg` and Gradle dependency reports. Do not remove `:data`, `:player`, provider, playback, Room, OkHttp, WorkManager, or other app dependencies while any app consumer remains.

- [ ] **Step 3: Run feature checks**

```powershell
.\gradlew.bat :feature:settings:verifyFeatureSettingsBoundary :feature:settings:testDebugUnitTest :feature:settings:lintDebug :feature:settings:check :feature:settings:assembleDebug --console=plain --warning-mode=none
```

Expected: every command exits 0. Record test counts, lint warnings, boundary report, dependency list, and durations.

- [ ] **Step 4: Run app and neighboring-feature integration checks**

```powershell
.\gradlew.bat :core:navigation:check :core:ui:check :domain:test :feature:playback:check :feature:provider:check :app:testDebugUnitTest :app:assembleDebug --console=plain --warning-mode=none
```

Expected: commands exit 0 or exact pre-existing failures are recorded with evidence and the gate remains open. Do not attribute an existing failure to extraction without a baseline comparison.

- [ ] **Step 5: Prove compile isolation**

Apply and revert a comment-only edit in a settings production file, run `:feature:settings:compileDebugKotlin :app:compileDebugKotlin --dry-run` and a real profiled build, and record executed Kotlin/KSP tasks. Confirm no `:feature:playback:compile*` or `:feature:provider:compile*` task executes because of the edit.

- [ ] **Step 6: Refresh Graphify**

```powershell
graphify update .
```

Expected: graph rebuild succeeds and settings implementation nodes point to `feature/settings`, not the old app package. Record node/edge/community counts.

- [ ] **Step 7: Commit cleanup/evidence**

```powershell
git add app feature/settings docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md validation/phase5_settings graphify-out
git commit -m "chore: verify settings feature ownership"
```

### Task 11: Connected acceptance, measurements, profiles, and final report

**Files:**
- Create: `validation/phase5_settings/task11-runtime-validation.md`
- Create: `validation/phase5_settings/build-after/README.md`
- Create: `validation/phase5_settings/profile-validation.md`
- Create: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_REPORT.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_ROADMAP.md`
- Modify: `docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`

**Interfaces:**
- Consumes: installed debug app, feature/app connected suites, pre/post refs, baseline-profile producer, all prior evidence.
- Produces: honest Settings extraction status, rollback SHA/commits, automated/runtime/performance/profile evidence, and explicit open gates.

- [x] **Step 1: Run focused connected suites on the TV emulator**

```powershell
.\gradlew.bat :feature:settings:connectedDebugAndroidTest :feature:provider:connectedDebugAndroidTest :app:connectedDebugAndroidTest --console=plain --warning-mode=none
```

Record device/AVD/API, per-class counts, XML result paths, screenshots, and every failure. Isolate reruns when a mixed suite times out; do not erase the mixed-run result.

- [ ] **Step 2: Exercise Settings navigation/focus/dialog behavior**

On a fresh debug APK, record Settings entry, section navigation, selected-section focus, dialog open/dismiss, Back restoration, add/edit provider handoff, parental route/return, touch/mouse where available, RTL, reduced motion, and accessibility semantics. Sanitize logs and screenshots.

- [ ] **Step 3: Exercise backup/restore and provider preview compatibility**

Cover picker launch, managed local backup, folder flow, share, preview selection, conflict strategy, dismiss/confirm, partial-import checkpoint, restore synchronization chooser, and provider setup preview rendering. Cover USB/Drive/account/provider-completion only when hardware/accounts/test data are available; otherwise list each as open.

- [ ] **Step 4: Exercise parental, recording, EPG, provider, and update paths**

Record parental search/protection/hide/PIN/save/reset, recording folder/browser/playback callback, EPG source/assignment dialogs, provider management/sync dialogs, and update check/download/install states that are safe and available. Never perform credentialed or destructive external operations without the existing test fixture/explicit authority.

- [ ] **Step 5: Capture five paired build samples**

Follow `performance-protocol.md` on clean committed pre/post refs with identical commands and host state. Report all samples, median, p95, task sets, daemon/cache state, and whether the 25% source-edit / 20% test-compile targets were demonstrated. If a comparable pre-ref cannot be constructed, keep the performance gate open.

- [x] **Step 6: Run profile regeneration and stale-descriptor checks**

```powershell
.\gradlew.bat :app:generateBaselineProfile verifyBaselineProfileSources :app:assembleBeta :app:assembleRelease --console=plain --warning-mode=none
rg -n 'com/streamvault/app/ui/screens/settings|com\.streamvault\.app\.ui\.screens\.settings' app/src/main/generated/baselineProfiles app/build -g '*.txt' -g '*.prof' -g '*.map'
```

Expected: profile tasks pass and stale app-settings descriptors are absent. Preserve the existing physical-device/release-approval caveats.

- [x] **Step 7: Write the settings report**

`COMPOSE_REDUCTION_PHASE5_SETTINGS_REPORT.md` must include ownership, public boundary, source/resource/test counts, exact ledger entries, automated commands/results, connected/manual results, provider preview compatibility, performance samples, profile status, rollback instructions, Graphify counts, known existing failures, and every open gate. State structural completion separately from runtime/performance acceptance.

- [x] **Step 8: Update roadmap/grand-plan status without closing unrelated gates**

Link the settings plan/report. Preserve the playback and provider open-gate text. Do not mark Phase 5 complete because settings structure passes.

- [x] **Step 9: Run final verification before any completion claim**

```powershell
git diff --check
.\gradlew.bat :feature:settings:check :feature:provider:check :feature:playback:check :app:testDebugUnitTest :app:assembleDebug --console=plain --warning-mode=none
graphify update .
git status --short
```

Read the full output and report the actual result. Do not claim a pass for any command not run fresh.

- [x] **Step 10: Commit report and final evidence**

```powershell
git add docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_REPORT.md docs/COMPOSE_REDUCTION_PHASE5_ROADMAP.md docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md validation/phase5_settings graphify-out
git commit -m "docs: report settings feature extraction"
```

## Exit criteria

- `:feature:settings` owns all 71 inventoried production paths, 12 inventoried unit-test paths, parental controls, backup preview, resources, connected tests, and Settings graph registration, subject to documented shared-resource exceptions.
- `:app` owns only Settings platform adapters, route compatibility/external intents, root graph registration, and provider-to-settings preview adaptation.
- Settings and parental route strings/arguments are unchanged.
- Provider backup-preview request type and callback mapping are unchanged.
- Feature boundary, independent tests, lint/check/assemble, app navigation tests, app unit tests, and app assembly have fresh evidence.
- No feature main source imports app, root controllers, `MainActivity`, provider implementation, or playback implementation.
- All temporary data types and the concrete player audit candidate are documented.
- Connected/manual/performance/profile outcomes and unavailable gates are recorded without converting open gates into passes.
- Graphify reflects feature ownership.
