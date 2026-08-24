package com.streamvault.feature.playback.multiview

import com.streamvault.domain.model.Channel
import com.streamvault.player.PlayerEngine

enum class DevicePerformanceTier {
    LOW,
    MID,
    HIGH
}

enum class MultiViewPerformanceMode {
    AUTO,
    CONSERVATIVE,
    BALANCED,
    MAXIMUM
}

enum class MultiViewThermalStatus {
    UNKNOWN,
    NORMAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL
}

data class MultiViewPerformancePolicyUiModel(
    val tier: DevicePerformanceTier = DevicePerformanceTier.MID,
    val mode: MultiViewPerformanceMode = MultiViewPerformanceMode.AUTO,
    val maxActiveSlots: Int = 3,
    val startupDelayMs: Long = 300L,
    val summary: String = ""
)

data class MultiViewTelemetryUiModel(
    val activeSlotLimit: Int = 3,
    val activeSlots: Int = 0,
    val standbySlots: Int = 0,
    val bufferingSlots: Int = 0,
    val errorSlots: Int = 0,
    val droppedFramesDelta: Int = 0,
    val totalDroppedFrames: Int = 0,
    val sustainedLoadScore: Int = 0,
    val thermalStatus: MultiViewThermalStatus = MultiViewThermalStatus.UNKNOWN,
    val isLowMemory: Boolean = false,
    val throttledReason: String? = null,
    val recommendation: String = ""
)

/**
 * Represents a single player slot in the 2x2 Multi-View grid.
 */
data class MultiViewSlot(
    val index: Int,
    val channel: Channel? = null,
    val streamUrl: String = "",
    val title: String = "",
    val playerEngine: PlayerEngine? = null,
    val isLoading: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String? = null,
    val isAudioPinned: Boolean = false,
    val performanceBlockedReason: String? = null
) {
    val isEmpty: Boolean get() = channel == null
}

data class MultiViewUiState(
    val slots: List<MultiViewSlot> = List(4) { MultiViewSlot(index = it) },
    val focusedSlotIndex: Int = 0,
    val isLaunching: Boolean = false,
    val showSelectionBorder: Boolean = true,
    val centerTwoSlotLayout: Boolean = false,
    val presets: List<MultiViewPresetUiModel> = emptyList(),
    val pinnedAudioSlotIndex: Int? = null,
    val replacementCandidates: List<Channel> = emptyList(),
    val pickerState: MultiViewPickerState = MultiViewPickerState(),
    val performancePolicy: MultiViewPerformancePolicyUiModel = MultiViewPerformancePolicyUiModel(),
    val telemetry: MultiViewTelemetryUiModel = MultiViewTelemetryUiModel(),
    val parentalControlLevel: Int = 0
)

data class MultiViewPresetUiModel(
    val index: Int,
    val label: String,
    val isPopulated: Boolean,
    val channelCount: Int
)

data class MultiViewPickerState(
    val categories: List<com.streamvault.domain.model.Category> = emptyList(),
    val selectedCategory: com.streamvault.domain.model.Category? = null,
    val channels: List<Channel> = emptyList(),
    val filteredChannels: List<Channel> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false
)
