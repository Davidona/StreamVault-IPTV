package com.streamvault.app.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.streamvault.feature.live.api.LiveMultiViewPlannerContent
import com.streamvault.feature.playback.multiview.MultiViewPlannerDialog
import com.streamvault.feature.playback.multiview.MultiViewViewModel

/** App composition adapter for the Playback-owned MultiView planner. */
internal data class AppLiveMultiViewPlannerAdapter(
    val content: LiveMultiViewPlannerContent,
    val isChannelQueued: (Long) -> Boolean,
)

@Composable
internal fun rememberAppLiveMultiViewPlannerContent(
    viewModel: MultiViewViewModel = hiltViewModel(),
): AppLiveMultiViewPlannerAdapter = remember(viewModel) {
    AppLiveMultiViewPlannerAdapter(
        content = { selectedChannel, onDismiss, onConfirmed ->
            MultiViewPlannerDialog(
                pendingChannel = selectedChannel,
                onDismiss = onDismiss,
                onLaunch = onConfirmed,
                viewModel = viewModel,
            )
        },
        isChannelQueued = viewModel::isQueued,
    )
}
