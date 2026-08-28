# Settings Runtime and Performance Completion Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every Settings-slice gate that can be completed safely in the current development environment while preserving the existing checkout and keeping Phase 7 and later feature slices out of scope.

**Architecture:** Keep `:feature:settings` as the presentation owner and `:app` as the composition root for platform adapters, route decoding, launchers, lifecycle, and external callbacks. Complete remaining evidence through isolated committed snapshots for performance and non-destructive TV journeys; add deterministic coverage only for a confirmed Settings behavior gap.

**Tech Stack:** Kotlin, Jetpack Compose for TV, Android instrumentation, Gradle 8.12, PowerShell, `E:\androidSdk`, Graphify, Git committed snapshots.

**Spec:** `docs/superpowers/specs/2026-08-27-phase-5-settings-feature-extraction-design.md`

## Global Constraints

- Preserve the current working tree; do not reset, clean, delete, or mix unrelated changes.
- Do not start the later live, catalog, or system extraction slices.
- Do not remove the nine temporary `:data` dependencies or `player.AudioCompatibilityMemoryStore` without domain-facing replacements.
- Do not use credentials, accounts, USB/Drive, or destructive restore operations without explicit fixtures and authority.
- Use `E:\androidSdk` for connected validation and keep existing playback/provider failures separately attributed.
- Use TDD for any production behavior change: failing test first, then minimal implementation, then green verification.
- After code changes, run `graphify update .`; finish with `git diff --check` and `git status --short`.

### Task 1: Complete the Settings dependency audit

**Files:**
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_REPORT.md`
- Read: `feature/settings/src/main/java/com/streamvault/feature/settings`

**Interfaces:**
- Consumes: the existing nine-entry `:data` ledger and the concrete `AudioCompatibilityMemoryStore` audit candidate.
- Produces: exact source locations, current presentation responsibility, and a contract-ready removal direction for every temporary dependency.

- [x] **Step 1: Enumerate exact imports and source locations**

```powershell
rg -n "data\.local\.dao|data\.local\.entity|data\.preferences\.DatabaseMaintenanceSnapshot|data\.preferences\.PreferencesRepository|data\.sync\.ProviderSyncCommands|data\.sync\.SyncRepairSection|player\.AudioCompatibilityMemoryStore" feature/settings/src/main/java
```

Expected: exactly the nine ledgered `:data` types plus `player.AudioCompatibilityMemoryStore`, with no new implementation dependencies.

- [x] **Step 2: Classify each use without changing production code**

For every result, record the owning Settings file, whether the use is state observation, action dispatch, formatting, or playback compatibility, and the domain-facing contract/model needed before removal. Confirm no `:feature:provider`, `:feature:playback`, or app-package import is introduced.

- [x] **Step 3: Update and verify the ledger**

Add source locations and explicit “contract not present; removal remains Phase 7” status to the existing table. Re-run the enumeration and inspect the diff. Expected: the same ten implementation references, documentation only, and no dependency removal.

### Task 2: Run safe remaining TV journeys

**Files:**
- Create or update: `validation/phase5_settings/manual_journeys/` artifacts
- Modify: `validation/phase5_settings/task11-runtime-validation.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_REPORT.md`

**Interfaces:**
- Consumes: installed `com.streamvault.app.debug`, seeded public provider fixture, `emulator-5554`, and existing Settings route.
- Produces: screenshots, exact UI/log outcomes, and explicit blockers for operations requiring authority or missing fixtures.

- [x] **Step 1: Launch without clearing app data**

```powershell
$env:ANDROID_HOME = 'E:\androidSdk'
& 'E:\androidSdk\platform-tools\adb.exe' -s emulator-5554 shell am start -W -n com.streamvault.app.debug/com.streamvault.app.MainActivity
```

Expected: `com.streamvault.app.debug/com.streamvault.app.MainActivity` is foregrounded and prior seeded state remains.

- [x] **Step 2: Exercise parental controls without changing protected state**

Navigate to Privacy and the parental-control entry with the D-pad. Open protection-level and PIN dialogs, inspect focus and Back dismissal, and do not enter a new PIN, save, reset, or hide content. Capture a binary-safe screenshot and record the focused UI node plus foreground activity.

- [x] **Step 3: Create one local export fixture only if the UI offers a picker-free safe export**

Use Settings backup export only when it writes an app-owned local export without credentials or external storage. Inspect the local-backup listing and preview. Do not import, restore, delete, or overwrite state. If export requires a picker or external authority, record the exact blocker.

- [x] **Step 4: Exercise diagnostics, recording, and EPG presentation callbacks safely**

Open the provider diagnostics panel, recording storage/browser controls, and EPG source/assignment dialogs. Do not add credentials, create recordings, select an external file, or invoke a player handoff. Record whether each action is reachable and capture screenshots for reachable states.

- [x] **Step 5: Verify runtime evidence and record blockers**

Use `uiautomator dump`, `dumpsys activity`, and bounded logcat extracts for each journey. Add exact outcomes to both reports. Keep credentialed provider flows, USB/Drive, destructive restore, and unavailable diagnostics actions open when they cannot be safely exercised.

### Task 3: Add deterministic coverage for any confirmed Settings gap

**Files:**
- Test: `feature/settings/src/androidTest/java/com/streamvault/feature/settings/presentation/SettingsConnectedBehaviorTest.kt`
- Modify: production Settings source only if a new test demonstrates a defect

**Interfaces:**
- Consumes: the confirmed behavior gap from Task 2 and existing feature test fixtures.
- Produces: one focused regression test per confirmed gap, with no unrelated refactor.

- [x] **Step 1: Write the smallest regression test first**

Use the existing `SettingsConnectedBehaviorTest` fixture style. Assert user-visible semantics or callback/state behavior rather than implementation details. If the manual journey finds no defect, add no production code and record that existing coverage is sufficient.

- [x] **Step 2: Run the focused test**

```powershell
$env:ANDROID_HOME = 'E:\androidSdk'
.\gradlew.bat :feature:settings:connectedDebugAndroidTest --tests "com.streamvault.feature.settings.presentation.SettingsConnectedBehaviorTest" --no-daemon --console=plain --warning-mode=none
```

Expected: the focused test suite passes, or a new regression case fails for the confirmed defect before implementation.

- [x] **Step 3: Implement the minimal fix only for a real failing behavior**

Follow the TDD red-green cycle, preserve existing route/callback contracts, and avoid Phase 7 dependency changes.

- [x] **Step 4: Run the focused Settings gates**

```powershell
.\gradlew.bat :feature:settings:verifyFeatureSettingsBoundary :feature:settings:testDebugUnitTest :feature:settings:lintDebug :feature:settings:compileDebugAndroidTestKotlin :feature:settings:connectedDebugAndroidTest --no-daemon --console=plain --warning-mode=none
```

Expected: all Settings-specific tasks pass; unrelated app/player/provider failures remain separately documented.

### Task 4: Capture comparable incremental-build samples

**Files:**
- Create: `validation/phase5_settings/performance-samples-2026-08-28.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_REPORT.md`

**Interfaces:**
- Consumes: committed pre-extraction ref `e12f816f`, committed post-extraction ref `cf468d3e`, identical host/SDK/Gradle settings, and `validation/phase5_settings/performance-protocol.md`.
- Produces: five comparable source-edit pairs, five comparable test-edit pairs, clean/warm guardrails, task sets, profile paths, medians, p95 values, and an honest target result.

- [x] **Step 1: Create isolated committed snapshots outside the checkout**

Use two detached Git worktrees under a unique system temporary directory. Do not change the current checkout, do not reset it, and leave the measurement snapshots intact rather than deleting them. Verify each snapshot is clean before building.

- [x] **Step 2: Establish identical SDK, Gradle, daemon, and warm-cache state**

In each snapshot set `ANDROID_HOME=E:\androidSdk`, use the repository Gradle wrapper, keep worker/cache settings unchanged, and run the protocol's clean and warm `:app:assembleDebug --profile` guardrails. Record command output, wall time, and profile path.

- [x] **Step 3: Run five source-edit pairs**

Add the same comment-only edit to the pre-ref app Settings production file and the post-ref feature Settings production file. Run the protocol's compile command five times on each ref, recording wall time, executed/up-to-date tasks, Kotlin/KSP tasks, and profile path. Revert only the temporary snapshot edit after its five runs.

- [x] **Step 4: Run five test-edit pairs**

Add the same comment-only edit to the pre-ref Settings unit test and the post-ref moved Settings unit test. Run the protocol's unit-test compilation command five times on each ref and record the same evidence. Revert only the temporary snapshot edit after its five runs.

- [x] **Step 5: Summarize results without overstating targets**

Compute median and p95 for each side and scenario. Demonstrate the 25% source-edit and 20% test-compile targets only if the clean committed pairs support them; otherwise keep the gate open and state why.

### Task 5: Final verification and reporting

**Files:**
- Modify: `validation/phase5_settings/task11-runtime-validation.md`
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_REPORT.md`
- Update: `graphify-out/` through `graphify update .`

**Interfaces:**
- Consumes: all task artifacts and command results.
- Produces: final Settings-slice status with no false completion claim.

- [x] **Step 1: Run the focused final verification**

```powershell
$env:ANDROID_HOME = 'E:\androidSdk'
.\gradlew.bat :feature:settings:verifyFeatureSettingsBoundary :feature:settings:testDebugUnitTest :feature:settings:lintDebug :feature:settings:compileDebugAndroidTestKotlin :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon --console=plain --warning-mode=none
```

- [x] **Step 2: Refresh Graphify after source changes**

```powershell
graphify update .
```

- [x] **Step 3: Check diff hygiene and status**

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors, only intentional Settings-slice/source-seam/report artifacts, and no current-checkout reset or cleanup.
