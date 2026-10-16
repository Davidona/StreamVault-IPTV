package com.streamvault.feature.stremio.api

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.streamvault.core.navigation.AppDestination

typealias StremioScaffoldContent = @Composable (
    currentDestination: AppDestination,
    title: String,
    subtitle: String?,
    compactHeader: Boolean,
    showScreenHeader: Boolean,
    topBarVisible: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) -> Unit

fun interface StremioDownloadStarter {
    fun startDownload(downloadId: String)
}
