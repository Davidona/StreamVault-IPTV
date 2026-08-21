package com.streamvault.app.ui.screens.settings

import android.app.Application
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.R
import com.streamvault.app.BuildConfig
import com.streamvault.app.diagnostics.CrashReportStore
import com.streamvault.app.tv.LauncherRecommendationsManager
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.app.update.AppUpdateInstaller
import com.streamvault.app.update.GitHubReleaseChecker
import com.streamvault.app.update.isRemoteVersionNewer
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.dao.XtreamLiveOnboardingDao
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.domain.model.VodCategoryLoadMode
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.BackupRestoreStatusStore
import com.streamvault.domain.manager.DriveBackupSyncManager
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.ExternalPlaybackMode
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStorageConfig
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutSelection
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.TimeshiftBackendPreference
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.usecase.ExportBackup
import com.streamvault.domain.usecase.ExportBackupCommand
import com.streamvault.domain.usecase.ExportBackupResult
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.ImportBackupCommand
import com.streamvault.domain.usecase.ImportBackupResult
import com.streamvault.domain.usecase.InspectBackupCommand
import com.streamvault.domain.usecase.InspectBackupResult
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.SyncProvider
import com.streamvault.domain.usecase.SyncProviderCommand
import com.streamvault.domain.usecase.SyncProviderResult
import com.streamvault.player.AudioCompatibilityMemoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.max
import javax.inject.Inject

private const val BACKGROUND_INDEX_STATUS_PREFIX = "Background index:"

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel @Inject constructor(
    application: Application,
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val categoryRepository: CategoryRepository,
    private val channelRepository: ChannelRepository,
    private val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
    private val programDao: ProgramDao,
    private val preferencesRepository: PreferencesRepository,
    private val internetSpeedTestRunner: InternetSpeedTestRunner,
    private val backupManager: BackupManager,
    private val backupRestoreStatusStore: BackupRestoreStatusStore,
    private val driveBackupSyncManager: DriveBackupSyncManager,
    private val recordingManager: RecordingManager,
    private val parentalControlManager: ParentalControlManager,
    private val syncManager: ProviderSyncCommands,
    private val xtreamIndexJobDao: XtreamIndexJobDao,
    private val xtreamLiveOnboardingDao: XtreamLiveOnboardingDao,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val playbackHistoryRepository: com.streamvault.domain.repository.PlaybackHistoryRepository,
    private val watchNextManager: WatchNextManager,
    private val launcherRecommendationsManager: LauncherRecommendationsManager,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager,
    private val syncProvider: SyncProvider,
    private val epgSourceRepository: com.streamvault.domain.repository.EpgSourceRepository,
    private val gitHubReleaseChecker: GitHubReleaseChecker,
    private val appUpdateInstaller: AppUpdateInstaller,
    private val getCustomCategories: GetCustomCategories,
    private val audioCompatibilityMemoryStore: AudioCompatibilityMemoryStore
) : ViewModel() {
    private val appContext = application
    private val exportBackup = ExportBackup(backupManager)
    private val importBackup = ImportBackup(backupManager)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    val playerLiveStreamFormatMode: StateFlow<LiveStreamFormatMode> =
        preferencesRepository.playerLiveStreamFormatMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LiveStreamFormatMode.AUTO
        )
    private val activeProviderIdFlow = providerRepository.getActiveProvider().map { it?.id }
    private val appUpdateActions = SettingsAppUpdateActions(
        appContext = application,
        preferencesRepository = preferencesRepository,
        gitHubReleaseChecker = gitHubReleaseChecker,
        appUpdateInstaller = appUpdateInstaller,
        uiState = _uiState
    )
    private val backupActions = SettingsBackupActions(
        exportBackup = exportBackup,
        importBackup = importBackup,
        uiState = _uiState
    )
    private val driveBackupActions = SettingsDriveBackupActions(
        driveManager = driveBackupSyncManager,
        importBackup = importBackup,
        providerRepository = providerRepository,
        uiState = _uiState
    )
    private val recordingActions = SettingsRecordingActions(
        appContext = application,
        recordingManager = recordingManager,
        uiState = _uiState
    )
    private val providerActions = SettingsProviderActions(
        providerRepository = providerRepository,
        combinedM3uRepository = combinedM3uRepository,
        preferencesRepository = preferencesRepository,
        syncProvider = syncProvider,
        syncManager = syncManager,
        syncMetadataRepository = syncMetadataRepository,
        watchNextManager = watchNextManager,
        launcherRecommendationsManager = launcherRecommendationsManager,
        tvInputChannelSyncManager = tvInputChannelSyncManager,
        uiState = _uiState
    )
    private val syncActions = SettingsSyncActions(
        appContext = application,
        syncManager = syncManager,
        tvInputChannelSyncManager = tvInputChannelSyncManager,
        uiState = _uiState,
        refreshProvider = { scope, providerId, syncMode, progressPrefix, startedAt, sectionLabel, isCancelable ->
            providerActions.refreshProvider(
                scope = scope,
                providerId = providerId,
                syncMode = syncMode,
                progressPrefix = progressPrefix,
                startedAt = startedAt,
                sectionLabel = sectionLabel,
                isCancelable = isCancelable
            )
        }
    )
    private val epgActions = SettingsEpgActions(
        epgSourceRepository = epgSourceRepository,
        uiState = _uiState
    )

    init {
        refreshCrashReport()
        registerPreferenceObservers()
        registerXtreamIndexJobObserver()
        registerXtreamLiveOnboardingObserver()
        registerSettingsAppUpdateObservers(
            scope = viewModelScope,
            preferencesRepository = preferencesRepository,
            appUpdateActions = appUpdateActions,
            appUpdateInstaller = appUpdateInstaller,
            uiState = _uiState
        )
        registerCombinedProfileObservers(
            scope = viewModelScope,
            combinedM3uRepository = combinedM3uRepository,
            uiState = _uiState
        )
        registerDerivedStateObservers(
            scope = viewModelScope,
            providerRepository = providerRepository,
            syncMetadataRepository = syncMetadataRepository,
            movieRepository = movieRepository,
            seriesRepository = seriesRepository,
            programDao = programDao,
            application = appContext,
            preferencesRepository = preferencesRepository,
            activeProviderIdFlow = activeProviderIdFlow,
            categoryRepository = categoryRepository,
            combinedM3uRepository = combinedM3uRepository,
            channelRepository = channelRepository,
            getCustomCategories = getCustomCategories,
            uiState = _uiState
        )
        registerRecordingObservers(
            scope = viewModelScope,
            recordingManager = recordingManager,
            preferencesRepository = preferencesRepository,
            uiState = _uiState
        )
        registerEpgObservers(
            scope = viewModelScope,
            epgSourceRepository = epgSourceRepository,
            preferencesRepository = preferencesRepository,
            uiState = _uiState
        )
        driveBackupActions.observeAuthState(viewModelScope)
        viewModelScope.launch {
            backupRestoreStatusStore.observeRestoreJobs().collect { jobs ->
                _uiState.update { it.copy(backupRestoreJobs = jobs) }
            }
        }
    }

    fun refreshCrashReport() {
        val report = CrashReportStore.latestReport(appContext)
        _uiState.update { state ->
            state.copy(
                crashReport = report?.toUiModel() ?: CrashReportUiModel(),
                viewedCrashReport = state.viewedCrashReport?.let { report?.toUiModel() }
            )
        }
    }

    fun viewCrashReport() {
        refreshCrashReport()
        _uiState.update { state ->
            state.copy(viewedCrashReport = state.crashReport.takeIf { it.hasReport })
        }
    }

    fun dismissCrashReport() {
        _uiState.update { it.copy(viewedCrashReport = null) }
    }

    fun deleteCrashReport() {
        val deleted = CrashReportStore.deleteLatestReport(appContext)
        _uiState.update {
            it.copy(
                crashReport = CrashReportUiModel(),
                viewedCrashReport = null,
                userMessage = if (deleted) {
                    appContext.getString(R.string.settings_crash_report_deleted)
                } else {
                    appContext.getString(R.string.settings_crash_report_delete_failed)
                }
            )
        }
    }

    private fun com.streamvault.app.diagnostics.CrashReportSummary.toUiModel(): CrashReportUiModel =
        CrashReportUiModel(
            timestamp = timestamp,
            exception = exception,
            fileName = fileName,
            content = content
        )

    private fun registerPreferenceObservers() {
        viewModelScope.launch {
            observeSettingsPreferenceSnapshot(
                providerRepository = providerRepository,
                activeProviderIdFlow = activeProviderIdFlow,
                preferencesRepository = preferencesRepository
            ).collect { snapshot ->
                val previousProviderIds = _uiState.value.providers.map { it.id }.toSet()
                _uiState.update { it.applyPreferenceSnapshot(snapshot) }
                val currentProviderIds = snapshot.providers.map { it.id }.toSet()
                val removedIds = previousProviderIds - currentProviderIds
                if (removedIds.isNotEmpty()) {
                    epgActions.cleanupEpgAssignmentsFor(removedIds)
                }
            }
        }
    }

    private fun registerXtreamIndexJobObserver() {
        viewModelScope.launch {
            xtreamIndexJobDao.observeAll().collect { jobs ->
                val jobWarningsByProvider = jobs
                    .groupBy { it.providerId }
                    .mapValues { (_, providerJobs) ->
                        providerJobs.mapNotNull { job -> job.toSettingsWarningMessage() }
                    }
                    .filterValues { it.isNotEmpty() }

                _uiState.update { state ->
                    val providerIds = state.providers.map { it.id }.toSet()
                    val preservedWarnings = state.syncWarningsByProvider
                        .filterKeys { it in providerIds }
                        .mapValues { (_, warnings) ->
                            warnings.filterNot { warning -> warning.startsWith(BACKGROUND_INDEX_STATUS_PREFIX) }
                        }
                        .filterValues { it.isNotEmpty() }
                    state.copy(
                        syncWarningsByProvider = preservedWarnings + jobWarningsByProvider,
                        xtreamIndexSectionStatusByProvider = jobs
                            .filter { job -> job.providerId in providerIds }
                            .mapNotNull { job ->
                                val section = job.section.toCatalogSectionKey() ?: return@mapNotNull null
                                job.providerId to (section to job.state.toCatalogCountStatus())
                            }
                            .groupBy({ it.first }, { it.second })
                            .mapValues { (_, pairs) -> pairs.toMap() }
                    )
                }
            }
        }
    }

    private fun String.toCatalogSectionKey(): String? = when (uppercase()) {
        "LIVE" -> "LIVE"
        "MOVIE" -> "MOVIE"
        "SERIES" -> "SERIES"
        "EPG" -> "EPG"
        else -> null
    }

    private fun String.toCatalogCountStatus(): ProviderCatalogCountStatus = when (uppercase()) {
        "QUEUED", "STALE" -> ProviderCatalogCountStatus.QUEUED
        "RUNNING" -> ProviderCatalogCountStatus.SYNCING
        "PARTIAL" -> ProviderCatalogCountStatus.PARTIAL
        "FAILED_RETRYABLE", "FAILED_PERMANENT" -> ProviderCatalogCountStatus.FAILED
        "SUCCESS" -> ProviderCatalogCountStatus.READY
        else -> ProviderCatalogCountStatus.PENDING
    }

    private fun registerXtreamLiveOnboardingObserver() {
        viewModelScope.launch {
            xtreamLiveOnboardingDao.observeIncomplete().collect { states ->
                _uiState.update { state ->
                    val providerIds = state.providers.map { it.id }.toSet()
                    state.copy(
                        xtreamLiveOnboardingPhaseByProvider = states
                            .filter { onboarding -> onboarding.providerId in providerIds }
                            .associate { onboarding -> onboarding.providerId to onboarding.phase },
                        xtreamLiveOnboardingByProvider = states
                            .filter { onboarding -> onboarding.providerId in providerIds }
                            .associate { onboarding -> onboarding.providerId to onboarding.toUiModel() }
                    )
                }
            }
        }
    }

    private fun XtreamIndexJobEntity.toSettingsWarningMessage(): String? {
        val label = when (section) {
            "LIVE" -> "Live TV"
            "MOVIE" -> "Movies"
            "SERIES" -> "Series"
            "EPG" -> "EPG"
            else -> section.lowercase().replaceFirstChar { it.titlecase() }
        }
        return when (state) {
            "PARTIAL" -> "$BACKGROUND_INDEX_STATUS_PREFIX $label partial: ${indexedRows} indexed"
            "FAILED_RETRYABLE" -> "$BACKGROUND_INDEX_STATUS_PREFIX $label retryable failed${lastError?.let { ": $it" }.orEmpty()}"
            "FAILED_PERMANENT" -> "$BACKGROUND_INDEX_STATUS_PREFIX $label permanently failed${lastError?.let { ": $it" }.orEmpty()}"
            else -> null
        }
    }

    fun setActiveProvider(providerId: Long) {
        providerActions.setActiveProvider(viewModelScope, providerId)
    }

    fun setActiveCombinedProfile(profileId: Long) {
        providerActions.setActiveCombinedProfile(viewModelScope, profileId)
    }

    fun createCombinedProfile(name: String, providerIds: List<Long>, onSuccess: () -> Unit = {}, onError: () -> Unit = {}) {
        providerActions.createCombinedProfile(viewModelScope, name, providerIds, onSuccess, onError)
    }

    fun deleteCombinedProfile(profileId: Long) {
        providerActions.deleteCombinedProfile(viewModelScope, profileId)
    }

    fun addProviderToCombinedProfile(profileId: Long, providerId: Long, onSuccess: () -> Unit = {}, onError: () -> Unit = {}) {
        providerActions.addProviderToCombinedProfile(viewModelScope, profileId, providerId, onSuccess, onError)
    }

    fun renameCombinedProfile(profileId: Long, name: String, onSuccess: () -> Unit = {}, onError: () -> Unit = {}) {
        providerActions.renameCombinedProfile(viewModelScope, profileId, name, onSuccess, onError)
    }

    fun removeProviderFromCombinedProfile(profileId: Long, providerId: Long) {
        providerActions.removeProviderFromCombinedProfile(viewModelScope, profileId, providerId)
    }

    fun moveCombinedProvider(profileId: Long, providerId: Long, moveUp: Boolean) {
        providerActions.moveCombinedProvider(viewModelScope, profileId, providerId, moveUp)
    }

    fun setCombinedProviderEnabled(profileId: Long, providerId: Long, enabled: Boolean) {
        providerActions.setCombinedProviderEnabled(viewModelScope, profileId, providerId, enabled)
    }

    fun setM3uVodClassificationEnabled(providerId: Long, enabled: Boolean) {
        providerActions.setM3uVodClassificationEnabled(viewModelScope, providerId, enabled)
    }

    fun setGuideSourcePolicy(providerId: Long, policy: GuideSourcePolicy) {
        providerActions.setGuideSourcePolicy(viewModelScope, providerId, policy)
    }

    fun setChannelLogoSourcePolicy(providerId: Long, policy: ChannelLogoSourcePolicy) {
        providerActions.setChannelLogoSourcePolicy(viewModelScope, providerId, policy)
    }

    fun refreshProviderClassification(providerId: Long) {
        refreshProvider(providerId)
    }

    fun setParentalControlLevel(level: Int) {
        viewModelScope.launch {
            preferencesRepository.setParentalControlLevel(level)
        }
    }

    fun setAppLanguage(language: String) {
        viewModelScope.launch {
            preferencesRepository.setAppLanguage(language)
        }
    }

    fun setAppLandingDestination(destination: AppLandingDestination) {
        viewModelScope.launch {
            preferencesRepository.setAppLandingDestination(
                AppTopLevelDestination.resolveLandingDestination(
                    preferred = destination,
                    destinations = _uiState.value.appTopLevelDestinations
                )
            )
        }
    }

    fun setAppTopLevelDestinations(destinations: List<AppTopLevelDestination>) {
        viewModelScope.launch {
            val normalized = AppTopLevelDestination.normalizeForStorage(destinations)
            val currentLanding = _uiState.value.appLandingDestination
            val resolvedLanding = AppTopLevelDestination.resolveLandingDestination(
                preferred = currentLanding,
                destinations = normalized
            )
            preferencesRepository.setAppTopLevelDestinations(normalized)
            if (resolvedLanding != currentLanding) {
                preferencesRepository.setAppLandingDestination(resolvedLanding)
                _uiState.update {
                    it.copy(
                        userMessage = appContext.getString(
                            R.string.settings_top_navigation_default_updated,
                            appContext.getString(resolvedLanding.labelResId())
                        )
                    )
                }
            }
        }
    }

    fun setAppHomeDashboardShelves(shelves: List<AppHomeDashboardShelf>) {
        viewModelScope.launch {
            preferencesRepository.setAppHomeDashboardShelves(
                AppHomeDashboardShelf.normalizeForStorage(shelves)
            )
        }
    }

    fun resetAppHomeDashboardShelves() {
        viewModelScope.launch {
            preferencesRepository.setAppHomeDashboardShelves(AppHomeDashboardShelf.defaultOrder)
        }
    }

    fun setLiveTvChannelMode(mode: LiveTvChannelMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveTvChannelMode(mode.name)
        }
    }

    fun setShowLiveSourceSwitcher(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowLiveSourceSwitcher(enabled)
        }
    }

    fun setShowAllChannelsCategory(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowAllChannelsCategory(enabled)
        }
    }

    fun setShowFavoritesCategory(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowFavoritesCategory(enabled)
        }
    }

    fun setShowRecentChannelsCategory(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowRecentChannelsCategory(enabled)
        }
    }

    fun setRemoteShortcutSelection(
        profile: RemoteShortcutProfile,
        button: RemoteColorButton,
        selection: RemoteShortcutSelection
    ) {
        viewModelScope.launch {
            preferencesRepository.setRemoteShortcutSelection(profile, button, selection)
        }
    }

    fun setLiveTvQuickFilterVisibilityMode(mode: LiveTvQuickFilterVisibilityMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveTvQuickFilterVisibility(mode.storageValue)
        }
    }

    fun addLiveTvCategoryFilter(filter: String) {
        viewModelScope.launch {
            val normalized = filter.trim()
            when {
                normalized.isBlank() -> {
                    _uiState.update {
                        it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_blank))
                    }
                }
                _uiState.value.liveTvCategoryFilters.any { existing ->
                    existing.equals(normalized, ignoreCase = true)
                } -> {
                    _uiState.update {
                        it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_duplicate, normalized))
                    }
                }
                preferencesRepository.addLiveTvCategoryFilter(normalized) -> {
                    _uiState.update {
                        it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_added, normalized))
                    }
                }
            }
        }
    }

    fun removeLiveTvCategoryFilter(filter: String) {
        viewModelScope.launch {
            if (preferencesRepository.removeLiveTvCategoryFilter(filter)) {
                _uiState.update {
                    it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_removed, filter))
                }
            }
        }
    }

    fun setLiveChannelNumberingMode(mode: ChannelNumberingMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveChannelNumberingMode(mode)
        }
    }

    fun setHideDecorativeLiveRows(hide: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setHideDecorativeLiveRows(hide)
        }
    }

    fun setLiveChannelGroupingMode(mode: LiveChannelGroupingMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveChannelGroupingMode(mode)
        }
    }

    fun setGroupedChannelLabelMode(mode: GroupedChannelLabelMode) {
        viewModelScope.launch {
            preferencesRepository.setGroupedChannelLabelMode(mode)
        }
    }

    fun setLiveVariantPreferenceMode(mode: LiveVariantPreferenceMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveVariantPreferenceMode(mode)
        }
    }

    fun setVodViewMode(mode: VodViewMode) {
        viewModelScope.launch {
            preferencesRepository.setVodViewMode(mode.storageValue)
        }
    }

    fun setVodInfiniteScroll(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setVodInfiniteScroll(enabled)
        }
    }

    fun setVodCompleteOnOpen(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setVodCategoryLoadMode(
                if (enabled) VodCategoryLoadMode.COMPLETE_ON_OPEN else VodCategoryLoadMode.PAGED
            )
        }
    }

    fun setVodDuplicateHandlingMode(mode: VodDuplicateHandlingMode) {
        viewModelScope.launch {
            preferencesRepository.setVodDuplicateHandlingMode(mode)
        }
    }

    fun setVodVariantPreferenceMode(mode: VodVariantPreferenceMode) {
        viewModelScope.launch {
            preferencesRepository.setVodVariantPreferenceMode(mode)
        }
    }

    fun setGuideDefaultCategory(categoryId: Long) {
        viewModelScope.launch {
            preferencesRepository.setGuideDefaultCategoryId(categoryId)
        }
    }

    fun setAppTimeFormat(format: AppTimeFormat) {
        viewModelScope.launch {
            preferencesRepository.setAppTimeFormat(format)
        }
    }

    fun setPreventStandbyDuringPlayback(prevent: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPreventStandbyDuringPlayback(prevent)
        }
    }

    fun setAutoPlayNextEpisode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoPlayNextEpisode(enabled)
        }
    }

    fun setAutoCheckAppUpdates(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoCheckAppUpdates(enabled)
        }
    }

    fun setAutoDownloadAppUpdates(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoDownloadAppUpdates(enabled)
        }
    }

    fun refreshDownloadState() {
        viewModelScope.launch {
            appUpdateInstaller.refreshState()
        }
    }

    fun checkForAppUpdates() {
        appUpdateActions.checkForAppUpdates(
            scope = viewModelScope,
            manual = true,
            isRemoteVersionNewer = ::isRemoteVersionNewer
        )
    }

    fun downloadLatestUpdate() {
        appUpdateActions.downloadLatestUpdate(viewModelScope)
    }

    fun installDownloadedUpdate() {
        appUpdateActions.installDownloadedUpdate(viewModelScope)
    }

    fun setCategorySortMode(type: ContentType, mode: CategorySortMode) {
        val providerId = _uiState.value.activeProviderId ?: return
        viewModelScope.launch {
            preferencesRepository.setCategorySortMode(providerId, type, mode)
        }
    }

    fun unhideCategory(category: Category) {
        val providerId = _uiState.value.activeProviderId ?: return
        viewModelScope.launch {
            preferencesRepository.setCategoryHidden(
                providerId = providerId,
                type = category.type,
                categoryId = category.id,
                hidden = false
            )
            _uiState.update { it.copy(userMessage = "Unhid ${category.name}") }
        }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch {
            preferencesRepository.setPlayerPlaybackSpeed(speed)
        }
    }

    fun setExternalPlaybackMode(mode: ExternalPlaybackMode) {
        viewModelScope.launch {
            preferencesRepository.setPlayerExternalPlaybackMode(mode)
        }
    }

    fun setPlayerAudioVideoOffsetMs(offsetMs: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerAudioVideoOffsetMs(offsetMs)
        }
    }

    fun setPlayerAudioVideoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPlayerAudioVideoSyncEnabled(enabled)
        }
    }

    fun setCenterTwoSlotMultiviewLayout(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMultiViewCenterTwoSlotLayout(enabled)
        }
    }

    fun setMultiViewRespectProviderConnectionLimit(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMultiViewRespectProviderConnectionLimit(enabled)
        }
    }

    fun setPlayerMediaSessionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPlayerMediaSessionEnabled(enabled)
        }
    }

    fun setPlayerFastRetryOnTransientFailures(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPlayerFastRetryOnTransientFailures(enabled)
        }
    }

    fun setPlayerTimeshiftEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPlayerTimeshiftEnabled(enabled)
        }
    }

    fun setPlayerLiveTranslationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPlayerLiveTranslationEnabled(enabled)
        }
    }

    fun setPlayerLiveTranslationEndpoint(endpoint: String) {
        viewModelScope.launch {
            preferencesRepository.setPlayerLiveTranslationEndpoint(endpoint)
        }
    }

    fun setPlayerTimeshiftDepthMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerTimeshiftDepthMinutes(minutes)
        }
    }

    fun setPlayerTimeshiftBackend(preference: TimeshiftBackendPreference) {
        viewModelScope.launch {
            preferencesRepository.setPlayerTimeshiftBackend(preference)
        }
    }

    fun setDefaultStopPlaybackTimerMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setDefaultStopPlaybackTimerMinutes(minutes)
        }
    }

    fun setDefaultIdleStandbyTimerMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setDefaultIdleStandbyTimerMinutes(minutes)
        }
    }

    fun setZapAutoRevert(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setZapAutoRevert(enabled)
        }
    }

    fun setRecordingWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setRecordingWifiOnly(enabled)
        }
    }

    fun setRecordingPaddingBeforeMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setRecordingPaddingBeforeMinutes(minutes)
        }
    }

    fun setRecordingPaddingAfterMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setRecordingPaddingAfterMinutes(minutes)
        }
    }

    fun setPlayerAudioDecoderMode(mode: DecoderMode) {
        viewModelScope.launch {
            preferencesRepository.setPlayerAudioDecoderMode(mode)
        }
    }

    fun setPlayerVideoDecoderMode(mode: DecoderMode) {
        viewModelScope.launch {
            preferencesRepository.setPlayerVideoDecoderMode(mode)
        }
    }

    fun setPlayerPlaybackBufferMode(mode: PlaybackBufferMode) {
        viewModelScope.launch {
            preferencesRepository.setPlayerPlaybackBufferMode(mode)
        }
    }

    fun setPlayerAudioOutputPreference(preference: AudioOutputPreference) {
        viewModelScope.launch {
            preferencesRepository.setPlayerAudioOutputPreference(preference)
        }
    }

    fun setPlayerCompatibilityMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPlayerCompatibilityMemoryEnabled(enabled)
        }
    }

    fun clearLearnedPlaybackCompatibility() {
        audioCompatibilityMemoryStore.clear()
        _uiState.update {
            it.copy(userMessage = appContext.getString(R.string.settings_ffmpeg_compatibility_cleared))
        }
    }

    fun setPlayerSurfaceMode(mode: com.streamvault.domain.model.PlayerSurfaceMode) {
        viewModelScope.launch {
            preferencesRepository.setPlayerSurfaceMode(mode)
        }
    }

    fun setPlayerLiveStreamFormatMode(mode: LiveStreamFormatMode) {
        viewModelScope.launch {
            preferencesRepository.setPlayerLiveStreamFormatMode(mode)
        }
    }

    fun setPlayerVodHttpProtocolMode(mode: VodHttpProtocolMode) {
        viewModelScope.launch {
            preferencesRepository.setPlayerVodHttpProtocolMode(mode)
        }
    }

    fun setPlayerControlsTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerControlsTimeoutSeconds(seconds)
        }
    }

    fun setPlayerLiveOverlayTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerLiveOverlayTimeoutSeconds(seconds)
        }
    }

    fun setPlayerNoticeTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerNoticeTimeoutSeconds(seconds)
        }
    }

    fun setPlayerDiagnosticsTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerDiagnosticsTimeoutSeconds(seconds)
        }
    }

    fun setPreferredAudioLanguage(languageTag: String?) {
        viewModelScope.launch {
            preferencesRepository.setPreferredAudioLanguage(languageTag)
        }
    }

    fun setSubtitleTextScale(scale: Float) {
        viewModelScope.launch {
            preferencesRepository.setPlayerSubtitleTextScale(scale)
        }
    }

    fun setSubtitleTextColor(colorArgb: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerSubtitleTextColor(colorArgb)
        }
    }

    fun setSubtitleBackgroundColor(colorArgb: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerSubtitleBackgroundColor(colorArgb)
        }
    }

    fun setWifiQualityCap(maxHeight: Int?) {
        viewModelScope.launch {
            preferencesRepository.setPlayerWifiMaxVideoHeight(maxHeight)
        }
    }

    fun setEthernetQualityCap(maxHeight: Int?) {
        viewModelScope.launch {
            preferencesRepository.setPlayerEthernetMaxVideoHeight(maxHeight)
        }
    }

    fun runInternetSpeedTest() {
        if (_uiState.value.isRunningInternetSpeedTest) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningInternetSpeedTest = true) }
            when (val result = internetSpeedTestRunner.run()) {
                is InternetSpeedTestResult.Success -> {
                    val snapshot = result.snapshot
                    preferencesRepository.setLastSpeedTestResult(
                        megabitsPerSecond = snapshot.megabitsPerSecond,
                        measuredAtMs = snapshot.measuredAtMs,
                        transport = snapshot.transport.name,
                        recommendedMaxHeight = snapshot.recommendedMaxVideoHeight,
                        estimated = snapshot.isEstimated
                    )
                    _uiState.update {
                        it.copy(
                            isRunningInternetSpeedTest = false,
                            userMessage = if (snapshot.isEstimated) {
                                appContext.getString(R.string.settings_speed_test_estimate_complete)
                            } else {
                                appContext.getString(R.string.settings_speed_test_complete)
                            }
                        )
                    }
                }
                is InternetSpeedTestResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isRunningInternetSpeedTest = false,
                            userMessage = appContext.getString(R.string.settings_speed_test_failed, result.message)
                        )
                    }
                }
            }
        }
    }

    fun applySpeedTestRecommendationToWifi() {
        viewModelScope.launch {
            val recommendation = _uiState.value.lastSpeedTest?.recommendedMaxVideoHeight
            preferencesRepository.setPlayerWifiMaxVideoHeight(recommendation)
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_speed_test_wifi_applied)) }
        }
    }

    fun applySpeedTestRecommendationToEthernet() {
        viewModelScope.launch {
            val recommendation = _uiState.value.lastSpeedTest?.recommendedMaxVideoHeight
            preferencesRepository.setPlayerEthernetMaxVideoHeight(recommendation)
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_speed_test_ethernet_applied)) }
        }
    }

    fun toggleIncognitoMode() {
        viewModelScope.launch {
            val current = _uiState.value.isIncognitoMode
            preferencesRepository.setIncognitoMode(!current)
        }
    }

    fun toggleXtreamTextClassification() {
        viewModelScope.launch {
            val current = _uiState.value.useXtreamTextClassification
            preferencesRepository.setUseXtreamTextClassification(!current)
        }
    }

    fun toggleXtreamBase64TextCompatibility() {
        viewModelScope.launch {
            val current = _uiState.value.xtreamBase64TextCompatibility
            preferencesRepository.setXtreamBase64TextCompatibility(!current)
            preferencesRepository.bumpXtreamTextImportGeneration()
            _uiState.update {
                it.copy(userMessage = "Xtream text decoding changed. Refresh each Xtream provider once to re-import titles.")
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSyncing = true,
                    syncStartedAt = 0L,
                    syncSectionLabel = null,
                    syncCanCancel = false
                )
            }
            when (val result = playbackHistoryRepository.clearAllHistory()) {
                is Result.Success -> {
                    preferencesRepository.clearAllRecentData()
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            syncStartedAt = 0L,
                            syncSectionLabel = null,
                            syncCanCancel = false,
                            userMessage = appContext.getString(R.string.settings_history_cleared)
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            syncStartedAt = 0L,
                            syncSectionLabel = null,
                            syncCanCancel = false,
                            userMessage = "Failed to clear history: ${result.message}"
                        )
                    }
                }
                Result.Loading -> Unit
            }
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    fun changePin(newPin: String) {
        viewModelScope.launch {
            preferencesRepository.setParentalPin(newPin)
            parentalControlManager.clearUnlockedCategories()
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_pin_changed)) }
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun showUserMessage(message: String) {
        _uiState.update { it.copy(userMessage = message) }
    }

    private fun AppLandingDestination.labelResId(): Int = when (this) {
        AppLandingDestination.HOME -> R.string.nav_home
        AppLandingDestination.LIVE_TV -> R.string.nav_live_tv
        AppLandingDestination.FIRST_FAVORITE_LIVE -> R.string.settings_startup_first_favorite_live
        AppLandingDestination.LAST_WATCHED_LIVE -> R.string.settings_startup_last_watched_live
        AppLandingDestination.MOVIES -> R.string.nav_movies
        AppLandingDestination.SERIES -> R.string.nav_series
        AppLandingDestination.GUIDE -> R.string.nav_epg
        AppLandingDestination.DOWNLOADS -> R.string.nav_downloads
        AppLandingDestination.PLUGINS -> R.string.nav_plugins
        AppLandingDestination.SETTINGS -> R.string.nav_settings
    }

    fun refreshProvider(
        providerId: Long,
        syncMode: SettingsProviderSyncMode = SettingsProviderSyncMode.SYNC_NOW
    ) {
        providerActions.refreshProvider(viewModelScope, providerId, syncMode)
    }

    fun syncProviderSection(providerId: Long, selection: ProviderSyncSelection) {
        syncActions.syncProviderSection(viewModelScope, providerId, selection)
    }

    fun syncProviderCustom(providerId: Long, selections: Set<ProviderSyncSelection>) {
        syncActions.syncProviderCustom(viewModelScope, providerId, selections)
    }

    fun retryWarningAction(providerId: Long, action: ProviderWarningAction) {
        syncActions.retryWarningAction(viewModelScope, providerId, action)
    }

    fun cancelSync() {
        syncActions.cancelSync()
    }

    fun deleteProvider(providerId: Long, onSuccess: () -> Unit = {}) {
        providerActions.deleteProvider(viewModelScope, providerId, onSuccess)
    }

    fun userMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun exportConfig(
        uriString: String,
        onSuccess: (() -> Unit)? = null,
        successMessage: String? = null,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        backupActions.exportConfig(
            scope = viewModelScope,
            uriString = uriString,
            onSuccess = onSuccess,
            successMessage = successMessage,
            onFinished = onFinished,
        )
    }

    fun inspectBackup(uriString: String) {
        backupActions.inspectBackup(viewModelScope, uriString)
    }

    fun dismissBackupPreview() {
        backupActions.dismissBackupPreview()
    }

    fun setBackupConflictStrategy(strategy: BackupConflictStrategy) {
        backupActions.setBackupConflictStrategy(strategy)
    }

    fun setImportPreferences(enabled: Boolean) {
        backupActions.setImportPreferences(enabled)
    }

    fun setImportProviders(enabled: Boolean) {
        backupActions.setImportProviders(enabled)
    }

    fun setImportSavedLibrary(enabled: Boolean) {
        backupActions.setImportSavedLibrary(enabled)
    }

    fun setImportPlaybackHistory(enabled: Boolean) {
        backupActions.setImportPlaybackHistory(enabled)
    }

    fun setImportMultiViewPresets(enabled: Boolean) {
        backupActions.setImportMultiViewPresets(enabled)
    }

    fun setImportRecordingSchedules(enabled: Boolean) {
        backupActions.setImportRecordingSchedules(enabled)
    }

    fun confirmBackupImport() {
        backupActions.confirmBackupImport(viewModelScope) {
            driveBackupActions.applyPendingCredentials(viewModelScope)
        }
    }

    fun toggleRestoreSyncProvider(index: Int) = backupActions.toggleRestoreProvider(index)

    fun selectAllRestoreSyncProviders() = backupActions.selectAllRestoreProviders()

    fun restoreSyncLater() = backupActions.dismissRestoreSyncChooser()

    fun retryRestoreProvider(providerId: Long) {
        viewModelScope.launch { backupRestoreStatusStore.retryProviders(setOf(providerId)) }
    }

    fun dismissRestoreItem(itemId: Long) {
        viewModelScope.launch { backupRestoreStatusStore.dismissItem(itemId) }
    }

    fun dismissRestoreProvider(jobId: String, providerIdentityKey: String) {
        viewModelScope.launch { backupRestoreStatusStore.dismissProvider(jobId, providerIdentityKey) }
    }

    fun dismissRestoreJob(jobId: String) {
        viewModelScope.launch { backupRestoreStatusStore.dismissRestore(jobId) }
    }

    fun syncSelectedRestoreProviders() {
        val state = _uiState.value
        val references = state.selectedRestoreProviderIndices.mapNotNull(state.pendingRestoreProviders::getOrNull)
        if (references.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncProgress = "Syncing restored providers…", syncCanCancel = false) }
            val failures = mutableListOf<String>()
            references.forEach { reference ->
                val provider = _uiState.value.providers.singleOrNull { candidate ->
                    candidate.serverUrl.trim().trimEnd('/').equals(reference.serverUrl.trim().trimEnd('/'), true) &&
                        candidate.username.trim() == reference.username.trim() &&
                        (reference.providerType == null || candidate.type == reference.providerType) &&
                        candidate.stalkerMacAddress.trim().equals(reference.stalkerMacAddress.orEmpty().trim(), true)
                }
                if (provider == null) {
                    failures += reference.serverUrl
                } else if (syncManager.sync(provider.id, force = true) is Result.Error) {
                    failures += provider.name
                }
            }
            backupActions.dismissRestoreSyncChooser()
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    syncProgress = null,
                    userMessage = if (failures.isEmpty()) {
                        "Selected restored providers synced successfully"
                    } else {
                        "Some restored providers could not sync: ${failures.joinToString()}"
                    }
                )
            }
        }
    }

    fun beginDriveSignIn(launcher: ActivityResultLauncher<Intent>) {
        driveBackupActions.beginSignIn(viewModelScope, launcher)
    }

    fun completeDriveSignIn(intentData: Intent?) {
        driveBackupActions.completeSignIn(viewModelScope, intentData)
    }

    fun signOutDrive() {
        driveBackupActions.signOut(viewModelScope)
    }

    fun pushToDrive() {
        driveBackupActions.pushBackup(viewModelScope)
    }

    fun pullFromDrive() {
        driveBackupActions.pullBackup(viewModelScope)
    }

    fun selectDriveBackup(snapshotId: String) {
        driveBackupActions.selectBackup(viewModelScope, snapshotId)
    }

    fun dismissDriveBackupOptions() {
        driveBackupActions.dismissBackupOptions()
    }

    fun manageDriveBackups() {
        driveBackupActions.manageBackups(viewModelScope)
    }

    fun dismissDriveBackupManagement() {
        driveBackupActions.dismissBackupManagement()
    }

    fun deleteDriveBackup(snapshotId: String) {
        driveBackupActions.deleteBackup(viewModelScope, snapshotId)
    }

    fun stopRecording(recordingId: String) {
        recordingActions.stopRecording(viewModelScope, recordingId)
    }

    fun cancelRecording(recordingId: String) {
        recordingActions.cancelRecording(viewModelScope, recordingId)
    }

    fun skipOccurrence(recordingId: String) {
        recordingActions.skipOccurrence(viewModelScope, recordingId)
    }

    fun deleteRecording(recordingId: String) {
        recordingActions.deleteRecording(viewModelScope, recordingId)
    }

    fun retryRecording(recordingId: String) {
        recordingActions.retryRecording(viewModelScope, recordingId)
    }

    fun setRecordingScheduleEnabled(recordingId: String, enabled: Boolean) {
        recordingActions.setRecordingScheduleEnabled(viewModelScope, recordingId, enabled)
    }

    fun reconcileRecordings() {
        recordingActions.reconcileRecordings(viewModelScope)
    }

    fun updateRecordingFolder(treeUri: String?, displayName: String?) {
        recordingActions.updateRecordingFolder(viewModelScope, treeUri, displayName)
    }

    fun useUsbRecordingStorage(localDirectory: String) {
        recordingActions.updateRecordingLocalDirectory(viewModelScope, localDirectory)
    }

    fun updateRecordingFileNamePattern(pattern: String) {
        recordingActions.updateRecordingFileNamePattern(viewModelScope, pattern)
    }

    fun updateRecordingRetentionDays(retentionDays: Int?) {
        recordingActions.updateRecordingRetentionDays(viewModelScope, retentionDays)
    }

    fun updateRecordingMaxSimultaneous(maxSimultaneousRecordings: Int) {
        recordingActions.updateRecordingMaxSimultaneous(viewModelScope, maxSimultaneousRecordings)
    }

    // ── EPG Source Management ────────────────────────────────────────

    fun loadEpgAssignments(providerId: Long) {
        epgActions.loadEpgAssignments(viewModelScope, providerId)
    }

    fun addEpgSource(
        name: String,
        url: String,
        timezoneId: String? = null,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {}
    ) {
        epgActions.addEpgSource(viewModelScope, name, url, timezoneId, onSuccess, onError)
    }

    fun updateEpgSourceTimezone(
        source: com.streamvault.domain.model.EpgSource,
        timezoneId: String?,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {}
    ) {
        epgActions.updateEpgSourceTimezone(
            viewModelScope,
            source,
            timezoneId,
            onSuccess,
            onError
        )
    }

    fun setPendingDeleteEpgSource(id: Long?) {
        _uiState.update { it.copy(epgPendingDeleteSourceId = id) }
    }

    fun deleteEpgSource(sourceId: Long) {
        epgActions.deleteEpgSource(viewModelScope, sourceId)
    }

    fun toggleEpgSourceEnabled(sourceId: Long, enabled: Boolean) {
        epgActions.toggleEpgSourceEnabled(viewModelScope, sourceId, enabled)
    }

    fun refreshEpgSource(sourceId: Long) {
        epgActions.refreshEpgSource(viewModelScope, sourceId)
    }

    fun adjustEpgTimeShift(providerId: Long, deltaMinutes: Int) {
        viewModelScope.launch {
            val current = _uiState.value.epgTimeShiftMinutesByProvider[providerId] ?: 0
            val next = (current + deltaMinutes).coerceIn(-720, 720)
            preferencesRepository.setEpgTimeShiftMinutes(providerId, next)
        }
    }

    fun resetEpgTimeShift(providerId: Long) {
        viewModelScope.launch {
            preferencesRepository.setEpgTimeShiftMinutes(providerId, 0)
        }
    }

    fun assignEpgSourceToProvider(providerId: Long, epgSourceId: Long) {
        epgActions.assignEpgSourceToProvider(viewModelScope, providerId, epgSourceId)
    }

    fun unassignEpgSourceFromProvider(providerId: Long, epgSourceId: Long) {
        epgActions.unassignEpgSourceFromProvider(viewModelScope, providerId, epgSourceId)
    }

    fun moveEpgSourceAssignmentUp(providerId: Long, epgSourceId: Long) {
        epgActions.moveEpgSourceAssignmentUp(viewModelScope, providerId, epgSourceId)
    }

    fun moveEpgSourceAssignmentDown(providerId: Long, epgSourceId: Long) {
        epgActions.moveEpgSourceAssignmentDown(viewModelScope, providerId, epgSourceId)
    }

}
