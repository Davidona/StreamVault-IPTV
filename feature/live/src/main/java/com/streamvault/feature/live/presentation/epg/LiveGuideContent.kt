package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.player.PlayerEngine

enum class LiveGuideContentMode {
    INITIAL_LOADING,
    MESSAGE,
    GRID
}

fun liveGuideContentMode(
    isInitialLoading: Boolean,
    hasChannels: Boolean,
    hasMessage: Boolean
): LiveGuideContentMode = when {
    isInitialLoading && !hasChannels -> LiveGuideContentMode.INITIAL_LOADING
    hasMessage -> LiveGuideContentMode.MESSAGE
    else -> LiveGuideContentMode.GRID
}

data class LiveGuideContentMessage(
    val title: String,
    val subtitle: String?,
    val actionLabel: String?,
    val onAction: (() -> Unit)?
)

data class LiveGuideContentLabels(
    val loading: String,
    val preview: LiveGuidePreviewLabels,
    val toolbar: LiveGuideToolbarLabels,
    val grid: LiveGuideGridLabels
)

@Composable
fun LiveGuideContent(
    modifier: Modifier = Modifier,
    isInitialLoading: Boolean,
    contentMessage: LiveGuideContentMessage?,
    channels: List<Channel>,
    favoriteChannelIds: Set<Long>,
    programsByChannel: Map<String, List<Program>>,
    guideWindowStart: Long,
    guideWindowEnd: Long,
    density: GuideDensity,
    selectedCategoryName: String,
    isRefreshing: Boolean,
    previewPlayerEngine: PlayerEngine?,
    isPreviewLoading: Boolean,
    focusedChannel: Channel?,
    focusedProgram: Program?,
    labels: LiveGuideContentLabels,
    onOpenCategoryPicker: () -> Unit,
    onJumpToNow: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenOptions: () -> Unit,
    onGuideInteract: () -> Unit,
    onChannelClick: (Channel) -> Unit,
    onChannelLongClick: ((Channel, Program?) -> Unit)? = null,
    onProgramClick: (Channel, Program) -> Unit,
    onChannelFocused: (Channel, Program?, Boolean) -> Unit,
    onProgramFocused: (Channel, Program, Boolean) -> Unit,
    onRequestMoreChannels: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (liveGuideContentMode(isInitialLoading, channels.isNotEmpty(), contentMessage != null)) {
            LiveGuideContentMode.INITIAL_LOADING -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(labels.loading, color = OnBackground)
                }
            }

            LiveGuideContentMode.MESSAGE -> {
                val message = requireNotNull(contentMessage)
                LiveGuideMessageState(
                    modifier = Modifier.weight(1f),
                    title = message.title,
                    subtitle = message.subtitle,
                    actionLabel = message.actionLabel,
                    onAction = message.onAction
                )
            }

            LiveGuideContentMode.GRID -> {
                LiveGuideNowProvider {
                    LiveGuidePreviewPane(
                        previewPlayerEngine = previewPlayerEngine,
                        isPreviewLoading = isPreviewLoading,
                        focusedChannel = focusedChannel,
                        focusedProgram = focusedProgram,
                        labels = labels.preview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                }
                LiveGuideToolbarRow(
                    selectedCategoryName = selectedCategoryName,
                    labels = labels.toolbar,
                    onOpenCategoryPicker = onOpenCategoryPicker,
                    onJumpToNow = onJumpToNow,
                    onOpenSearch = onOpenSearch,
                    onOpenOptions = onOpenOptions,
                    onGuideInteract = onGuideInteract,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                )
                if (isRefreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .height(3.dp),
                        color = Primary,
                        trackColor = SurfaceHighlight
                    )
                }
                LiveGuideNowProvider {
                    LiveGuideGrid(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        channels = channels,
                        favoriteChannelIds = favoriteChannelIds,
                        programsByChannel = programsByChannel,
                        guideWindowStart = guideWindowStart,
                        guideWindowEnd = guideWindowEnd,
                        density = density,
                        labels = labels.grid,
                        onChannelClick = onChannelClick,
                        onChannelLongClick = onChannelLongClick,
                        onProgramClick = onProgramClick,
                        onChannelFocused = onChannelFocused,
                        onProgramFocused = onProgramFocused,
                        onRequestMoreChannels = onRequestMoreChannels
                    )
                }
            }
        }
    }
}
