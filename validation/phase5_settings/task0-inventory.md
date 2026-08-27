# Settings Phase 5 Task 0 Inventory

Captured: 2026-08-27
Branch: `feature/improveCompose`
Pre-extraction SHA: `78cb3b14c0370661a60bb8ba3f076a4e0e3146ff`

The checkout was clean apart from the two approved settings design/plan files
already present at the start of execution. Those files are intentionally
preserved. No unrelated paths were changed or staged.

## Current size

- Production settings source: 71 Kotlin files / 19,483 lines.
- Settings unit tests: 12 Kotlin files / 1,031 lines.
- Resource references: 665 unique references (660 strings, five plurals).
- The exact paths are recorded in `source-inventory.txt`,
  `test-inventory.txt`, `resource-inventory.txt`, and
  `dependency-inventory.txt`.

## Route and ownership inventory

- Settings route: `settings?backupUri={backupUri}`.
- Parental route: `parental_control_groups/{providerId}`.
- Current registration: `app/src/main/java/com/streamvault/app/navigation/graph/SystemGraph.kt`.
- Current provider preview consumer: `app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt`.
- Current preview implementation: `app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsBackupImportPreviewDialog.kt`.

## Direct implementation audit

The settings package imports nine `:data` implementation types:

1. `ProgramDao`
2. `XtreamIndexJobDao`
3. `XtreamLiveOnboardingDao`
4. `XtreamIndexJobEntity`
5. `XtreamLiveOnboardingStateEntity`
6. `DatabaseMaintenanceSnapshot`
7. `PreferencesRepository`
8. `ProviderSyncCommands`
9. `SyncRepairSection`

It also imports concrete `:player` type `AudioCompatibilityMemoryStore`; this
is a separate Phase 7 API-cleanup candidate, not a `:data` ledger entry.

## Open gates inherited from Phase 5

Playback live stability/manual/performance and provider full completion/
paired-performance gates remain open in their existing reports. Settings
runtime and performance gates begin separately in Task 11. No inherited gate
is treated as passed by this inventory.
