# Provider Feature Module Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract provider setup, edit, import, pairing, presentation state, navigation registration, resources, and tests into `:feature:provider` without changing provider behavior, focus/typing behavior, file-launcher behavior, or app navigation contracts.

**Architecture:** `:app` remains the composition root. `:feature:provider` owns the provider route, screen family, `ProviderSetupViewModel`, QR pairing, resources, and provider-scoped tests. It depends only on `:core:navigation`, `:core:ui`, `:domain`, and explicitly ledgered temporary `:data` types. The app supplies the existing settings-owned backup-preview dialog through a composable request renderer, avoiding an app import, feature-to-feature dependency, or duplicated dialog.

**Tech Stack:** Kotlin 2.2/JVM 17, Android compile SDK 36/min SDK 25, Compose/TV Compose, Navigation Compose 2.9.7, Hilt 2.56.2/KSP, coroutines/StateFlow, ZXing 3.5.3, JUnit 4, Truth, Mockito Kotlin, Robolectric, Compose instrumentation, Gradle boundary tasks, Baseline Profile tooling, ADB, and graphify.

**Spec:** `docs/superpowers/specs/2026-08-24-phase-5-feature-module-extraction-design.md`

## Global constraints

- Do not reset, delete, overwrite, or mix the current uncommitted navigation, golden-capture, baseline-profile, playback-test, or documentation work into provider extraction commits.
- Begin implementation only after that work is reviewed, verified, and preserved as its own baseline commit(s).
- Preserve create/edit/import/pairing behavior, provider types, defaults, validation, cancellation, retry, completion ordering, Activity Result contracts, persisted URI handling, focus order, keyboard and D-pad behavior, semantics, routes, arguments, and callbacks.
- Do not redesign the UI or refactor business behavior during extraction. Mechanical moves and package/import changes come first; behavior fixes need separate evidence and commits.
- `:feature:provider` must not depend on or import `:app`, another feature implementation, `MainActivity`, `NavController`, or `NavHostController`.
- Allowed project dependencies are exactly `:core:navigation`, `:core:ui`, `:domain`, and temporary `:data` imports recorded in `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`.
- Keep `AppDestination`, `AppRouteCodec`, external-route compatibility, startup coordination, and the root controller in their current owners.
- Keep `SettingsBackupImportPreviewDialog.kt` app-owned until settings extraction. Inject it using a feature-owned composable request contract.
- Performance timings remain open until exact pre/post Git refs exist. The idle-host release-like profile regeneration is complete, but the five paired samples must still run from clean checkouts; do not compare the current mixed dirty tree.
- Playback acceptance remains independently open. Provider structural work does not resolve or waive live HLS, manual playback, or playback profile gates.
- Run `graphify update .` after code changes.

## Current execution status (2026-08-27)

- Tasks 0–8 structural work is complete: the provider module, seams, moved
  setup/pairing/UI state, resources, tests, graph registration, ownership
  cleanup, boundaries, dependency audit, unit tests, feature lint, and debug
  assembly have passed.
- Task 9 remains open. SDK-local `adb` reached the connected emulator for the
  focused provider completion test, app navigation/golden checks, playback
  overlays, and additional manual source/launcher/Drive/QR journeys. Full
  manual provider journeys and mixed-suite stabilization are recorded in
  `validation/phase5_provider/task9-runtime-validation.md`.
- Task 10 is open for the paired timing samples and complete manual evidence;
  the idle-host release-like profile subgate passed on 2026-08-27. Ten profile
  tests passed, eight ordinary macrobenchmarks were skipped by assumption,
  generated profiles have no stale provider paths, and `:app:assembleRelease`
  passed afterward. See `validation/phase5_provider/performance-rerun.md`.
- The repository has no root `verifyModuleBoundaries` task. The registered
  `verifyCoreNavigationBoundary`, `verifyCoreUiBoundary`,
  `verifyFeaturePlaybackBoundary`, `verifyFeatureProviderBoundary`, and
  `app:verifyFeatureNavigationBoundary` tasks are the authoritative checks.

## Planned ownership

```text
feature/provider/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/java/com/streamvault/feature/provider/
    api/ProviderBackupPreviewContent.kt
    navigation/ProviderGraph.kt
    navigation/ProviderRoutePatterns.kt
    pairing/ProviderQrPairingManager.kt
    setup/ProviderSetup*.kt
  src/main/res/values*/strings.xml
  src/test/java/com/streamvault/feature/provider/setup/ProviderSetupViewModelTest.kt
  src/androidTest/java/com/streamvault/feature/provider/setup/ProviderSetupCompletionLayerTest.kt
  src/test/resources/boundary-fixtures/

core/ui/src/main/java/com/streamvault/core/ui/progress/ProgressFraction.kt
core/ui/src/test/java/com/streamvault/core/ui/progress/ProgressFractionTest.kt
```

---

### Task 0: Stabilize the existing Phase 5 baseline

**Files:**

- Inspect: every path reported by `git status --short`
- Verify: `app/src/test/java/com/streamvault/app/navigation/AppNavigationCoordinatorTest.kt`
- Verify: `app/src/androidTest/java/com/streamvault/app/ui/AppNavigationContractTest.kt`
- Verify: `app/src/androidTest/java/com/streamvault/app/ui/test/GoldenCaptureTest.kt`
- Verify: `feature/playback/src/androidTest/java/com/streamvault/feature/playback/player/overlay/PlayerOverlayGoldenCaptureTest.kt`
- Verify: `docs/COMPOSE_REDUCTION_PHASE5_PLAYBACK_REPORT.md`
- Verify: `validation/phase5_playback/task10-validation.md`

**Interfaces:** Produces a preserved, reviewable hardening baseline for comparison.

- [ ] **Step 1: Re-inventory the dirty tree**

```powershell
git status --short
git diff --stat
git diff --check
```

Expected: only known navigation, golden, profile, playback-test, and documentation work is present; whitespace validation passes.

- [ ] **Step 2: Verify focused navigation regressions**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.navigation.AppNavigationCoordinatorTest" --tests "com.streamvault.app.navigation.StartupNavigationResolverTest" --tests "com.streamvault.app.navigation.AppRouteCodecTest" --tests "com.streamvault.app.navigation.ExternalDestinationTest" --rerun-tasks --no-daemon --console=plain
```

With an emulator connected:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.AppNavigationContractTest --no-daemon --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.test.GoldenCaptureTest --no-daemon --console=plain
.\gradlew.bat :feature:playback:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.feature.playback.player.overlay.PlayerOverlayGoldenCaptureTest --no-daemon --console=plain
```

Expected: startup ordering, deferred live-player opening, unified-catalog reconciliation, detail-return replacement, and both golden suites pass.

- [ ] **Step 3: Preserve this work separately**

Review and commit existing hardening changes in coherent commits before adding any provider file. Then record:

```powershell
git status --short
git rev-parse HEAD
```

Expected: empty status and an immutable baseline SHA. If cleaning the tree would discard or mix user work, stop and report the overlapping files.

---

### Task 1: Record inventory, rollback point, and deferred measurements

**Files:**

- Create: `validation/phase5_provider/build-baseline/README.md`
- Create: `validation/phase5_provider/build-baseline/source-files.txt`
- Create: `validation/phase5_provider/build-baseline/project-imports.txt`
- Create: `validation/phase5_provider/build-baseline/resources.txt`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`
- Inspect: provider screen, pairing, test, and graph roots

**Interfaces:** Produces the move inventory, rollback SHA, temporary dependency list, and idle-host timing obligation.

- [ ] **Step 1: Capture exact inventory**

Run and preserve outputs in the named files using `apply_patch`:

```powershell
rg --files app/src/main/java/com/streamvault/app/ui/screens/provider app/src/main/java/com/streamvault/app/pairing app/src/test/java/com/streamvault/app/ui/screens/provider app/src/androidTest/java/com/streamvault/app/ui/screens/provider
rg -n "^import com\.streamvault\.(app|data|domain|core)" app/src/main/java/com/streamvault/app/ui/screens/provider app/src/main/java/com/streamvault/app/pairing/ProviderQrPairingManager.kt
rg -n "R\.(string|drawable|plurals|array)\." app/src/main/java/com/streamvault/app/ui/screens/provider
```

Expected: all 22 `ProviderSetup*.kt` files, QR manager, ViewModel test, and completion instrumentation test are accounted for.

- [ ] **Step 2: Extend the transitional ledger**

Add Phase 7-owned rows for:

- `StalkerParamOverride`, `StalkerRequestRule`
- `StalkerAdvancedOptions`, `StalkerAdvancedOptionsCodec`, `StalkerCompatibilityRegistry`
- `ProviderInputSanitizer`
- `XtreamAuthenticationException`, `XtreamNetworkException`, `XtreamParsingException`, `XtreamRequestException`, `XtreamResponseTooLargeException`
- `CredentialDecryptionException`

Name current consumers. Removal direction is domain-owned setup/configuration policy for Stalker/input types and domain-owned provider-access/setup errors for exceptions.

- [ ] **Step 3: Record deferred performance protocol**

Record the clean baseline SHA, representative production edit `ProviderSetupTextField.kt`, representative test edit `ProviderSetupViewModelTest.kt`, five before/after production and test-compile runs, clean/warm guardrails, and same idle-host requirement. The profile subgate is complete, but mark timing rows `OPEN — requires committed post-extraction ref` until the paired samples are captured; never infer a pass from the current mixed dirty tree.

Current execution note (2026-08-27): the idle-host profile subgate passed, but
the paired timing rows remain `OPEN` until a committed post-extraction ref is
available. Do not infer a performance result from the mixed dirty tree.

Later before commands:

```powershell
.\gradlew.bat :app:compileDebugKotlin --profile --no-daemon --console=plain
.\gradlew.bat :app:compileDebugUnitTestKotlin --profile --no-daemon --console=plain
```

- [ ] **Step 4: Commit evidence separately**

```powershell
git add docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md validation/phase5_provider/build-baseline
git commit -m "docs: record provider extraction baseline"
```

---

### Task 2: Create the module and enforce its boundary

**Files:**

- Create: `feature/provider/build.gradle.kts`
- Create: `feature/provider/src/main/AndroidManifest.xml`
- Create: `feature/provider/src/test/resources/boundary-fixtures/AppPackageImport.kt`
- Create: `feature/provider/src/test/resources/boundary-fixtures/FullyQualifiedAppReference.kt`
- Create: `feature/provider/src/test/resources/boundary-fixtures/MainActivityReference.java`
- Create: `feature/provider/src/test/resources/boundary-fixtures/RootNavigation.kt`
- Create: `feature/provider/src/test/resources/boundary-fixtures/RootNavigation.java`
- Modify: `settings.gradle.kts`, `app/build.gradle.kts`, `build.gradle.kts`

**Interfaces:** Adds `:feature:provider` with exact allowed dependencies and a fail-closed guard.

- [ ] **Step 1: Register module and coverage**

Add `include(":feature:provider")`, app implementation dependency, and root Kover dependency.

- [ ] **Step 2: Configure library**

Use namespace `com.streamvault.feature.provider`, SDK 36/25, JVM 17, Compose, Hilt/KSP, Kover, and existing runner. Project dependencies must be exactly:

```kotlin
implementation(project(":core:navigation"))
implementation(project(":core:ui"))
implementation(project(":domain"))
implementation(project(":data"))
```

Add only used external libraries: Compose BOM/UI/Material3/icons, TV foundation/material, Activity Compose, lifecycle runtime/ViewModel Compose, Navigation Compose, Hilt Android/compiler/navigation, coroutines core/android, Core KTX, ZXing, and existing unit/instrumentation libraries. Do not add `:player`, `:feature:playback`, or broad app-only libraries.

- [ ] **Step 3: Add boundary task**

Mirror playback as `:feature:provider:verifyFeatureProviderBoundary`. Require the exact four project paths; scan Kotlin/Java production for `import com.streamvault.app`, `com.streamvault.app`, `MainActivity`, `NavHostController`, and `NavController`; prove fixture detection; write `build/reports/feature-provider-boundary/report.txt`; attach to `check` and tests. Add root aggregator.

- [ ] **Step 4: Prove empty module**

```powershell
.\gradlew.bat :feature:provider:verifyFeatureProviderBoundary :feature:provider:assembleDebug --no-daemon --console=plain
```

- [ ] **Step 5: Commit shell**

```powershell
git add settings.gradle.kts build.gradle.kts app/build.gradle.kts feature/provider
git commit -m "build: add provider feature boundary"
```

---

### Task 3: Establish shared and app-supplied presentation seams

**Files:**

- Move: `app/src/main/java/com/streamvault/app/ui/components/SyncProgressUtil.kt` to `core/ui/src/main/java/com/streamvault/core/ui/progress/ProgressFraction.kt`
- Create: `core/ui/src/test/java/com/streamvault/core/ui/progress/ProgressFractionTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsSyncOverlay.kt`
- Create: `feature/provider/src/main/java/com/streamvault/feature/provider/api/ProviderBackupPreviewContent.kt`
- Modify during integration: `app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt`
- Reuse unchanged: `app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsBackupImportPreviewDialog.kt`

**Interfaces:** Pure shared parser plus provider-owned backup-preview request rendered by app.

- [ ] **Step 1: Test-drive parser**

Test normal ratio, whitespace, missing ratio, zero total, and clamping. Run before implementation and expect failure:

```powershell
.\gradlew.bat :core:ui:testDebugUnitTest --tests "com.streamvault.core.ui.progress.ProgressFractionTest" --no-daemon --console=plain
```

- [ ] **Step 2: Move parser unchanged**

Move regex/function to `com.streamvault.core.ui.progress`; update settings and provider imports; rerun and pass.

- [ ] **Step 3: Define backup-preview seam**

Create:

```kotlin
typealias ProviderBackupPreviewContent =
    @Composable (ProviderBackupPreviewRequest) -> Unit

data class ProviderBackupPreviewRequest(
    val preview: BackupPreview,
    val plan: BackupImportPlan,
    val onDismiss: () -> Unit,
    val onStrategySelected: (BackupConflictStrategy) -> Unit,
    val onImportPreferencesChanged: (Boolean) -> Unit,
    val onImportProvidersChanged: (Boolean) -> Unit,
    val onImportSavedLibraryChanged: (Boolean) -> Unit,
    val onImportPlaybackHistoryChanged: (Boolean) -> Unit,
    val onImportMultiViewChanged: (Boolean) -> Unit,
    val onImportRecordingSchedulesChanged: (Boolean) -> Unit,
    val isImporting: Boolean,
    val onConfirm: () -> Unit,
)
```

The feature constructs the request from existing state/callbacks. `AppNavHost` delegates every field to the existing dialog. Do not duplicate/move the dialog.

- [ ] **Step 4: Compile and commit**

```powershell
.\gradlew.bat :core:ui:testDebugUnitTest :app:compileDebugKotlin --no-daemon --console=plain
git add core/ui app/src/main/java/com/streamvault/app/ui/components/SyncProgressUtil.kt app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsSyncOverlay.kt feature/provider/src/main/java/com/streamvault/feature/provider/api
git commit -m "refactor: define provider presentation seams"
```

---

### Task 4: Move pairing and presentation state

**Files:**

- Move: `app/src/main/java/com/streamvault/app/pairing/ProviderQrPairingManager.kt` to `feature/provider/src/main/java/com/streamvault/feature/provider/pairing/ProviderQrPairingManager.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/provider/ProviderSetupViewModel.kt` to `feature/provider/src/main/java/com/streamvault/feature/provider/setup/ProviderSetupViewModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/provider/ProviderSetupAdvancedOptionsModel.kt` to `feature/provider/src/main/java/com/streamvault/feature/provider/setup/ProviderSetupAdvancedOptionsModel.kt`
- Move: `app/src/main/java/com/streamvault/app/ui/screens/provider/ProviderSetupFormModels.kt` to `feature/provider/src/main/java/com/streamvault/feature/provider/setup/ProviderSetupFormModels.kt`
- Move: `app/src/test/java/com/streamvault/app/ui/screens/provider/ProviderSetupViewModelTest.kt` to `feature/provider/src/test/java/com/streamvault/feature/provider/setup/ProviderSetupViewModelTest.kt`

**Interfaces:** Feature owns QR pairing state/manager and provider setup state with unchanged Hilt scopes and dependencies.

- [ ] **Step 1: Move test first and show red**

Change only package/imports, run feature test, expect missing production symbols.

```powershell
.\gradlew.bat :feature:provider:testDebugUnitTest --tests "com.streamvault.feature.provider.setup.ProviderSetupViewModelTest" --no-daemon --console=plain
```

- [ ] **Step 2: Move QR pairing mechanically**

Preserve annotations, socket limits, session duration, token validation, response behavior, QR size, coroutine scope, and provider commands exactly.

- [ ] **Step 3: Move ViewModel/models mechanically**

Preserve constructor dependencies, StateFlow, edit initialization, defaults, Drive import, completion state, cancellation, pairing lifecycle, and error mapping.

- [ ] **Step 4: Run and commit**

```powershell
.\gradlew.bat :feature:provider:testDebugUnitTest --tests "com.streamvault.feature.provider.setup.ProviderSetupViewModelTest" --rerun-tasks --no-daemon --console=plain
git add app/src/main/java/com/streamvault/app/pairing app/src/main/java/com/streamvault/app/ui/screens/provider app/src/test/java/com/streamvault/app/ui/screens/provider feature/provider
git commit -m "refactor: move provider setup state to feature"
```

---

### Task 5: Move UI, resources, and instrumentation tests

**Files:**

- Move to `feature/provider/src/main/java/com/streamvault/feature/provider/setup/`: `ProviderSetupActionButtons.kt`, `ProviderSetupAdvancedOptions.kt`, `ProviderSetupCompletionHost.kt`, `ProviderSetupFormPrimitives.kt`, `ProviderSetupImportDialogs.kt`, `ProviderSetupImportSupport.kt`, `ProviderSetupJellyfinForm.kt`, `ProviderSetupPairingCard.kt`, `ProviderSetupPasswordGlyph.kt`, `ProviderSetupPolicyOptions.kt`, `ProviderSetupProviderForms.kt`, `ProviderSetupScreen.kt`, `ProviderSetupSourceForms.kt`, `ProviderSetupSourcePanel.kt`, `ProviderSetupSourceSelector.kt`, `ProviderSetupStalkerCompatibilitySelector.kt`, `ProviderSetupStalkerRules.kt`, `ProviderSetupSyncDialog.kt`, and `ProviderSetupTextField.kt`
- Move: `app/src/androidTest/java/com/streamvault/app/ui/screens/provider/ProviderSetupCompletionLayerTest.kt` to `feature/provider/src/androidTest/java/com/streamvault/feature/provider/setup/ProviderSetupCompletionLayerTest.kt`
- Create/modify: `feature/provider/src/main/res/values*/strings.xml` and `app/src/main/res/values*/strings.xml`

**Interfaces:** Complete presentation ownership with launchers, focus, typing, semantics, and completion preserved.

- [ ] **Step 1: Move instrumentation test first and show red**

Run the moved test before production move; expect missing symbols.

```powershell
.\gradlew.bat :feature:provider:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.feature.provider.setup.ProviderSetupCompletionLayerTest --no-daemon --console=plain
```

- [ ] **Step 2: Move remaining UI mechanically**

Replace app R with feature R; pairing imports with feature pairing; progress import with core UI; device helper with `com.streamvault.core.ui.device.rememberIsTelevisionDevice`; direct backup dialog with the injected request renderer.

Keep all three Activity Result contracts: playlist `OpenDocument`, backup `OpenDocument`, Drive `StartActivityForResult`.

- [ ] **Step 3: Move resources locale by locale**

Inventory:

```powershell
rg -o "R\.(string|drawable|plurals|array)\.[A-Za-z0-9_]+" feature/provider/src/main/java | Sort-Object -Unique
```

Copy provider-owned keys and every existing translation. Keep settings backup-preview strings app-owned. Remove an app key only after a repo-wide exact-key scan shows no app consumer. Do not rename keys or alter translations.

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat :feature:provider:verifyFeatureProviderBoundary :feature:provider:testDebugUnitTest :feature:provider:assembleDebug --no-daemon --console=plain
.\gradlew.bat :feature:provider:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.feature.provider.setup.ProviderSetupCompletionLayerTest --no-daemon --console=plain
git add app/src/main/java/com/streamvault/app/ui/screens/provider app/src/androidTest/java/com/streamvault/app/ui/screens/provider app/src/main/res feature/provider
git commit -m "refactor: move provider presentation to feature"
```

---

### Task 6: Move graph registration and preserve route compatibility

**Files:**

- Create: `feature/provider/src/main/java/com/streamvault/feature/provider/navigation/ProviderRoutePatterns.kt`
- Move: `app/src/main/java/com/streamvault/app/navigation/graph/ProviderGraph.kt` to `feature/provider/src/main/java/com/streamvault/feature/provider/navigation/ProviderGraph.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppRouteCodec.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt`
- Verify: `app/src/test/java/com/streamvault/app/navigation/AppRouteCodecTest.kt`
- Verify: `app/src/test/java/com/streamvault/app/navigation/ExternalDestinationTest.kt`
- Verify: `app/src/androidTest/java/com/streamvault/app/compat/PlatformCompatibilityMatrixTest.kt`

**Interfaces:** Controller-free feature graph with unchanged route and arguments.

- [ ] **Step 1: Add route constant**

```kotlin
object ProviderRoutePatterns {
    const val PROVIDER_SETUP =
        "provider_setup?providerId={providerId}&importUri={importUri}"
}
```

Alias `AppRoutePatterns.PROVIDER_SETUP` to it so route text remains identical.

- [ ] **Step 2: Move graph**

Expose:

```kotlin
fun NavGraphBuilder.registerProviderGraph(
    actions: NavigationActions,
    startupReady: Boolean,
    onStartupNavigationRequested: (AppDestination) -> Unit,
    backupPreviewContent: ProviderBackupPreviewContent,
)
```

Preserve `providerId = -1L`, empty `importUri`, decoding, `actions.back()`, `dropUnlessResumed`, and `AppDestination.ProviderSetup()`. Retain currently unused `startupReady`; removal is separate cleanup.

- [ ] **Step 3: Wire app renderer**

Import feature graph in `AppNavHost`; pass navigation callbacks unchanged and adapt request to `BackupImportPreviewDialog` field-for-field.

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.navigation.AppRouteCodecTest" --tests "com.streamvault.app.navigation.ExternalDestinationTest" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:compileDebugKotlin :feature:provider:verifyFeatureProviderBoundary --no-daemon --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.compat.PlatformCompatibilityMatrixTest --no-daemon --console=plain
git add app/src/main/java/com/streamvault/app/navigation feature/provider/src/main/java/com/streamvault/feature/provider/navigation
git commit -m "refactor: register provider graph from feature"
```

---

### Task 7: Remove legacy ownership and audit dependencies

**Files:**

- Remove only after verification: empty app provider/pairing directories
- Modify: app/feature Gradle files and transitional ledger

**Interfaces:** One production owner and only justified dependencies.

- [ ] **Step 1: Scan**

```powershell
rg -n "com\.streamvault\.app\.(ui\.screens\.provider|pairing\.ProviderQrPairing)" --glob "*.kt" --glob "*.java" .
rg -n "com\.streamvault\.app|MainActivity|NavHostController|NavController" feature/provider/src/main --glob "*.kt" --glob "*.java"
rg -n "^import com\.streamvault\.data" feature/provider/src/main --glob "*.kt" --glob "*.java"
```

Expected: no legacy package, no forbidden feature import, and every data import matches the ledger.

- [ ] **Step 2: Audit app dependencies**

Remove a project/library dependency only after all app source sets show no direct consumer or generated/runtime requirement. App retains provider feature; provider retains temporary data.

- [ ] **Step 3: Remove only confirmed-empty directories and verify**

```powershell
.\gradlew.bat verifyModuleBoundaries verifyFeaturePlaybackBoundary verifyFeatureProviderBoundary :feature:provider:dependencies --configuration debugRuntimeClasspath --no-daemon --console=plain
```

- [ ] **Step 4: Commit cleanup**

```powershell
git add app feature/provider docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md
git commit -m "refactor: remove legacy provider ownership"
```

---

### Task 8: Automated structural and regression validation

**Files:**

- Create: `validation/phase5_provider/task8-automated-validation.md`

**Interfaces:** Reproducible build, test, boundary, lint, package, and graph evidence.

- [ ] **Step 1: Feature verification**

```powershell
.\gradlew.bat :feature:provider:verifyFeatureProviderBoundary :feature:provider:testDebugUnitTest :feature:provider:assembleDebug :feature:provider:lintDebug --no-daemon --console=plain
```

- [ ] **Step 2: App integration**

```powershell
.\gradlew.bat verifyModuleBoundaries verifyFeaturePlaybackBoundary verifyFeatureProviderBoundary :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
```

Classify an existing failure as pre-existing only if reproduced at the Task 0 SHA.

- [ ] **Step 3: Connected suites**

```powershell
.\gradlew.bat :feature:provider:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.feature.provider.setup.ProviderSetupCompletionLayerTest --no-daemon --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.AppNavigationContractTest --no-daemon --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.compat.PlatformCompatibilityMatrixTest --no-daemon --console=plain
.\gradlew.bat :feature:provider:connectedDebugAndroidTest :app:connectedDebugAndroidTest --no-daemon --console=plain
```

- [ ] **Step 4: Graph update**

```powershell
graphify update .
graphify query "How does the provider feature relate to app navigation, core navigation, core UI, domain, and data after extraction?"
```

Expected: app registers feature, no app/other-feature edge from provider, temporary data edges match ledger.

- [ ] **Step 5: Record and commit**

Include commands, timestamps, counts, failures, boundary report, source counts, and limitations.

```powershell
git add validation/phase5_provider graphify-out
git commit -m "test: validate provider feature extraction"
```

---

### Task 9: Provider runtime acceptance

**Files:**

- Create: `validation/phase5_provider/task9-runtime-validation.md`

**Interfaces:** Validates roadmap gate: create/edit/import, file launchers, focus, typing, completion, pairing.

- [ ] **Step 1: Fresh install**

```powershell
.\gradlew.bat :app:installDebug --no-daemon --console=plain
adb logcat -c
adb shell am force-stop com.streamvault.app
adb shell monkey -p com.streamvault.app 1
```

- [ ] **Step 2: Create journeys**

Exercise Xtream, Stalker, M3U, and Jellyfin selection. Verify initial focus, typing, password visibility, validation, advanced options, Back/cancel, and successful or expected fixture-limited submission. Never record credentials/full URLs.

- [ ] **Step 3: Edit journey**

Open provider setup through the app for a sanitized test provider; verify population, harmless edit/save, callback/return, persistence, and cancel leaving stored state unchanged.

- [ ] **Step 4: Launcher journeys**

Verify playlist and backup `OpenDocument` launchers, MIME behavior, return/cancel, single URI delivery, Drive `StartActivityForResult`, focus return, and cold-start `importUri`.

- [ ] **Step 5: Backup import journey**

Use a non-sensitive test backup. Verify preview counts, strategy, toggles, dismiss, confirm, progress, completion, and restored-provider onboarding through the app renderer.

- [ ] **Step 6: Focus and typing**

Record initial focus, D-pad traversal, insertion/deletion, IME Next/Done, dialog/launcher focus restoration, and no double activation on phone/TV form factors available. Capture transition screenshots, not only a final screenshot.

- [ ] **Step 7: QR pairing**

Validate start/stop, QR/code state, expiry/cancel, and—when LAN permits—one sanitized second-device submission. If LAN/credentials are unavailable, leave that exact gate open; QR rendering alone is not acceptance.

- [ ] **Step 8: Evidence**

```powershell
adb logcat -d -v time
adb shell dumpsys activity activities
```

Record device/API/form factor, journeys, launcher results, focus/typing, sanitized errors, and screenshots. Never store tokens, passwords, backup contents, full URLs, or QR payloads.

---

### Task 10: Idle-host performance/profile gates and report

**Files:**

- Create: `validation/phase5_provider/performance-rerun.md`
- Create: `docs/COMPOSE_REDUCTION_PHASE5_PROVIDER_REPORT.md`
- Modify: Phase 5 roadmap and main architecture plan
- Regenerate when available: app generated baseline/startup profiles

**Interfaces:** Final before/after evidence, release-like/profile evidence, rollback record, and honest Phase 5 status.

- [ ] **Step 1: Record clean post SHA**

```powershell
git status --short
git rev-parse HEAD
```

Name both pre/post SHAs in the report.

- [ ] **Step 2: Five paired production-edit runs on idle host**

Using clean checkouts of both SHAs, make/restore the same comment-only edit in the before app-owned and after feature-owned `ProviderSetupTextField.kt`. Run five samples:

```powershell
.\gradlew.bat :app:compileDebugKotlin --profile --no-daemon --console=plain
.\gradlew.bat :feature:provider:compileDebugKotlin :app:compileDebugKotlin --profile --no-daemon --console=plain
```

Record wall time, task states, profile path, host load, and unrelated compilation.

- [ ] **Step 3: Five paired test-edit runs**

Make/restore the same edit in before/after `ProviderSetupViewModelTest.kt` and run five:

```powershell
.\gradlew.bat :app:compileDebugUnitTestKotlin --profile --no-daemon --console=plain
.\gradlew.bat :feature:provider:compileDebugUnitTestKotlin --profile --no-daemon --console=plain
```

- [ ] **Step 4: Clean/warm guardrails**

Run standard clean debug once and same no-change build once for each clean checkout. Compare only same-machine, same-load runs.

- [x] **Step 5: Refresh profiles and release-like build**

Use the existing Baseline Profile generator; do not hand-edit generated profiles.

```powershell
rg -n "com/streamvault/app/(ui/screens/provider|pairing/ProviderQrPairing)" app/src/main/generated/baselineProfiles
.\gradlew.bat :app:assembleRelease --no-daemon --console=plain
```

Result (2026-08-27): generation passed (10 profile tests, eight macrobenchmark
assumptions skipped), no stale provider package paths remain, and release
assembly passed. The timing and manual-journey gates remain open.

- [ ] **Step 6: Publish report**

Include ownership, SHAs/rollback, boundaries, source/test counts, all test/build/lint results, runtime journeys, five-pair idle-host results, profile result, data ledger, independent playback gates, failures, and limitations.

- [ ] **Step 7: Update status honestly**

Provider is structurally complete only after Tasks 2–8 pass and accepted only after Tasks 9–10 pass. Do not mark Phase 5 complete: settings, live, catalog, optional-system evaluation, and open playback gates remain.

- [ ] **Step 8: Final verification and commit**

```powershell
.\gradlew.bat verifyModuleBoundaries verifyFeaturePlaybackBoundary verifyFeatureProviderBoundary :feature:provider:check :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon --console=plain
graphify update .
git diff --check
git status --short
```

Then review evidence/generated files and commit:

```powershell
git add docs validation/phase5_provider app/src/main/generated/baselineProfiles graphify-out
git commit -m "docs: report provider feature extraction"
```

## Exit criteria

The provider slice is accepted only when:

- feature owns setup/edit/import/pairing UI, state, resources, tests, and graph;
- exact dependency boundary passes with no app/controller/other-feature imports;
- route/external compatibility is byte-for-byte unchanged;
- feature tests pass independently and app integration/build checks pass;
- create/edit/import/launchers/Drive/focus/typing/completion/pairing have evidence or an explicit open blocker;
- timing samples are rerun on an idle host using the two SHAs;
- generated profiles have no stale provider paths and release-like build passes;
- ledger and provider report are current;
- playback and later Phase 5 gates remain open unless independently accepted.
