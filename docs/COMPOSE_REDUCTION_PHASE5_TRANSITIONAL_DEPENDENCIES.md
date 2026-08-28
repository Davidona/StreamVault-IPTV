# Phase 5 Transitional Dependency Ledger

| Feature | Imported implementation | Current consumer | Removal direction | Owner |
|---|---|---|---|---|
| playback | data.preferences.PreferencesRepository | PlayerPreferencesCoordinator | domain preference contract | Phase 7 |
| playback | data.remote.stalker.StalkerPlaybackResolutionException | PlayerContentResolver | player/domain resolution error | Phase 7 |
| playback | data.remote.xtream.ProviderPlaybackResolver | PlayerContentResolver | domain playback resolver | Phase 7 |
| playback | data.remote.xtream.XtreamStreamKind | alternate stream support | domain/player stream kind | Phase 7 |
| playback | data.remote.xtream.XtreamUrlFactory | alternate stream support | domain/player URL factory | Phase 7 |
| playback | data.security.CredentialDecryptionException | content/provider resolution | domain provider-access error | Phase 7 |

## Task 9 integration status

Task 9 re-scanned the extracted feature after the Player and MultiView moves.
The six ledger entries remain intentional and are the only direct `:data`
types imported by feature playback production code:

| Ledger implementation | Current feature files |
|---|---|
| `PreferencesRepository` | `MultiViewViewModel`, `PlayerPreferencesCoordinator` |
| `StalkerPlaybackResolutionException` | `PlayerContentResolver` |
| `ProviderPlaybackResolver` | `PlayerContentResolver` |
| `XtreamStreamKind`, `XtreamUrlFactory` | `PlayerAlternateStreamSupport` |
| `CredentialDecryptionException` | `PlayerContentResolver`, `PlayerProviderCoordinator` |

Accordingly, `:feature:playback` retains its temporary `:data` dependency.
`:app` also retains `:data`, `:player`, `:domain`, `:core:navigation`,
`:core:ui`, and `:feature:playback`: its composition root, platform adapters,
other app features, and `AppNavHost` still consume them. The feature-owned
`PlaybackGraph` is now registered by `AppNavHost`; the Task 4 graph extraction
is complete. No project dependency was removed in Task 9 because doing so
would break an active consumer rather than remove obsolete presentation
ownership.

The feature is otherwise app-independent: Task 9 source scans found no app
references to legacy `ui.screens.player` or `ui.screens.multiview` packages,
and no feature-main references to `com.streamvault.app`, `NavController`, or
`NavHostController`.

## Provider extraction inventory (Task 1)

The provider slice is not yet moved. The following imports are the planned
temporary `:data` boundary for `:feature:provider`; each is owned by Phase 7
and must be removed only after an equivalent domain-facing contract exists.

| Feature | Imported implementation | Current consumer | Removal direction | Owner |
|---|---|---|---|---|
| provider | `data.remote.stalker.StalkerParamOverride` | setup form models | domain-owned Stalker setup policy | Phase 7 |
| provider | `data.remote.stalker.StalkerRequestRule` | setup form models | domain-owned Stalker setup policy | Phase 7 |
| provider | `data.remote.stalker.StalkerAdvancedOptions` | advanced options model/UI | domain-owned Stalker setup policy | Phase 7 |
| provider | `data.remote.stalker.StalkerAdvancedOptionsCodec` | provider setup screen | domain-owned Stalker setup policy codec | Phase 7 |
| provider | `data.remote.stalker.StalkerCompatibilityRegistry` | setup screen/selector | domain-owned Stalker compatibility policy | Phase 7 |
| provider | `data.util.ProviderInputSanitizer` | provider setup screen | domain-owned provider input validation | Phase 7 |
| provider | `data.remote.xtream.XtreamAuthenticationException` | setup ViewModel | domain-owned provider-access error | Phase 7 |
| provider | `data.remote.xtream.XtreamNetworkException` | setup ViewModel | domain-owned provider-access error | Phase 7 |
| provider | `data.remote.xtream.XtreamParsingException` | setup ViewModel | domain-owned provider-access error | Phase 7 |
| provider | `data.remote.xtream.XtreamRequestException` | setup ViewModel | domain-owned provider-access error | Phase 7 |
| provider | `data.remote.xtream.XtreamResponseTooLargeException` | setup ViewModel | domain-owned provider-access error | Phase 7 |
| provider | `data.security.CredentialDecryptionException` | setup ViewModel | domain-owned provider-access/setup error | Phase 7 |

## Settings extraction inventory (Task 0, 2026-08-27)

The settings slice retains a temporary `:data` dependency while DAO and
concrete persistence contracts are audited. These imports are presentation
consumers only; each is a Phase 7 removal candidate after an equivalent
domain-facing contract exists.

| Feature | Imported implementation | Current consumer | Removal direction | Owner |
|---|---|---|---|---|
| settings | `data.local.dao.ProgramDao` | parental controls/settings state | domain-owned program query contract | Phase 7 |
| settings | `data.local.dao.XtreamIndexJobDao` | diagnostics/settings state | domain-owned indexing status contract | Phase 7 |
| settings | `data.local.dao.XtreamLiveOnboardingDao` | diagnostics/settings state | domain-owned onboarding status contract | Phase 7 |
| settings | `data.local.entity.XtreamIndexJobEntity` | diagnostics formatting/state | domain-owned indexing status model | Phase 7 |
| settings | `data.local.entity.XtreamLiveOnboardingStateEntity` | diagnostics formatting/state | domain-owned onboarding status model | Phase 7 |
| settings | `data.preferences.DatabaseMaintenanceSnapshot` | database maintenance UI/state | domain-owned maintenance snapshot | Phase 7 |
| settings | `data.preferences.PreferencesRepository` | settings/preferences state | domain-owned settings preference contract | Phase 7 |
| settings | `data.sync.ProviderSyncCommands` | sync actions/state | domain-owned provider sync command contract | Phase 7 |
| settings | `data.sync.SyncRepairSection` | sync repair UI/state | domain-owned sync repair model | Phase 7 |

`player.AudioCompatibilityMemoryStore` is a separate concrete dependency
audit candidate. It is not included in the nine-entry `:data` ledger above and
must not become a feature-to-feature implementation dependency.

Settings dependency audit snapshot (2026-08-28): the feature production source
still imports exactly the nine ledgered `:data` types listed above, plus
`player.AudioCompatibilityMemoryStore`. The imports are confined to the
Settings/parental state, observer, action, and formatting orchestration; no
additional DAO or concrete implementation types were found. Replacing them
requires domain-facing contracts and remains a Phase 7 task.
