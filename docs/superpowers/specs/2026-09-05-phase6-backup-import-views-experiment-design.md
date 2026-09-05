# Phase 6 Backup Import Views Experiment Design

## Decision

Phase 6 will begin with a controlled A/B experiment for the backup-import preview dialog. The existing Compose implementation remains the production default while a classic Android Views implementation is built against the same state and action contract. The experiment must produce a keep-or-drop decision before the Views implementation is wired into the real Settings journey.

The first candidate is intentionally narrower than the complete backup/import flow. File pickers, backup inspection, import execution, Drive actions, restore checkpoints, and provider synchronization remain unchanged. This isolates the UI-toolkit decision from persistence, I/O, and asynchronous import behavior.

## Context

The current preview surface is `BackupImportPreviewDialog` in `:feature:settings`. It renders a fixed set of backup counts, conflict information, two conflict-strategy actions, six import toggles, and Cancel/Import actions. The surrounding state and behavior already have explicit ownership:

- `BackupPreview` and `BackupImportPlan` provide the rendered state.
- `SettingsBackupActions` owns inspection, plan updates, confirmation, and retry behavior.
- `SettingsViewModel` exposes the actions to `SettingsScreenDialogs`.
- `SettingsPlatformHost` and the app adapters own Android framework operations outside the dialog.

The current Compose dialog also carries TV-specific behavior that the experiment must preserve: delayed interaction protection while the dialog opens, initial body focus on television devices, scrollable body content, D-pad focus movement, Back dismissal, disabled actions while importing, and the existing localized strings.

## Goals

1. Establish a repeatable baseline for the current Compose preview surface.
2. Build an equivalent classic Views surface without changing domain, ViewModel, or backup behavior.
3. Compare both implementations under the same deterministic fixture and remote-control journey.
4. Verify focus, accessibility, Back behavior, callback behavior, and visual parity before considering a migration.
5. Make a documented keep-or-drop decision using benchmark evidence.

## Non-goals

- Converting the complete Settings screen.
- Converting backup selection, local-backup management, Drive backup dialogs, or file pickers in this experiment.
- Changing `BackupImportPlan`, `BackupPreview`, `SettingsBackupActions`, `SettingsViewModel`, or import execution behavior.
- Introducing a new navigation destination for the production application.
- Removing Compose dependencies from `:feature:settings` during the experiment.
- Optimizing unrelated Settings or startup performance.

## Options considered

### Option A: Convert the complete backup/import flow

This would cover file selection, preview, confirmation, import execution, and restore follow-up in one migration. It would provide the most realistic production result but would confound UI-toolkit measurements with Android document APIs, asynchronous work, and state restoration. It is too broad for the first Phase 6 decision.

### Option B: Convert only the preview dialog with a deterministic benchmark host

This keeps the state and behavior contract fixed, allows the same Compose and Views surfaces to be measured repeatedly, and leaves the current production path untouched until the result is known. It is the recommended option.

### Option C: Convert a provider setup form first

Provider setup is a valid later candidate, but it includes text input, provider-specific branches, validation, password handling, file launchers, pairing, and more extensive TV focus behavior. It is a larger migration and a weaker first experiment.

## Recommended architecture

### Shared contract

The Views candidate consumes the existing `BackupPreview`, `BackupImportPlan`, and `isImporting` values. It emits the same callbacks as the Compose dialog:

- dismiss
- conflict-strategy selection
- six section-toggle changes
- import confirmation

No new domain or persistence interface is introduced. The existing `SettingsBackupActions` remains the only owner of import behavior.

### Views implementation

Add a classic Views implementation in `:feature:settings` using an XML layout and a small Kotlin controller/view binder. The layout will contain:

- title and version subtitle;
- a vertically scrollable summary section;
- two focusable conflict-strategy controls;
- six focusable import switches;
- Cancel and Import footer buttons.

The dialog window will reproduce the current `PremiumDialog` contract: transparent window background, centered rounded surface, TV-sized width/height, bounded scrollable body, explicit initial focus, delayed interaction enablement, and Back/outside dismissal only after the open guard is released. Focus selectors and colors will use the existing `:core:ui` design values where XML resources are required; the candidate will not recreate a second application-wide design system.

The production-facing integration point will be a small wrapper that can host the Views dialog from the existing Compose dialog host. It will be used only after the benchmark decision is positive. The existing Compose destination remains available as the rollback path throughout the experiment.

### Debug-only benchmark host

Add a debug-only activity in `:app` that renders the preview from a deterministic fixture. The activity accepts an explicit presentation mode (`compose` or `views`) and supplies identical callbacks that update the local fixture state. It does not access a real backup file, network provider, file picker, or database.

This host gives Macrobenchmark a stable entry point and ensures the comparison measures the preview surface rather than backup I/O or the rest of Settings. A separate connected smoke test will exercise the real Settings integration after the candidate is wired behind the development switch.

## Benchmark protocol

Use the existing `:benchmark` module and seeded API 36 TV emulator. Add paired journeys that launch the same debug host in Compose and Views modes.

Each mode will run the same sequence:

1. Launch the benchmark host.
2. Wait for the preview surface to become idle and record initial display timing.
3. Move focus through the title/body/footer using D-pad input.
4. Select the alternate conflict strategy.
5. Toggle representative import sections, including a disabled/enabled transition for Import.
6. Move focus back to the footer and dismiss with Back.

Collect the same metrics and configuration for both modes:

- `FrameTimingMetric` for focus and interaction work;
- startup/initial-display timing for opening the surface;
- memory/PSS metrics where supported by the installed Macrobenchmark version;
- trace sections for the mode and journey boundaries;
- functional and accessibility assertions outside the timing window.

Use the same emulator image, APK variant, compilation mode, fixture, iteration count, and device reset policy. Run enough repetitions to report medians and P95 values rather than single samples. The diagnostic benchmark is not a release-quality physical-device claim; it is a paired comparison for this surface.

## Functional and visual acceptance

Both implementations must pass the same behavior matrix:

- all six summary rows render with the same localized labels and counts;
- conflict strategy selection updates the selected state and callback;
- each import switch updates the corresponding callback;
- Import is disabled when every section is disabled;
- Cancel and Back dismiss when idle;
- dismiss and Import are blocked while an import is active;
- initial focus lands on the first body control on TV;
- D-pad navigation reaches every interactive control in a predictable order;
- scrollable content remains reachable without trapping focus;
- visible text has equivalent accessibility labels and roles;
- RTL and large-text layouts do not clip the dialog or hide actions;
- the real Settings flow can still inspect and confirm a backup without behavior changes.

The existing Compose instrumentation test remains as the baseline. Add View-hosted instrumentation coverage for the same states and callbacks, plus a paired screenshot/golden review on the seeded emulator. Visual parity is required for the experiment; a performance win that materially degrades the established StreamVault TV surface is not a successful migration.

## Decision rule and rollback

Views becomes the preferred implementation only if:

- it improves at least one primary performance metric by approximately 10% or more across repeated paired runs;
- it has no material regression in P95 interaction timing or memory behavior;
- it passes the complete functional, focus, accessibility, RTL, large-text, and visual matrix;
- the Compose/View host boundary is stable and understandable.

If the results are tied, noisy, or fail any required behavior gate, the Views candidate is removed and the experiment report records that this surface remains Compose. If Views wins, it is first enabled through the development switch in the real Settings flow, validated again, and then made the default in a separate change. The original Compose implementation is not deleted in the same change that introduces the unvalidated replacement.

## Deliverables

1. A checked-in baseline/experiment benchmark entry for the Compose surface.
2. A Views layout and binder/controller with the shared contract.
3. A debug-only mode-selectable benchmark host.
4. Equivalent Compose, Views, connected, and accessibility/focus tests.
5. Paired benchmark output with medians, P95s, device/configuration, and functional results.
6. A Phase 6 experiment report recording the keep-or-drop decision and next candidate.
