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

### Exact source sites and contract readiness (2026-08-28)

The second audit enumerated the concrete references rather than counting only
distinct type names:

| Implementation | Exact Settings source sites | Use classification | Replacement readiness |
|---|---|---|---|
| `ProgramDao` | `SettingsDerivedStateObservers.kt:36,50`; `SettingsObserverRegistrations.kt:5,161,177`; `SettingsViewModel.kt:16,106` | Provider program-count observation for diagnostics and parental/settings state wiring | No domain query port exists; keep until a provider-scoped program-count contract is introduced |
| `XtreamIndexJobDao` | `SettingsViewModel.kt:17,115,311` | Xtream indexing-job observation | No domain indexing-status port exists; keep until the job state is modeled in `domain` |
| `XtreamLiveOnboardingDao` | `SettingsViewModel.kt:18,116,362` | Xtream live-onboarding state observation | No domain onboarding-status port exists; keep until the onboarding state is modeled in `domain` |
| `XtreamIndexJobEntity` | `SettingsViewModel.kt:19,378` | Diagnostics warning formatting from the persistence entity | No domain indexing-status model exists; keep the entity-to-message conversion in the transitional feature boundary |
| `XtreamLiveOnboardingStateEntity` | `SettingsOperationalModels.kt:3,92` | Persistence entity to Settings UI-model mapping | No domain onboarding-status model exists; keep the mapper transitional |
| `DatabaseMaintenanceSnapshot` | `SettingsOperationalModels.kt:4,128` | Database-health snapshot to maintenance UI-model mapping | No domain maintenance snapshot exists; keep the mapper transitional |
| `PreferencesRepository` | `ParentalControlGroupViewModel.kt:6,48`; `SettingsAppUpdateActions.kt:10,16`; `SettingsDerivedStateObservers.kt:9,94,119,136`; `SettingsGuideDefaultCategoryBindings.kt:4,23`; `SettingsObserverRegistrations.kt:6,28,103,140,163`; `SettingsProviderActions.kt:19,37`; `SettingsStateBindings.kt:11,35`; `SettingsViewModel.kt:20,107` | Preference flows and writes for parental, update, provider, EPG, recording, playback, and presentation state | The repository is still the concrete implementation of a broad preference surface; no complete domain preference contract exists |
| `ProviderSyncCommands` | `SettingsProviderActions.kt:18,39`; `SettingsSyncActions.kt:6,19`; `SettingsViewModel.kt:21,114` | Provider sync, retry, background EPG, and index command dispatch | No domain sync-command contract covering these commands exists; keep until command ownership is moved |
| `SyncRepairSection` | `SettingsSyncActions.kt:7,112-114,197-200`; `SettingsViewModel.kt:22` | Sync-warning and selected-section mapping | No domain repair-section model exists; keep until sync repair semantics are domain-owned |
| `AudioCompatibilityMemoryStore` | `SettingsViewModel.kt:85,125,872` | Clears player compatibility memory when the related preference is changed | Separate concrete `:player` dependency; no feature-to-feature replacement or Settings port exists |

No row is removal-ready in this slice. The audit therefore produces no source
or Gradle dependency changes; each row remains explicitly owned by Phase 7.

## Live extraction inventory (Task 0/4, 2026-08-28)

The live presentation seam now consumes narrow app-host ports while the
existing implementations remain in the composition root or Playback feature.
These are transitional adapters, not new policy owners:

| Feature | Imported implementation | Current consumer | Removal direction | Owner |
|---|---|---|---|---|
| live | `data.preferences.PreferencesRepository` | Home live preferences and MultiView status adapter | domain-owned live presentation preference contract | Phase 7 |
| live | `data.sync.ProviderSyncStateSource` | Home provider synchronization state | domain-owned provider sync state contract | Phase 7 |
| live | `app.plugins.StreamVaultPluginManager` | `AppLivePreviewStreamPreparer` | host-provided stream preparation contract | Phase 5/7 |
| live | `app.tvinput.TvInputChannelSyncManager` | `AppLiveSurfaceRefreshAdapter` | host-provided TV-input refresh contract | Phase 5/7 |
| live | `feature.playback.preview.LivePreviewHandoffManager` | `AppLivePreviewHandoffAdapter` | keep lifecycle/release ownership in Playback | Phase 5 |
| live | `feature.playback.multiview.MultiViewManager` | `AppLiveMultiViewStatusAdapter` | keep slot ownership in Playback; expose status only | Phase 5 |

The `:feature:live` module itself depends only on `:core:navigation`, `:core:ui`,
`:domain`, `:data`, and `:player`; its boundary verifier rejects app and
feature-to-feature imports. Home and Guide ViewModels are being moved behind
the live boundary incrementally: `HomeViewModel`, the sidebar/preview surface,
the loading/preview host supporting composables, the live source switcher, Home
quick-filter panel/chip row, the reusable EPG guide shortcut chip, the shared
EPG guide-now ticker/provider, EPG search field/row, Home reorder top bar, and
Home channel row surface, Home channel-results header, Home category sidebar
header, channel-content state host, channel-list host, and category-list host
and hidden category/channel dialogs, the category options dialog, the
quick-filter add dialog, delete-group dialog, rename-group dialog, M3U category
organizer dialog, M3U series-assignment dialogs, EPG search overlay, guide
category picker, compact guide-program dialog, guide control rows, EPG preview
pane, guide toolbar, guide message state, hero badge, timeline header, grid
rows/cells, grid host, EPG content host, focus reconciliation, Home layout
metrics, and Home parental-lock policy are now feature-owned, with the options
overlay and EPG-match dialog also feature-owned
and the EPG guide category/channel lock policies, category-selection fallback
policy, and schedule metrics now feature-owned
while the Home and EPG
screen/dialog composition remains in `:app` for the remaining app-specific
dialogs.

The Live-browse remote shortcut dispatcher is no longer app-owned: its handler
types, Android colour-key mapping, and action dispatch live in
`feature/live/.../presentation/remote/LiveRemoteShortcutDispatch.kt`. The app
retains only player-wide shortcut dispatch and adapts Home callbacks to the
feature handler API. This is a presentation move, not a new transitional
implementation dependency.
The source switcher receives localized labels from the app at the current
composition boundary. Live-owned
presentation primitives, `HomeUiState`, `HomePreviewUiState`, Guide
mode/density/reminder presentation models, hidden collection dialogs, the
category options dialog, the quick-filter add dialog, the delete-group dialog,
and the rename-group dialog, M3U category organizer dialog, and M3U
series-assignment dialogs, the EPG search overlay, guide category picker, and
compact guide-program dialog, guide control rows, EPG preview pane, guide
toolbar, guide message state, hero badge, stable EPG channel-key helper,
timeline header, grid rows/cells, grid host, EPG content host, focus
reconciliation, Home layout metrics, Home parental-lock policy, EPG guide
category/channel lock policies, category-selection fallback policy, schedule
metrics, options overlay, and EPG-match dialog are independent of app code;
screen/resource moves, golden/runtime/performance gates, and legacy app cleanup
remain open, so no live slice acceptance gate is claimed yet.
