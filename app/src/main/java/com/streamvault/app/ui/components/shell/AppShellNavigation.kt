package com.streamvault.app.ui.components.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.streamvault.app.R
import com.streamvault.app.navigation.Routes
import com.streamvault.core.ui.components.shell.CoreAppScreenScaffold
import com.streamvault.core.ui.components.shell.NavigationChrome
import com.streamvault.core.ui.components.shell.UiDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.CatalogLayout

enum class AppNavigationChrome {
    Rail,
    TopBar
}

internal val LocalAppDestinationItems = staticCompositionLocalOf<List<UiDestination>?> { null }

@Composable
fun AppScreenScaffold(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationChrome: AppNavigationChrome = AppNavigationChrome.Rail,
    topBarVisible: Boolean = true,
    compactHeader: Boolean = false,
    showScreenHeader: Boolean = true,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    topBarActions: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable ColumnScope.() -> Unit
) {
    val navigationDestinations = LocalAppDestinationItems.current
        ?: rememberAppDestinationItems(
            configuredDestinations = AppTopLevelDestination.defaultOrder,
            catalogLayout = CatalogLayout.SPLIT
        )

    CoreAppScreenScaffold(
        currentDestinationId = currentRoute,
        destinations = navigationDestinations,
        onDestinationSelected = onNavigate,
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        navigationChrome = when (navigationChrome) {
            AppNavigationChrome.Rail -> NavigationChrome.Rail
            AppNavigationChrome.TopBar -> NavigationChrome.TopBar
        },
        topBarVisible = topBarVisible,
        compactHeader = compactHeader,
        showScreenHeader = showScreenHeader,
        header = header,
        topBarActions = topBarActions,
        contentPadding = contentPadding,
        content = content
    )
}

internal data class AppDestinationItem(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector
)

internal fun buildDestinationItems(
    configured: List<AppTopLevelDestination>,
    layout: CatalogLayout
): List<AppDestinationItem> {
    if (layout == CatalogLayout.SPLIT) return configured.map { it.toDestinationItem() }

    var insertedVod = false
    return buildList {
        configured.forEach { destination ->
            when (destination) {
                AppTopLevelDestination.MOVIES,
                AppTopLevelDestination.SERIES -> if (!insertedVod) {
                    add(AppDestinationItem(Routes.VOD, R.string.nav_vod, Icons.Default.Star))
                    insertedVod = true
                }
                else -> add(destination.toDestinationItem())
            }
        }
    }
}

@Composable
internal fun rememberAppDestinationItems(
    configuredDestinations: List<AppTopLevelDestination>,
    catalogLayout: CatalogLayout
): List<UiDestination> {
    val destinationItems = remember(configuredDestinations, catalogLayout) {
        buildDestinationItems(configuredDestinations, catalogLayout)
    }
    return destinationItems.map { item ->
        UiDestination(
            id = item.route,
            label = stringResource(item.labelRes),
            icon = item.icon
        )
    }
}

private fun AppTopLevelDestination.toDestinationItem(): AppDestinationItem = when (this) {
    AppTopLevelDestination.HOME -> AppDestinationItem(Routes.HOME, R.string.nav_home, Icons.Default.Home)
    AppTopLevelDestination.LIVE_TV -> AppDestinationItem(Routes.LIVE_TV, R.string.nav_live_tv, Icons.Default.PlayArrow)
    AppTopLevelDestination.MOVIES -> AppDestinationItem(Routes.MOVIES, R.string.nav_movies, Icons.Default.Star)
    AppTopLevelDestination.SERIES -> AppDestinationItem(Routes.SERIES, R.string.nav_series, Icons.Default.Menu)
    AppTopLevelDestination.DOWNLOADS -> AppDestinationItem(Routes.DOWNLOADS, R.string.nav_downloads, Icons.Default.Download)
    AppTopLevelDestination.GUIDE -> AppDestinationItem(Routes.EPG, R.string.nav_epg, Icons.Default.Info)
    AppTopLevelDestination.SEARCH -> AppDestinationItem(Routes.SEARCH, R.string.search_title, Icons.Default.Search)
    AppTopLevelDestination.PLUGINS -> AppDestinationItem(Routes.PLUGINS, R.string.nav_plugins, PluginBlocksIcon)
    AppTopLevelDestination.SETTINGS -> AppDestinationItem(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings)
}

private val PluginBlocksIcon: ImageVector
    get() {
        if (_pluginBlocksIcon != null) return _pluginBlocksIcon!!
        _pluginBlocksIcon = ImageVector.Builder(
            name = "PluginBlocks",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 4f)
                horizontalLineTo(10f)
                verticalLineTo(11f)
                horizontalLineTo(3f)
                close()
                moveTo(14f, 4f)
                horizontalLineTo(21f)
                verticalLineTo(11f)
                horizontalLineTo(14f)
                close()
                moveTo(8.5f, 13f)
                horizontalLineTo(15.5f)
                verticalLineTo(20f)
                horizontalLineTo(8.5f)
                close()
            }
        }.build()
        return _pluginBlocksIcon!!
    }

private var _pluginBlocksIcon: ImageVector? = null
