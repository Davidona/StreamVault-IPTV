package com.streamvault.feature.catalog.api

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.streamvault.core.navigation.AppDestination
import com.streamvault.domain.model.AppHomeDashboardShelf

enum class CatalogNavigationChrome {
    Rail,
    TopBar,
}

typealias CatalogScaffoldContent = @Composable (
    currentDestination: AppDestination,
    title: String,
    subtitle: String?,
    navigationChrome: CatalogNavigationChrome,
    topBarVisible: Boolean,
    compactHeader: Boolean,
    showScreenHeader: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) -> Unit

typealias CatalogDashboardShelfCustomizationContent = @Composable (
    currentShelves: List<AppHomeDashboardShelf>,
    onDismiss: () -> Unit,
    onSave: (List<AppHomeDashboardShelf>) -> Unit,
) -> Unit
