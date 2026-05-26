package com.streamvault.app.ui.components.shell

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppMotion
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.interaction.rememberTvInteractionSounds
import com.streamvault.app.ui.interaction.TvIconButton
import com.streamvault.app.ui.design.LocalAppShapes
import com.streamvault.app.ui.design.LocalAppSpacing

enum class AppNavigationChrome {
    Rail,
    TopBar
}

@Composable
fun AppScreenScaffold(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    navigationChrome: AppNavigationChrome = AppNavigationChrome.Rail,
    topBarVisible: Boolean = true,
    compactHeader: Boolean = false,
    showScreenHeader: Boolean = true,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    topBarActions: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable ColumnScope.() -> Unit
) {
    val spacing = LocalAppSpacing.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        AppColors.Canvas,
                        AppColors.CanvasElevated,
                        AppColors.Surface
                    )
                )
            )
    ) {
        if (navigationChrome == AppNavigationChrome.Rail) {
            Row(modifier = Modifier.fillMaxSize()) {
                DestinationRail(
                    currentRoute = currentRoute,
                    onNavigate = onNavigate,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(spacing.railWidth)
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = spacing.lg,
                            end = spacing.screenGutter,
                            top = spacing.safeTop,
                            bottom = spacing.safeBottom
                        )
                ) {
                    if (showScreenHeader) {
                        AppScreenHeader(
                            title = title,
                            subtitle = subtitle,
                            modifier = Modifier.fillMaxWidth(),
                            compact = compactHeader
                        )
                        if (header != null) {
                            Spacer(modifier = Modifier.height(spacing.lg))
                            header()
                        }
                        Spacer(modifier = Modifier.height(spacing.lg))
                    } else if (header != null) {
                        header()
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding)
                    ) {
                        content()
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = 14.dp,
                        vertical = 10.dp
                    )
            ) {
                if (topBarVisible) {
                    TopNavigationBar(
                        currentRoute = currentRoute,
                        onNavigate = onNavigate,
                        actions = topBarActions,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                if (showScreenHeader) {
                    AppScreenHeader(
                        title = title,
                        subtitle = subtitle,
                        modifier = Modifier.fillMaxWidth(),
                        compact = true
                    )
                    if (header != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        header()
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (header != null) {
                    header()
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun AppScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    compact: Boolean = false
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!eyebrow.isNullOrBlank()) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.Brand
            )
        }
        Text(
            text = title,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.displaySmall,
            color = AppColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TopNavigationBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val items = remember { buildDestinationItems() }
    val scrollState = rememberScrollState()

    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    
    Surface(
        modifier = modifier.focusProperties {
            onEnter = {
                val activeItem = findActiveDestinationItem(items, currentRoute)
                focusRequesters[activeItem?.route] ?: FocusRequester.Default
            }
        },
        shape = RoundedCornerShape(18.dp),
        colors = SurfaceDefaults.colors(containerColor = AppColors.Surface.copy(alpha = 0.9f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.TextPrimary,
                modifier = Modifier.wrapContentWidth(Alignment.Start)
            )
            Spacer(modifier = Modifier.width(32.dp)) // Increased spacing to prevent overlap
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items.forEach { item ->
                    val requester = focusRequesters.getOrPut(item.route) { FocusRequester() }
                    TopNavigationButton(
                        label = stringResource(item.labelRes),
                        icon = item.icon,
                        selected = currentRoute.startsWith(item.route),
                        focusRequester = requester,
                        onClick = {
                            if (!currentRoute.startsWith(item.route)) {
                                onNavigate(item.route)
                            }
                        }
                    )
                }
            }
            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
        }
    }
}

@Composable
fun AppTopBarCloseAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.settings_close_app)
) {
    TvIconButton(
        onClick = onClick,
        modifier = modifier,
        colors = androidx.tv.material3.IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = AppColors.SurfaceEmphasis,
            contentColor = AppColors.TextSecondary,
            focusedContentColor = AppColors.TextPrimary
        ),
        border = androidx.tv.material3.IconButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus),
                shape = RoundedCornerShape(14.dp)
            )
        )
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun TopNavigationButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val sounds = rememberTvInteractionSounds()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) FocusSpec.FocusedScale else 1f,
        animationSpec = AppMotion.FocusSpec,
        label = "topNavScale"
    )

    Surface(
        onClick = {
            sounds.playSelect()
            onClick()
        },
        modifier = modifier
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = {
                    sounds.playSelect()
                    onClick()
                }
            )
            .zIndex(if (isFocused) 1f else 0f) // Keep focused button on top
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                if (it.isFocused && !isFocused) {
                    sounds.playNavigate()
                }
                isFocused = it.isFocused
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppColors.BrandMuted else Color.Transparent,
            focusedContainerColor = AppColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus),
                shape = RoundedCornerShape(14.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) AppColors.Brand else AppColors.TextSecondary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) AppColors.TextPrimary else AppColors.TextSecondary
            )
        }
    }
}

@Composable
fun AppHeroHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            AppColors.Canvas,
                            AppColors.SurfaceAccent,
                            AppColors.SurfaceEmphasis
                        )
                    )
                )
                .padding(32.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                AppScreenHeader(
                    title = title,
                    subtitle = subtitle,
                    eyebrow = eyebrow
                )
                if (actions != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
                if (footer != null) {
                    footer()
                }
            }
        }
    }
}

@Composable
fun AppSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionContentColor: Color = AppColors.TextTertiary
) {
    val shapes = LocalAppShapes.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = if (onActionClick != null && !actionLabel.isNullOrBlank()) Modifier.weight(1f) else Modifier,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary,
                modifier = Modifier.semantics { heading() }
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary
                )
            }
        }

        if (onActionClick != null && !actionLabel.isNullOrBlank()) {
            val actionFocusRequester = remember { FocusRequester() }
            Surface(
                onClick = onActionClick,
                modifier = Modifier
                    .focusRequester(actionFocusRequester)
                    .mouseClickable(
                        focusRequester = actionFocusRequester,
                        onClick = onActionClick
                    ),
                shape = ClickableSurfaceDefaults.shape(shapes.pill),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = AppColors.Brand.copy(alpha = 0.12f),
                    focusedContainerColor = AppColors.Brand.copy(alpha = 0.22f),
                    contentColor = actionContentColor
                )
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = AppColors.SurfaceEmphasis,
    contentColor: Color = AppColors.TextPrimary,
    cornerRadius: Dp = 999.dp,
    horizontalPadding: Dp = 10.dp,
    verticalPadding: Dp = 4.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}

@Composable
fun AppMessageState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    shape: RoundedCornerShape? = null,
    containerBrush: Brush? = null,
    borderColor: Color? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    subtitleStyle: TextStyle = MaterialTheme.typography.bodySmall,
    titleColor: Color = AppColors.TextPrimary,
    subtitleColor: Color = AppColors.TextSecondary,
    titleTextAlign: TextAlign = TextAlign.Start,
    subtitleTextAlign: TextAlign = TextAlign.Start
) {
    val resolvedShape = shape ?: LocalAppShapes.current.large
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = resolvedShape,
        border = Border(
            border = BorderStroke(
                width = if (borderColor != null) 1.dp else 0.dp,
                color = borderColor ?: Color.Transparent
            ),
            shape = resolvedShape
        ),
        colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated)
    ) {
        Column(
            modifier = Modifier
                .then(if (containerBrush != null) Modifier.background(containerBrush) else Modifier)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = titleStyle,
                color = titleColor,
                textAlign = titleTextAlign,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = subtitle,
                style = subtitleStyle,
                color = subtitleColor,
                textAlign = subtitleTextAlign,
                modifier = Modifier.fillMaxWidth()
            )
            if (action != null) {
                Spacer(modifier = Modifier.height(8.dp))
                action()
            }
        }
    }
}

@Composable
fun LoadMoreCard(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shapes = LocalAppShapes.current
    val focusRequester = remember { FocusRequester() }
    Surface(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = onClick
            ),
        shape = ClickableSurfaceDefaults.shape(shapes.medium),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.SurfaceElevated,
            focusedContainerColor = AppColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus),
                shape = shapes.medium
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = label,
                tint = AppColors.Brand,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary
            )
        }
    }
}

@Composable
fun ContentMetadataStrip(
    values: List<String>,
    modifier: Modifier = Modifier
) {
    val filteredValues = values.filter { it.isNotBlank() }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        filteredValues.forEachIndexed { index, value ->
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.TextSecondary
            )
            if (index < filteredValues.lastIndex) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(AppColors.TextTertiary)
                )
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DestinationRail(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalAppSpacing.current
    val items = remember { buildDestinationItems() }
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    Box(
        modifier = modifier
            .padding(start = spacing.lg, top = spacing.safeTop, bottom = spacing.safeBottom)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        AppColors.SurfaceElevated,
                        AppColors.Surface
                    )
                )
            )
            .focusProperties {
                onEnter = {
                    val activeItem = findActiveDestinationItem(items, currentRoute)
                    focusRequesters[activeItem?.route] ?: FocusRequester.Default
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary
            )
            Text(
                text = stringResource(R.string.label_tv),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary
            )
            Spacer(modifier = Modifier.height(10.dp))
            items.forEach { item ->
                val requester = focusRequesters.getOrPut(item.route) { FocusRequester() }
                RailButton(
                    label = stringResource(item.labelRes),
                    icon = item.icon,
                    selected = currentRoute.startsWith(item.route),
                    modifier = Modifier.focusRequester(requester),
                    onClick = {
                        if (!currentRoute.startsWith(item.route)) {
                            onNavigate(item.route)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun RailButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) FocusSpec.FocusedScale else 1f,
        animationSpec = AppMotion.FocusSpec,
        label = "railButtonScale"
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = onClick
            )
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppColors.BrandMuted else Color.Transparent,
            focusedContainerColor = AppColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus),
                shape = RoundedCornerShape(18.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) AppColors.Brand else AppColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) AppColors.TextPrimary else AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class DestinationItem(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector
)

private fun findActiveDestinationItem(
    items: List<DestinationItem>,
    currentRoute: String
): DestinationItem? =
    items
        .filter { currentRoute.startsWith(it.route) }
        .maxByOrNull { it.route.length }
        ?: items.firstOrNull { it.route == currentRoute }

private fun buildDestinationItems(): List<DestinationItem> = listOf(
    DestinationItem(Routes.HOME, R.string.nav_home, Icons.Default.Home),
    DestinationItem(Routes.LIVE_TV, R.string.nav_live_tv, Icons.Default.PlayArrow),
    DestinationItem(Routes.MOVIES, R.string.nav_movies, Icons.Default.Star),
    DestinationItem(Routes.SERIES, R.string.nav_series, Icons.Default.Menu),
    DestinationItem(Routes.EPG, R.string.nav_epg, Icons.Default.Info),
    DestinationItem(Routes.SETTINGS_DOWNLOADS, R.string.nav_downloads, Icons.Default.KeyboardArrowDown),
    DestinationItem(Routes.SEARCH, R.string.search_title, Icons.Default.Search),
    DestinationItem(Routes.PLUGINS, R.string.nav_plugins, PluginBlocksIcon),
    DestinationItem(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings)
)

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
