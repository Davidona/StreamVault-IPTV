# Settings control preservation inventory

> Status: implementation inventory. Every entry keeps its existing `SettingsViewModel`/platform callback and underlying repository or operational store. A visual move must not rename preference keys, reset values, or replace an action with local UI state.

The persistence identifier below is the stable domain write API (and, where applicable, the `PreferencesKeys` family it owns). This is the feature boundary Settings can verify without importing the data module. Physical DataStore key strings remain owned by `PreferencesRepository` and the backup registry.

Common verification for every preference: open from its catalog result, change it, leave and return, restart in final device verification, and confirm the displayed value and behavior persist. Common focus return: dialogs return to the invoking `stableSettingId`; nested-page Back returns to its page opener.

## Navigation and focus ownership trace

| Boundary | Owner | Entry target | Return behavior |
|---|---|---|---|
| App → Settings | `AppNavHost` supplies `NavigationActions.back` to `SettingsScreen` | Current category content | Settings root Back pops to the actual prior app route; Settings does not render the global app navigation bar |
| Category list/rail → category | `SettingsScreen` | First overview row or first direct control | Compact Back returns to the category list and restores the selected category; wide navigation retains the selected rail item |
| Category overview → detail | `SettingsContentPane` + `SettingsFocusCoordinator` | First enabled row after attachment | Visible and system Back clear the typed `SettingsPage`, retain the overview list state, and focus the exact page opener |
| Settings search → result | `SettingsScreen` request ID + `SettingsContentPane` coordinator | Stable catalog item ID | Search dismissal restores the Search action; selection opens the owning category/page and focuses the registered target after layout |
| Privacy → category management | Settings graph + `ParentalControlGroupScreen` local coordinator | Protection mode after loading | Visible and system Back call the same graph action and return to Settings; Up/Down explicitly connects the first control and local Back button |
| Choice/PIN/confirmation dialogs | Dialog component while visible | Selected value or first required action | Dismissal exposes the invoking page without changing category/page state; final connected validation verifies each opener retains focus |

Removed competing behavior: Settings no longer composes the global app navigation bar, page entry has no timed `delay(80)`/`delay(100)` focus race, and category/page/search transitions are issued by one attachment-aware coordinator per Settings surface. The parental loading transition uses the same stale-intent check. Dialog-local requesters remain contained inside their modal and do not select an app destination. Final connected tests cover modal opener restoration because host tests cannot observe platform focus after a real dialog closes.

## Sources & guide

| stableSettingId | Old → new location | Label/resource | Read → write/action | Availability / confirmation / aliases |
|---|---|---|---|---|
| `sources.add` | Providers → Sources & guide / Sources | provider add copy | provider list → `onAddProvider` | Always; aliases add playlist/source |
| `sources.active_provider` | Providers → Sources | active provider | active provider → `setActiveProvider` | Provider exists |
| `sources.edit` | Provider card → Source detail | provider name/type | provider → `onEditProvider` | Per provider |
| `sources.sync` | Provider card → Source detail | sync actions | sync state → `syncProviderSection`/`syncProviderCustom` | Per provider; progress/cancel/error retained |
| `sources.delete` | Provider card → Source detail | delete provider | provider → `deleteProvider` | Destructive confirmation retained |
| `sources.parental_categories` | Provider card → Privacy & parental | category controls | active provider → parental route | Provider required; aliases protected/hidden categories |
| `sources.combined.active` | Combined M3U → Sources | combined source | active profile → `setActiveCombinedProfile` | Combined profile exists |
| `sources.combined.members` | Combined M3U → Source detail | provider membership | member state → add/remove/enable combined provider | Profile/provider required |
| `sources.combined.delete` | Combined M3U → Source detail | delete combined profile | profile → `deleteCombinedProfile` | Destructive confirmation |
| `sources.m3u_vod_classification` | Provider options → Source detail / Compatibility | VOD classification | provider flag → `setM3uVodClassificationEnabled` | M3U only |
| `sources.xtream_text_classification` | Privacy → Sources / Compatibility | `settings_xtream_text_classification` | `useXtreamTextClassification` → `toggleXtreamTextClassification` | Xtream compatibility |
| `sources.xtream_base64` | Privacy → Sources / Compatibility | `settings_xtream_base64_compatibility` | `xtreamBase64TextCompatibility` → `toggleXtreamBase64TextCompatibility` | Xtream compatibility |
| `guide.source.add` | Guide → Sources & guide / Guide sources | add EPG source | fields → `addEpgSource` | Name and URL required; file picker retained |
| `guide.source.enabled` | Guide source card → Guide source detail | source enabled | source flag → `toggleEpgSourceEnabled` | Per EPG source |
| `guide.source.refresh` | Guide source card → Guide source detail | refresh | refresh state → `refreshEpgSource` | Per source; busy/error retained |
| `guide.source.timezone` | Guide source card → Guide source detail | timezone | timezone → `updateEpgSourceTimezone` | Validation/error retained |
| `guide.source.delete` | Guide source card → Guide source detail | delete | source → `deleteEpgSource` | Two-step destructive confirmation |
| `guide.assignment` | Provider assignments → Guide source detail | assignments | assignment order → assign/unassign/move APIs | Provider and EPG source required |
| `guide.policy` | Provider assignments → Source detail | guide source policy | provider policy → `setGuideSourcePolicy` | Supported provider types only |
| `guide.logo_policy` | Provider assignments → Source detail | logo source policy | provider policy → `setChannelLogoSourcePolicy` | Supported provider types only |
| `guide.time_shift` | Guide → Guide behavior | EPG time shift | provider minutes → adjust/reset APIs | Per provider |
| `guide.default_category` | Browsing → Live TV / Browsing | `settings_guide_default_category` | category id → `setGuideDefaultCategory` | Categories available |

## Playback

| stableSettingId | Old → new page | Label/resource | Read → write/action | Availability / aliases |
|---|---|---|---|---|
| `playback.media_session` | Playback → General playback | `settings_media_session` | `playerMediaSessionEnabled` → `setPlayerMediaSessionEnabled` | Always; first General target |
| `playback.external` | Playback → General playback | `settings_external_playback` | mode → dialog → `setExternalPlaybackMode` | Compatible external apps |
| `playback.speed` | Playback → General playback | `settings_default_playback_speed` | speed → dialog → `setDefaultPlaybackSpeed` | Always |
| `playback.av_sync` | Playback → Audio | audio/video sync | enabled → `setPlayerAudioVideoSyncEnabled` | Always |
| `playback.av_offset` | Playback → Audio | A/V offset | milliseconds → `setPlayerAudioVideoOffsetMs` | Sync enabled |
| `playback.audio_decoder` | Playback → Audio | audio decoder | mode → `setPlayerAudioDecoderMode` | Always |
| `playback.video_decoder` | Playback → Compatibility | video decoder | mode → `setPlayerVideoDecoderMode` | Always |
| `playback.audio_output` | Playback → Audio | audio output | preference → `setPlayerAudioOutputPreference` | Device capability explained |
| `playback.audio_language` | Playback → Audio | preferred audio language | tag → `setPreferredAudioLanguage` | Always |
| `playback.live_translation` | Playback → Subtitles | `settings_live_translation_enabled` | enabled → `setPlayerLiveTranslationEnabled` | Always |
| `playback.translation_endpoint` | Playback → Subtitles | `settings_live_translation_endpoint` | endpoint → `setPlayerLiveTranslationEndpoint` | Translation enabled |
| `playback.subtitle_size` | Playback → Subtitles | `settings_subtitle_size` | scale → `setSubtitleTextScale` | Always |
| `playback.subtitle_text_color` | Playback → Subtitles | `settings_subtitle_text_color` | ARGB → `setSubtitleTextColor` | Always |
| `playback.subtitle_background` | Playback → Subtitles | `settings_subtitle_background` | ARGB → `setSubtitleBackgroundColor` | Always |
| `playback.fast_retry` | Playback → Network | `settings_fast_retry_on_transient_failures` | enabled → `setPlayerFastRetryOnTransientFailures` | Always |
| `playback.buffer` | Playback → Network | `settings_live_buffer_size` | mode → `setPlayerPlaybackBufferMode` | Always |
| `playback.wifi_cap` | Playback → Network | `settings_wifi_quality_cap` | height → `setWifiQualityCap` | Always |
| `playback.ethernet_cap` | Playback → Network | `settings_ethernet_quality_cap` | height → `setEthernetQualityCap` | Always |
| `playback.speed_test` | Playback → Network | internet speed test | probe state → existing speed-test runner | Network required; progress/error retained |
| `playback.back_button` | Playback → Controls | `settings_player_back_button` | visibility → `setPlayerBackButtonVisibility` | Player setting, distinct from Settings Back |
| `playback.controls_timeout` | Playback → Controls | `settings_player_controls_timeout` | seconds → `setPlayerControlsTimeoutSeconds` | Always |
| `playback.live_overlay_timeout` | Playback → Controls | `settings_live_overlay_timeout` | seconds → `setPlayerLiveOverlayTimeoutSeconds` | Always |
| `playback.notice_timeout` | Playback → Controls | `settings_player_notice_timeout` | seconds → `setPlayerNoticeTimeoutSeconds` | Always |
| `playback.diagnostics_timeout` | Playback → Controls | `settings_player_diagnostics_timeout` | seconds → `setPlayerDiagnosticsTimeoutSeconds` | Always |
| `playback.prevent_standby` | Playback → Timers | `settings_prevent_standby` | enabled → `setPreventStandbyDuringPlayback` | Always |
| `playback.stop_timer` | Playback → Timers | `settings_default_stop_timer` | minutes → `setDefaultStopPlaybackTimerMinutes` | Always |
| `playback.idle_timer` | Playback → Timers | `settings_default_idle_standby_timer` | minutes → `setDefaultIdleStandbyTimerMinutes` | Always |
| `playback.compatibility_memory` | Playback → Compatibility | learned compatibility | enabled → `setPlayerCompatibilityMemoryEnabled` | Always |
| `playback.clear_compatibility` | Playback → Compatibility | clear learned compatibility | state → `clearLearnedPlaybackCompatibility` | Confirmation retained |
| `playback.surface` | Playback → Compatibility | player surface | mode → `setPlayerSurfaceMode` | Always |
| `playback.live_format` | Playback → Compatibility | live stream format | mode → `setPlayerLiveStreamFormatMode` | Advanced |

## Live TV

| stableSettingId | New page | Label/resource | Read → write/action | Dependency |
|---|---|---|---|---|
| `live.mode` | Browsing layout | channel mode | `liveTvChannelMode` → `setLiveTvChannelMode` | Always; first page target |
| `live.auto_hide_categories` | Browsing layout | auto-hide categories | flag → `setLiveTvAutoHideCategories` | Mode-dependent explanation |
| `live.source_switcher` | Browsing layout | show source switcher | flag → `setShowLiveSourceSwitcher` | Always |
| `live.favorites_category` | Browsing layout | favorites category | flag → `setShowFavoritesCategory` | Always |
| `live.all_category` | Browsing layout | all channels category | flag → `setShowAllChannelsCategory` | Always |
| `live.recent_category` | Browsing layout | recent category | flag → `setShowRecentChannelsCategory` | Always |
| `live.hide_decorative_rows` | Channels & grouping | hide decorative rows | flag → `setHideDecorativeLiveRows` | Always |
| `live.numbering` | Channels & grouping | numbering mode | mode → `setLiveChannelNumberingMode` | Always |
| `live.grouping` | Channels & grouping | grouping mode | mode → `setLiveChannelGroupingMode` | Always |
| `live.group_label` | Channels & grouping | grouped label | mode → `setGroupedChannelLabelMode` | Grouping enabled |
| `live.variant_preference` | Channels & grouping | preferred variant | mode → `setLiveVariantPreferenceMode` | Grouping enabled |
| `live.sort` | Channels & grouping | live category sort | mode → `setCategorySortMode(LIVE, …)` | Always |
| `live.quick_filters` | Filters | quick filters | list → add/remove filter APIs | Always |
| `live.filter_visibility` | Filters | quick-filter visibility | mode → `setLiveTvQuickFilterVisibilityMode` | Always |
| `live.timeshift_enabled` | Timeshift | live timeshift | flag → `setPlayerTimeshiftEnabled` | Always |
| `live.timeshift_depth` | Timeshift | timeshift depth | minutes → `setPlayerTimeshiftDepthMinutes` | Timeshift enabled |
| `live.timeshift_backend` | Timeshift | timeshift backend | preference → `setPlayerTimeshiftBackend` | Timeshift enabled/device state |
| `live.zap_auto_revert` | Timeshift | auto-return on failed channel | flag → `setZapAutoRevert` | Always |
| `live.clock_enabled` | Clock | live clock | flag → `setPlayerLiveClockEnabled` | Always |
| `live.clock_position` | Clock | clock position | value → `setPlayerLiveClockPosition` | Clock enabled |
| `live.clock_size` | Clock | clock size | value → `setPlayerLiveClockSize` | Clock enabled |
| `live.clock_font` | Clock | clock font | value → `setPlayerLiveClockFont` | Clock enabled |
| `live.multiview_center_two` | Multiview | center two-slot layout | flag → `setCenterTwoSlotMultiviewLayout` | Always |
| `live.multiview_connection_limit` | Multiview | respect connection limit | flag → `setMultiViewRespectProviderConnectionLimit` | Always |

## Movies & series

| stableSettingId | New page | Read → write/action | Dependency |
|---|---|---|---|
| `vod.view_mode` | Library | `vodViewMode` → `setVodViewMode` | Always |
| `vod.type_badge_icon` | Library | flag → `setVodTypeBadgeAsIcon` | Always |
| `vod.complete_on_open` | Library | load mode → `setVodCompleteOnOpen` | Always |
| `vod.infinite_scroll` | Library | flag → `setVodInfiniteScroll` | Load-mode dependent |
| `vod.portal_search` | Library | flag → `setVodPortalSearch` | Provider capability |
| `vod.duplicate_handling` | Organization | mode → `setVodDuplicateHandlingMode` | Always |
| `vod.variant_preference` | Organization | mode → `setVodVariantPreferenceMode` | Duplicate mode is not SHOW_ALL |
| `vod.movie_sort` | Organization | mode → `setCategorySortMode(MOVIE, …)` | Always |
| `vod.series_sort` | Organization | mode → `setCategorySortMode(SERIES, …)` | Always |
| `vod.auto_next_episode` | Episode playback | flag → `setAutoPlayNextEpisode` | Always |
| `vod.http_protocol` | Episode playback | mode → `setPlayerVodHttpProtocolMode` | Advanced |

## Appearance & remote

| stableSettingId | New page | Read → write/action | Notes |
|---|---|---|---|
| `appearance.theme` | Appearance | theme → `setAppTheme` | Classic blue, M3 purple, Light; update in place |
| `appearance.language` | Appearance | locale → `setAppLanguage` | All shipped locales |
| `appearance.time_format` | Appearance | format → `setAppTimeFormat` | Always |
| `appearance.top_navigation` | Home & navigation | destinations → `setAppTopLevelDestinations` | Ordered list dialog |
| `appearance.home_shelves` | Home & navigation | shelves → set/reset dashboard APIs | Ordered list dialog |
| `appearance.landing` | Home & navigation | destination → `setAppLandingDestination` | Always |
| `remote.shortcuts.global.red` | Remote | Global / red selection → `setRemoteShortcutSelection` | Profile default and explicit action retained |
| `remote.shortcuts.global.green` | Remote | Global / green selection → `setRemoteShortcutSelection` | Profile default and explicit action retained |
| `remote.shortcuts.global.yellow` | Remote | Global / yellow selection → `setRemoteShortcutSelection` | Profile default and explicit action retained |
| `remote.shortcuts.global.blue` | Remote | Global / blue selection → `setRemoteShortcutSelection` | Profile default and explicit action retained |
| `remote.shortcuts.playback.red` | Remote | Playback / red selection → `setRemoteShortcutSelection` | Profile/global defaults and explicit action retained |
| `remote.shortcuts.playback.green` | Remote | Playback / green selection → `setRemoteShortcutSelection` | Profile/global defaults and explicit action retained |
| `remote.shortcuts.playback.yellow` | Remote | Playback / yellow selection → `setRemoteShortcutSelection` | Profile/global defaults and explicit action retained |
| `remote.shortcuts.playback.blue` | Remote | Playback / blue selection → `setRemoteShortcutSelection` | Profile/global defaults and explicit action retained |
| `remote.shortcuts.browse.red` | Remote | Browse / red selection → `setRemoteShortcutSelection` | Profile/global defaults and explicit action retained |
| `remote.shortcuts.browse.green` | Remote | Browse / green selection → `setRemoteShortcutSelection` | Profile/global defaults and explicit action retained |
| `remote.shortcuts.browse.yellow` | Remote | Browse / yellow selection → `setRemoteShortcutSelection` | Profile/global defaults and explicit action retained |
| `remote.shortcuts.browse.blue` | Remote | Browse / blue selection → `setRemoteShortcutSelection` | Profile/global defaults and explicit action retained |

## Privacy & parental

| stableSettingId | New page | Read → write/action | Dependency / confirmation |
|---|---|---|---|
| `privacy.protection_level` | Parental controls | level → PIN flow → `setParentalControlLevel` | Existing PIN verification/creation retained |
| `privacy.pin` | Parental controls | PIN state → set/change PIN flow | PIN never exposed in search value |
| `privacy.category_protection` | Parental categories | pending protection → save/reset APIs | Provider; PIN required to save |
| `privacy.category_visibility` | Parental categories | hidden state → toggle/hide all/unhide all | Provider required |
| `privacy.incognito` | Privacy & history | `isIncognitoMode` → `toggleIncognitoMode` | Always |
| `privacy.clear_history` | Privacy & history | playback history → `clearHistory` | Destructive confirmation |

## Recordings

| stableSettingId | New page | Read → write/action | Dependency |
|---|---|---|---|
| `recording.browser` | Status & files | recording items → open browser | Operational states retained |
| `recording.reconcile` | Status & files | storage/database → `reconcileRecordings` | Storage available |
| `recording.folder` | Storage | folder → `updateRecordingFolder` | Document permission retained |
| `recording.app_storage` | Storage | folder → `updateRecordingFolder(null, null)` | Always |
| `recording.usb_storage` | Storage | removable directory → `useUsbRecordingStorage` | USB action only when available |
| `recording.filename` | Defaults | pattern → `updateRecordingFileNamePattern` | Validation retained |
| `recording.retention` | Defaults | days → `updateRecordingRetentionDays` | Always |
| `recording.concurrency` | Defaults | count → `updateRecordingMaxSimultaneous` | Existing bounds |
| `recording.padding` | Defaults | before/after → padding setters | Existing bounds |
| `recording.wifi_only` | Defaults | flag → `setRecordingWifiOnly` | Always |

## Backup, support, and app actions

| stableSettingId | New page | Read → write/action | Dependency / confirmation |
|---|---|---|---|
| `backup.create` | Local backup | backup state → `exportConfig`/platform file host | TV picker-free fallback retained |
| `backup.restore` | Local backup | selected URI → `inspectBackup` then import plan | Preview/conflict confirmation retained |
| `backup.manage_local` | Local backup | managed files → list/delete platform host | Delete confirmation retained |
| `backup.share` | Local backup | export → platform share | Share failure retained |
| `backup.usb_create` | Local backup | removable dir → export | USB only |
| `backup.usb_restore` | Local backup | removable backups → preview | USB only |
| `backup.drive_auth` | Cloud backup | auth → begin sign-in/sign-out | Drive capability/account state |
| `backup.drive_push` | Cloud backup | sync state → `pushToDrive` | Signed in |
| `backup.drive_pull` | Cloud backup | snapshots → `pullFromDrive`/select | Signed in; conflict preview |
| `backup.drive_manage` | Cloud backup | snapshots → manage/delete | Signed in; destructive confirmation |
| `about.auto_update_check` | Updates | flag → `setAutoCheckAppUpdates` | Platform update capability |
| `about.auto_update_download` | Updates | flag → `setAutoDownloadAppUpdates` | Auto-check enabled |
| `about.check_update` | Updates | update state → `checkForAppUpdates` | Busy/error retained |
| `about.download_install` | Updates | action state → download/install callbacks | Permission-required state retained |
| `about.release` | Updates | release URL → `onOpenUri` | URL available |
| `support.crash_view` | Diagnostics | report → `viewCrashReport` | Report exists |
| `support.crash_share` | Diagnostics | report → platform share callback | Report exists |
| `support.crash_delete` | Diagnostics | report → `deleteCrashReport` | Report exists; destructive |
| `about.github` | About | URL → `onOpenUri` | Always |
| `about.donate` | About | URL → `onOpenUri` | Always |
| `about.close_app` | About | action → `onCloseApp` | Explicit action |

## Route and focus preservation

- Settings root: visible exit control returns through `NavigationActions.back`.
- Every nested page: visible Back and remote/system Back return one level and restore the exact page opener.
- Forward page entry: first enabled content row receives focus; Back remains visible and reachable by Up.
- Category selection: the first category destination or direct control receives focus. No Settings transition targets app Home.
- Parental category route: first mode control receives focus only after loading completes; loading completion must not overwrite a later user move.
- Dialog completion/dismissal: return to the exact invoking stable ID.
- Search: result stores its stable ID, owning category/page, and return point; sensitive values such as PIN are never indexed.

## Inventory audit result

The completed source audit compares this inventory against `SettingsUiState`, every public Settings mutation/action in `SettingsViewModel`, provider/EPG/recording operational components, `SettingsPreferences`, `PreferenceBackupRegistry`, and conditional platform callbacks. It found and added the previously omitted `live.zap_auto_revert` control and expanded the remote shortcut matrix into its 12 independently persisted profile/button fields.

The inventory and production search catalog now contain the same 143 unique stable IDs. Dynamic provider, combined-source, EPG, parental, USB, Drive, and update actions retain their availability rules; unavailable actions are excluded from search and dependent preferences remain visible with a prerequisite explanation. The localization coverage test verifies every replacement resource and formatting placeholder in all 25 shipped translated resource packs.
