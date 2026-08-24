package com.streamvault.feature.playback.multiview

import com.streamvault.domain.model.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that holds the 4 fixed slots for the Multi-View (Split Screen) feature.
 * Each slot can hold exactly one channel. Slots persist across navigation.
 */
@Singleton
class MultiViewManager @Inject constructor() {

    // Null = empty slot, non-null = channel in that slot
    private val _slots = MutableStateFlow<List<Channel?>>(List(MAX_SLOTS) { null })
    val slots: StateFlow<List<Channel?>> = _slots.asStateFlow()

    val hasAnyChannel: Boolean get() = _slots.value.any { it != null }

    /** Place a channel in a specific slot index (0–3). Replaces whatever was there. */
    fun setChannel(slotIndex: Int, channel: Channel) {
        if (slotIndex !in 0 until MAX_SLOTS) return
        _slots.update { current ->
            current.toMutableList().also { slots ->
                slots.indices.forEach { index ->
                    if (index != slotIndex && slots[index]?.id == channel.id) {
                        slots[index] = null
                    }
                }
                slots[slotIndex] = channel
            }
        }
    }

    /** Clear a specific slot. */
    fun clearSlot(slotIndex: Int) {
        if (slotIndex !in 0 until MAX_SLOTS) return
        _slots.update { current -> current.toMutableList().also { it[slotIndex] = null } }
    }

    /** Clear all slots. */
    fun clearAll() {
        _slots.update { List(MAX_SLOTS) { null } }
    }

    /** Atomically replace all slot assignments. `plan` must have exactly MAX_SLOTS entries. */
    fun setSlots(plan: List<Channel?>) {
        require(plan.size == MAX_SLOTS)
        _slots.update { plan.toList() }
    }

    /** Returns true if the given channel is in any slot. */
    fun isQueued(channelId: Long): Boolean = _slots.value.any { it?.id == channelId }

    companion object {
        const val MAX_SLOTS = 4
    }
}
