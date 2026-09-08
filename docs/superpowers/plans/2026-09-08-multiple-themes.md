# Multiple Themes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans (inline execution is approved for this branch). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three selectable palette themes, persist the choice, apply it at both app entry points, expose it in Settings, and round-trip it through portable backups.

**Architecture:** The domain defines stable theme IDs and fallback parsing. DataStore and the existing SettingsPreferences/snapshot flow carry the enum; core UI resolves the ID into a state-backed palette facade and Material TV color scheme without taking a dependency on domain. MainActivity and TvInputSetupActivity pass the persisted storage ID into StreamVaultTheme.

**Tech Stack:** Kotlin/JVM, Android DataStore Preferences, Jetpack Compose for TV, Material 3 TV, existing JUnit/Truth/robolectric test setup.

**Spec:** `docs/superpowers/specs/2026-09-08-multiple-themes-design.md`

## Global Constraints

- `classic_blue` is the default and preserves the existing palette.
- `m3_purple` is palette-only; typography, shapes, spacing, layout, and behavior remain shared.
- `light` uses a light Material color scheme; typography, shapes, spacing, layout, and behavior remain shared.
- Invalid or missing theme values fall back to `classic_blue`.
- Core UI must remain independent of `:domain`, `:data`, `:app`, and feature modules.
- Portable backups must include the theme and restore it tolerantly.
- Write tests before production code for each new behavior and run the smallest targeted test task after each cycle.

---

### Task 1: Domain theme ID model

**Files:**
- Create: `domain/src/main/java/com/streamvault/domain/model/AppTheme.kt`
- Test: `domain/src/test/java/com/streamvault/domain/model/AppThemeTest.kt`

**Interfaces:**
- Produces `AppTheme.CLASSIC_BLUE`, `AppTheme.M3_PURPLE`, `AppTheme.LIGHT`, `AppTheme.DEFAULT`, `AppTheme.storageValue`, and `AppTheme.fromStorage(value: String?): AppTheme`.

- [ ] **Step 1: Write the failing tests** for stable IDs, case-insensitive parsing, and fallback for null/unknown/blank input.
- [ ] **Step 2: Run `:domain:test` for `AppThemeTest` and confirm the failure is caused by the missing model.**
- [ ] **Step 3: Add the enum with explicit storage values and a tolerant parser.**
- [ ] **Step 4: Re-run the focused test and confirm it passes.**

### Task 2: Core UI palette resolver and reactive theme

**Files:**
- Create or modify: `core/ui/src/main/java/com/streamvault/core/ui/design/AppPalette.kt`
- Modify: `core/ui/src/main/java/com/streamvault/core/ui/design/AppColors.kt`
- Modify: `core/ui/src/main/java/com/streamvault/core/ui/theme/Color.kt`
- Modify: `core/ui/src/main/java/com/streamvault/core/ui/theme/Theme.kt`
- Test: `core/ui/src/test/java/com/streamvault/core/ui/design/AppPaletteTest.kt`

**Interfaces:**
- Produces `AppPalette.forTheme(themeId: String): AppPalette` and `StreamVaultTheme(themeId: String = "classic_blue", content: @Composable () -> Unit)`.

- [ ] **Step 1: Write failing palette tests** proving classic blue retains the current brand/surface colors, M3 purple changes the primary/surface colors, light uses light surfaces/dark text, and an unknown ID resolves to classic blue.
- [ ] **Step 2: Run `:core:ui:testDebugUnitTest` for the palette test and confirm the expected missing-symbol failure.**
- [ ] **Step 3: Implement the three complete palettes and state-backed `AppColors` getters.**
- [ ] **Step 4: Change top-level color aliases to computed getters and build the appropriate dark or light Material TV color scheme from the selected palette.**
- [ ] **Step 5: Re-run the focused core UI test and `:core:ui:check` to verify both behavior and the existing module boundary.**

### Task 3: Persist theme through the settings contract and DataStore

**Files:**
- Modify: `domain/src/main/java/com/streamvault/domain/settings/SettingsPreferences.kt`
- Modify: `data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt`
- Test: `data/src/test/java/com/streamvault/data/preferences/PreferencesRepositoryThemeTest.kt`

**Interfaces:**
- Adds `SettingsPreferences.appTheme: Flow<AppTheme>` and `setAppTheme(theme: AppTheme)`.
- Stores the value under the stable DataStore key `app_theme` with default `classic_blue`.

- [ ] **Step 1: Write a repository test** for default theme and persisted theme round-trip using the existing DataStore test pattern.
- [ ] **Step 2: Run the focused data test and confirm the contract/key is missing.**
- [ ] **Step 3: Add the contract property/setter and DataStore implementation.**
- [ ] **Step 4: Re-run the focused data test.**

### Task 4: Settings snapshot, ViewModel setter, and selector dialog

**Files:**
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsPreferenceModels.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsUiStateModel.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsStateBindings.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsPreferenceSnapshotMapper.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsViewModel.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsScreenDialogState.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsBrowsingSection.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsContentPane.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsScreenDialogs.kt`
- Modify: `feature/settings/src/main/res/values/strings.xml`
- Tests: existing settings snapshot/contract tests plus a focused selector state test if needed by the existing patterns.

**Interfaces:**
- Adds `SettingsUiState.appTheme: AppTheme` and a `SettingsViewModel.setAppTheme(theme: AppTheme)` command.
- Adds a saved `showThemeDialog` flag and a selector row in the existing Browsing category.

- [ ] **Step 1: Update existing snapshot tests/fixtures first** so they assert the default theme and fail until the flow is wired.
- [ ] **Step 2: Run the focused settings tests and confirm the new field is not propagated.**
- [ ] **Step 3: Thread `appTheme` through snapshot defaults, combine bindings, mapper, and ViewModel setter.**
- [ ] **Step 4: Add localized labels and a TV-friendly single-choice dialog using the existing dialog/state patterns.**
- [ ] **Step 5: Add the selector row in the Browsing settings section and connect it through `SettingsContentPane`/`SettingsScreenDialogs`.**
- [ ] **Step 6: Run the focused settings unit tests and the feature boundary verification.**

### Task 5: Apply the selected theme at app entry points

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/MainActivity.kt`
- Modify: `app/src/main/java/com/streamvault/app/tvinput/TvInputSetupActivity.kt`

- [ ] **Step 1: Add entry-point tests or update existing Compose smoke fixtures** to pass a selected theme ID into `StreamVaultTheme`.
- [ ] **Step 2: Collect `preferencesRepository.appTheme` in both activities and pass `themeId = appTheme.storageValue`.**
- [ ] **Step 3: Run the relevant app compile/unit target and verify no stale `StreamVaultTheme` call sites are broken.**

### Task 6: Portable backup and restore

**Files:**
- Modify: `data/src/main/java/com/streamvault/data/manager/PreferenceBackupRegistry.kt`
- Modify: `data/src/main/java/com/streamvault/data/manager/BackupManagerImpl.kt`
- Modify: `data/src/test/java/com/streamvault/data/manager/PreferenceBackupRegistryTest.kt`
- Modify: `data/src/test/java/com/streamvault/data/manager/BackupManagerImplTest.kt` only where existing snapshot expectations require it.

- [ ] **Step 1: Add a failing registry assertion** that `app_theme` is portable and `appTheme` is admitted as a global backup key.
- [ ] **Step 2: Run the focused registry test and confirm the key is not yet admitted.**
- [ ] **Step 3: Export `appTheme` in both backup snapshot builders and restore it through `AppTheme.fromStorage`/`setAppTheme`.**
- [ ] **Step 4: Re-run registry and targeted backup tests, including malformed-value coverage.**

### Task 7: Integration verification and graph refresh

**Files:**
- Modify: `docs/superpowers/plans/2026-09-08-multiple-themes.md` to mark completed steps.
- Generated: `graphify-out/` via `graphify update .`.

- [ ] **Step 1: Run domain, core UI, settings, and targeted data unit tests.**
- [ ] **Step 2: Run `:app:compileDebugKotlin` or the narrowest successful app verification target.**
- [ ] **Step 3: Run `graphify update .` from the worktree root.**
- [ ] **Step 4: Inspect `git diff`, `git status`, and the final test output for accidental scope, stale files, or generated changes.**
