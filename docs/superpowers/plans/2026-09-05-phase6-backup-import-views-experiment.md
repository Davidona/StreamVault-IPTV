# Phase 6 Backup Import Views Experiment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reversible Compose-versus-classic-Views experiment for the backup-import preview surface and produce a measured keep-or-drop decision.

**Architecture:** Keep `BackupImportPreviewDialog` and all existing backup state/actions unchanged as the Compose baseline. Add an XML/classic-Views dialog in `:feature:settings` that consumes the same `BackupPreview`, `BackupImportPlan`, and callbacks, then exercise both implementations through a debug-only deterministic host activity and paired Macrobenchmark journeys. Do not wire Views into the real Settings flow until the experiment report proves it is worth keeping.

**Tech Stack:** Kotlin/JVM 17, Android SDK 36/min SDK 25, Android Views/XML, existing `:feature:settings` and `:app` modules, View Binding, JUnit4/Truth/Robolectric, Compose instrumentation, Espresso, Macrobenchmark/UiAutomator, API 36 TV emulator, and Graphify.

**Spec:** `docs/superpowers/specs/2026-09-05-phase6-backup-import-views-experiment-design.md`

## Global Constraints

- Preserve `BackupPreview`, `BackupImportPlan`, `SettingsBackupActions`, `SettingsViewModel`, and backup execution behavior exactly.
- Keep the current Compose dialog as the production/default path throughout this experiment.
- The debug benchmark host must use a deterministic in-memory fixture and must not access network, database, file-picker, Drive, or provider-sync code.
- The Views candidate must preserve TV focus, D-pad navigation, Back behavior, accessibility roles, RTL behavior, large-text layout, and disabled-import behavior.
- Use the existing API 36 `AOSP_TV_on_x86` / `Television_1080p` emulator for connected and Macrobenchmark validation; diagnostic emulator measurements are not physical-device release claims.
- Run `graphify update .` after modifying Kotlin, XML, Gradle, manifest, or other code/resource files.
- Do not delete or replace the Compose implementation before the benchmark and connected acceptance gates pass.

### Task 1: Add a deterministic debug benchmark host with the Compose baseline

**Files:**
- Create: `app/src/debug/AndroidManifest.xml`
- Create: `app/src/debug/java/com/streamvault/app/benchmark/BackupPreviewBenchmarkFixture.kt`
- Create: `app/src/debug/java/com/streamvault/app/benchmark/BackupPreviewBenchmarkActivity.kt`
- Create: `app/src/androidTest/java/com/streamvault/app/benchmark/BackupPreviewBenchmarkActivityTest.kt`

**Interfaces:**
- Produces `com.streamvault.app.benchmark.BackupPreviewBenchmarkActivity` with explicit presentation extra `presentation=compose`.
- Produces fixture values containing non-zero counts/conflicts and a default `BackupImportPlan` with at least one enabled section.
- The activity exposes the visible title `Review Backup Import` and version subtitle to UiAutomator/Espresso.

- [ ] **Step 1: Write the failing host test**

Create an Android test that launches the debug-only activity with `presentation=compose`, waits for `Review Backup Import`, and asserts the preview title and one summary label are visible through UiAutomator. UiAutomator is required for the Compose baseline because the text is exposed through Compose semantics rather than native `TextView` children.

```kotlin
@RunWith(AndroidJUnit4::class)
class BackupPreviewBenchmarkActivityTest {
    @Test
    fun composeModeRendersDeterministicPreview() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            BackupPreviewBenchmarkActivity::class.java,
        ).putExtra(BackupPreviewBenchmarkActivity.EXTRA_PRESENTATION, "compose")

        ActivityScenario.launch<BackupPreviewBenchmarkActivity>(intent).use {
            val device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation(),
            )
            assertThat(device.wait(Until.hasObject(By.text("Review Backup Import")), 20_000L))
                .isTrue()
            assertThat(device.hasObject(By.text("Preferences"))).isTrue()
        }
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails for the missing activity**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.benchmark.BackupPreviewBenchmarkActivityTest' `
  --no-daemon --console=plain --warning-mode=none
```

Expected: compilation or test-discovery failure because the debug activity and fixture do not exist.

- [ ] **Step 3: Add the fixture and Compose-only activity**

Implement a fixture factory returning one stable `BackupPreview` and `BackupImportPlan`. The activity must parse the presentation extra, render the current `BackupImportPreviewDialog` inside `StreamVaultTheme`, and keep a local `mutableStateOf(BackupImportPlan)` so toggle/strategy callbacks update the rendered Compose state. Use no ViewModel or Hilt dependency.

```kotlin
internal const val EXTRA_PRESENTATION = "presentation"
internal const val PRESENTATION_COMPOSE = "compose"
internal const val PRESENTATION_VIEWS = "views"

private var plan by mutableStateOf(BackupPreviewBenchmarkFixture.plan)

BackupImportPreviewDialog(
    preview = BackupPreviewBenchmarkFixture.preview,
    plan = plan,
    onDismiss = ::finish,
    onStrategySelected = { plan = plan.copy(conflictStrategy = it) },
    onImportPreferencesChanged = { plan = plan.copy(importPreferences = it) },
    onImportProvidersChanged = { plan = plan.copy(importProviders = it) },
    onImportSavedLibraryChanged = { plan = plan.copy(importSavedLibrary = it) },
    onImportPlaybackHistoryChanged = { plan = plan.copy(importPlaybackHistory = it) },
    onImportMultiViewChanged = { plan = plan.copy(importMultiViewPresets = it) },
    onImportRecordingSchedulesChanged = { plan = plan.copy(importRecordingSchedules = it) },
    onConfirm = { finish() },
)
```

Register the activity only in `app/src/debug/AndroidManifest.xml` with an explicit action and `android:exported="true"` so the benchmark instrumentation can launch it. The Views mode may fail with a clear unsupported-mode exception until Task 3.

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same connected test command. Expected: the Compose host test passes on the API 36 TV emulator.

- [ ] **Step 5: Commit the baseline host**

```powershell
git add app/src/debug app/src/androidTest/java/com/streamvault/app/benchmark
git commit -m "test: add compose backup preview benchmark host"
```

### Task 2: Add the classic Views layout and controller with unit coverage

**Files:**
- Modify: `feature/settings/build.gradle.kts: buildFeatures`
- Create: `feature/settings/src/main/res/layout/settings_backup_import_preview_views.xml`
- Create: `feature/settings/src/main/res/values/settings_backup_preview_views.xml`
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/BackupImportPreviewViewController.kt`
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/BackupImportPreviewViewDialog.kt`
- Create: `feature/settings/src/test/java/com/streamvault/feature/settings/presentation/BackupImportPreviewViewControllerTest.kt`

**Interfaces:**

```kotlin
class BackupImportPreviewViewController(
    private val binding: SettingsBackupImportPreviewViewsBinding,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val onDismiss: () -> Unit,
        val onStrategySelected: (BackupConflictStrategy) -> Unit,
        val onImportPreferencesChanged: (Boolean) -> Unit,
        val onImportProvidersChanged: (Boolean) -> Unit,
        val onImportSavedLibraryChanged: (Boolean) -> Unit,
        val onImportPlaybackHistoryChanged: (Boolean) -> Unit,
        val onImportMultiViewChanged: (Boolean) -> Unit,
        val onImportRecordingSchedulesChanged: (Boolean) -> Unit,
        val onConfirm: () -> Unit,
    )

    fun render(preview: BackupPreview, plan: BackupImportPlan, isImporting: Boolean)
    fun requestInitialFocus(): Boolean
}

class BackupImportPreviewViewDialog(
    context: Context,
    callbacks: BackupImportPreviewViewController.Callbacks,
) {
    fun show(preview: BackupPreview, plan: BackupImportPlan, isImporting: Boolean = false)
    fun render(preview: BackupPreview, plan: BackupImportPlan, isImporting: Boolean)
    fun dismiss()
}
```

- [ ] **Step 1: Write failing controller tests**

Use the existing Robolectric dependency and a minimal test `Activity` to inflate the binding. Cover these observable behaviors: preview labels/counts render, Import is disabled when no section is enabled, strategy selection invokes the callback, and a switch invokes the matching callback.

```kotlin
@Test
fun renderDisablesImportWhenNoSectionIsSelected() {
    controller.render(preview, BackupImportPlan(), isImporting = false)

    assertThat(binding.confirmButton.isEnabled).isFalse()
}

@Test
fun strategySelectionEmitsReplaceExisting() {
    controller.render(preview, BackupImportPlan(), isImporting = false)

    binding.replaceExistingButton.performClick()

    assertThat(selectedStrategy).isEqualTo(BackupConflictStrategy.REPLACE_EXISTING)
}
```

- [ ] **Step 2: Run the unit tests and verify they fail**

Run:

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest `
  --tests 'com.streamvault.feature.settings.presentation.BackupImportPreviewViewControllerTest' `
  --no-daemon --console=plain --warning-mode=none
```

Expected: compilation failure because View Binding, the XML IDs, and the controller do not exist.

- [ ] **Step 3: Enable View Binding and add the XML surface**

Set `viewBinding = true` alongside the existing `compose = true` flag. Create the layout with stable IDs for the title/subtitle, six summary rows, two strategy controls, six switches, the scroll container, the scroll hint, and Cancel/Import buttons. Use standard Android focusable controls and a feature-local dialog style with transparent window background, rounded surface, existing StreamVault colors, and TV-sized padding.

The layout must keep the body inside a bounded `ScrollView`, put the first strategy control at the start of the focus order, and place the footer buttons after the body. All user-facing text must come from the existing `feature/settings` string resources.

- [ ] **Step 4: Implement the controller and dialog wrapper**

Implement `render` as the single state-to-Views update point. Clear and reinstall checked-change listeners before assigning switch state, update summary text/counts from `BackupPreview`, derive Import enabled state from the six plan flags, disable all interactive controls while importing, and expose callbacks without calling domain or ViewModel code.

Implement the dialog wrapper with `android.app.Dialog` and the View Binding layout. Match the Compose dialog's TV dimensions (`0.58f` width and `0.78f` height on TV), use a bounded body, request initial focus after show, delay interaction enablement by 500 ms, and allow Back/outside dismissal only after that guard is released. The wrapper must expose `render` so the debug host can update the same dialog after a callback.

- [ ] **Step 5: Run the focused unit tests and verify they pass**

Run the same `:feature:settings:testDebugUnitTest --tests ...BackupImportPreviewViewControllerTest` command. Expected: all controller tests pass.

- [ ] **Step 6: Commit the Views surface**

```powershell
git add feature/settings/build.gradle.kts feature/settings/src/main/res feature/settings/src/main/java/com/streamvault/feature/settings/presentation/BackupImportPreviewViewController.kt feature/settings/src/main/java/com/streamvault/feature/settings/presentation/BackupImportPreviewViewDialog.kt feature/settings/src/test/java/com/streamvault/feature/settings/presentation/BackupImportPreviewViewControllerTest.kt
git commit -m "feat: add views backup import preview experiment"
```

### Task 3: Add the Views benchmark mode and paired connected behavior tests

**Files:**
- Modify: `app/src/debug/java/com/streamvault/app/benchmark/BackupPreviewBenchmarkActivity.kt`
- Create: `app/src/androidTest/java/com/streamvault/app/benchmark/BackupPreviewPresentationMatrixTest.kt`
- Create: `app/src/androidTest/java/com/streamvault/app/benchmark/BackupPreviewViewGoldenCapture.kt`
- Create: `app/src/androidTest/assets/ui-goldens/backup_preview_views.png` after visual review

**Interfaces:**
- `presentation=views` launches `BackupImportPreviewViewDialog` with the same fixture and callback state updates as Compose mode.
- The connected matrix test can select `compose` or `views`, drive the same D-pad/toggle/Back path, and assert visible state through Espresso or UiAutomator.

- [ ] **Step 1: Add failing Views-mode and behavior tests**

Extend the host test to launch `presentation=views` and assert the same title, summary label, disabled Import state, strategy change, switch change, and Back dismissal. Keep Compose and Views assertions in the same test class so the interaction matrix remains visibly paired.

```kotlin
@Test
fun viewsModeSupportsImportPlanInteraction() {
    launchMode("views")

    onView(withId(R.id.replaceExistingButton)).perform(click())
    onView(withId(R.id.importProvidersSwitch)).perform(click())
    onView(withId(R.id.cancelButton)).perform(click())

    onView(withText("Review Backup Import")).check(doesNotExist())
}
```

- [ ] **Step 2: Run the connected matrix and verify Views mode fails before wiring**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.benchmark.BackupPreviewPresentationMatrixTest' `
  --no-daemon --console=plain --warning-mode=none
```

Expected: Compose mode passes and Views mode fails because the activity still rejects or does not render the Views presentation.

- [ ] **Step 3: Wire Views mode to the same local state contract**

Create the `BackupImportPreviewViewDialog` from the activity, provide the same callbacks used by Compose, update the local `BackupImportPlan` in each callback, and call `dialog.render(...)` after every update. Keep `isImporting=false` for the deterministic host; import execution is not part of this experiment.

- [ ] **Step 4: Add connected behavior and accessibility assertions**

Assert both modes for visible title/counts, selected conflict strategy, switch state, Import enabled/disabled state, Back dismissal, and focus progression. Use `contentDescription`/text-role assertions for Views and existing Compose semantics assertions for Compose. Add RTL and large-font configuration coverage to the host test if the connected runner supports the repository's existing locale/font setup; otherwise run the ADB configuration checks manually and record their output in the report.

- [ ] **Step 5: Capture and review the Views golden**

Use `UiDevice.takeScreenshot()` after the dialog reaches its first stable frame. Save the reviewed output as `app/src/androidTest/assets/ui-goldens/backup_preview_views.png`; compare it side-by-side with a Compose capture at the same 1920x1080 emulator configuration. Reject the candidate if the dialog is clipped, focus styling is absent, footer actions are hidden, or text/counts differ.

- [ ] **Step 6: Run the connected tests and verify they pass**

Run the same focused connected command. Expected: both modes pass the behavior matrix and the reviewed Views capture is stable.

- [ ] **Step 7: Commit the paired host tests**

```powershell
git add app/src/debug/java/com/streamvault/app/benchmark/BackupPreviewBenchmarkActivity.kt app/src/androidTest/java/com/streamvault/app/benchmark app/src/androidTest/assets/ui-goldens/backup_preview_views.png
git commit -m "test: exercise backup preview compose and views modes"
```

### Task 4: Add paired Macrobenchmark journeys

**Files:**
- Modify: `benchmark/src/main/java/com/streamvault/benchmark/BenchmarkConfig.kt`
- Modify: `benchmark/src/main/java/com/streamvault/benchmark/StreamVaultMacrobenchmark.kt`

**Interfaces:**
- `launchBackupPreviewMode(mode: String)` starts the debug host explicitly and waits for `Review Backup Import`.
- `exerciseBackupPreview()` drives the identical focus, strategy, toggle, and Back journey for either presentation.
- Four benchmark methods report separate Compose/Views initial-display and interaction results.

- [ ] **Step 1: Add the benchmark helper tests/build checks first**

Add compile-visible constants and helper signatures, then run the benchmark Kotlin compilation so missing activity names, imports, and Macrobenchmark APIs fail before the full connected run.

```powershell
.\gradlew.bat :benchmark:compileBenchmarkBenchmarkKotlin `
  --no-daemon --console=plain --warning-mode=none
```

Expected before implementation: failure for the missing `launchBackupPreviewMode` and `exerciseBackupPreview` helpers.

- [ ] **Step 2: Implement explicit host launch and deterministic interaction**

Add the activity component name and mode extra in `BenchmarkConfig.kt`. Launch with `startActivityAndWait(Intent)` or the existing `UiDevice` shell pattern, wait for the title, move focus with D-pad, select the alternate strategy, toggle a section, verify Import becomes enabled, and press Back after returning focus to Cancel.

Keep all fixed delays and timeouts in `BenchmarkConfig.kt`; do not add coordinate taps or network waits. Use resource IDs for Views only where necessary and text/description selectors for the shared visible contract.

- [ ] **Step 3: Add paired benchmark methods**

Add Compose and Views interaction methods using `FrameTimingMetric()` and a trace marker for the measured mode. Add Compose and Views initial-display methods using the same cold/warm policy and startup metric supported by the current Macrobenchmark dependency. If `MemoryUsageMetric` is available in the resolved dependency, include it in both interaction methods; otherwise capture PSS in the validation step using `adb shell dumpsys meminfo` and record that the metric was external.

```kotlin
@Test
fun backupPreviewComposeInteraction() = measureBackupPreviewInteraction("compose")

@Test
fun backupPreviewViewsInteraction() = measureBackupPreviewInteraction("views")

private fun measureBackupPreviewInteraction(mode: String) = benchmarkRule.measureRepeated(
    packageName = SEEDED_DEBUG_PACKAGE,
    metrics = listOf(FrameTimingMetric()),
    startupMode = StartupMode.WARM,
    iterations = BENCHMARK_ITERATIONS,
    setupBlock = { launchBackupPreviewMode(mode) },
) {
    exerciseBackupPreview()
}
```

- [ ] **Step 4: Compile and run the focused benchmark selector**

Run:

```powershell
.\gradlew.bat :benchmark:connectedBenchmarkBenchmarkAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark' `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.benchmark.StreamVaultMacrobenchmark#backupPreviewComposeInteraction' `
  --no-daemon --console=plain --warning-mode=none

.\gradlew.bat :benchmark:connectedBenchmarkBenchmarkAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark' `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.benchmark.StreamVaultMacrobenchmark#backupPreviewViewsInteraction' `
  --no-daemon --console=plain --warning-mode=none
```

Expected: each selector completes the configured repeated iterations and writes Macrobenchmark output under `benchmark/build/outputs`.

- [ ] **Step 5: Commit the benchmark journeys**

```powershell
git add benchmark/src/main/java/com/streamvault/benchmark/BenchmarkConfig.kt benchmark/src/main/java/com/streamvault/benchmark/StreamVaultMacrobenchmark.kt
git commit -m "perf: benchmark backup preview compose and views"
```

### Task 5: Run the full experiment gates and document the decision

**Files:**
- Create: `docs/COMPOSE_REDUCTION_PHASE6_BACKUP_VIEWS_REPORT.md`
- Modify: none unless a test or benchmark defect is found

**Interfaces:**
- Consumes focused unit-test results, connected behavior/golden results, Macrobenchmark outputs, and external PSS samples.
- Produces a checked-in report with device/configuration, iteration count, Compose and Views medians/P95s, functional results, visual review, and the keep/drop decision.

- [ ] **Step 1: Run focused unit and module validation**

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest `
  --tests 'com.streamvault.feature.settings.presentation.BackupImportPreviewViewControllerTest' `
  :feature:settings:verifyFeatureSettingsBoundary `
  :feature:settings:lintDebug `
  --no-daemon --console=plain --warning-mode=none
```

Expected: the new controller tests, feature boundary, and lint pass. Existing unrelated baseline findings remain governed by the repository lint baseline.

- [ ] **Step 2: Run the paired connected behavior suite**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.benchmark.BackupPreviewPresentationMatrixTest' `
  --no-daemon --console=plain --warning-mode=none
```

Expected: Compose and Views modes pass the behavior matrix, the dialog can be dismissed, and the reviewed 1920x1080 output remains visually equivalent.

- [ ] **Step 3: Capture paired external PSS samples when the device is idle**

For each mode, launch the debug host, wait five seconds, and record the target process line:

```powershell
adb shell dumpsys meminfo com.streamvault.app.debug | Select-String 'TOTAL PSS'
```

Use the same fresh-process/reset policy for both modes and record at least five samples per mode. Report median PSS; do not compare one cold sample to one warm sample.

- [ ] **Step 4: Run the complete focused Macrobenchmark pair**

Run both interaction selectors from Task 4 and the two initial-display selectors. Save the generated result paths and extract median/P95 frame timing, startup timing, and memory metrics when present.

- [ ] **Step 5: Apply the decision rule**

Keep Views only when it improves a primary performance metric by approximately 10% or more across repeated paired runs, introduces no material P95/memory regression, passes the complete behavior/focus/accessibility/RTL/large-text/visual matrix, and leaves a comprehensible host boundary. Otherwise mark the candidate dropped and keep Compose as the production choice.

- [ ] **Step 6: Write the experiment report with actual evidence**

The report must include:

```markdown
# Phase 6 Backup Import Views Experiment Report

## Environment
- Commit:
- Device/API/resolution:
- APK variant and compilation mode:
- Iterations per mode:

## Results
| Mode | Initial display median/P95 | Interaction frame median/P95 | Median PSS | Functional result |
|---|---:|---:|---:|---|

## Behavior and visual review

## Decision

## Follow-up
```

Replace every field with the actual recorded value before committing. If Views is dropped, explicitly record why and do not start production integration. If Views wins, record that production wiring requires a separate reviewed change which keeps Compose behind the rollback path.

- [ ] **Step 7: Update the graph and verify the final tree**

Run:

```powershell
graphify update .
.\gradlew.bat :feature:settings:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug `
  --no-daemon --console=plain --warning-mode=none
git diff --check
git status --short
```

Expected: Graphify reflects the new experiment code, focused unit tests and debug assembly pass, no whitespace errors exist, and only intended files are modified.

- [ ] **Step 8: Commit the report and graph refresh**

```powershell
git add docs/COMPOSE_REDUCTION_PHASE6_BACKUP_VIEWS_REPORT.md graphify-out
git commit -m "docs: record phase 6 backup views experiment"
```

## Execution boundary

This plan intentionally ends after the keep/drop decision. If Views wins, create a follow-up implementation plan for the real Settings integration, development switch, connected Settings journey, and removal of the Compose path after a release-like validation pass. If Views does not win, retain the report and use it to choose the next Phase 6 candidate without carrying experimental code forward.
