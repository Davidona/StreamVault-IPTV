package com.streamvault.app.ui.screens.epg

import com.streamvault.app.ui.model.GuideCachePolicy
import com.streamvault.app.ui.model.isArchivePlayable
import com.streamvault.app.ui.model.guideLookupKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.ui.model.applyProviderCategoryDisplayPreferences
import com.streamvault.app.ui.model.orderedByRequestedRawIds
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.manager.ProgramReminderManager
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ChannelEpgMapping
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.EpgOverrideCandidate
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ProgramReminder
import com.streamvault.domain.model.ProgramReminderDeliveryState
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.LiveStreamProgramRequest
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.Result
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.ScheduleRecording
import com.streamvault.domain.usecase.ScheduleRecordingCommand
import com.streamvault.domain.util.AdultContentVisibilityPolicy
import com.streamvault.data.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import android.app.Application
import com.streamvault.app.R
import com.streamvault.app.di.AuxiliaryPlayerEngine
import com.streamvault.app.player.LivePreviewHandoffManager
import com.streamvault.app.player.PreviewHandoffSource
import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import javax.inject.Provider as InjectProvider

data class RecordingConflictInfo(
    val conflictingItems: List<RecordingItem>,
    val pendingRequest: RecordingRequest,
    val programTitle: String
)

data class EpgUiState(
    val currentProviderName: String? = null,
    val providerSourceLabel: String = "",
    val providerArchiveSummary: String = "",
    val combinedProfileId: Long? = null,
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: Long = ChannelRepository.ALL_CHANNELS_ID,
    val programSearchQuery: String = "",
    val showScheduledOnly: Boolean = false,
    val selectedChannelMode: GuideChannelMode = GuideChannelMode.ALL,
    val selectedDensity: GuideDensity = GuideDensity.COMPACT,
    val parentalControlLevel: Int = 0,
    val showFavoritesOnly: Boolean = false,
    val favoriteChannelIds: Set<Long> = emptySet(),
    val channels: List<Channel> = emptyList(),
    val programsByChannel: Map<String, List<Program>> = emptyMap(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val totalChannelCount: Int = 0,
    val channelsWithSchedule: Int = 0,
    val failedScheduleCount: Int = 0,
    val lastUpdatedAt: Long? = null,
    val isGuideStale: Boolean = false,
    val guideAnchorTime: Long = DEFAULT_NOW,
    val guideWindowStart: Long = DEFAULT_NOW - EpgViewModel.LOOKBACK_MS,
    val guideWindowEnd: Long = DEFAULT_NOW + EpgViewModel.LOOKAHEAD_MS,
    val recordingMessage: String? = null,
    val reminderMessage: String? = null,
    val pendingRecordingConflict: RecordingConflictInfo? = null,
    val hasMoreChannels: Boolean = false,
    val previewPlayerEngine: PlayerEngine? = null,
    val isPreviewLoading: Boolean = false,
    val previewErrorMessage: String? = null,
    val previewChannelId: Long? = null,
) {
    companion object {
        private val DEFAULT_NOW = System.currentTimeMillis()
    }
}

data class EpgOverrideUiState(
    val channel: Channel? = null,
    val currentMapping: ChannelEpgMapping? = null,
    val searchQuery: String = "",
    val candidates: List<EpgOverrideCandidate> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null
)

data class ProgramReminderUiState(
    val providerId: Long = 0L,
    val channelId: String = "",
    val programTitle: String = "",
    val programStartTime: Long = 0L,
    val isScheduled: Boolean = false,
    val isLoading: Boolean = false
) {
    fun matches(providerId: Long, channelId: String, programTitle: String, programStartTime: Long): Boolean =
        this.providerId == providerId &&
            this.channelId == channelId &&
            this.programTitle == programTitle &&
            this.programStartTime == programStartTime
}

internal fun programReminderDeliveryIssueMessage(reminders: List<ProgramReminder>): String? {
    val issue = reminders.firstOrNull {
        it.deliveryState == ProgramReminderDeliveryState.BLOCKED ||
            it.deliveryState == ProgramReminderDeliveryState.FAILED
    } ?: return null
    val reason = issue.deliveryFailureReason
        ?.takeIf { it.isNotBlank() }
        ?: "The notification was not accepted."
    return "Reminder for ${issue.programTitle} was not delivered: $reason"
}

enum class GuideChannelMode {
    ALL,
    ANCHORED,
    ARCHIVE_READY
}

enum class GuideDensity {
    COMPACT,
    COMFORTABLE,
    CINEMATIC
}

private data class GuideProgramsResult(
    val programsByChannel: Map<String, List<Program>>,
    val failedCount: Int
)

private data class GuideChannelSelection(
    val channels: List<Channel>,
    val favoriteChannelIds: Set<Long>
)

private data class GuideBaseRequest(
    val categories: List<Category>,
    val hiddenCategoryIds: Set<Long>,
    val resolvedCategoryId: Long,
    val parentalControlLevel: Int,
    val anchorTime: Long,
    val favoritesOnly: Boolean,
    val windowStart: Long,
    val windowEnd: Long
)

private data class GuideBaseSnapshot(
    val providerId: Long,
    val currentProviderName: String,
    val providerSourceLabel: String,
    val providerArchiveSummary: String,
    val categories: List<Category>,
    val selectedCategoryId: Long,
    val parentalControlLevel: Int,
    val showFavoritesOnly: Boolean,
    val favoriteChannelIds: Set<Long>,
    val allChannels: List<Channel>,
    val visibleChannels: List<Channel>,
    val baseProgramsByChannel: Map<String, List<Program>>,
    val failedScheduleCount: Int,
    val lastUpdatedAt: Long,
    val baseChannelsWithSchedule: Int,
    val baseGuideStale: Boolean,
    val guideAnchorTime: Long,
    val guideWindowStart: Long,
    val guideWindowEnd: Long,
    val hiddenCategoryIds: Set<Long> = emptySet(),
    val nextRawChannelOffset: Int = 0,
    val hasMoreChannels: Boolean = false
)

private data class GuideDisplaySnapshot(
    val channels: List<Channel>,
    val programsByChannel: Map<String, List<Program>>,
    val totalChannelCount: Int,
    val channelsWithSchedule: Int,
    val isGuideStale: Boolean
)

private data class GuideBaseComputation(
    val guideResult: GuideProgramsResult,
    val now: Long,
    val channelsWithSchedule: Int,
    val hasUpcomingData: Boolean
)

private data class GuidePrefetchedPage(
    val channels: List<Channel>,
    val programs: GuideProgramsResult,
    val nextRawOffset: Int
)

private data class GuideSelectionRequest(
    val requestedCategoryId: Long,
    val anchorTime: Long,
    val favoritesOnly: Boolean,
    val parentalControlLevel: Int,
    val unlockedCategoryIds: Set<Long>,
    val isStartupSelection: Boolean
)

private data class CombinedGuideDependencies(
    val combinedCategories: List<CombinedCategory>,
    val providerIds: List<Long>,
    val customCategories: List<Category>,
    val selection: Pair<GuideSelectionSeed, Int>
)

private data class CombinedGuideRequest(
    val request: GuideBaseRequest,
    val providerIds: List<Long>
)

private data class GuideFallbackContext(
    val providerId: Long,
    val selectedCategoryId: Long,
    val guideAnchorTime: Long,
    val guideWindowStart: Long,
    val guideWindowEnd: Long,
    val visibleChannelIds: List<Long>
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class EpgViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val epgSourceRepository: EpgSourceRepository,
    private val favoriteRepository: FavoriteRepository,
    private val preferencesRepository: PreferencesRepository,
    private val parentalControlManager: ParentalControlManager,
    private val programReminderManager: ProgramReminderManager,
    private val getCustomCategories: GetCustomCategories,
    private val scheduleRecording: ScheduleRecording,
    private val recordingManager: RecordingManager,
    @param:AuxiliaryPlayerEngine private val playerEngineProvider: InjectProvider<PlayerEngine>,
    private val pluginManager: StreamVaultPluginManager,
    private val livePreviewHandoffManager: LivePreviewHandoffManager,
    application: Application,
) : ViewModel() {

    private val appContext = application

    companion object {
        const val MAX_CHANNELS = 60
        private const val MAX_XTREAM_GUIDE_FALLBACK_CHANNELS = 60
        private const val MAX_XTREAM_GUIDE_FALLBACK_PROGRAMS = 6
        // Fetch guide fallback in small chunks so the grid fills progressively instead of
        // waiting for the whole page of paced requests.
        private const val GUIDE_FALLBACK_CHUNK_SIZE = 12
        const val LOOKBACK_MS = 60 * 60 * 1000L
        const val LOOKAHEAD_MS = 6 * 60 * 60 * 1000L
        const val HALF_HOUR_SHIFT_MS = 30 * 60 * 1000L
        const val WINDOW_SHIFT_MS = 3 * 60 * 60 * 1000L
        const val PAGE_SHIFT_MS = LOOKBACK_MS + LOOKAHEAD_MS
        const val DAY_SHIFT_MS = 24 * 60 * 60 * 1000L
        const val PRIME_TIME_HOUR = 20
        const val NO_ACTIVE_PROVIDER = "NO_ACTIVE_PROVIDER"
    }

    private val _uiState = MutableStateFlow(EpgUiState())
    val uiState: StateFlow<EpgUiState> = _uiState.asStateFlow()

    private val selectedCategoryId = MutableStateFlow(ChannelRepository.ALL_CHANNELS_ID)
    private val guideAnchorTime = MutableStateFlow(System.currentTimeMillis())
    private val showScheduledOnly = MutableStateFlow(false)
    private val selectedChannelMode = MutableStateFlow(GuideChannelMode.ALL)
    private val selectedDensity = MutableStateFlow(GuideDensity.COMPACT)
    private val showFavoritesOnly = MutableStateFlow(false)
    private val programSearchQuery = MutableStateFlow("")
    private val startupCategoryId = MutableStateFlow<Long?>(null)
    private val refreshNonce = MutableStateFlow(0)
    private val baseGuideSnapshot = MutableStateFlow<GuideBaseSnapshot?>(null)
    private val _overrideUiState = MutableStateFlow(EpgOverrideUiState())
    val overrideUiState: StateFlow<EpgOverrideUiState> = _overrideUiState.asStateFlow()
    private val _programReminderUiState = MutableStateFlow(ProgramReminderUiState())
    val programReminderUiState: StateFlow<ProgramReminderUiState> = _programReminderUiState.asStateFlow()
    private var overrideSearchJob: Job? = null
    private var guideFallbackJob: Job? = null
    private var guideFallbackContext: GuideFallbackContext? = null
    private var prefetchJob: Deferred<GuidePrefetchedPage?>? = null
    private var loadMoreJob: Job? = null
    // Guide keys that returned successfully-but-empty from on-demand fetch this session.
    // Skipped on later pages so channels without portal EPG don't cost a request per visit.
    private val knownEmptyGuideKeys = mutableSetOf<String>()
    // Durable empty-key cache (per provider) so the same ids are not re-fetched after relaunch.
    private val persistedEmptyGuideKeys = mutableMapOf<Long, Map<String, Long>>()
    private var combinedCategoriesById: Map<Long, CombinedCategory> = emptyMap()
    private var previewPlayerEngine: PlayerEngine? = null
    private var previewSessionVersion: Long = 0L
    private var previewPlaybackJob: Job? = null
    private var previewErrorJob: Job? = null

    init {
        restoreGuidePreferences()
        observeGuideBase()
        observeGuidePresentation()
        observeReminderDeliveryIssues()
        viewModelScope.launch {
            livePreviewHandoffManager.reverseSessionFlow.collect { session ->
                if (session != null && session.source == PreviewHandoffSource.GUIDE) {
                    resumePreviewFromHandoff()
                }
            }
        }
    }

    private fun observeReminderDeliveryIssues() {
        viewModelScope.launch {
            programReminderManager.observeUpcomingReminders()
                .map(::programReminderDeliveryIssueMessage)
                .distinctUntilChanged()
                .collectLatest { message ->
                    if (message != null) {
                        _uiState.update { it.copy(reminderMessage = message) }
                    }
                }
        }
    }

    fun selectCategory(categoryId: Long) {
        startupCategoryId.value = null
        if (selectedCategoryId.value == categoryId) return
        baseGuideSnapshot.value?.providerId?.takeIf { it > 0L }?.let { providerId ->
            parentalControlManager.retainUnlockedCategory(
                providerId = providerId,
                categoryId = categoryId.takeIf { it > 0L && it != ChannelRepository.ALL_CHANNELS_ID }
            )
        }
        selectedCategoryId.value = categoryId
    }

    fun updateProgramSearchQuery(query: String) {
        programSearchQuery.value = query
    }

    fun clearProgramSearch() {
        if (programSearchQuery.value.isBlank()) return
        programSearchQuery.value = ""
    }

    suspend fun verifyPin(pin: String): Boolean =
        preferencesRepository.verifyParentalPin(pin)

    fun previewChannel(channel: Channel) {
        if (_uiState.value.previewChannelId == channel.id && _uiState.value.previewPlayerEngine != null) return
        val version = ++previewSessionVersion
        val engine = previewPlayerEngine ?: playerEngineProvider.get().also { previewPlayerEngine = it }
        previewPlaybackJob?.cancel()
        previewErrorJob?.cancel()
        _uiState.update {
            it.copy(
                previewChannelId = channel.id,
                previewPlayerEngine = engine,
                isPreviewLoading = true,
                previewErrorMessage = null
            )
        }
        previewPlaybackJob = viewModelScope.launch {
            engine.playbackState.collectLatest { state ->
                if (!isActivePreviewSession(version, channel.id)) return@collectLatest
                _uiState.update { s ->
                    s.copy(
                        isPreviewLoading = state == PlaybackState.IDLE || state == PlaybackState.BUFFERING,
                        previewErrorMessage = when {
                            state == PlaybackState.ERROR && s.previewErrorMessage.isNullOrBlank() ->
                                appContext.getString(R.string.live_preview_failed)
                            state != PlaybackState.ERROR -> null
                            else -> s.previewErrorMessage
                        }
                    )
                }
            }
        }
        previewErrorJob = viewModelScope.launch {
            engine.error.collectLatest { error ->
                if (!isActivePreviewSession(version, channel.id)) return@collectLatest
                if (error != null) {
                    _uiState.update {
                        it.copy(
                            isPreviewLoading = false,
                            previewErrorMessage = error.message.ifBlank { appContext.getString(R.string.live_preview_failed) }
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            when (val result = channelRepository.getStreamInfo(channel)) {
                is Result.Success -> {
                    if (!isActivePreviewSession(version, channel.id)) return@launch
                    val preparedStreamInfo = when (val r = pluginManager.preparePlaybackStreamInfo(result.data)) {
                        is Result.Success -> r.data
                        is Result.Error -> {
                            if (!isActivePreviewSession(version, channel.id)) return@launch
                            _uiState.update {
                                it.copy(isPreviewLoading = false, previewErrorMessage = r.message.ifBlank { appContext.getString(R.string.live_preview_failed) })
                            }
                            return@launch
                        }
                        Result.Loading -> result.data
                    }
                    if (!isActivePreviewSession(version, channel.id)) return@launch
                    engine.stop()
                    engine.setDecoderModes(
                        audioMode = preferencesRepository.playerAudioDecoderMode.first(),
                        videoMode = preferencesRepository.playerVideoDecoderMode.first()
                    )
                    engine.setSurfaceMode(preferencesRepository.playerSurfaceMode.first())
                    engine.setPlaybackBufferMode(preferencesRepository.playerPlaybackBufferMode.first())
                    engine.prepare(preparedStreamInfo)
                    engine.setVolume(1f)
                    engine.play()
                    livePreviewHandoffManager.registerPreviewSession(
                        channel = channel,
                        streamInfo = preparedStreamInfo,
                        engine = engine,
                        source = PreviewHandoffSource.GUIDE
                    )
                }
                is Result.Error -> {
                    if (!isActivePreviewSession(version, channel.id)) return@launch
                    _uiState.update { it.copy(isPreviewLoading = false, previewErrorMessage = result.message) }
                }
                Result.Loading -> Unit
            }
        }
    }

    fun beginPreviewHandoff(channel: Channel): Boolean {
        val engine = previewPlayerEngine ?: return false
        if (_uiState.value.previewChannelId != channel.id) return false
        if (!livePreviewHandoffManager.beginFullscreenHandoff(channel.id, engine)) return false

        previewSessionVersion++
        previewPlaybackJob?.cancel()
        previewErrorJob?.cancel()
        previewPlaybackJob = null
        previewErrorJob = null
        previewPlayerEngine = null
        _uiState.update {
            it.copy(
                previewChannelId = null,
                previewPlayerEngine = null,
                isPreviewLoading = false,
                previewErrorMessage = null
            )
        }
        return true
    }

    fun resumePreviewFromHandoff() {
        val session = livePreviewHandoffManager.consumeReverseHandoff(PreviewHandoffSource.GUIDE) ?: return
        val engine = session.engine
        previewSessionVersion++
        val version = previewSessionVersion
        previewPlaybackJob?.cancel()
        previewErrorJob?.cancel()
        previewPlaybackJob = null
        previewErrorJob = null
        previewPlayerEngine?.stop()
        previewPlayerEngine?.release()
        previewPlayerEngine = engine
        (engine as? com.streamvault.player.Media3PlayerEngine)?.let {
            it.enableMediaSession = false
            it.bypassAudioFocus = true
        }
        engine.play()
        livePreviewHandoffManager.registerPreviewSession(
            channelId = session.channelId,
            providerId = session.providerId,
            streamInfo = session.streamInfo,
            engine = engine,
            source = PreviewHandoffSource.GUIDE
        )
        _uiState.update {
            it.copy(
                previewChannelId = session.channelId,
                previewPlayerEngine = engine,
                isPreviewLoading = false,
                previewErrorMessage = null
            )
        }
        previewPlaybackJob = viewModelScope.launch {
            engine.playbackState.collectLatest { state ->
                if (!isActivePreviewSession(version, session.channelId)) return@collectLatest
                if (state == PlaybackState.ERROR && _uiState.value.previewErrorMessage == null) {
                    _uiState.update { it.copy(previewErrorMessage = appContext.getString(R.string.live_preview_failed)) }
                }
            }
        }
    }

    fun clearPreview() {
        val engine = previewPlayerEngine ?: return
        previewSessionVersion++
        previewPlaybackJob?.cancel()
        previewErrorJob?.cancel()
        previewPlaybackJob = null
        previewErrorJob = null
        livePreviewHandoffManager.clear(engine)
        engine.stop()
        engine.release()
        previewPlayerEngine = null
        _uiState.update {
            it.copy(
                previewChannelId = null,
                previewPlayerEngine = null,
                isPreviewLoading = false,
                previewErrorMessage = null
            )
        }
    }

    /**
     * Call before navigating to the fullscreen player. If `channel` matches the current preview,
     * hands the engine off; otherwise releases the preview so two streams can't run in parallel.
     */
    fun handoffOrClearForFullscreen(channel: Channel) {
        if (!beginPreviewHandoff(channel)) clearPreview()
    }

    private fun isActivePreviewSession(version: Long, channelId: Long): Boolean =
        version == previewSessionVersion && _uiState.value.previewChannelId == channelId

    override fun onCleared() {
        super.onCleared()
        previewPlaybackJob?.cancel()
        previewErrorJob?.cancel()
        previewPlayerEngine?.stop()
        previewPlayerEngine?.release()
        previewPlayerEngine = null
    }

    fun unlockCategory(categoryId: Long) {
        val providerId = baseGuideSnapshot.value?.providerId?.takeIf { it > 0L } ?: return
        parentalControlManager.unlockCategory(providerId, categoryId)
    }

    fun refresh() {
        refreshNonce.update { it + 1 }
    }

    fun requestMoreChannels() {
        val snapshot = baseGuideSnapshot.value ?: return
        if (!snapshot.hasMoreChannels) return
        if (loadMoreJob?.isActive == true) return
        val deferred = prefetchJob
        if (deferred == null) {
            val nextChannels = snapshot.allChannels
                .drop(snapshot.visibleChannels.size)
                .take(MAX_CHANNELS)
            if (nextChannels.isEmpty()) {
                baseGuideSnapshot.update { it?.copy(hasMoreChannels = false) }
                return
            }
            loadMoreJob = viewModelScope.launch {
                val programs = loadGuideProgramsForSnapshot(snapshot, nextChannels)
                val current = baseGuideSnapshot.value ?: return@launch
                if (current.selectedCategoryId != snapshot.selectedCategoryId ||
                    current.providerId != snapshot.providerId
                ) return@launch
                val newVisible = current.visibleChannels + nextChannels
                baseGuideSnapshot.update { s ->
                    s?.copy(
                        visibleChannels = newVisible,
                        baseProgramsByChannel = s.baseProgramsByChannel + programs.programsByChannel,
                        hasMoreChannels = newVisible.size < s.allChannels.size
                    )
                }
            }
            return
        }
        if (deferred.isCancelled) return
        prefetchJob = null
        loadMoreJob = viewModelScope.launch {
            val page = deferred.await() ?: run {
                baseGuideSnapshot.update { it?.copy(hasMoreChannels = false) }
                return@launch
            }
            val current = baseGuideSnapshot.value ?: return@launch
            // Guard: category must not have changed while prefetch was running
            if (current.selectedCategoryId != snapshot.selectedCategoryId ||
                current.providerId != snapshot.providerId
            ) return@launch
            val newVisible = current.visibleChannels + page.channels
            baseGuideSnapshot.update { s ->
                s?.copy(
                    visibleChannels = newVisible,
                    allChannels = newVisible,
                    baseProgramsByChannel = s.baseProgramsByChannel + page.programs.programsByChannel,
                    nextRawChannelOffset = page.nextRawOffset,
                    hasMoreChannels = page.channels.size >= MAX_CHANNELS
                )
            }
            baseGuideSnapshot.value?.let { schedulePrefetchNextPage(it) }
        }
    }

    private suspend fun loadGuideProgramsForSnapshot(
        snapshot: GuideBaseSnapshot,
        channels: List<Channel>
    ): GuideProgramsResult =
        if (snapshot.providerId > 0L) {
            loadGuidePrograms(
                providerId = snapshot.providerId,
                channels = channels,
                windowStart = snapshot.guideWindowStart,
                windowEnd = snapshot.guideWindowEnd
            )
        } else {
            loadCombinedGuidePrograms(
                channels = channels,
                windowStart = snapshot.guideWindowStart,
                windowEnd = snapshot.guideWindowEnd
            )
        }

    private fun schedulePrefetchNextPage(snapshot: GuideBaseSnapshot) {
        if (!snapshot.hasMoreChannels) return
        prefetchJob = viewModelScope.async<GuidePrefetchedPage?> {
            val channels = fetchGuideChannelPage(
                providerId = snapshot.providerId,
                categoryId = snapshot.selectedCategoryId,
                offset = snapshot.nextRawChannelOffset,
                hiddenCategoryIds = snapshot.hiddenCategoryIds,
                favoritesOnly = snapshot.showFavoritesOnly,
                favoriteChannelIds = snapshot.favoriteChannelIds
            )
            if (channels.isEmpty()) return@async null
            val programs = loadGuidePrograms(
                providerId = snapshot.providerId,
                channels = channels,
                windowStart = snapshot.guideWindowStart,
                windowEnd = snapshot.guideWindowEnd
            )
            GuidePrefetchedPage(
                channels = channels,
                programs = programs,
                nextRawOffset = snapshot.nextRawChannelOffset + MAX_CHANNELS
            )
        }
    }

    private suspend fun fetchGuideChannelPage(
        providerId: Long,
        categoryId: Long,
        offset: Int,
        hiddenCategoryIds: Set<Long>,
        favoritesOnly: Boolean,
        favoriteChannelIds: Set<Long>
    ): List<Channel> {
        if (providerId <= 0L) return emptyList()
        if (
            categoryId != ChannelRepository.ALL_CHANNELS_ID &&
            (categoryId == VirtualCategoryIds.FAVORITES || categoryId < 0L)
        ) return emptyList()
        val withoutErrors = channelRepository.getChannelsWithoutErrorsPageOffset(providerId, categoryId, MAX_CHANNELS, offset)
        val raw = withoutErrors.ifEmpty {
            channelRepository.getChannelsByCategoryPageOffset(providerId, categoryId, MAX_CHANNELS, offset)
        }
        val filtered = if (hiddenCategoryIds.isEmpty()) raw else raw.filterNot { it.categoryId in hiddenCategoryIds }
        return if (favoritesOnly) filtered.filter { it.id in favoriteChannelIds } else filtered
    }

    fun openEpgOverride(channel: Channel) {
        _overrideUiState.value = EpgOverrideUiState(
            channel = channel,
            isLoading = true
        )
        loadEpgOverrideCandidates(channel = channel, query = "", refreshMapping = true)
    }

    fun dismissEpgOverride() {
        overrideSearchJob?.cancel()
        _overrideUiState.value = EpgOverrideUiState()
    }

    fun loadProgramReminderState(channel: Channel, program: Program) {
        val providerId = program.providerId.takeIf { it > 0L } ?: channel.providerId
        val channelId = program.channelId
        if (providerId <= 0L || channelId.isBlank()) {
            _programReminderUiState.value = ProgramReminderUiState()
            return
        }
        _programReminderUiState.update {
            it.copy(
                providerId = providerId,
                channelId = channelId,
                programTitle = program.title,
                programStartTime = program.startTime,
                isLoading = true
            )
        }
        viewModelScope.launch {
            val scheduled = programReminderManager.isReminderScheduled(
                providerId = providerId,
                channelId = channelId,
                programTitle = program.title,
                programStartTime = program.startTime
            )
            _programReminderUiState.value = ProgramReminderUiState(
                providerId = providerId,
                channelId = channelId,
                programTitle = program.title,
                programStartTime = program.startTime,
                isScheduled = scheduled,
                isLoading = false
            )
        }
    }

    fun toggleProgramReminder(channel: Channel, program: Program) {
        val providerId = program.providerId.takeIf { it > 0L } ?: channel.providerId
        val channelId = program.channelId
        if (providerId <= 0L || channelId.isBlank()) return
        val currentState = _programReminderUiState.value
        val isScheduled = currentState.matches(providerId, channelId, program.title, program.startTime) &&
            currentState.isScheduled
        _programReminderUiState.update {
            it.copy(
                providerId = providerId,
                channelId = channelId,
                programTitle = program.title,
                programStartTime = program.startTime,
                isLoading = true
            )
        }
        viewModelScope.launch {
            if (isScheduled) {
                programReminderManager.cancelReminder(
                    providerId = providerId,
                    channelId = channelId,
                    programTitle = program.title,
                    programStartTime = program.startTime
                )
            } else {
                programReminderManager.scheduleReminder(
                    providerId = providerId,
                    channelId = channelId,
                    channelName = channel.name,
                    program = program
                )
            }
            loadProgramReminderState(channel, program)
        }
    }

    fun scheduleRecording(channel: Channel, program: Program, recurrence: RecordingRecurrence = RecordingRecurrence.NONE) {
        // When the program is airing now, capture starts immediately. Free the provider connection
        // the guide preview is holding, or on single-connection Xtream accounts the capture would
        // be a second connection and get a 403. Recording from the guide means "record without
        // watching", so dropping the preview is intended; it only restarts on an explicit click.
        // Future programs schedule for later, so leave the preview running.
        if (program.startTime <= System.currentTimeMillis()) {
            clearPreview()
        }
        viewModelScope.launch {
            val command = ScheduleRecordingCommand(
                contentType = ContentType.LIVE,
                providerId = channel.providerId,
                channel = channel,
                streamUrl = channel.streamUrl,
                currentProgram = program,
                nextProgram = null,
                recurrence = recurrence
            )
            val result = scheduleRecording(command)
            when (result) {
                is Result.Success -> {
                    _uiState.update { it.copy(recordingMessage = "Recording scheduled: ${program.title}") }
                }
                is Result.Error -> {
                    val msg = result.message.orEmpty()
                    if (msg.contains("conflicts", ignoreCase = true)) {
                        val scheduledStartMs = maxOf(System.currentTimeMillis(), program.startTime)
                        val conflicts = recordingManager.getConflictingRecordings(
                            scheduledStartMs, program.endTime, channel.providerId
                        )
                        if (conflicts.isNotEmpty()) {
                            _uiState.update {
                                it.copy(
                                    pendingRecordingConflict = RecordingConflictInfo(
                                        conflictingItems = conflicts,
                                        pendingRequest = RecordingRequest(
                                            providerId = channel.providerId,
                                            channelId = channel.id,
                                            channelName = channel.name,
                                            streamUrl = channel.streamUrl,
                                            scheduledStartMs = scheduledStartMs,
                                            scheduledEndMs = program.endTime,
                                            programTitle = program.title,
                                            recurrence = recurrence
                                        ),
                                        programTitle = program.title ?: channel.name
                                    )
                                )
                            }
                            return@launch
                        }
                    }
                    _uiState.update { it.copy(recordingMessage = msg.ifBlank { "Failed to schedule recording" }) }
                }
                else -> {}
            }
        }
    }

    fun forceScheduleRecording() {
        val conflict = _uiState.value.pendingRecordingConflict ?: return
        viewModelScope.launch {
            val result = recordingManager.forceScheduleRecording(conflict.pendingRequest)
            val message = when (result) {
                is Result.Success -> "Recording scheduled: ${conflict.programTitle}"
                is Result.Error -> result.message ?: "Failed to schedule recording"
                else -> return@launch
            }
            _uiState.update { it.copy(recordingMessage = message, pendingRecordingConflict = null) }
        }
    }

    fun dismissRecordingConflict() {
        _uiState.update { it.copy(pendingRecordingConflict = null) }
    }

    fun clearRecordingMessage() {
        _uiState.update { it.copy(recordingMessage = null) }
    }

    fun clearReminderMessage() {
        _uiState.update { it.copy(reminderMessage = null) }
    }

    fun reconcileProgramReminders() {
        viewModelScope.launch {
            programReminderManager.restoreScheduledReminders()
        }
    }

    fun updateEpgOverrideSearch(query: String) {
        val channel = _overrideUiState.value.channel ?: return
        _overrideUiState.update {
            it.copy(
                searchQuery = query,
                isLoading = true,
                error = null
            )
        }
        loadEpgOverrideCandidates(channel = channel, query = query, refreshMapping = false)
    }

    fun applyEpgOverride(candidate: EpgOverrideCandidate) {
        val channel = _overrideUiState.value.channel ?: return
        viewModelScope.launch {
            _overrideUiState.update { it.copy(isSaving = true, error = null) }
            when (val result = epgSourceRepository.applyManualOverride(
                providerId = channel.providerId,
                channelId = channel.id,
                epgSourceId = candidate.epgSourceId,
                xmltvChannelId = candidate.xmltvChannelId
            )) {
                is com.streamvault.domain.model.Result.Error -> {
                    _overrideUiState.update { it.copy(isSaving = false, error = result.message) }
                }
                else -> {
                    dismissEpgOverride()
                    refresh()
                }
            }
        }
    }

    fun clearEpgOverride() {
        val channel = _overrideUiState.value.channel ?: return
        viewModelScope.launch {
            _overrideUiState.update { it.copy(isSaving = true, error = null) }
            when (val result = epgSourceRepository.clearManualOverride(channel.providerId, channel.id)) {
                is com.streamvault.domain.model.Result.Error -> {
                    _overrideUiState.update { it.copy(isSaving = false, error = result.message) }
                }
                else -> {
                    dismissEpgOverride()
                    refresh()
                }
            }
        }
    }

    fun jumpToNow() {
        updateGuideAnchorTime(System.currentTimeMillis())
    }

    fun jumpForwardHalfHour() {
        updateGuideAnchorTime(guideAnchorTime.value + HALF_HOUR_SHIFT_MS)
    }

    fun jumpBackwardHalfHour() {
        updateGuideAnchorTime((guideAnchorTime.value - HALF_HOUR_SHIFT_MS).coerceAtLeast(0L))
    }

    fun jumpForward() {
        updateGuideAnchorTime(guideAnchorTime.value + WINDOW_SHIFT_MS)
    }

    fun jumpBackward() {
        updateGuideAnchorTime((guideAnchorTime.value - WINDOW_SHIFT_MS).coerceAtLeast(0L))
    }

    fun pageBackward() {
        updateGuideAnchorTime((guideAnchorTime.value - PAGE_SHIFT_MS).coerceAtLeast(0L))
    }

    fun pageForward() {
        updateGuideAnchorTime(guideAnchorTime.value + PAGE_SHIFT_MS)
    }

    fun jumpToTomorrow() {
        updateGuideAnchorTime(shiftGuideAnchorByDays(System.currentTimeMillis(), 1))
    }

    fun jumpToPrimeTime() {
        updateGuideAnchorTime(guidePrimeTimeAnchor(guideAnchorTime.value, PRIME_TIME_HOUR))
    }

    fun jumpToPreviousDay() {
        updateGuideAnchorTime(shiftGuideAnchorByDays(guideAnchorTime.value, -1).coerceAtLeast(0L))
    }

    fun jumpToNextDay() {
        updateGuideAnchorTime(shiftGuideAnchorByDays(guideAnchorTime.value, 1))
    }

    fun jumpToDay(dayStartMillis: Long) {
        updateGuideAnchorTime(jumpGuideAnchorToDay(guideAnchorTime.value, dayStartMillis))
    }

    fun toggleScheduledOnly() {
        val enabled = !showScheduledOnly.value
        showScheduledOnly.value = enabled
        viewModelScope.launch {
            preferencesRepository.setGuideScheduledOnly(enabled)
        }
    }

    fun selectChannelMode(mode: GuideChannelMode) {
        selectedChannelMode.value = mode
        viewModelScope.launch {
            preferencesRepository.setGuideChannelMode(mode.name)
        }
    }

    fun selectDensity(density: GuideDensity) {
        selectedDensity.value = density
        viewModelScope.launch {
            preferencesRepository.setGuideDensity(density.name)
        }
    }

    fun toggleFavoritesOnly() {
        val enabled = !showFavoritesOnly.value
        showFavoritesOnly.value = enabled
        viewModelScope.launch {
            preferencesRepository.setGuideFavoritesOnly(enabled)
        }
    }

    fun applyNavigationContext(
        categoryId: Long?,
        anchorTime: Long?,
        favoritesOnly: Boolean?
    ) {
        categoryId?.let { requested ->
            startupCategoryId.value = null
            selectedCategoryId.value = requested
        }
        anchorTime?.takeIf { it > 0L }?.let { requested ->
            guideAnchorTime.value = requested
        }
        favoritesOnly?.let { requested ->
            showFavoritesOnly.value = requested
        }
    }

    private fun observeGuideBase() {
        viewModelScope.launch {
            combine(
                combinedM3uRepository.getActiveLiveSource(),
                providerRepository.getActiveProvider()
            ) { activeSource, activeProvider ->
                Pair(activeSource ?: activeProvider?.id?.let { ActiveLiveSource.ProviderSource(it) }, activeProvider)
            }.distinctUntilChanged().collectLatest { (activeSource, activeProvider) ->
                if (activeSource == null && activeProvider == null) {
                    guideFallbackJob?.cancel()
                    baseGuideSnapshot.value = null
                    _uiState.update {
                        it.copy(
                            currentProviderName = null,
                            providerSourceLabel = "",
                            providerArchiveSummary = "",
                            combinedProfileId = null,
                            categories = emptyList(),
                            channels = emptyList(),
                            programsByChannel = emptyMap(),
                            isInitialLoading = false,
                            isRefreshing = false,
                            error = NO_ACTIVE_PROVIDER,
                            totalChannelCount = 0,
                            channelsWithSchedule = 0,
                            failedScheduleCount = 0,
                            lastUpdatedAt = null,
                            isGuideStale = false,
                            guideAnchorTime = System.currentTimeMillis(),
                            guideWindowStart = System.currentTimeMillis() - LOOKBACK_MS,
                            guideWindowEnd = System.currentTimeMillis() + LOOKAHEAD_MS
                        )
                    }
                    return@collectLatest
                }

                when (activeSource) {
                    is ActiveLiveSource.ProviderSource -> {
                        _uiState.update { it.copy(combinedProfileId = null) }
                        val provider = activeProvider?.takeIf { it.id == activeSource.providerId }
                            ?: providerRepository.getProvider(activeSource.providerId)
                            ?: return@collectLatest
                        observeSingleProviderGuide(provider)
                    }
                    is ActiveLiveSource.CombinedM3uSource -> {
                        _uiState.update { it.copy(combinedProfileId = activeSource.profileId) }
                        observeCombinedGuide(activeSource.profileId)
                    }
                    null -> Unit
                }
            }
        }
    }

    private suspend fun observeSingleProviderGuide(provider: com.streamvault.domain.model.LegacyProvider) {
        combine(
            channelRepository.getCategories(provider.id),
            getCustomCategories(provider.id, ContentType.LIVE),
            preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE),
            preferencesRepository.getCategorySortMode(provider.id, ContentType.LIVE)
        ) { providerCategories, customCategories, hiddenCategoryIds, sortMode ->
            GuideCategoryData(
                providerCategories = providerCategories,
                customCategories = customCategories,
                hiddenCategoryIds = hiddenCategoryIds,
                sortMode = sortMode
            )
        }.combine(preferencesRepository.showAllChannelsCategory) { data, showAll ->
            data.copy(showAllChannels = showAll)
        }
            .combine(
                guideSelectionStateFlow().combine(
                    parentalControlManager.unlockedCategoriesForProvider(provider.id)
                ) { (selection, parentalControlLevel), unlockedCategoryIds ->
                    GuideSelectionRequest(
                        requestedCategoryId = selection.requestedCategoryId,
                        anchorTime = selection.anchorTime,
                        favoritesOnly = selection.favoritesOnly,
                        parentalControlLevel = parentalControlLevel,
                        unlockedCategoryIds = unlockedCategoryIds,
                        isStartupSelection = selection.isStartupSelection
                    )
                }
            ) { categoryData, selection ->
                val visibleProviderCategories = applyProviderCategoryDisplayPreferences(
                    categories = categoryData.providerCategories.filter { it.id != ChannelRepository.ALL_CHANNELS_ID },
                    hiddenCategoryIds = categoryData.hiddenCategoryIds,
                    sortMode = categoryData.sortMode
                )
                val orderedCategories = buildGuideCategoryList(
                    providerCategories = visibleProviderCategories,
                    customCategories = categoryData.customCategories,
                    showAllChannels = categoryData.showAllChannels
                )
                val resolvedCategoryId = resolveGuideCategorySelection(
                    requestedCategoryId = selection.requestedCategoryId,
                    categories = orderedCategories,
                    parentalControlLevel = selection.parentalControlLevel,
                    unlockedCategoryIds = selection.unlockedCategoryIds,
                    fallbackFromEmptyFavorites = selection.isStartupSelection
                )
                GuideBaseRequest(
                    categories = orderedCategories,
                    hiddenCategoryIds = categoryData.hiddenCategoryIds,
                    resolvedCategoryId = resolvedCategoryId,
                    parentalControlLevel = selection.parentalControlLevel,
                    anchorTime = selection.anchorTime,
                    favoritesOnly = selection.favoritesOnly,
                    windowStart = selection.anchorTime - LOOKBACK_MS,
                    windowEnd = selection.anchorTime + LOOKAHEAD_MS
                )
            }.collectLatest { request ->
                val categories = request.categories
                val hasVisibleGuide = _uiState.value.channels.isNotEmpty() || _uiState.value.programsByChannel.isNotEmpty()
                val providerSourceLabel = buildProviderSourceLabel(provider)
                val providerArchiveSummary = buildProviderArchiveSummary(provider)
                _uiState.update {
                    it.copy(
                        currentProviderName = provider.name,
                        providerSourceLabel = providerSourceLabel,
                        providerArchiveSummary = providerArchiveSummary,
                        categories = categories,
                        parentalControlLevel = request.parentalControlLevel,
                        showFavoritesOnly = request.favoritesOnly,
                        selectedCategoryId = request.resolvedCategoryId,
                        guideAnchorTime = request.anchorTime,
                        guideWindowStart = request.windowStart,
                        guideWindowEnd = request.windowEnd,
                        isInitialLoading = !hasVisibleGuide,
                        isRefreshing = hasVisibleGuide,
                        error = null
                    )
                }

                combine(loadGuideChannelsForProvider(provider.id, request), favoriteRepository.getFavorites(provider.id, ContentType.LIVE)) { preferredChannels, favorites ->
                    val favoriteIds = favorites.map { it.contentId }.toSet()
                    GuideChannelSelection(
                        channels = preferredChannels.filterNot { channel -> channel.categoryId in request.hiddenCategoryIds },
                        favoriteChannelIds = favoriteIds
                    )
                }.collectLatest { channelSelection ->
                    val visibleChannels = channelSelection.channels.take(MAX_CHANNELS)
                    val guideResult = loadGuidePrograms(
                        providerId = provider.id,
                        channels = visibleChannels,
                        windowStart = request.windowStart,
                        windowEnd = request.windowEnd
                    )
                    publishGuideSnapshot(
                        providerId = provider.id,
                        providerName = provider.name,
                        providerSourceLabel = providerSourceLabel,
                        providerArchiveSummary = providerArchiveSummary,
                        categories = categories,
                        request = request,
                        channelSelection = channelSelection,
                        guideResult = guideResult
                    )
                    scheduleGuideFallbackEnrichment(
                        snapshotContext = GuideFallbackContext(
                            providerId = provider.id,
                            selectedCategoryId = request.resolvedCategoryId,
                            guideAnchorTime = request.anchorTime,
                            guideWindowStart = request.windowStart,
                            guideWindowEnd = request.windowEnd,
                            visibleChannelIds = visibleChannels.map(Channel::id)
                        ),
                        channels = visibleChannels,
                        existingProgramsByChannel = guideResult.programsByChannel
                    )
                    baseGuideSnapshot.value?.let { schedulePrefetchNextPage(it) }
                    finalizeStartupCategory(request.resolvedCategoryId)
                }
            }
    }

    private suspend fun observeCombinedGuide(profileId: Long) {
        val providerIdsFlow = combinedProviderIdsFlow(profileId)
        combine(
            combinedM3uRepository.getCombinedCategories(profileId),
            providerIdsFlow,
            providerIdsFlow.flatMapLatest { providerIds ->
                getCustomCategories(providerIds, ContentType.LIVE)
            },
            guideSelectionStateFlow()
        ) { combinedCategories, providerIds, customCategories, selection ->
            combinedCategoriesById = combinedCategories.associateBy { it.category.id }
            CombinedGuideDependencies(
                combinedCategories = combinedCategories,
                providerIds = providerIds,
                customCategories = customCategories,
                selection = selection
            )
        }.combine(preferencesRepository.showAllChannelsCategory) { data, showAllChannels ->
            val categories = buildGuideCategoryList(
                providerCategories = data.combinedCategories.map { it.category },
                customCategories = data.customCategories,
                showAllChannels = showAllChannels
            )
            val resolvedCategoryId = resolveGuideCategorySelection(
                requestedCategoryId = data.selection.first.requestedCategoryId,
                categories = categories,
                parentalControlLevel = data.selection.second,
                unlockedCategoryIds = emptySet(),
                fallbackFromEmptyFavorites = data.selection.first.isStartupSelection
            )
            CombinedGuideRequest(
                request = GuideBaseRequest(
                    categories = categories,
                    hiddenCategoryIds = emptySet(),
                    resolvedCategoryId = resolvedCategoryId,
                    parentalControlLevel = data.selection.second,
                    anchorTime = data.selection.first.anchorTime,
                    favoritesOnly = data.selection.first.favoritesOnly,
                    windowStart = data.selection.first.anchorTime - LOOKBACK_MS,
                    windowEnd = data.selection.first.anchorTime + LOOKAHEAD_MS
                ),
                providerIds = data.providerIds
            )
        }.collectLatest { combinedRequest ->
            val request = combinedRequest.request
            val providerIds = combinedRequest.providerIds
            val categories = request.categories
            val profile = combinedM3uRepository.getProfile(profileId)
            val profileName = profile?.name ?: "Combined M3U"
            val hasVisibleGuide = _uiState.value.channels.isNotEmpty() || _uiState.value.programsByChannel.isNotEmpty()
            _uiState.update {
                it.copy(
                    currentProviderName = profileName,
                    providerSourceLabel = "Combined M3U",
                    providerArchiveSummary = "Guide data is merged from each member playlist's own EPG sources.",
                    categories = categories,
                    parentalControlLevel = request.parentalControlLevel,
                    showFavoritesOnly = request.favoritesOnly,
                    selectedCategoryId = request.resolvedCategoryId,
                    guideAnchorTime = request.anchorTime,
                    guideWindowStart = request.windowStart,
                    guideWindowEnd = request.windowEnd,
                    isInitialLoading = !hasVisibleGuide,
                    isRefreshing = hasVisibleGuide,
                    error = null
                )
            }

            combine(
                combinedGuideChannels(profileId, request.resolvedCategoryId, providerIds),
                observeLiveFavorites(providerIds)
            ) { channels, favorites ->
                val favoriteIds = favorites.map { it.contentId }.toSet()
                val preferredChannels = if (request.favoritesOnly) channels.filter { it.id in favoriteIds } else channels
                GuideChannelSelection(
                    channels = preferredChannels,
                    favoriteChannelIds = favoriteIds
                )
            }.collectLatest { channelSelection ->
                val visibleChannels = channelSelection.channels.take(MAX_CHANNELS)
                val guideResult = loadCombinedGuidePrograms(
                    channels = visibleChannels,
                    windowStart = request.windowStart,
                    windowEnd = request.windowEnd
                )
                publishGuideSnapshot(
                    providerId = 0L,
                    providerName = profileName,
                    providerSourceLabel = "Combined M3U",
                    providerArchiveSummary = "Guide data is merged from each member playlist's own EPG sources.",
                    categories = categories,
                    request = request,
                    channelSelection = channelSelection,
                    guideResult = guideResult
                )
                scheduleGuideFallbackEnrichment(
                    snapshotContext = GuideFallbackContext(
                        providerId = 0L,
                        selectedCategoryId = request.resolvedCategoryId,
                        guideAnchorTime = request.anchorTime,
                        guideWindowStart = request.windowStart,
                        guideWindowEnd = request.windowEnd,
                        visibleChannelIds = visibleChannels.map(Channel::id)
                    ),
                    channels = visibleChannels,
                    existingProgramsByChannel = guideResult.programsByChannel
                )
                finalizeStartupCategory(request.resolvedCategoryId)
            }
        }
    }

    private fun combinedGuideChannels(
        profileId: Long,
        categoryId: Long,
        providerIds: List<Long>
    ): Flow<List<Channel>> {
        return if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            val flows = combinedCategoriesById.values.map { combinedM3uRepository.getCombinedChannels(profileId, it) }
            if (flows.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(emptyList<Channel>())
            } else {
                combine(flows) { arrays: Array<List<Channel>> -> arrays.toList().flatMap { it } }
            }
        } else if (categoryId == VirtualCategoryIds.FAVORITES) {
            observeLiveFavorites(providerIds)
                .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                .flatMapLatest(::loadGuideChannelsByOrderedIds)
        } else if (categoryId < 0L) {
            favoriteRepository.getFavoritesByGroup(-categoryId)
                .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                .flatMapLatest(::loadGuideChannelsByOrderedIds)
        } else {
            val combinedCategory = combinedCategoriesById[categoryId]
            if (combinedCategory == null) {
                kotlinx.coroutines.flow.flowOf(emptyList<Channel>())
            } else {
                combinedM3uRepository.getCombinedChannels(profileId, combinedCategory)
            }
        }
    }

    private fun guideSelectionSeedFlow(): Flow<GuideSelectionSeed> =
        combine(
            selectedCategoryId,
            startupCategoryId,
            guideAnchorTime,
            showFavoritesOnly,
            refreshNonce
        ) { requestedCategoryId, startupSelectionId, anchorTime, favoritesOnly, _ ->
            GuideSelectionSeed(
                requestedCategoryId = startupSelectionId ?: requestedCategoryId,
                anchorTime = anchorTime,
                favoritesOnly = favoritesOnly,
                isStartupSelection = startupSelectionId != null
            )
        }

    private fun guideSelectionStateFlow(): Flow<Pair<GuideSelectionSeed, Int>> =
        guideSelectionSeedFlow().combine(preferencesRepository.parentalControlLevel) { selection, parentalControlLevel ->
            selection to parentalControlLevel
        }

    private suspend fun publishGuideSnapshot(
        providerId: Long,
        providerName: String,
        providerSourceLabel: String,
        providerArchiveSummary: String,
        categories: List<Category>,
        request: GuideBaseRequest,
        channelSelection: GuideChannelSelection,
        guideResult: GuideProgramsResult
    ) {
        prefetchJob?.cancel()
        prefetchJob = null
        val visibleChannels = channelSelection.channels.take(MAX_CHANNELS)
        val supportsOffsetPaging = providerId > 0L &&
            (request.resolvedCategoryId == ChannelRepository.ALL_CHANNELS_ID ||
                (request.resolvedCategoryId != VirtualCategoryIds.FAVORITES && request.resolvedCategoryId >= 0L))
        val computedNow = System.currentTimeMillis()
        val computedChannelsWithSchedule = visibleChannels.count { channel ->
            channel.guideLookupKey()
                ?.let { lookupKey -> guideResult.programsByChannel[lookupKey].orEmpty().isNotEmpty() }
                ?: false
        }
        val computedHasUpcomingData = guideResult.programsByChannel.values.any { programs ->
            programs.any { program -> program.endTime > request.windowStart }
        }
        baseGuideSnapshot.value = GuideBaseSnapshot(
            providerId = providerId,
            currentProviderName = providerName,
            providerSourceLabel = providerSourceLabel,
            providerArchiveSummary = providerArchiveSummary,
            categories = categories,
            selectedCategoryId = request.resolvedCategoryId,
            parentalControlLevel = request.parentalControlLevel,
            showFavoritesOnly = request.favoritesOnly,
            favoriteChannelIds = channelSelection.favoriteChannelIds,
            allChannels = channelSelection.channels,
            visibleChannels = visibleChannels,
            baseProgramsByChannel = guideResult.programsByChannel,
            failedScheduleCount = guideResult.failedCount,
            lastUpdatedAt = computedNow,
            baseChannelsWithSchedule = computedChannelsWithSchedule,
            baseGuideStale = visibleChannels.isNotEmpty() && (computedChannelsWithSchedule == 0 || !computedHasUpcomingData),
            guideAnchorTime = request.anchorTime,
            guideWindowStart = request.windowStart,
            guideWindowEnd = request.windowEnd,
            hiddenCategoryIds = request.hiddenCategoryIds,
            nextRawChannelOffset = MAX_CHANNELS,
            hasMoreChannels = if (supportsOffsetPaging) {
                channelSelection.channels.size >= MAX_CHANNELS
            } else {
                channelSelection.channels.size > visibleChannels.size
            }
        )
    }

    private fun loadEpgOverrideCandidates(channel: Channel, query: String, refreshMapping: Boolean) {
        overrideSearchJob?.cancel()
        overrideSearchJob = viewModelScope.launch {
            val mapping = if (refreshMapping) {
                epgSourceRepository.getChannelMapping(channel.providerId, channel.id)
            } else {
                _overrideUiState.value.currentMapping
            }
            val candidates = epgSourceRepository.getOverrideCandidates(
                providerId = channel.providerId,
                query = query
            )
            _overrideUiState.update {
                it.copy(
                    channel = channel,
                    currentMapping = mapping,
                    searchQuery = query,
                    candidates = candidates,
                    isLoading = false,
                    isSaving = false,
                    error = null
                )
            }
        }
    }

    private fun observeGuidePresentation() {
        viewModelScope.launch {
            combine(
                baseGuideSnapshot,
                programSearchQuery.debounce(150L),
                showScheduledOnly,
                selectedChannelMode,
                selectedDensity
            ) { baseSnapshot, searchQuery, scheduledOnly, channelMode, density ->
                GuidePresentationState(
                    baseSnapshot = baseSnapshot,
                    searchQuery = searchQuery.trim(),
                    scheduledOnly = scheduledOnly,
                    channelMode = channelMode,
                    density = density
                )
            }.collectLatest { presentation ->
                val baseSnapshot = presentation.baseSnapshot ?: run {
                    _uiState.update {
                        it.copy(
                            programSearchQuery = presentation.searchQuery,
                            showScheduledOnly = presentation.scheduledOnly,
                            selectedChannelMode = presentation.channelMode,
                            selectedDensity = presentation.density
                        )
                    }
                    return@collectLatest
                }

                val displaySnapshot = buildGuideDisplaySnapshot(
                    baseSnapshot = baseSnapshot,
                    searchQuery = presentation.searchQuery,
                    scheduledOnly = presentation.scheduledOnly,
                    channelMode = presentation.channelMode
                )

                _uiState.update {
                    it.copy(
                        currentProviderName = baseSnapshot.currentProviderName,
                        providerSourceLabel = baseSnapshot.providerSourceLabel,
                        providerArchiveSummary = baseSnapshot.providerArchiveSummary,
                        categories = baseSnapshot.categories,
                        selectedCategoryId = baseSnapshot.selectedCategoryId,
                        parentalControlLevel = baseSnapshot.parentalControlLevel,
                        programSearchQuery = presentation.searchQuery,
                        showScheduledOnly = presentation.scheduledOnly,
                        selectedChannelMode = presentation.channelMode,
                        selectedDensity = presentation.density,
                        showFavoritesOnly = baseSnapshot.showFavoritesOnly,
                        favoriteChannelIds = baseSnapshot.favoriteChannelIds,
                        channels = displaySnapshot.channels,
                        programsByChannel = displaySnapshot.programsByChannel,
                        isInitialLoading = false,
                        isRefreshing = false,
                        error = null,
                        totalChannelCount = displaySnapshot.totalChannelCount,
                        channelsWithSchedule = displaySnapshot.channelsWithSchedule,
                        failedScheduleCount = baseSnapshot.failedScheduleCount,
                        lastUpdatedAt = baseSnapshot.lastUpdatedAt,
                        isGuideStale = displaySnapshot.isGuideStale,
                        guideAnchorTime = baseSnapshot.guideAnchorTime,
                        guideWindowStart = baseSnapshot.guideWindowStart,
                        guideWindowEnd = baseSnapshot.guideWindowEnd,
                        hasMoreChannels = baseSnapshot.hasMoreChannels
                    )
                }
            }
        }
    }

    private fun restoreGuidePreferences() {
        viewModelScope.launch {
            preferencesRepository.guideDensity.first()
                ?.let { saved ->
                    GuideDensity.entries.firstOrNull { it.name == saved }?.let { density ->
                        selectedDensity.value = density
                    }
                }
            preferencesRepository.guideChannelMode.first()
                ?.let { saved ->
                    GuideChannelMode.entries.firstOrNull { it.name == saved }?.let { mode ->
                        selectedChannelMode.value = mode
                    }
                }
            startupCategoryId.value = preferencesRepository.guideDefaultCategoryId.first() ?: VirtualCategoryIds.FAVORITES
            showFavoritesOnly.value = preferencesRepository.guideFavoritesOnly.first()
            showScheduledOnly.value = preferencesRepository.guideScheduledOnly.first()
            preferencesRepository.guideAnchorTime.first()
                ?.takeIf { it > 0L }
                ?.let { guideAnchorTime.value = it }
        }
    }

    private fun buildProviderSourceLabel(provider: com.streamvault.domain.model.LegacyProvider): String {
        return when (provider.type) {
            com.streamvault.domain.model.ProviderType.XTREAM_CODES -> "Xtream Codes"
            com.streamvault.domain.model.ProviderType.M3U -> "M3U Playlist"
            com.streamvault.domain.model.ProviderType.STALKER_PORTAL -> "Stalker/MAG Portal"
            com.streamvault.domain.model.ProviderType.JELLYFIN -> "Jellyfin"
        }
    }

    private fun buildProviderArchiveSummary(provider: com.streamvault.domain.model.LegacyProvider): String {
        return when (provider.type) {
            com.streamvault.domain.model.ProviderType.XTREAM_CODES ->
                "Xtream replay depends on archive-enabled channels and valid replay stream ids from the provider."
            com.streamvault.domain.model.ProviderType.M3U ->
                if (provider.epgUrl.isBlank()) {
                    "M3U replay is limited: archive depends on provider templates and guide coverage is weaker without XMLTV."
                } else {
                    "M3U replay depends on the provider catch-up template and matching guide data."
                }
            com.streamvault.domain.model.ProviderType.STALKER_PORTAL ->
                if (provider.epgUrl.isBlank()) {
                    "Portal guide falls back to on-demand Stalker data when XMLTV is unavailable."
                } else {
                    "Guide combines optional XMLTV with on-demand Stalker portal data."
                }
            com.streamvault.domain.model.ProviderType.JELLYFIN ->
                if (provider.epgUrl.isBlank()) {
                    "Jellyfin replay depends on the server guide data being populated."
                } else {
                    "Jellyfin replay combines server guide data with optional XMLTV import."
                }
        }
    }

    private fun updateGuideAnchorTime(anchorTimeMs: Long) {
        guideAnchorTime.value = anchorTimeMs
        viewModelScope.launch {
            preferencesRepository.setGuideAnchorTime(anchorTimeMs)
        }
    }

    private fun buildGuideCategoryList(
        providerCategories: List<Category>,
        customCategories: List<Category>,
        showAllChannels: Boolean = true
    ): List<Category> {
        val favoritesCategory = customCategories.find { it.id == VirtualCategoryIds.FAVORITES }
        return buildList {
            if (favoritesCategory != null) {
                add(favoritesCategory)
            }
            addAll(customCategories.filter { it.id != VirtualCategoryIds.FAVORITES })
            if (showAllChannels) {
                add(
                    Category(
                        id = ChannelRepository.ALL_CHANNELS_ID,
                        name = "All Channels",
                        type = ContentType.LIVE,
                        count = providerCategories.sumOf(Category::count)
                    )
                )
            }
            addAll(providerCategories)
        }
    }

    private fun loadGuideChannelsForProvider(
        providerId: Long,
        request: GuideBaseRequest
    ) = when (request.resolvedCategoryId) {
        ChannelRepository.ALL_CHANNELS_ID -> loadPreferredGuideChannelsPage(
            providerId = providerId,
            categoryId = request.resolvedCategoryId,
            favoritesOnly = request.favoritesOnly
        )

        VirtualCategoryIds.FAVORITES -> favoriteRepository.getFavorites(providerId, ContentType.LIVE)
            .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
            .flatMapLatest { ids -> loadGuideChannelsByOrderedIds(ids, providerId) }

        in Long.MIN_VALUE..<0L -> favoriteRepository.getFavoritesByGroup(-request.resolvedCategoryId)
            .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
            .flatMapLatest { ids -> loadGuideChannelsByOrderedIds(ids, providerId) }

        else -> loadPreferredGuideChannelsPage(
            providerId = providerId,
            categoryId = request.resolvedCategoryId,
            favoritesOnly = request.favoritesOnly
        )
    }

    private fun loadPreferredGuideChannelsPage(
        providerId: Long,
        categoryId: Long,
        favoritesOnly: Boolean
    ): Flow<List<Channel>> = if (favoritesOnly) {
        favoriteRepository.getFavorites(providerId, ContentType.LIVE)
            .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
            .flatMapLatest { favoriteIds ->
                val favoriteChannels = loadGuideChannelsByOrderedIds(favoriteIds, providerId)
                if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
                    favoriteChannels
                } else {
                    combine(
                        favoriteChannels,
                        channelRepository.getChannelsByCategory(providerId, categoryId)
                    ) { orderedFavorites, categoryChannels ->
                        val categoryChannelIds = categoryChannels.map(Channel::id).toSet()
                        orderedFavorites.filter { it.id in categoryChannelIds }
                    }
                }
            }
    } else {
        combine(
            channelRepository.getChannelsByCategoryPage(providerId, categoryId, MAX_CHANNELS),
            channelRepository.getChannelsWithoutErrorsPage(providerId, categoryId, MAX_CHANNELS),
            favoriteRepository.getFavorites(providerId, ContentType.LIVE)
        ) { channelsByNumber, healthyChannels, _ ->
            healthyChannels.ifEmpty { channelsByNumber }
        }
    }

    private fun combinedProviderIdsFlow(profileId: Long): kotlinx.coroutines.flow.Flow<List<Long>> = kotlinx.coroutines.flow.flow {
        emit(combinedM3uRepository.getProfile(profileId)?.members.orEmpty())
    }.map { members ->
        members.filter { it.enabled }.map { it.providerId }
    }

    private fun observeLiveFavorites(providerIds: List<Long>): kotlinx.coroutines.flow.Flow<List<Favorite>> = when (providerIds.size) {
        0 -> kotlinx.coroutines.flow.flowOf(emptyList())
        1 -> favoriteRepository.getFavorites(providerIds.first(), ContentType.LIVE)
        else -> favoriteRepository.getFavorites(providerIds, ContentType.LIVE)
    }

    private fun loadGuideChannelsByOrderedIds(ids: List<Long>, providerId: Long? = null) =
        if (ids.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        } else {
            channelRepository.getChannelsByIds(ids).map { unsorted ->
                val filtered = providerId?.let { requiredProviderId ->
                    unsorted.filter { it.providerId == requiredProviderId }
                } ?: unsorted
                filtered.orderedByRequestedRawIds(ids)
            }
        }

    private fun finalizeStartupCategory(resolvedCategoryId: Long) {
        if (startupCategoryId.value == null) return
        startupCategoryId.value = null
        selectedCategoryId.value = resolvedCategoryId
    }

    private suspend fun loadGuidePrograms(
        providerId: Long,
        channels: List<Channel>,
        windowStart: Long,
        windowEnd: Long
    ): GuideProgramsResult {
        if (channels.isEmpty()) {
            return GuideProgramsResult(emptyMap(), failedCount = 0)
        }

        // 1. Try the multi-source resolved path first.
        val channelIds = channels.map { it.id }
        val resolvedPrograms: Map<String, List<Program>> = runCatching {
            epgRepository.getResolvedProgramsForChannels(providerId, channelIds, windowStart, windowEnd)
        }.getOrElse { emptyMap() }

        // 2. For channels not covered by resolution, fall back to legacy provider-native query.
        val unresolvedChannels = channels.filter { channel ->
            val key = channel.guideLookupKey()
            key == null || resolvedPrograms[key].isNullOrEmpty()
        }
        val legacyPrograms: Map<String, List<Program>> = if (unresolvedChannels.isNotEmpty()) {
            val fallbackGuideKeys = unresolvedChannels.mapNotNull(Channel::guideLookupKey).distinct()
            runCatching {
                if (fallbackGuideKeys.isEmpty()) emptyMap()
                else epgRepository.getProgramsForChannelsSnapshot(providerId, fallbackGuideKeys, windowStart, windowEnd)
            }.getOrElse { emptyMap() }
        } else {
            emptyMap()
        }

        // Sentinel keys (shared portal placeholders like "glotv") must never surface cached
        // placeholder rows as if they were this channel's real schedule.
        val programsByChannel = (resolvedPrograms + legacyPrograms)
            .filterKeys { it !in GuideCachePolicy.EPG_SENTINEL_KEYS }
        return GuideProgramsResult(
            programsByChannel = programsByChannel,
            failedCount = countMissingGuideEntries(channels, programsByChannel)
        )
    }

    private suspend fun loadCombinedGuidePrograms(
        channels: List<Channel>,
        windowStart: Long,
        windowEnd: Long
    ): GuideProgramsResult {
        if (channels.isEmpty()) {
            return GuideProgramsResult(emptyMap(), failedCount = 0)
        }
        val groupedPrograms = channels.groupBy { it.providerId }
            .mapValues { (_, providerChannels) ->
                providerChannels
            }

        val mergedProgramsByChannel = buildMap<String, List<Program>> {
            groupedPrograms.forEach { (providerId, providerChannels) ->
                val result = loadGuidePrograms(
                    providerId = providerId,
                    channels = providerChannels,
                    windowStart = windowStart,
                    windowEnd = windowEnd
                )
                putAll(result.programsByChannel)
            }
        }
        val guideKeys = channels.mapNotNull(Channel::guideLookupKey).distinct()
        return GuideProgramsResult(
            programsByChannel = mergedProgramsByChannel,
            failedCount = countMissingGuideEntries(channels, mergedProgramsByChannel)
        )
    }

    private fun scheduleGuideFallbackEnrichment(
        snapshotContext: GuideFallbackContext,
        channels: List<Channel>,
        existingProgramsByChannel: Map<String, List<Program>>
    ) {
        // The guide observation re-emits whenever any upstream flow changes (e.g. the
        // catalog sync writing categories/channels). Cancelling and restarting an identical
        // in-flight fallback would starve the chunked short-EPG fetch before it completes,
        // so let a matching job run to completion and only (re)start when the context differs.
        if (guideFallbackJob?.isActive == true && guideFallbackContext == snapshotContext) {
            return
        }
        guideFallbackJob?.cancel()
        guideFallbackContext = snapshotContext
        if (channels.isEmpty()) {
            return
        }

        guideFallbackJob = viewModelScope.launch {
            val fallbackProgramsByChannel = buildMap {
                channels.groupBy(Channel::providerId).forEach { (providerId, providerChannels) ->
                    val provider = providerRepository.getProvider(providerId) ?: return@forEach
                    val providerPrograms = fetchXtreamGuideFallback(
                        provider = provider,
                        providerId = providerId,
                        channels = providerChannels,
                        existingProgramsByChannel = existingProgramsByChannel + this,
                        windowStart = snapshotContext.guideWindowStart,
                        windowEnd = snapshotContext.guideWindowEnd,
                        // Publish each fetched chunk so the grid fills progressively instead of
                        // waiting for the entire page of paced requests.
                        onPartial = { additions -> publishGuideFallbackUpdate(snapshotContext, additions) }
                    )
                    putAll(providerPrograms)
                }
            }
            // Final pass covers any additions that were not published incrementally.
            publishGuideFallbackUpdate(snapshotContext, fallbackProgramsByChannel)
        }
    }

    private fun publishGuideFallbackUpdate(
        snapshotContext: GuideFallbackContext,
        additions: Map<String, List<Program>>
    ) {
        if (additions.isEmpty()) return
        baseGuideSnapshot.update { currentSnapshot ->
            if (currentSnapshot == null || !currentSnapshot.matches(snapshotContext)) {
                return@update currentSnapshot
            }

            val mergedProgramsByChannel = currentSnapshot.baseProgramsByChannel + additions
            val visibleChannels = currentSnapshot.visibleChannels
            val channelsWithSchedule = countChannelsWithSchedule(visibleChannels, mergedProgramsByChannel)
            val hasUpcomingData = hasUpcomingGuideData(mergedProgramsByChannel, currentSnapshot.guideWindowStart)

            currentSnapshot.copy(
                baseProgramsByChannel = mergedProgramsByChannel,
                failedScheduleCount = countMissingGuideEntries(visibleChannels, mergedProgramsByChannel),
                lastUpdatedAt = System.currentTimeMillis(),
                baseChannelsWithSchedule = channelsWithSchedule,
                baseGuideStale = visibleChannels.isNotEmpty() && (channelsWithSchedule == 0 || !hasUpcomingData)
            )
        }
    }

    private suspend fun fetchXtreamGuideFallback(
        provider: com.streamvault.domain.model.LegacyProvider,
        providerId: Long,
        channels: List<Channel>,
        existingProgramsByChannel: Map<String, List<Program>>,
        windowStart: Long,
        windowEnd: Long,
        onPartial: (Map<String, List<Program>>) -> Unit = {}
    ): Map<String, List<Program>> {
        if (provider.guideSourcePolicy == GuideSourcePolicy.EXTERNAL_ONLY ||
            provider.guideSourcePolicy == GuideSourcePolicy.DISABLED
        ) {
            return emptyMap()
        }
        if (
            provider.type != com.streamvault.domain.model.ProviderType.XTREAM_CODES &&
            provider.type != com.streamvault.domain.model.ProviderType.STALKER_PORTAL
        ) {
            return emptyMap()
        }

        ensurePersistedEmptyGuideKeys(providerId)
        val persistedFresh = persistedEmptyGuideKeys[providerId].orEmpty()

        // Sentinel keys (shared portal placeholders) never carry a real schedule; remember
        // them as empty without spending a paced request.
        val sentinelKeys = GuideCachePolicy.sentinelKeysOf(channels)
        if (sentinelKeys.isNotEmpty()) {
            knownEmptyGuideKeys += sentinelKeys
            preferencesRepository.addEmptyGuideKeys(providerId, sentinelKeys)
        }

        val missingChannels = GuideCachePolicy.requestableChannels(
            channels = channels,
            sessionEmptyKeys = knownEmptyGuideKeys,
            persistedEmptyAt = persistedFresh,
            existingProgramsByChannel = existingProgramsByChannel
        )
        if (missingChannels.isEmpty()) {
            return emptyMap()
        }

        val fallbackChannels = missingChannels.take(MAX_XTREAM_GUIDE_FALLBACK_CHANNELS)
        val result = linkedMapOf<String, List<Program>>()

        // Fetch in small chunks so partial results can be published as they land; a full
        // page of paced short-EPG requests would otherwise hold the grid empty for a minute.
        fallbackChannels.chunked(GUIDE_FALLBACK_CHUNK_SIZE).forEach { chunk ->
            val programsByRequest = providerRepository.getProgramsForLiveStreams(
                providerId = providerId,
                requests = chunk.map { channel ->
                    LiveStreamProgramRequest(
                        streamId = channel.streamId,
                        epgChannelId = channel.epgChannelId
                    )
                },
                limit = MAX_XTREAM_GUIDE_FALLBACK_PROGRAMS
            )
            val newlyEmpty = mutableSetOf<String>()
            val recovered = mutableSetOf<String>()

            chunk.forEach { channel ->
                val fetchResult = programsByRequest[
                    LiveStreamProgramRequest(
                        streamId = channel.streamId,
                        epgChannelId = channel.epgChannelId
                    )
                ]
                val programs = (fetchResult as? com.streamvault.domain.model.Result.Success)?.data
                    .orEmpty()
                    .filter { program -> program.endTime > windowStart && program.startTime < windowEnd }
                    .sortedBy { program -> program.startTime }
                val lookupKey = channel.guideLookupKey() ?: return@forEach
                if (programs.isEmpty()) {
                    // Successful but empty: the portal has no guide for this channel right
                    // now. Remember it in-memory and durably so later pages and future
                    // sessions skip it instantly. Errors are left out so transient failures
                    // retry on the next page.
                    if (fetchResult is com.streamvault.domain.model.Result.Success) {
                        newlyEmpty += lookupKey
                    }
                } else {
                    // Fresh data may have appeared since a previous empty result.
                    recovered += lookupKey
                    result[lookupKey] = programs
                }
            }
            if (newlyEmpty.isNotEmpty()) {
                knownEmptyGuideKeys += newlyEmpty
                preferencesRepository.addEmptyGuideKeys(providerId, newlyEmpty)
            }
            if (recovered.isNotEmpty()) {
                knownEmptyGuideKeys -= recovered
                preferencesRepository.removeEmptyGuideKeys(providerId, recovered)
            }
            if (result.isNotEmpty()) {
                onPartial(result)
            }
        }
        return result
    }

    private suspend fun ensurePersistedEmptyGuideKeys(providerId: Long) {
        if (providerId !in persistedEmptyGuideKeys) {
            persistedEmptyGuideKeys[providerId] = preferencesRepository.getEmptyGuideKeys(providerId)
        }
    }

    private fun GuideBaseSnapshot.matches(context: GuideFallbackContext): Boolean =
        providerId == context.providerId &&
            selectedCategoryId == context.selectedCategoryId &&
            guideAnchorTime == context.guideAnchorTime &&
            guideWindowStart == context.guideWindowStart &&
            guideWindowEnd == context.guideWindowEnd &&
            visibleChannels.map(Channel::id) == context.visibleChannelIds

    private fun countMissingGuideEntries(
        channels: List<Channel>,
        programsByChannel: Map<String, List<Program>>
    ): Int =
        channels.mapNotNull(Channel::guideLookupKey)
            .distinct()
            .count { lookupKey -> programsByChannel[lookupKey].isNullOrEmpty() }

    private fun countChannelsWithSchedule(
        channels: List<Channel>,
        programsByChannel: Map<String, List<Program>>
    ): Int =
        channels.count { channel ->
            channel.guideLookupKey()
                ?.let { lookupKey -> programsByChannel[lookupKey].orEmpty().isNotEmpty() }
                ?: false
        }

    private fun hasUpcomingGuideData(
        programsByChannel: Map<String, List<Program>>,
        windowStart: Long
    ): Boolean =
        programsByChannel.values.any { programs ->
            programs.any { program -> program.endTime > windowStart }
        }

    private suspend fun buildGuideDisplaySnapshot(
        baseSnapshot: GuideBaseSnapshot,
        searchQuery: String,
        scheduledOnly: Boolean,
        channelMode: GuideChannelMode
    ): GuideDisplaySnapshot {
        val normalizedQuery = searchQuery.trim()
        val (candidateChannels, candidateProgramsByChannel) = if (normalizedQuery.isBlank()) {
            baseSnapshot.visibleChannels to baseSnapshot.baseProgramsByChannel
        } else {
            buildSearchGuideSnapshot(baseSnapshot, normalizedQuery)
        }

        val displayChannels = candidateChannels.filter { channel ->
            val programs = channel.guideLookupKey()
                ?.let { lookupKey -> candidateProgramsByChannel[lookupKey].orEmpty() }
                .orEmpty()
            val matchesScheduled = !scheduledOnly || programs.isNotEmpty()
            val matchesMode = when (channelMode) {
                GuideChannelMode.ALL -> true
                GuideChannelMode.ANCHORED -> programs.any { program ->
                    baseSnapshot.guideAnchorTime in program.startTime until program.endTime
                }
                GuideChannelMode.ARCHIVE_READY -> programs.any { program ->
                    channel.isArchivePlayable(program, baseSnapshot.guideAnchorTime)
                }
            }
            matchesScheduled && matchesMode
        }
        val channelsWithSchedule = candidateChannels.count { channel ->
            channel.guideLookupKey()
                ?.let { lookupKey -> candidateProgramsByChannel[lookupKey].orEmpty().isNotEmpty() }
                ?: false
        }
        val hasUpcomingData = candidateProgramsByChannel.values.any { programs ->
            programs.any { program -> program.endTime > baseSnapshot.guideWindowStart }
        }

        return GuideDisplaySnapshot(
            channels = displayChannels,
            programsByChannel = candidateProgramsByChannel,
            totalChannelCount = candidateChannels.size,
            channelsWithSchedule = channelsWithSchedule,
            isGuideStale = candidateChannels.isNotEmpty() && (channelsWithSchedule == 0 || !hasUpcomingData)
        )
    }

    private suspend fun buildSearchGuideSnapshot(
        baseSnapshot: GuideBaseSnapshot,
        searchQuery: String
    ): Pair<List<Channel>, Map<String, List<Program>>> {
        val scopedChannels = loadGuideSearchScopeChannels(baseSnapshot)
        val scopedChannelsByLookup = scopedChannels.mapNotNull { channel ->
            channel.guideLookupKey()?.let { lookupKey -> lookupKey to channel }
        }.toMap()

        val metadataMatchedLookupKeys = scopedChannels.filter { channel ->
            channel.matchesGuideMetadataSearch(searchQuery)
        }.mapNotNull(Channel::guideLookupKey).toSet()

        val repositoryMatchedPrograms = epgRepository.searchPrograms(
            providerId = baseSnapshot.providerId,
            query = searchQuery,
            startTime = baseSnapshot.guideWindowStart,
            endTime = baseSnapshot.guideWindowEnd,
            categoryId = baseSnapshot.selectedCategoryId.takeIf { it >= 0L }
        ).first().groupBy { it.channelId }

        val repositoryMatchedLookupKeys = repositoryMatchedPrograms.keys
        val matchedLookupKeys = buildList {
            scopedChannels.forEach { channel ->
                val lookupKey = channel.guideLookupKey() ?: return@forEach
                if (lookupKey in metadataMatchedLookupKeys || lookupKey in repositoryMatchedLookupKeys) {
                    add(lookupKey)
                }
            }
        }.take(MAX_CHANNELS)

        val missingMetadataLookupKeys = matchedLookupKeys.filter { lookupKey ->
            lookupKey in metadataMatchedLookupKeys && lookupKey !in baseSnapshot.baseProgramsByChannel
        }
        val missingMetadataPrograms = if (missingMetadataLookupKeys.isEmpty()) {
            emptyMap()
        } else {
            epgRepository.getProgramsForChannelsSnapshot(
                providerId = baseSnapshot.providerId,
                channelIds = missingMetadataLookupKeys,
                startTime = baseSnapshot.guideWindowStart,
                endTime = baseSnapshot.guideWindowEnd
            )
        }

        val matchedChannels = matchedLookupKeys.mapNotNull(scopedChannelsByLookup::get)
        val matchedPrograms = matchedLookupKeys.associateWith { lookupKey ->
            if (lookupKey in metadataMatchedLookupKeys) {
                baseSnapshot.baseProgramsByChannel[lookupKey]
                    ?: missingMetadataPrograms[lookupKey]
                    ?: emptyList()
            } else {
                repositoryMatchedPrograms[lookupKey].orEmpty()
            }
        }
        return matchedChannels to matchedPrograms
    }

    private suspend fun loadGuideSearchScopeChannels(baseSnapshot: GuideBaseSnapshot): List<Channel> {
        val rawChannels = when {
            baseSnapshot.showFavoritesOnly || baseSnapshot.selectedCategoryId < 0L -> baseSnapshot.allChannels
            baseSnapshot.selectedCategoryId == ChannelRepository.ALL_CHANNELS_ID ->
                channelRepository.getChannels(baseSnapshot.providerId).first()
            else -> channelRepository.getChannelsByCategory(baseSnapshot.providerId, baseSnapshot.selectedCategoryId).first()
        }
        if (baseSnapshot.selectedCategoryId != ChannelRepository.ALL_CHANNELS_ID) {
            return rawChannels.filterNot { channel ->
                channel.categoryId != null && channel.categoryId in baseSnapshot.hiddenCategoryIds
            }
        }

        val accessibleCategoryIds = baseSnapshot.categories.filter { category ->
            isGuideCategoryAccessible(
                category = category,
                parentalControlLevel = baseSnapshot.parentalControlLevel,
                unlockedCategoryIds = emptySet()
            )
        }.map(Category::id).toSet()

        return rawChannels.filterNot { channel ->
            channel.categoryId != null && channel.categoryId in baseSnapshot.hiddenCategoryIds
        }.filter { channel ->
            channel.categoryId == null || channel.categoryId in accessibleCategoryIds
        }
    }

    private fun Channel.matchesGuideMetadataSearch(searchQuery: String): Boolean {
        return name.contains(searchQuery, ignoreCase = true) ||
            categoryName?.contains(searchQuery, ignoreCase = true) == true
    }

    private fun resolveGuideCategorySelection(
        requestedCategoryId: Long,
        categories: List<Category>,
        parentalControlLevel: Int,
        unlockedCategoryIds: Set<Long>,
        fallbackFromEmptyFavorites: Boolean = false
    ): Long {
        val requestedExists = categories.any { it.id == requestedCategoryId }
        if (requestedCategoryId == ChannelRepository.ALL_CHANNELS_ID && requestedExists) {
            return ChannelRepository.ALL_CHANNELS_ID
        }

        val requestedCategory = categories.firstOrNull { it.id == requestedCategoryId }
        if (requestedCategory != null && isGuideCategoryAccessible(requestedCategory, parentalControlLevel, unlockedCategoryIds)) {
            if (fallbackFromEmptyFavorites && requestedCategory.id == VirtualCategoryIds.FAVORITES && requestedCategory.count <= 0) {
                return categories.find { it.id == ChannelRepository.ALL_CHANNELS_ID }?.id
                    ?: categories.firstOrNull {
                        !(it.id == VirtualCategoryIds.FAVORITES && it.count <= 0) &&
                            isGuideCategoryAccessible(it, parentalControlLevel, unlockedCategoryIds)
                    }?.id
                    ?: categories.firstOrNull()?.id
                    ?: ChannelRepository.ALL_CHANNELS_ID
            }
            return requestedCategory.id
        }

        return categories.firstOrNull { category ->
            if (fallbackFromEmptyFavorites && category.id == VirtualCategoryIds.FAVORITES && category.count <= 0) {
                false
            } else {
                isGuideCategoryAccessible(category, parentalControlLevel, unlockedCategoryIds)
            }
        }?.id ?: categories.firstOrNull()?.id ?: ChannelRepository.ALL_CHANNELS_ID
    }

    private fun isGuideCategoryAccessible(
        category: Category,
        parentalControlLevel: Int,
        unlockedCategoryIds: Set<Long>
    ): Boolean {
        // Non-adult/protected categories are always accessible
        if (!category.isAdult && !category.isUserProtected) return true
        // Adult/protected: accessible if level permits aggregated surfaces (OFF or LOCKED),
        // OR if the user has explicitly unlocked this category
        return AdultContentVisibilityPolicy.showInAggregatedSurfaces(parentalControlLevel) ||
            unlockedCategoryIds.contains(category.id)
    }
}

private data class GuidePresentationState(
    val baseSnapshot: GuideBaseSnapshot?,
    val searchQuery: String,
    val scheduledOnly: Boolean,
    val channelMode: GuideChannelMode,
    val density: GuideDensity
)

private data class GuideSelectionSeed(
    val requestedCategoryId: Long,
    val anchorTime: Long,
    val favoritesOnly: Boolean,
    val isStartupSelection: Boolean
)

private data class GuideCategoryData(
    val providerCategories: List<Category>,
    val customCategories: List<Category>,
    val hiddenCategoryIds: Set<Long>,
    val sortMode: com.streamvault.domain.model.CategorySortMode,
    val showAllChannels: Boolean = true
)
