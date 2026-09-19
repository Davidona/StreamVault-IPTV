package com.streamvault.feature.system.api

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.streamvault.core.navigation.AppDestination

typealias SystemScaffoldContent = @Composable (
    currentDestination: AppDestination,
    title: String,
    subtitle: String?,
    compactHeader: Boolean,
    showScreenHeader: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) -> Unit
