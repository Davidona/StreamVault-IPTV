package com.streamvault.core.ui.components.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.LocalBrowseVisuals
import com.streamvault.core.ui.design.browseBackdrop
import com.streamvault.core.ui.design.browsePanel
import com.streamvault.core.ui.design.AppMotion
import com.streamvault.core.ui.design.BrowseFocusSpec as FocusSpec
import com.streamvault.core.ui.design.LocalAppShapes
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.core.ui.interaction.TvIconButton
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.interaction.rememberTvInteractionSounds

enum class NavigationChrome {
    Rail,
    TopBar
}

data class UiDestination(
    val id: String,
    val label: String,
    val icon: ImageVector
)

@Composable
fun CoreAppScreenScaffold(
    currentDestinationId: String,
    destinations: List<UiDestination>,
    onDestinationSelected: (String) -> Unit,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    navigationChrome: NavigationChrome = NavigationChrome.Rail,
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
            .semantics {
                contentDescription = "streamvault.destination:$currentDestinationId"
            }
            .browseBackdrop()
    ) {
        if (navigationChrome == NavigationChrome.Rail) {
            Row(modifier = Modifier.fillMaxSize()) {
                DestinationRail(
                    currentDestinationId = currentDestinationId,
                    destinations = destinations,
                    onDestinationSelected = onDestinationSelected,
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
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (topBarVisible) {
                    TopNavigationBar(
                        currentDestinationId = currentDestinationId,
                        destinations = destinations,
                        onDestinationSelected = onDestinationSelected,
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
                color = BrowseColors.Brand
            )
        }
        Text(
            text = title,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.displaySmall,
            color = BrowseColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                color = BrowseColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TopNavigationBar(
    currentDestinationId: String,
    destinations: List<UiDestination>,
    onDestinationSelected: (String) -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    Surface(
        modifier = modifier.focusProperties {
            onEnter = {
                val activeItem = findActiveDestination(destinations, currentDestinationId)
                focusRequesters[activeItem?.id] ?: FocusRequester.Default
            }
        },
        shape = BrowseShapes.Panel,
        colors = SurfaceDefaults.colors(containerColor = BrowseColors.Surface.copy(alpha = 0.94f)),
        border = Border(BorderStroke(1.dp, BrowseColors.Outline), shape = BrowseShapes.Panel)
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
                text = "StreamVault",
                style = MaterialTheme.typography.titleSmall.copy(letterSpacing = (-0.4).sp),
                color = BrowseColors.TextPrimary,
                modifier = Modifier.wrapContentWidth(Alignment.Start)
            )
            Spacer(modifier = Modifier.width(24.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                destinations.forEach { destination ->
                    val requester = focusRequesters.getOrPut(destination.id) { FocusRequester() }
                    TopNavigationButton(
                        destination = destination,
                        selected = currentDestinationId.startsWith(destination.id),
                        focusRequester = requester,
                        onClick = {
                            if (!currentDestinationId.startsWith(destination.id)) {
                                onDestinationSelected(destination.id)
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
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    TvIconButton(
        onClick = onClick,
        modifier = modifier,
        colors = androidx.tv.material3.IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = BrowseColors.Brand.copy(alpha = 0.18f),
            contentColor = BrowseColors.TextSecondary,
            focusedContentColor = BrowseColors.TextPrimary
        ),
        border = androidx.tv.material3.IconButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, BrowseColors.Focus),
                shape = BrowseShapes.Control
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
    destination: UiDestination,
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
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .zIndex(if (isFocused) 1f else 0f)
            .onFocusChanged {
                if (it.isFocused && !isFocused) sounds.playNavigate()
                isFocused = it.isFocused
            },
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Control),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) BrowseColors.Brand.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = BrowseColors.Brand.copy(alpha = 0.18f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, BrowseColors.Focus),
                shape = BrowseShapes.Control
            )
        )
    ) {
        Row(
            modifier = Modifier
                .then(if (selected) Modifier.border(1.dp, BrowseColors.Brand.copy(alpha = 0.28f), BrowseShapes.Control) else Modifier)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = destination.label,
                tint = if (selected) BrowseColors.Brand else BrowseColors.TextSecondary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) BrowseColors.TextPrimary else BrowseColors.TextSecondary
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
        shape = BrowseShapes.Hero,
        colors = SurfaceDefaults.colors(containerColor = BrowseColors.SurfaceElevated),
        border = Border(BorderStroke(1.dp, BrowseColors.Outline), shape = BrowseShapes.Hero)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BrowseShapes.Hero)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(BrowseColors.Surface, BrowseColors.SurfaceEmphasis, BrowseColors.Brand.copy(alpha = 0.16f))
                    )
                )
                .padding(32.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                AppScreenHeader(title = title, subtitle = subtitle, eyebrow = eyebrow)
                if (actions != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
                if (footer != null) footer()
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
    actionContentColor: Color = BrowseColors.TextTertiary
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
                color = BrowseColors.TextPrimary,
                modifier = Modifier.semantics { heading() }
            )
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = BrowseColors.TextTertiary)
            }
        }
        if (onActionClick != null && !actionLabel.isNullOrBlank()) {
            val actionFocusRequester = remember { FocusRequester() }
            Surface(
                onClick = onActionClick,
                modifier = Modifier
                    .focusRequester(actionFocusRequester)
                    .mouseClickable(focusRequester = actionFocusRequester, onClick = onActionClick),
                shape = ClickableSurfaceDefaults.shape(shapes.pill),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = BrowseColors.Brand.copy(alpha = 0.12f),
                    focusedContainerColor = BrowseColors.Brand.copy(alpha = 0.22f),
                    contentColor = actionContentColor
                )
            ) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
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
    val modern = LocalBrowseVisuals.current
    val isStatusColor = containerColor in listOf(AppColors.Live, AppColors.Brand, AppColors.Warning, AppColors.Success, AppColors.Info)
    val tinted = modern && isStatusColor
    val shape = RoundedCornerShape(if (modern && cornerRadius == 999.dp) 6.dp else cornerRadius)
    val resolvedContentColor = when {
        tinted -> containerColor
        modern && containerColor.copy(alpha = 1f) == Color.Black -> Color.White
        else -> contentColor
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (tinted) containerColor.copy(alpha = 0.16f) else containerColor)
            .then(if (modern) Modifier.border(1.dp, (if (tinted) containerColor else AppColors.TextPrimary).copy(alpha = 0.14f), shape) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = resolvedContentColor)
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
            border = BorderStroke(if (borderColor != null) 1.dp else 0.dp, borderColor ?: Color.Transparent),
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
            Text(text = title, style = titleStyle, color = titleColor, textAlign = titleTextAlign, modifier = Modifier.fillMaxWidth())
            Text(text = subtitle, style = subtitleStyle, color = subtitleColor, textAlign = subtitleTextAlign, modifier = Modifier.fillMaxWidth())
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
            .mouseClickable(focusRequester = focusRequester, onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(shapes.medium),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = BrowseColors.SurfaceElevated,
            focusedContainerColor = BrowseColors.Brand.copy(alpha = 0.18f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, BrowseColors.Focus),
                shape = shapes.medium
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.Info, contentDescription = label, tint = BrowseColors.Brand, modifier = Modifier.size(18.dp))
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = BrowseColors.TextPrimary)
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
            Text(text = value, style = MaterialTheme.typography.labelMedium, color = BrowseColors.TextSecondary)
            if (index < filteredValues.lastIndex) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(BrowseColors.TextTertiary)
                )
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DestinationRail(
    currentDestinationId: String,
    destinations: List<UiDestination>,
    onDestinationSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalAppSpacing.current
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    Box(
        modifier = modifier
            .padding(start = spacing.lg, top = spacing.safeTop, bottom = spacing.safeBottom)
            .browsePanel(BrowseShapes.Panel)
            .focusProperties {
                onEnter = {
                    val activeItem = findActiveDestination(destinations, currentDestinationId)
                    focusRequesters[activeItem?.id] ?: FocusRequester.Default
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "StreamVault", style = MaterialTheme.typography.titleMedium, color = BrowseColors.TextPrimary)
            Text(text = "TV", style = MaterialTheme.typography.labelSmall, color = BrowseColors.TextTertiary)
            Spacer(modifier = Modifier.height(10.dp))
            destinations.forEach { destination ->
                val requester = focusRequesters.getOrPut(destination.id) { FocusRequester() }
                RailButton(
                    destination = destination,
                    selected = currentDestinationId.startsWith(destination.id),
                    modifier = Modifier.focusRequester(requester),
                    onClick = {
                        if (!currentDestinationId.startsWith(destination.id)) {
                            onDestinationSelected(destination.id)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun RailButton(
    destination: UiDestination,
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
            .semantics {
                contentDescription = destination.label
                onClick(label = destination.label) {
                    onClick()
                    true
                }
            }
            .focusRequester(focusRequester)
            .mouseClickable(focusRequester = focusRequester, onClick = onClick)
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Panel),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) BrowseColors.Brand.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = BrowseColors.Brand.copy(alpha = 0.18f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, BrowseColors.Focus),
                shape = BrowseShapes.Panel
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
                imageVector = destination.icon,
                contentDescription = destination.label,
                tint = if (selected) BrowseColors.Brand else BrowseColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = destination.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) BrowseColors.TextPrimary else BrowseColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun findActiveDestination(
    destinations: List<UiDestination>,
    currentDestinationId: String
): UiDestination? = destinations
    .filter { currentDestinationId.startsWith(it.id) }
    .maxByOrNull { it.id.length }
    ?: destinations.firstOrNull { it.id == currentDestinationId }
