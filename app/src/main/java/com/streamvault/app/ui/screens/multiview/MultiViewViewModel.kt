package com.streamvault.app.ui.screens.multiview

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.di.AuxiliaryPlayerEngine
import com.streamvault.app.ui.model.associateByAnyRawId
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.UnlockParentalCategory
import com.streamvault.domain.usecase.UnlockParentalCategoryCommand
import com.streamvault.player.PlayerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@HiltViewModel
class MultiViewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val multiViewManager: MultiViewManager,
    @AuxiliaryPlayerEngine
    private val playerEngineProvider: Provider<PlayerEngine>,
    private val preferencesRepository: PreferencesRepository,
    private val channelRepository: ChannelRepository,
    private val favoriteRepository: FavoriteRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val providerRepository: ProviderRepository,
    private val parentalControlManager: ParentalControlManager,
    private val unlockParentalCategory: UnlockParentalCategory
) : ViewModel() {

    private val _uiState = MutableStateFlow(MultiViewUiState())
    val uiState: StateFlow<MultiViewUiState> = _uiState.asStateFlow()
    
    private var borderHideJob: kotlinx.coroutines.Job? = null
    private var telemetryJob: kotlinx.coroutines.Job? = null
    private var pinnedAudioSlotIndex: Int? = null
    private val detectedTier: DevicePerformanceTier by lazy { detectDeviceTier() }
    private val powerManager: PowerManager? by lazy { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    private var thermalStatus: MultiViewThermalStatus = readThermalStatus()
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var runtimeActiveSlotLimit: Int = MultiViewManager.MAX_SLOTS
    private var activeProviderConnectionLimit: Int? = null
    private var sustainedStressSamples: Int = 0
    private var stableSamples: Int = 0
    private var lastPolicyAdjustmentAt: Long = 0L
    private val lastDroppedFramesBySlot = mutableMapOf<Int, Int>()
    private val slotStartupJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
    private val slotErrorJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
    private val slotGenerations = mutableMapOf<Int, Long>()
    private var slotInitVersion: Long = 0L
    private var playbackSessionActive: Boolean = false
    private var currentProviderId: Long? = null
    private var lastPerformanceMode: MultiViewPerformanceMode? = null

    /** Flow of the current 4 slot channels from the manager */
    val slotsFlow = multiViewManager.slots

    private val playerEngines = mutableMapOf<Int, PlayerEngine>()

    init {
        viewModelScope.launch {
            combine(
                preferencesRepository.playerAudioVideoSyncEnabled,
                preferencesRepository.playerAudioVideoOffsetMs
            ) { _, _ -> Unit }.collect {
                applyAudioVideoOffsetsToActiveEngines()
            }
        }
        viewModelScope.launch {
            combine(
                preferencesRepository.getMultiViewPreset(0),
                preferencesRepository.getMultiViewPreset(1),
                preferencesRepository.getMultiViewPreset(2),
                preferencesRepository.multiViewPerformanceMode,
                preferencesRepository.multiViewCenterTwoSlotLayout
            ) { preset1, preset2, preset3, performanceMode, centerTwoSlotLayout ->
                Triple(listOf(preset1, preset2, preset3), performanceMode, centerTwoSlotLayout)
            }.collect { presets ->
                val presetList = presets.first
                val mode = presets.second
                    ?.let { saved -> MultiViewPerformanceMode.entries.firstOrNull { it.name == saved } }
                    ?: MultiViewPerformanceMode.AUTO
                val policy = resolvePolicy(mode, detectedTier)
                val modeChanged = lastPerformanceMode != null && lastPerformanceMode != mode
                lastPerformanceMode = mode
                runtimeActiveSlotLimit = if (modeChanged &&
                    thermalStatus != MultiViewThermalStatus.SEVERE &&
                    thermalStatus != MultiViewThermalStatus.CRITICAL
                ) {
                    policy.maxActiveSlots
                } else {
                    runtimeActiveSlotLimit.coerceAtLeast(1).coerceAtMost(policy.maxActiveSlots)
                }
                _uiState.value = _uiState.value.copy(
                    centerTwoSlotLayout = presets.third,
                    presets = presetList.mapIndexed { index, ids ->
                        MultiViewPresetUiModel(
                            index = index,
                            label = "Preset ${index + 1}",
                            isPopulated = ids.isNotEmpty(),
                            channelCount = ids.size
                        )
                    },
                    pinnedAudioSlotIndex = pinnedAudioSlotIndex,
                    performancePolicy = policy,
                    telemetry = _uiState.value.telemetry.copy(
                        activeSlotLimit = runtimeActiveSlotLimit,
                        thermalStatus = thermalStatus,
                        recommendation = buildTelemetryRecommendation(
                            activeSlotLimit = runtimeActiveSlotLimit,
                            thermalStatus = thermalStatus,
                            isLowMemory = false,
                            sustainedLoadScore = 0,
                            policy = policy
                        )
                    )
                )
            }
        }

        viewModelScope.launch {
            combine(
                providerRepository.getActiveProvider(),
                preferencesRepository.multiViewRespectProviderConnectionLimit
            ) { provider, respectProviderLimit ->
                if (!respectProviderLimit) {
                    null
                } else {
                    provider
                        ?.takeIf { it.type == ProviderType.XTREAM_CODES }
                        ?.maxConnections
                        ?.coerceAtLeast(1)
                }
            }.collect { nextConnectionLimit ->
                if (activeProviderConnectionLimit != nextConnectionLimit) {
                    activeProviderConnectionLimit = nextConnectionLimit
                    if (multiViewManager.hasAnyChannel) {
                        restartPlaybackIfActive()
                    }
                }
            }
        }

        viewModelScope.launch {
            preferencesRepository.parentalControlLevel.collect { level ->
                _uiState.value = _uiState.value.copy(parentalControlLevel = level)
            }
        }

        registerThermalListener()
        observeReplacementCandidates()
    }

    suspend fun verifyPin(pin: String): Boolean =
        preferencesRepository.verifyParentalPin(pin)

    fun unlockPickerCategory(categoryId: Long) {
        val providerId = currentProviderId ?: return
        parentalControlManager.unlockCategory(providerId, categoryId)
    }

    fun releasePlayersForBackground() {
        playbackSessionActive = false
        telemetryJob?.cancel()
        telemetryJob = null
        releaseActivePlayers()
    }

    /** Called when MultiViewScreen is opened — spins up player engines for occupied slots */
    fun initSlots() {
        playbackSessionActive = true
        normalizeCenteredTwoSlotLayoutIfNeeded()
        val initVersion = ++slotInitVersion
        telemetryJob?.cancel()
        cancelSlotStartupJobs()
        cancelSlotErrorJobs()
        releaseActivePlayers()
        lastDroppedFramesBySlot.clear()
        val channels = multiViewManager.slots.value
        val deviceActiveSlotLimit = runtimeActiveSlotLimit
            .coerceAtLeast(1)
            .coerceAtMost(_uiState.value.performancePolicy.maxActiveSlots)
        val providerConnectionLimit = activeProviderConnectionLimit
        val maxActiveSlots = providerConnectionLimit
            ?.let { deviceActiveSlotLimit.coerceAtMost(it) }
            ?: deviceActiveSlotLimit
        var occupiedCount = 0
        val slots = channels.mapIndexed { index, channel ->
            if (channel != null) {
                val isActive = occupiedCount < maxActiveSlots
                occupiedCount++
                val performanceBlockedReason = when {
                    isActive -> null
                    providerConnectionLimit != null && providerConnectionLimit < deviceActiveSlotLimit ->
                        "Held back by the provider connection limit (${providerConnectionLimit} stream(s) max)."
                    else ->
                        "Held back by ${_uiState.value.performancePolicy.mode.name.lowercase()} policy on this device tier."
                }
                MultiViewSlot(
                    index = index,
                    channel = channel,
                    streamUrl = channel.streamUrl,
                    title = channel.name,
                    isLoading = isActive,
                    isAudioPinned = pinnedAudioSlotIndex == index,
                    performanceBlockedReason = performanceBlockedReason
                )
            } else {
                MultiViewSlot(index = index)
            }
        }
        _uiState.value = _uiState.value.copy(
            slots = slots,
            pinnedAudioSlotIndex = pinnedAudioSlotIndex,
            telemetry = _uiState.value.telemetry.copy(
                activeSlotLimit = maxActiveSlots,
                activeSlots = slots.count { !it.isEmpty && it.performanceBlockedReason == null },
                standbySlots = slots.count { !it.isEmpty && it.performanceBlockedReason != null },
                throttledReason = _uiState.value.telemetry.throttledReason,
                thermalStatus = thermalStatus,
                recommendation = buildTelemetryRecommendation(
                    activeSlotLimit = maxActiveSlots,
                    thermalStatus = thermalStatus,
                    isLowMemory = false,
                    sustainedLoadScore = _uiState.value.telemetry.sustainedLoadScore,
                    policy = _uiState.value.performancePolicy
                )
            )
        )

        // Start all occupied engines
        slots.forEachIndexed { index, slot ->
            if (!slot.isEmpty) {
                if (slot.performanceBlockedReason != null) {
                    updateSlot(index) { it.copy(isLoading = false, playerEngine = null) }
                    return@forEachIndexed
                }
                val startupJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(index * _uiState.value.performancePolicy.startupDelayMs)
                    if (initVersion != slotInitVersion) return@launch
                    val slotGen = slotGenerations.getOrDefault(index, 0L)
                    var localEngine: PlayerEngine? = null
                    var enginePublished = false
                    try {
                        localEngine = playerEngineProvider.get()
                        // Cap each multi-view slot to 720p so slots don't compete for 4K bandwidth
                        (localEngine as? com.streamvault.player.Media3PlayerEngine)
                            ?.let {
                                it.constrainResolutionForMultiView = true
                                it.bypassAudioFocus = true
                                it.enableMediaSession = false
                            }
                        if (initVersion != slotInitVersion || slotGen != slotGenerations.getOrDefault(index, 0L)) {
                            return@launch
                        }
                        observeSlotErrors(index, localEngine, initVersion)

                        val channel = slot.channel
                            ?: throw IllegalStateException("Missing channel for slot ${slot.index}")
                        val avSyncEnabled = preferencesRepository.playerAudioVideoSyncEnabled.first()
                        localEngine.setAudioVideoSyncEnabled(avSyncEnabled)
                        localEngine.setAudioVideoOffsetMs(
                            if (avSyncEnabled) effectiveAudioVideoOffsetForChannel(channel.id) else 0
                        )
                        localEngine.setFastRetryOnTransientFailures(
                            preferencesRepository.playerFastRetryOnTransientFailures.first()
                        )
                        localEngine.setPlaybackBufferMode(preferencesRepository.playerPlaybackBufferMode.first())
                        val streamInfo = when (val result = channelRepository.getStreamInfo(channel)) {
                            is Result.Success -> result.data
                            is Result.Error -> throw IllegalStateException(result.message)
                            Result.Loading -> throw IllegalStateException("Stream info still loading for ${slot.title}")
                        }

                        if (initVersion != slotInitVersion || slotGen != slotGenerations.getOrDefault(index, 0L)) {
                            return@launch
                        }
                        localEngine.prepare(streamInfo)
                        localEngine.play()

                        // Success: publish engine
                        playerEngines[index] = localEngine
                        enginePublished = true

                        // Copy the engine into the slot UI state as well
                        updateSlot(index) {
                            it.copy(
                                isLoading = false,
                                hasError = false,
                                errorMessage = null,
                                playerEngine = localEngine
                            )
                        }

                        // Re-apply audio to make sure the focused one is audible
                        applyFocusAudio(_uiState.value.focusedSlotIndex)
                    } catch (e: Exception) {
                        val isCancellation = e is kotlinx.coroutines.CancellationException
                        if (!isCancellation) {
                            slotErrorJobs.remove(index)?.cancel()
                            if (initVersion == slotInitVersion) {
                                updateSlot(index) {
                                    it.copy(
                                        isLoading = false,
                                        hasError = true,
                                        errorMessage = e.message,
                                        playerEngine = null
                                    )
                                }
                            }
                        }
                        if (isCancellation) throw e
                    } finally {
                        if (!enginePublished) {
                            runCatching { localEngine?.stop() }
                            runCatching { localEngine?.release() }
                        }
                        slotStartupJobs.remove(index)
                    }
                }
                slotStartupJobs[index] = startupJob
            }
        }

        applyFocusAudio(0)
        showSelectionBorderTemporarily()
        startTelemetryMonitoring()
    }

    fun setFocus(slotIndex: Int) {
        if (_uiState.value.focusedSlotIndex != slotIndex) {
            _uiState.value = _uiState.value.copy(focusedSlotIndex = slotIndex)
            applyFocusAudio(slotIndex)
            showSelectionBorderTemporarily()
        }
    }

    private fun showSelectionBorderTemporarily() {
        borderHideJob?.cancel()
        _uiState.value = _uiState.value.copy(showSelectionBorder = true)
        borderHideJob = viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.value = _uiState.value.copy(showSelectionBorder = false)
        }
    }

    private fun applyFocusAudio(focusedIndex: Int) {
        val audibleIndex = pinnedAudioSlotIndex
            ?.takeIf { playerEngines.containsKey(it) }
            ?: focusedIndex
        playerEngines.forEach { (index, engine) ->
            if (index == audibleIndex) engine.setVolume(1f) else engine.setVolume(0f)
        }
    }

    /** Assign a channel to a specific slot (0–3). Called from dialog or AddToGroupDialog. */
    fun assignChannelToSlot(slotIndex: Int, channel: Channel) {
        multiViewManager.setChannel(slotIndex, channel)
        normalizeCenteredTwoSlotLayoutIfNeeded()
    }

    /** Clear a specific slot */
    fun clearSlot(slotIndex: Int) {
        slotStartupJobs.remove(slotIndex)?.cancel()
        slotErrorJobs.remove(slotIndex)?.cancel()
        slotGenerations[slotIndex] = (slotGenerations.getOrDefault(slotIndex, 0L) + 1L)
        if (playbackSessionActive) {
            playerEngines.remove(slotIndex)?.let { engine ->
                runCatching { engine.stop() }
                runCatching { engine.release() }
            }
            updateSlot(slotIndex) { it.copy(isLoading = false, playerEngine = null) }
        }
        multiViewManager.clearSlot(slotIndex)
        if (pinnedAudioSlotIndex == slotIndex) {
            pinnedAudioSlotIndex = null
        }
        _uiState.value = _uiState.value.copy(pinnedAudioSlotIndex = pinnedAudioSlotIndex)
    }

    /** Clear all slots */
    fun clearAll() {
        cancelSlotStartupJobs()
        multiViewManager.clearAll()
        pinnedAudioSlotIndex = null
        _uiState.value = _uiState.value.copy(pinnedAudioSlotIndex = null)
    }

    fun replaceFocusedSlot(channel: Channel) {
        val slotIndex = _uiState.value.focusedSlotIndex
        multiViewManager.setChannel(slotIndex, channel)
        restartPlaybackIfActive()
    }

    fun removeFocusedSlot() {
        clearSlot(_uiState.value.focusedSlotIndex)
        restartPlaybackIfActive()
    }

    fun pinAudioToFocusedSlot() {
        val focusedIndex = _uiState.value.focusedSlotIndex
        if (!playerEngines.containsKey(focusedIndex)) return  // Cannot pin audio to a slot without an active engine
        pinnedAudioSlotIndex = focusedIndex
        _uiState.value = _uiState.value.copy(
            pinnedAudioSlotIndex = pinnedAudioSlotIndex,
            slots = _uiState.value.slots.mapIndexed { index, slot -> slot.copy(isAudioPinned = index == pinnedAudioSlotIndex) }
        )
        applyFocusAudio(focusedIndex)
    }

    fun clearPinnedAudio() {
        pinnedAudioSlotIndex = null
        _uiState.value = _uiState.value.copy(
            pinnedAudioSlotIndex = null,
            slots = _uiState.value.slots.map { it.copy(isAudioPinned = false) }
        )
        applyFocusAudio(_uiState.value.focusedSlotIndex)
    }

    fun saveCurrentAsPreset(presetIndex: Int) {
        viewModelScope.launch {
            // Save as a fixed-size list (one entry per slot); 0L marks an empty slot so positions are preserved.
            val channelIds = multiViewManager.slots.value.map { it?.id ?: 0L }
            preferencesRepository.setMultiViewPreset(presetIndex, channelIds)
        }
    }

    fun setPerformanceMode(mode: MultiViewPerformanceMode) {
        viewModelScope.launch {
            preferencesRepository.setMultiViewPerformanceMode(mode.name)
            restartPlaybackIfActive()
        }
    }

    fun loadPreset(presetIndex: Int) {
        viewModelScope.launch {
            val channelIds = preferencesRepository.getMultiViewPreset(presetIndex).first()
            if (channelIds.isEmpty() || channelIds.all { it <= 0L }) return@launch
            val validIds = channelIds.filter { it > 0L }
            val channels = channelRepository.getChannelsByIds(validIds).first()
                .associateByAnyRawId()
            // Rebuild a fixed-size plan preserving slot positions; 0L entries become empty slots.
            val plan: List<com.streamvault.domain.model.Channel?> = List(MultiViewManager.MAX_SLOTS) { index ->
                val id = channelIds.getOrNull(index) ?: 0L
                if (id > 0L) channels[id] else null
            }
            multiViewManager.setSlots(plan)
            restartPlaybackIfActive()
        }
    }

    fun isQueued(channelId: Long): Boolean = multiViewManager.isQueued(channelId)

    fun normalizeCenteredTwoSlotLayoutIfNeeded() {
        if (!_uiState.value.centerTwoSlotLayout) return
        val current = multiViewManager.slots.value
        val normalized = buildCenteredTwoSlotPlan(current)
        if (normalized == current) return
        multiViewManager.setSlots(normalized)
        if (pinnedAudioSlotIndex != null && normalized.getOrNull(pinnedAudioSlotIndex ?: -1) == null) {
            pinnedAudioSlotIndex = null
            _uiState.value = _uiState.value.copy(pinnedAudioSlotIndex = null)
        }
    }

    private fun updateSlot(index: Int, transform: (MultiViewSlot) -> MultiViewSlot) {
        val updated = _uiState.value.slots.toMutableList()
        if (index < updated.size) {
            updated[index] = transform(updated[index])
            _uiState.value = _uiState.value.copy(slots = updated)
        }
    }

    private fun restartPlaybackIfActive() {
        if (playbackSessionActive) {
            initSlots()
        }
    }

    private fun buildCenteredTwoSlotPlan(slots: List<Channel?>): List<Channel?> {
        val firstTwoPopulated = slots.filterNotNull().take(2)
        return List(MultiViewManager.MAX_SLOTS) { index ->
            firstTwoPopulated.getOrNull(index)
        }
    }

    private fun releaseActivePlayers() {
        cancelSlotStartupJobs()
        cancelSlotErrorJobs()
        playerEngines.values.forEach { engine ->
            runCatching { engine.stop() }
            runCatching { engine.setVolume(0f) }
            runCatching { engine.release() }
        }
        playerEngines.clear()
        _uiState.value = _uiState.value.copy(
            slots = _uiState.value.slots.map { slot ->
                if (slot.isEmpty) slot else slot.copy(isLoading = false, playerEngine = null)
            }
        )
    }

    fun openReplacementPicker() {
        val providerId = currentProviderId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                pickerState = MultiViewPickerState(isLoading = true)
            )
            val categories = channelRepository.getCategoriesSnapshot(providerId)
            _uiState.value = _uiState.value.copy(
                pickerState = MultiViewPickerState(categories = categories)
            )
        }
    }

    fun selectPickerCategory(category: Category) {
        val providerId = currentProviderId ?: return
        parentalControlManager.retainUnlockedCategory(
            providerId = providerId,
            categoryId = category.id.takeIf { !category.isVirtual && it > 0L }
        )
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                pickerState = _uiState.value.pickerState.copy(
                    selectedCategory = category,
                    isLoading = true,
                    searchQuery = "",
                    channels = emptyList(),
                    filteredChannels = emptyList()
                )
            )
            val channels = if (category.id == com.streamvault.domain.repository.ChannelRepository.ALL_CHANNELS_ID) {
                channelRepository.getChannels(providerId).first()
            } else {
                channelRepository.getChannelsByCategory(providerId, category.id).first()
            }
            _uiState.value = _uiState.value.copy(
                pickerState = _uiState.value.pickerState.copy(
                    channels = channels,
                    filteredChannels = channels,
                    isLoading = false
                )
            )
        }
    }

    fun updatePickerSearch(query: String) {
        val state = _uiState.value.pickerState
        val filtered = if (query.isBlank()) state.channels
            else state.channels.filter { it.name.contains(query, ignoreCase = true) }
        _uiState.value = _uiState.value.copy(
            pickerState = state.copy(searchQuery = query, filteredChannels = filtered)
        )
    }

    fun backToPickerCategories() {
        currentProviderId?.let { parentalControlManager.clearUnlockedCategories(it) }
        _uiState.value = _uiState.value.copy(
            pickerState = _uiState.value.pickerState.copy(
                selectedCategory = null,
                channels = emptyList(),
                filteredChannels = emptyList(),
                searchQuery = ""
            )
        )
    }

    fun resetPicker() {
        currentProviderId?.let { parentalControlManager.clearUnlockedCategories(it) }
        _uiState.value = _uiState.value.copy(pickerState = MultiViewPickerState())
    }

    private fun observeReplacementCandidates() {
        viewModelScope.launch {
            preferencesRepository.lastActiveProviderId.collectLatest { providerId ->
                if (providerId == null || providerId <= 0L) {
                    currentProviderId = null
                    _uiState.value = _uiState.value.copy(replacementCandidates = emptyList())
                    return@collectLatest
                }
                currentProviderId = providerId
                combine(
                    favoriteRepository.getFavorites(providerId, com.streamvault.domain.model.ContentType.LIVE),
                    playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, limit = 12)
                ) { favorites, history ->
                    val favoriteIds = favorites.map { it.contentId }
                    val historyIds = history.map { it.contentId }
                    (favoriteIds + historyIds)
                        .distinct()
                        .take(16)
                }.collect { candidateIds ->
                    if (candidateIds.isEmpty()) {
                        _uiState.value = _uiState.value.copy(replacementCandidates = emptyList())
                    } else {
                        val channels = channelRepository.getChannelsByIds(candidateIds).first()
                        val currentSlotIds = multiViewManager.slots.value.mapNotNull { it?.id }.toSet()
                        _uiState.value = _uiState.value.copy(
                            replacementCandidates = channels.filterNot { it.id in currentSlotIds }
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        telemetryJob?.cancel()
        borderHideJob?.cancel()
        unregisterThermalListener()
        releasePlayersForBackground()
    }

    private fun cancelSlotStartupJobs() {
        slotStartupJobs.values.forEach { job -> job.cancel() }
        slotStartupJobs.clear()
    }

    private fun cancelSlotErrorJobs() {
        slotErrorJobs.values.forEach { job -> job.cancel() }
        slotErrorJobs.clear()
    }

    private fun observeSlotErrors(index: Int, engine: PlayerEngine, initVersion: Long) {
        slotErrorJobs.remove(index)?.cancel()
        slotErrorJobs[index] = viewModelScope.launch {
            engine.error.collect { error ->
                if (initVersion != slotInitVersion) return@collect
                if (error != null) {
                    updateSlot(index) { current ->
                        current.copy(
                            isLoading = false,
                            hasError = true,
                            errorMessage = error.message,
                            playerEngine = engine
                        )
                    }
                }
            }
        }
    }

    private suspend fun effectiveAudioVideoOffsetForChannel(channelId: Long): Int {
        if (!preferencesRepository.playerAudioVideoSyncEnabled.first()) return 0
        val globalOffset = preferencesRepository.playerAudioVideoOffsetMs.first()
        if (channelId <= 0L) return globalOffset
        return preferencesRepository.observeAudioVideoOffsetForChannel(channelId).first() ?: globalOffset
    }

    private fun applyAudioVideoOffsetsToActiveEngines() {
        val slots = _uiState.value.slots
        playerEngines.forEach { (index, engine) ->
            val channelId = slots.getOrNull(index)?.channel?.id ?: return@forEach
            viewModelScope.launch {
                val enabled = preferencesRepository.playerAudioVideoSyncEnabled.first()
                engine.setAudioVideoSyncEnabled(enabled)
                engine.setAudioVideoOffsetMs(if (enabled) effectiveAudioVideoOffsetForChannel(channelId) else 0)
            }
        }
    }

    private fun detectDeviceTier(): DevicePerformanceTier {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClass = activityManager?.memoryClass ?: 256
        val isLowRam = activityManager?.isLowRamDevice ?: false
        return when {
            isLowRam || memoryClass <= 192 -> DevicePerformanceTier.LOW
            memoryClass <= 320 -> DevicePerformanceTier.MID
            else -> DevicePerformanceTier.HIGH
        }
    }

    private fun resolvePolicy(
        mode: MultiViewPerformanceMode,
        tier: DevicePerformanceTier
    ): MultiViewPerformancePolicyUiModel {
        val (maxSlots, startupDelayMs) = when (mode) {
            MultiViewPerformanceMode.AUTO -> when (tier) {
                DevicePerformanceTier.LOW -> 2 to 550L
                DevicePerformanceTier.MID -> 4 to 350L
                DevicePerformanceTier.HIGH -> 4 to 220L
            }
            MultiViewPerformanceMode.CONSERVATIVE -> when (tier) {
                DevicePerformanceTier.LOW -> 1 to 700L
                DevicePerformanceTier.MID -> 2 to 500L
                DevicePerformanceTier.HIGH -> 3 to 320L
            }
            MultiViewPerformanceMode.BALANCED -> when (tier) {
                DevicePerformanceTier.LOW -> 2 to 550L
                DevicePerformanceTier.MID -> 3 to 350L
                DevicePerformanceTier.HIGH -> 4 to 240L
            }
            MultiViewPerformanceMode.MAXIMUM -> when (tier) {
                DevicePerformanceTier.LOW -> 2 to 450L
                DevicePerformanceTier.MID -> 4 to 260L
                DevicePerformanceTier.HIGH -> 4 to 160L
            }
        }
        return MultiViewPerformancePolicyUiModel(
            tier = tier,
            mode = mode,
            maxActiveSlots = maxSlots,
            startupDelayMs = startupDelayMs,
            summary = "Runs up to $maxSlots active slot(s) on ${tier.name.lowercase()} tier with ${startupDelayMs}ms startup staggering."
        )
    }

    private fun startTelemetryMonitoring() {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                evaluateTelemetry()
                kotlinx.coroutines.delay(2_500)
            }
        }
    }

    private fun evaluateTelemetry() {
        val policy = _uiState.value.performancePolicy
        val engines = playerEngines.toMap()
        val lowMemory = isLowMemoryDeviceState()
        var bufferingSlots = 0
        var errorSlots = 0
        var totalDroppedFrames = 0
        var droppedFramesDelta = 0

        engines.forEach { (index, engine) ->
            when (engine.playbackState.value) {
                com.streamvault.player.PlaybackState.BUFFERING -> bufferingSlots += 1
                com.streamvault.player.PlaybackState.ERROR -> errorSlots += 1
                else -> Unit
            }

            val stats = engine.playerStats.value
            totalDroppedFrames += stats.droppedFrames
            val previousDropped = lastDroppedFramesBySlot[index] ?: stats.droppedFrames
            droppedFramesDelta += (stats.droppedFrames - previousDropped).coerceAtLeast(0)
            lastDroppedFramesBySlot[index] = stats.droppedFrames
        }

        val thermalPenalty = when (thermalStatus) {
            MultiViewThermalStatus.MODERATE -> 2
            MultiViewThermalStatus.SEVERE -> 4
            MultiViewThermalStatus.CRITICAL -> 6
            else -> 0
        }
        val loadScore = (bufferingSlots * 3) +
            (errorSlots * 4) +
            when {
                droppedFramesDelta >= 24 -> 3
                droppedFramesDelta >= 10 -> 2
                droppedFramesDelta > 0 -> 1
                else -> 0
            } +
            if (lowMemory) 2 else 0 +
            thermalPenalty

        val underStress = thermalStatus == MultiViewThermalStatus.SEVERE ||
            thermalStatus == MultiViewThermalStatus.CRITICAL ||
            loadScore >= 8 ||
            (bufferingSlots >= 2 && droppedFramesDelta >= 10) ||
            errorSlots >= 2
        val stable = loadScore <= 2 &&
            !lowMemory &&
            (thermalStatus == MultiViewThermalStatus.NORMAL ||
                thermalStatus == MultiViewThermalStatus.LIGHT ||
                thermalStatus == MultiViewThermalStatus.UNKNOWN)

        when {
            underStress -> {
                sustainedStressSamples += 1
                stableSamples = 0
            }
            stable -> {
                stableSamples += 1
                sustainedStressSamples = 0
            }
            else -> {
                sustainedStressSamples = 0
                stableSamples = 0
            }
        }

        val activeSlots = _uiState.value.slots.count { !it.isEmpty && it.performanceBlockedReason == null }
        val standbySlots = _uiState.value.slots.count { !it.isEmpty && it.performanceBlockedReason != null }
        var throttledReason = _uiState.value.telemetry.throttledReason

        if (shouldThrottleDown()) {
            if (runtimeActiveSlotLimit > 1) {
                runtimeActiveSlotLimit -= 1
                throttledReason = if (thermalStatus == MultiViewThermalStatus.SEVERE || thermalStatus == MultiViewThermalStatus.CRITICAL) {
                    "Thermal pressure forced Split Screen to hold extra slots in standby."
                } else {
                    "Sustained load forced Split Screen to reduce active decoders on this device."
                }
                lastPolicyAdjustmentAt = System.currentTimeMillis()
                _uiState.value = _uiState.value.copy(
                    telemetry = _uiState.value.telemetry.copy(throttledReason = throttledReason)
                )
                restartPlaybackIfActive()
                return
            }
        } else if (shouldRecover(policy)) {
            runtimeActiveSlotLimit = (runtimeActiveSlotLimit + 1).coerceAtMost(policy.maxActiveSlots)
            throttledReason = null
            lastPolicyAdjustmentAt = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(
                telemetry = _uiState.value.telemetry.copy(throttledReason = null)
            )
            restartPlaybackIfActive()
            return
        }

        _uiState.value = _uiState.value.copy(
            telemetry = _uiState.value.telemetry.copy(
                activeSlotLimit = runtimeActiveSlotLimit,
                activeSlots = activeSlots,
                standbySlots = standbySlots,
                bufferingSlots = bufferingSlots,
                errorSlots = errorSlots,
                droppedFramesDelta = droppedFramesDelta,
                totalDroppedFrames = totalDroppedFrames,
                sustainedLoadScore = loadScore,
                thermalStatus = thermalStatus,
                isLowMemory = lowMemory,
                throttledReason = throttledReason,
                recommendation = buildTelemetryRecommendation(
                    activeSlotLimit = runtimeActiveSlotLimit,
                    thermalStatus = thermalStatus,
                    isLowMemory = lowMemory,
                    sustainedLoadScore = loadScore,
                    policy = policy
                )
            )
        )
    }

    private fun shouldThrottleDown(): Boolean {
        val coolingDown = System.currentTimeMillis() - lastPolicyAdjustmentAt < 8_000
        if (coolingDown) return false
        if (thermalStatus == MultiViewThermalStatus.SEVERE || thermalStatus == MultiViewThermalStatus.CRITICAL) {
            return true
        }
        val activeSlots = _uiState.value.slots.count { !it.isEmpty && it.performanceBlockedReason == null }
        val loadScore = _uiState.value.telemetry.sustainedLoadScore
        return activeSlots > 2 && sustainedStressSamples >= 3 && loadScore >= 8
    }

    private fun shouldRecover(policy: MultiViewPerformancePolicyUiModel): Boolean {
        val coolingDown = System.currentTimeMillis() - lastPolicyAdjustmentAt < 6_000
        if (coolingDown) return false
        return stableSamples >= 3 &&
            runtimeActiveSlotLimit < policy.maxActiveSlots
    }

    private fun isLowMemoryDeviceState(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory || memoryInfo.availMem < 220L * 1024L * 1024L
    }

    private fun readThermalStatus(): MultiViewThermalStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return MultiViewThermalStatus.UNKNOWN
        val status = powerManager?.currentThermalStatus ?: return MultiViewThermalStatus.UNKNOWN
        return mapThermalStatus(status)
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || thermalListener != null) return
        val manager = powerManager ?: return
        thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            val newStatus = mapThermalStatus(status)
            val wasBelow = thermalStatus != MultiViewThermalStatus.SEVERE &&
                thermalStatus != MultiViewThermalStatus.CRITICAL
            thermalStatus = newStatus
            if ((newStatus == MultiViewThermalStatus.SEVERE || newStatus == MultiViewThermalStatus.CRITICAL) && wasBelow) {
                // Immediately cap admission and update UI without waiting for next telemetry tick
                runtimeActiveSlotLimit = (runtimeActiveSlotLimit - 1).coerceAtLeast(1)
                lastPolicyAdjustmentAt = System.currentTimeMillis()
                _uiState.value = _uiState.value.copy(
                    telemetry = _uiState.value.telemetry.copy(
                        thermalStatus = newStatus,
                        throttledReason = "Thermal pressure forced Split Screen to hold extra slots in standby."
                    )
                )
                restartPlaybackIfActive()
            } else {
                _uiState.value = _uiState.value.copy(
                    telemetry = _uiState.value.telemetry.copy(thermalStatus = newStatus)
                )
            }
        }
        manager.addThermalStatusListener(context.mainExecutor, thermalListener!!)
    }

    private fun unregisterThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val listener = thermalListener ?: return
        powerManager?.removeThermalStatusListener(listener)
        thermalListener = null
    }

    private fun mapThermalStatus(status: Int): MultiViewThermalStatus {
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> MultiViewThermalStatus.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> MultiViewThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> MultiViewThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> MultiViewThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> MultiViewThermalStatus.CRITICAL
            else -> MultiViewThermalStatus.UNKNOWN
        }
    }

    private fun buildTelemetryRecommendation(
        activeSlotLimit: Int,
        thermalStatus: MultiViewThermalStatus,
        isLowMemory: Boolean,
        sustainedLoadScore: Int,
        policy: MultiViewPerformancePolicyUiModel
    ): String {
        return when {
            thermalStatus == MultiViewThermalStatus.SEVERE || thermalStatus == MultiViewThermalStatus.CRITICAL ->
                "Device is under thermal pressure. Keep Split Screen in ${policy.mode.name.lowercase()} mode and limit active playback to $activeSlotLimit slot(s)."
            isLowMemory ->
                "Available memory is tight. Extra slots stay in standby until playback stabilizes."
            sustainedLoadScore >= 8 ->
                "Sustained decode load is high. Standby slots will protect lower-end Android TV devices from stutter."
            activeSlotLimit < policy.maxActiveSlots ->
                "Playback is stabilizing. Split Screen will restore more active slots when dropped frames and buffering settle down."
            else ->
                "Runtime telemetry is healthy. This device can keep up with the current Split Screen load."
        }
    }
}
