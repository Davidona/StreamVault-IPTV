package com.streamvault.feature.live.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.*
import com.streamvault.domain.policy.*
import com.streamvault.domain.playback.orderedByRequestedRawIds
import com.streamvault.domain.repository.*
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.usecase.UnlockParentalCategory
import com.streamvault.domain.usecase.UnlockParentalCategoryCommand
import com.streamvault.domain.util.AdultContentVisibilityPolicy
import com.streamvault.feature.live.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ProviderChannelOrderUiState(
    val category: Category? = null,
    val channels: List<Channel> = emptyList(),
    val draft: LiveOrderDraft? = null,
    val loading: Boolean = true,
    val locked: Boolean = false,
    val saving: Boolean = false,
    val closed: Boolean = false,
    val mode: LiveChannelOrderMode = LiveChannelOrderMode.USE_DEFAULT,
    val hasManualOrder: Boolean = false,
    val defaultMode: LiveChannelOrderMode = LiveChannelOrderMode.PROVIDER,
    val canUndoReset: Boolean = false,
    val canRedoReset: Boolean = false,
    val message: Int? = null
)

/** Isolated management session: opening Settings must not switch the active playback provider. */
@HiltViewModel
class ProviderChannelOrderViewModel @Inject constructor(
    private val providers: ProviderRepository,
    private val categories: CategoryRepository,
    private val channels: ChannelRepository,
    private val favorites: FavoriteRepository,
    private val orders: LiveChannelOrderRepository,
    private val preferences: SettingsPreferences,
    private val parental: ParentalControlManager,
    private val unlock: UnlockParentalCategory,
    private val usage: LiveWatchUsageRepository? = null
) : ViewModel() {
    private val state = MutableStateFlow(ProviderChannelOrderUiState())
    val uiState = state.asStateFlow()
    private var job: Job? = null
    private var providerId = 0L
    private var requestedCategory: Category? = null
    private var scope: LiveChannelOrderScope? = null
    private var saved = LiveChannelOrder()
    private var baseKeys = emptyList<String>()
    private var original = emptyList<Channel>()
    private var pickup: LiveOrderDraft? = null
    private var pickupMode: LiveChannelOrderMode? = null
    private var pendingMode: LiveChannelOrderMode? = null
    private var providerRows = emptyList<Channel>()
    private var resetManualKeys: List<String>? = null
    private var undoneResetManualKeys: List<String>? = null
    private var usageSnapshot = emptyMap<String, LiveWatchUsage>()

    fun open(providerId: Long, category: Category) {
        this.providerId = providerId
        requestedCategory = category
        load()
    }

    private suspend fun currentCategory(): Category = when {
        requestedCategory?.id == ChannelRepository.ALL_CHANNELS_ID || requestedCategory?.id == VirtualCategoryIds.FAVORITES ->
            checkNotNull(requestedCategory)
        requestedCategory?.isVirtual == true -> {
            val groupId = -checkNotNull(requestedCategory).id
            checkNotNull(favorites.getGroups(providerId, ContentType.LIVE).first().singleOrNull { it.id == groupId })
            checkNotNull(requestedCategory)
        }
        else -> checkNotNull(categories.getCategories(providerId).first().firstOrNull {
            it.id == requestedCategory?.id && it.type == ContentType.LIVE && !it.isVirtual
        })
    }

    private suspend fun locked(category: Category): Boolean =
        (category.isAdult || category.isUserProtected) && preferences.parentalControlLevel.first() in 1..2 &&
            kotlin.math.abs(category.id) !in parental.unlockedCategoriesForProvider(providerId).first()

    private fun load(pin: String? = null) {
        job?.cancel()
        state.value = ProviderChannelOrderUiState(category = requestedCategory)
        job = viewModelScope.launch {
            try {
                val category = currentCategory()
                if (pin != null && unlock(UnlockParentalCategoryCommand(providerId, category.id, pin)) !is Result.Success) {
                    state.value = ProviderChannelOrderUiState(category = category, loading = false, locked = true, message = R.string.home_incorrect_pin)
                    return@launch
                }
                if (locked(category)) {
                    state.value = ProviderChannelOrderUiState(category = category, loading = false, locked = true)
                    return@launch
                }
                val provider = checkNotNull(providers.getProvider(providerId))
                val virtual = category.isVirtual
                val group = if (virtual && category.id != VirtualCategoryIds.FAVORITES) {
                    favorites.getGroups(providerId, ContentType.LIVE).first().single { it.id == -category.id }
                } else null
                val orderScope = when {
                    category.id == VirtualCategoryIds.FAVORITES -> liveFavoritesOrderScope(liveProviderOrderIdentity(provider))
                    group != null -> liveCustomGroupOrderScope(provider, group)
                    else -> liveChannelOrderScope(provider, category)
                }
                val favoriteSnapshot = if (virtual) {
                    val groupId = group?.id
                    val source = if (groupId == null) favorites.getFavorites(providerId, ContentType.LIVE).first()
                        else favorites.getFavoritesByGroup(groupId).first()
                    source.filter { it.providerId == providerId && it.contentType == ContentType.LIVE && it.groupId == groupId }
                        .sortedWith(compareBy(Favorite::position, Favorite::id))
                } else null
                val complete = if (favoriteSnapshot != null) {
                    val ids = favoriteSnapshot.map { it.contentId }
                    if (ids.isEmpty()) emptyList() else channels.getChannelsByIds(ids).first()
                        .filter { it.providerId == providerId }
                        .orderedByRequestedRawIds(ids)
                } else channels.getChannelsByCategory(providerId, category.id).first()
                require(complete.all { it.liveOrderKeys().isNotEmpty() })
                var order = orders.observe(orderScope).first()
                if (favoriteSnapshot != null && complete.isNotEmpty()) {
                    val completeKeys = complete.flatMap { it.liveOrderKeys() }.distinct()
                    order = if (orders.hasSavedOrder(orderScope)) {
                        order.reconcileVisibleMembership(favoriteSnapshot.map { it.contentId }, complete, completeKeys).let { reconciled ->
                            if (reconciled.manualKeys == order.manualKeys) order else when (
                                val saved = orders.save(orderScope, order.revision, reconciled)
                            ) {
                                is LiveChannelOrderSaveResult.Saved -> saved.order
                                is LiveChannelOrderSaveResult.Conflict -> saved.current
                            }
                        }
                    } else {
                        orders.initializeIfAbsent(
                            orderScope,
                            LiveChannelOrder(LiveChannelOrderMode.MANUAL, completeKeys)
                        )
                    }
                }
                val defaultMode = preferences.defaultLiveChannelOrder.first()
                usageSnapshot = if (usage != null && !preferences.isIncognitoMode.first()) {
                    usage.observe(providerId).first().toMap()
                } else emptyMap()
                val ordered = orderLiveChannels(complete, order, defaultMode, usageSnapshot)
                val visible = if (category.id == ChannelRepository.ALL_CHANNELS_ID) {
                    AdultContentVisibilityPolicy.filterForAggregatedSurface(ordered, preferences.parentalControlLevel.first()) { isAdult || isUserProtected }
                } else ordered
                scope = orderScope
                saved = order
                baseKeys = (order.manualKeys + ordered.flatMap { it.liveOrderKeys() }).distinct()
                original = visible
                val visibleIds = visible.map { it.id }.toHashSet()
                providerRows = complete.filter { it.id in visibleIds }
                resetManualKeys = null
                undoneResetManualKeys = null
                pendingMode = null
                state.value = ProviderChannelOrderUiState(category = category, channels = visible,
                    draft = LiveOrderDraft.start(visible.map(::rowKey)), loading = false, mode = order.mode, defaultMode = defaultMode, hasManualOrder = order.manualKeys.isNotEmpty())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                state.value = ProviderChannelOrderUiState(category = requestedCategory, loading = false, message = R.string.home_error_load_failed)
            }
        }
    }

    fun unlock(pin: String) = load(pin)
    fun beginMove() { if (!state.value.saving) { pickup = state.value.draft; pickupMode = pendingMode } }
    fun finishMove() { pickup = null }
    fun cancelMove() { if (!state.value.saving) { pendingMode = pickupMode; pickup?.let(::publish); pickup = null } }
    fun undo() {
        if (state.value.saving) return
        val undoingReset = resetManualKeys != null
        if (!undoingReset) pendingMode = null
        resetManualKeys?.let { undoneResetManualKeys = it }
        resetManualKeys = null
        state.value.draft?.undo()?.let(::publish)
        state.update { it.copy(canUndoReset = false, canRedoReset = undoneResetManualKeys != null) }
    }

    fun redo() {
        if (state.value.saving) return
        val redoingReset = undoneResetManualKeys != null
        if (!redoingReset) pendingMode = null
        undoneResetManualKeys?.let { resetManualKeys = it }
        undoneResetManualKeys = null
        state.value.draft?.redo()?.let(::publish)
        state.update { it.copy(canUndoReset = resetManualKeys != null, canRedoReset = false) }
    }

    fun move(channel: Channel, position: Int) = moveMany(listOf(channel), position)

    fun moveMany(channels: List<Channel>, position: Int) {
        val current = state.value
        val draft = current.draft ?: return
        val keys = channels.map(::rowKey).toSet()
        if (current.saving || current.locked || keys.isEmpty() || !draft.keys.containsAll(keys) ||
            position !in 1..(draft.keys.size - keys.size + 1)) return
        val moved = draft.moveToPosition(keys, position)
        if (moved == draft) return
        pendingMode = null
        resetManualKeys = null
        undoneResetManualKeys = null
        publish(moved)
    }

    private fun publish(draft: LiveOrderDraft, previewMode: LiveChannelOrderMode? = null) {
        val byKey = original.associateBy(::rowKey)
        state.update { it.copy(draft = draft, channels = draft.keys.map(byKey::getValue),
            mode = previewMode ?: pendingMode ?: if (draft.hasChanges) LiveChannelOrderMode.MANUAL else saved.mode,
            canUndoReset = resetManualKeys != null, canRedoReset = undoneResetManualKeys != null) }
    }

    fun hasChanges(): Boolean = state.value.draft?.hasChanges == true ||
        (pendingMode != null && pendingMode != saved.mode) || resetManualKeys != null

    /** Preview a provider-order reset while preserving hidden/protected slots until Save. */
    fun resetManualArrangement() {
        val current = state.value
        val draft = current.draft ?: return
        if (current.saving || current.loading || current.locked || saved.manualKeys.isEmpty()) return
        val requestedKeys = mergeLiveOrderVisibleKeys(
            baseKeys,
            providerRows.flatMap { it.liveOrderKeys() }.distinct()
        )
        resetManualKeys = requestedKeys.takeIf { it != saved.manualKeys }
        undoneResetManualKeys = null
        val arranged = draft.arrange(providerRows.map(::rowKey))
        publish(arranged, previewMode = pendingMode ?: saved.mode)
        if (resetManualKeys == null && !draft.hasChanges) {
            state.update { it.copy(canUndoReset = false) }
        }
    }

    fun save(useDefault: Boolean = false) = persist(
        if (useDefault) LiveChannelOrderMode.USE_DEFAULT else pendingMode)

    /** Choosing a mode is explicit; an unchanged manual draft still creates no override. */
    fun selectMode(mode: LiveChannelOrderMode) {
        val current = state.value
        if (current.saving || current.loading || current.locked || current.draft == null) return
        if (mode != LiveChannelOrderMode.USE_DEFAULT && mode !in liveChannelPresetModes &&
            !(mode == LiveChannelOrderMode.MANUAL && saved.manualKeys.isNotEmpty())) return
        resetManualKeys = null
        undoneResetManualKeys = null
        pendingMode = mode
        val arranged = orderLiveChannels(providerRows, saved.copy(mode = mode), current.defaultMode, usageSnapshot)
        publish(LiveOrderDraft.start(original.map(::rowKey)).arrange(arranged.map(::rowKey)))
    }

    private fun persist(selectedMode: LiveChannelOrderMode?) {
        val current = state.value
        val orderScope = scope ?: return
        if (current.saving || current.draft == null || current.locked) return
        if (selectedMode == null && !current.draft.hasChanges && resetManualKeys == null) {
            state.update { it.copy(closed = true) }
            return
        }
        state.update { it.copy(saving = true, message = null) }
        job = viewModelScope.launch {
            try {
                if (locked(currentCategory())) {
                    state.value = ProviderChannelOrderUiState(category = current.category, loading = false, locked = true)
                    return@launch
                }
                val requested = when {
                    resetManualKeys != null -> saved.copy(
                        mode = selectedMode ?: saved.mode,
                        manualKeys = checkNotNull(resetManualKeys)
                    )
                    selectedMode != null -> saved.copy(mode = selectedMode)
                    else -> saved.copy(
                        mode = LiveChannelOrderMode.MANUAL,
                        manualKeys = mergeLiveOrderVisibleKeys(baseKeys, current.channels.flatMap { it.liveOrderKeys() }.distinct())
                    )
                }
                when (orders.save(orderScope, saved.revision, requested)) {
                    is LiveChannelOrderSaveResult.Saved -> state.update { it.copy(saving = false, closed = true) }
                    is LiveChannelOrderSaveResult.Conflict -> state.update { it.copy(saving = false, message = R.string.live_order_conflict) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(saving = false, message = R.string.live_order_save_failed) } }
        }
    }

    fun reset() {
        job?.cancel()
        scope = null
        original = emptyList()
        baseKeys = emptyList()
        pickup = null
        pendingMode = null
        providerRows = emptyList()
        resetManualKeys = null
        undoneResetManualKeys = null
        usageSnapshot = emptyMap()
        state.value = ProviderChannelOrderUiState()
    }

    private fun rowKey(channel: Channel) = "${channel.providerId}:${channel.id}"
}
