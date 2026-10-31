# Backup and restore test coverage

The matrices below make the finite state coverage explicit. They do not claim
that every possible backup payload, Android environment, or Google account has
been tested. Keep this inventory alongside changes to the backup schema.

## Automated state matrices

| Boundary | Cases | Assertions |
| --- | --- | --- |
| Settings import confirmation | 128 section selections × 2 conflict policies × 4 restore outcomes = 1,024 | Exact source and plan forwarded once, double-tap protection, empty selection rejected, retry source/plan retained for partial or failed-before-commit, follow-up only for complete or waiting-for-sync |
| Selective export | All 127 nonempty selections through the real exporter | Exact section presence, provider/add-on secret exclusion, checksum inspection, matching import availability and default selections; unselected corrupt data cannot block export |
| Empty selective exports | Each of the seven sections without providers | Explicitly empty sections remain valid and importable; omitted sections remain unavailable |
| Deferred restore | 13 sections × available/malformed/write failure, plus 12 sections with missing dependencies = 51 | Actual destination writes use remapped IDs and saved values; missing/malformed data performs no destination writes; durable status and job-count refresh; missing dependencies and write failures succeed after retry |
| Durable ledger and status display | Real Room database queries | Retry eligibility and order, dismissal boundaries, applied-item preservation, duplicate insert protection, counts/status precedence, replace-marker exclusion, provider grouping, persisted attempts/errors/provider rebinding after database reopen |
| Replace scope | Ready and unresolved catalog targets | No clearing until all required references resolve; successful replacement applies each target once |
| Cross-provider split-screen | Missing provider, then both available | No partial preset; saved channel order preserved after remapping |

The deferred sections are favorites, custom groups, playback history, protected
content, hidden content, hidden categories, channel preferences, variant choices,
VOD track preferences, manual EPG mappings, split-screen presets, recording
schedules, and search history. Search has no missing-catalog case. The matrix
uses live channels, with movie references for VOD track preferences. Separate
coordinator tests exercise episode identity collisions and missing parents.

The settings matrix verifies orchestration through a mocked import use case.
It does **not** perform 1,024 full database restores. The deferred matrix calls
the real coordinator with mocked destinations; the ledger tests use real Room.
Inventory assertions fail when a new import-selection flag or deferred section
is introduced without updating these matrices.

## Other regression coverage

- `BackupExportSelectionTest`: real Room/DataStore export across all section
  combinations, omitted versus explicitly empty sections, independent Stremio
  export with incomplete providers, and empty-selection rejection.
- `SettingsBackupExportDialogTest`: device UI checks for default selections,
  toggling Providers, confirmation, disabled empty selection, cancellation, and
  saved-state restoration. TV footer actions use semantic activation because a
  touch tap on a TV Material button first moves focus.
- `ProviderlessBackupExportTest`: full export and checksum inspection using real
  Room and DataStore for a fresh installation and deletion of the last provider.
  Stale active-provider, Guide/Home category, promoted-group, EPG-shift, variant,
  live-source, and split-screen selections do not block a providerless backup.
  Built-in Guide/Home categories and global preferences remain exportable.
- `BackupManagerImplTest`: malformed/admission-limited input, checksum and
  release-obfuscated fields, legacy and portable data, provider identity remapping,
  conflict handling, transaction/checkpoint boundaries, preference failures,
  unresolved partial subsets, Guide categories, and recording import outcomes.
- `PreferenceBackupRegistryTest`: new DataStore keys must have an explicit
  backup classification and portable codec.
- `GoogleDriveBackupSyncManagerTest`: bundle formats and credentials, exact JSON
  preservation, selected snapshots, retention, HTTP failure classification,
  serialization of uploads, missing/deleted snapshots, malformed bundles,
  failed-download retry, export failure/cancellation/invalid output cleanup, and
  retention failure after successful upload. The simulated round trip reuses the
  actual uploaded bundle for download, checksum inspection, and real preference
  restore. Its access token and remote service are test substitutes.
- `SettingsBackupActionsTest` and `SettingsDriveBackupActionsTest`: error/retry
  behavior, credentials, dismissal, follow-up callbacks, busy guards, preview,
  snapshot selection, and management. Restore sync choices exclude missing and
  ambiguous providers, retain pending restore jobs, and use the committed provider
  list even before UI observers catch up. Excluded or empty provider sections
  never offer post-import sync, even when matching providers exist locally.
- Domain backup use cases and `BackupFileBridgeTest`: source validation,
  summaries, file admission, managed-file boundaries, and fallback file cleanup.

## Reproduce the affected JVM suite

From the repository root in PowerShell:

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests '*Backup*Test' :domain:test --tests '*Backup*Test' :feature:settings:testDebugUnitTest --tests '*SettingsBackup*Test' --tests '*SettingsDriveBackupActionsTest' --console=plain --no-build-cache --max-workers=1
```

`--no-build-cache` avoids interference from a shared Gradle cache held by another
process. It does not skip test execution when inputs change. XML reports live in
each module's `build/test-results` directory.

## Authenticated device release gate

This JVM suite does not establish a real authenticated Google Drive round trip.
Use a disposable app profile and test Google account for that gate:

1. Populate two providers and representative saved library/history, Guide
   categories (all channels, built-in favorites, custom group, provider category,
   combined category), preferences, EPG mappings, presets, and future schedules.
2. Sign in, grant the app-data scope, upload a snapshot, list it, and select it.
3. Restore into a clean app profile with different local catalog IDs. Verify
   immediate settings, waiting-for-sync status, and catalog-dependent state after
   both providers sync. Restart during the waiting phase and verify continuation.
4. Exercise revoked consent, sign-out, an unavailable network, retry, and a
   snapshot removed before download. Verify actionable errors and retry behavior.
5. Delete only the snapshots created by this test and sign out. Record build,
   device/API, sanitized results, and the tested data inventory.

Android document-provider permissions, process death at arbitrary instruction
boundaries, device-specific storage behavior, all provider/content combinations,
and real Google authentication remain outside the finite JVM matrices. Add
regressions whenever a new failure or state dimension is found; passing the
matrices alone must not be reported as proof that literally everything works.
