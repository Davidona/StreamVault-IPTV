package com.streamvault.app.ui.screens.epg

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.feature.live.presentation.epg.GuideChannelMode
import com.streamvault.feature.live.presentation.epg.GuideDensity
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SelectionChipRow
import com.streamvault.feature.live.presentation.epg.dayRelativeOffset
import com.streamvault.feature.live.presentation.epg.shiftGuideDayStart
import com.streamvault.feature.live.presentation.epg.startOfGuideDay
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LocalGuideNow = staticCompositionLocalOf { 0L }

@Composable
internal fun rememberGuideNow(): Long {
    val currentTime by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    return currentTime
}

@Composable
internal fun GuideNowProvider(content: @Composable () -> Unit) {
    val now = rememberGuideNow()
    CompositionLocalProvider(LocalGuideNow provides now) {
        content()
    }
}

@Composable
internal fun currentGuideNow(): Long = LocalGuideNow.current

@Composable
internal fun GuideDensityRow(
    selectedDensity: GuideDensity,
    onDensitySelected: (GuideDensity) -> Unit
) {
    SelectionChipRow(
        title = stringResource(R.string.epg_density_label),
        subtitle = stringResource(R.string.epg_density_subtitle),
        chips = listOf(
            SelectionChip(
                key = GuideDensity.COMPACT.name,
                label = stringResource(R.string.epg_density_compact),
                supportingText = stringResource(R.string.epg_density_compact_hint)
            ),
            SelectionChip(
                key = GuideDensity.COMFORTABLE.name,
                label = stringResource(R.string.epg_density_comfortable),
                supportingText = stringResource(R.string.epg_density_comfortable_hint)
            ),
            SelectionChip(
                key = GuideDensity.CINEMATIC.name,
                label = stringResource(R.string.epg_density_cinematic),
                supportingText = stringResource(R.string.epg_density_cinematic_hint)
            )
        ),
        selectedKey = selectedDensity.name,
        onChipSelected = { key ->
            GuideDensity.entries.firstOrNull { it.name == key }?.let(onDensitySelected)
        }
    )
}

@Composable
internal fun GuideProgramSearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: ((String) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    autoRequestFocus: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    showLabel: Boolean = true,
    onSearchFieldActivated: (() -> Unit)? = null
) {
    val resolvedFocusRequester = focusRequester ?: remember { FocusRequester() }
    var localQuery by rememberSaveable { mutableStateOf(query) }
    var refocusToken by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(query) {
        if (query != localQuery) {
            localQuery = query
        }
    }

    LaunchedEffect(localQuery) {
        if (localQuery == query) return@LaunchedEffect
        if (localQuery.isBlank()) {
            onQueryChange("")
            return@LaunchedEffect
        }
        delay(250)
        if (localQuery != query) {
            onQueryChange(localQuery)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        if (showLabel) {
            Text(
                text = stringResource(R.string.epg_search_label),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GuideSearchField(
                value = localQuery,
                onValueChange = { localQuery = it },
                placeholder = stringResource(R.string.epg_search_placeholder),
                modifier = Modifier.weight(1f),
                focusRequester = resolvedFocusRequester,
                autoRequestFocus = autoRequestFocus,
                refocusToken = refocusToken,
                onSearch = { onSearch?.invoke(localQuery.trim()) },
                onActivated = onSearchFieldActivated
            )
            Box(modifier = Modifier.widthIn(min = 104.dp), contentAlignment = Alignment.CenterEnd) {
                if (localQuery.isNotBlank()) {
                    GuideShortcutChip(
                        label = stringResource(R.string.epg_clear_search),
                        onClick = {
                            localQuery = ""
                            onClear()
                            refocusToken += 1
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun GuideSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    autoRequestFocus: Boolean = false,
    refocusToken: Int = 0,
    onSearch: ((String) -> Unit)? = null,
    onActivated: (() -> Unit)? = null
) {
    val isTelevisionDevice = rememberIsTelevisionDevice()
    var hasContainerFocus by remember { mutableStateOf(false) }
    var hasInputFocus by remember { mutableStateOf(false) }
    var acceptsInput by remember(isTelevisionDevice) { mutableStateOf(!isTelevisionDevice) }
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val inputFocusRequester = remember { FocusRequester() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var pendingKeyboardRequest by remember { mutableStateOf(0) }
    val inputMethodManager = remember(context) {
        context.getSystemService(InputMethodManager::class.java)
    }
    val isFocused = hasContainerFocus || hasInputFocus

    fun requestBringIntoView(delayMillis: Long = 0L) {
        coroutineScope.launch {
            if (delayMillis > 0) {
                delay(delayMillis)
            }
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
    }

    fun requestKeyboard() {
        if (!isTelevisionDevice) {
            acceptsInput = true
            inputFocusRequester.requestFocus()
            view.post {
                val focusedView = view.findFocus() ?: view
                focusedView.requestFocus()
                keyboardController?.show()
                inputMethodManager?.showSoftInput(focusedView, InputMethodManager.SHOW_IMPLICIT)
            }
            onActivated?.invoke()
            requestBringIntoView()
            requestBringIntoView(180)
            return
        }
        acceptsInput = true
        pendingKeyboardRequest += 1
        onActivated?.invoke()
        requestBringIntoView()
    }

    LaunchedEffect(autoRequestFocus) {
        if (autoRequestFocus) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(refocusToken) {
        if (refocusToken > 0) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(pendingKeyboardRequest) {
        if (!isTelevisionDevice || pendingKeyboardRequest <= 0) return@LaunchedEffect
        inputFocusRequester.requestFocus()
        delay(80)
        view.post {
            val focusedView = view.findFocus() ?: view
            focusedView.requestFocus()
            keyboardController?.show()
            inputMethodManager?.showSoftInput(focusedView, InputMethodManager.SHOW_IMPLICIT)
        }
        requestBringIntoView(120)
    }

    LaunchedEffect(value) {
        if (value != textFieldValue.text) {
            val coercedSelectionStart = textFieldValue.selection.start.coerceIn(0, value.length)
            val coercedSelectionEnd = textFieldValue.selection.end.coerceIn(0, value.length)
            val coercedComposition = textFieldValue.composition?.let { composition ->
                val compositionStart = composition.start.coerceIn(0, value.length)
                val compositionEnd = composition.end.coerceIn(0, value.length)
                if (compositionStart <= compositionEnd) {
                    TextRange(compositionStart, compositionEnd)
                } else {
                    null
                }
            }
            textFieldValue = textFieldValue.copy(
                text = value,
                selection = TextRange(coercedSelectionStart, coercedSelectionEnd),
                composition = coercedComposition
            )
        }
    }

    TvClickableSurface(
        onClick = { requestKeyboard() },
        modifier = modifier
            .height(40.dp)
            .focusRequester(focusRequester)
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { hasContainerFocus = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isFocused) SurfaceHighlight else SurfaceElevated,
            focusedContainerColor = SurfaceHighlight,
            contentColor = OnSurface,
            focusedContentColor = OnSurface
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(10.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.tv.material3.Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = if (isFocused) Primary else OnSurfaceDim
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { updatedValue ->
                        textFieldValue = updatedValue
                        if (updatedValue.text != value) {
                            onValueChange(updatedValue.text)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .focusProperties {
                            canFocus = !isTelevisionDevice || acceptsInput
                            if (isTelevisionDevice && acceptsInput) {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (!isTelevisionDevice || !acceptsInput || event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                                return@onPreviewKeyEvent false
                            }
                            val cursor = textFieldValue.selection.end
                            when (event.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    val nextCursor = (cursor - 1).coerceAtLeast(0)
                                    textFieldValue = textFieldValue.copy(selection = TextRange(nextCursor))
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    val nextCursor = (cursor + 1).coerceAtMost(textFieldValue.text.length)
                                    textFieldValue = textFieldValue.copy(selection = TextRange(nextCursor))
                                    true
                                }
                                else -> false
                            }
                        }
                        .onFocusChanged {
                            if (it.isFocused) {
                                hasInputFocus = true
                                requestBringIntoView(120)
                            } else {
                                hasInputFocus = false
                                if (isTelevisionDevice) {
                                    acceptsInput = false
                                }
                                keyboardController?.hide()
                            }
                        },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = OnSurface),
                    singleLine = true,
                    cursorBrush = SolidColor(Primary),
                    readOnly = isTelevisionDevice && !acceptsInput,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus(force = true)
                        acceptsInput = false
                        keyboardController?.hide()
                        onSearch?.invoke(textFieldValue.text.trim())
                    })
                )
            }
        }
    }
}

@Composable
internal fun GuideModeRow(
    selectedMode: GuideChannelMode,
    onModeSelected: (GuideChannelMode) -> Unit
) {
    SelectionChipRow(
        title = stringResource(R.string.epg_mode_label),
        subtitle = stringResource(R.string.epg_mode_subtitle),
        chips = listOf(
            SelectionChip(
                key = GuideChannelMode.ALL.name,
                label = stringResource(R.string.epg_mode_all),
                supportingText = stringResource(R.string.epg_mode_all_hint)
            ),
            SelectionChip(
                key = GuideChannelMode.ANCHORED.name,
                label = stringResource(R.string.epg_mode_anchored),
                supportingText = stringResource(R.string.epg_mode_anchored_hint)
            ),
            SelectionChip(
                key = GuideChannelMode.ARCHIVE_READY.name,
                label = stringResource(R.string.epg_mode_archive),
                supportingText = stringResource(R.string.epg_mode_archive_hint)
            )
        ),
        selectedKey = selectedMode.name,
        onChipSelected = { key ->
            GuideChannelMode.entries
                .firstOrNull { it.name == key }
                ?.let(onModeSelected)
        }
    )
}

@Composable
internal fun GuideTimeControlsRow(
    onJumpToPreviousDay: () -> Unit,
    onPageBackward: () -> Unit,
    onJumpBackwardHalfHour: () -> Unit,
    onJumpBackward: () -> Unit,
    onJumpToNow: () -> Unit,
    onJumpForwardHalfHour: () -> Unit,
    onJumpForward: () -> Unit,
    onPageForward: () -> Unit,
    onJumpToPrimeTime: () -> Unit,
    onJumpToTomorrow: () -> Unit,
    onJumpToNextDay: () -> Unit,
    firstChipFocusRequester: FocusRequester? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    showLabel: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        if (showLabel) {
            Text(
                text = stringResource(R.string.epg_time_controls),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                GuideShortcutChip(
                    modifier = firstChipFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                    label = stringResource(R.string.epg_previous_day),
                    onClick = onJumpToPreviousDay
                )
            }
            item { GuideShortcutChip(label = stringResource(R.string.epg_page_back), onClick = onPageBackward) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_jump_back_half_hour), onClick = onJumpBackwardHalfHour) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_jump_back), onClick = onJumpBackward) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_jump_now), onClick = onJumpToNow) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_jump_forward_half_hour), onClick = onJumpForwardHalfHour) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_jump_forward), onClick = onJumpForward) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_page_forward), onClick = onPageForward) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_jump_prime_time), onClick = onJumpToPrimeTime) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_jump_tomorrow), onClick = onJumpToTomorrow) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_next_day), onClick = onJumpToNextDay) }
        }
    }
}

@Composable
internal fun GuideDayRow(
    selectedDayStart: Long,
    onDaySelected: (Long) -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
    showLabel: Boolean = true
) {
    val dayFormat = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val dayAnchors = remember(selectedDayStart) {
        (-1L..3L).map { offset ->
            shiftGuideDayStart(selectedDayStart, offset)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        if (showLabel) {
            Text(
                text = stringResource(R.string.epg_day_selector_label),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(dayAnchors, key = { it }) { dayStart ->
                val isSelected = dayStart == selectedDayStart
                TvClickableSurface(
                    onClick = { onDaySelected(dayStart) },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = if (isSelected) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(text = dayRelativeLabel(dayStart), style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = dayFormat.format(Date(dayStart)),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun GuideViewOptionsRow(
    showScheduledOnly: Boolean,
    onToggleScheduledOnly: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.epg_view_options_label),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceDim
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                TvClickableSurface(
                    onClick = onToggleScheduledOnly,
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (showScheduledOnly) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = if (showScheduledOnly) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (showScheduledOnly) R.string.epg_view_scheduled_only_on
                                else R.string.epg_view_scheduled_only_off
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = stringResource(R.string.epg_view_scheduled_only_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun GuideFavoritesRow(
    showFavoritesOnly: Boolean,
    onToggleFavoritesOnly: () -> Unit
) {
    SelectionChipRow(
        title = stringResource(R.string.epg_favorites_filter_title),
        subtitle = stringResource(R.string.epg_favorites_filter_subtitle),
        chips = listOf(
            SelectionChip(
                key = "all",
                label = stringResource(R.string.epg_favorites_filter_all),
                supportingText = stringResource(R.string.epg_favorites_filter_all_hint)
            ),
            SelectionChip(
                key = "favorites",
                label = stringResource(R.string.epg_favorites_filter_favorites),
                supportingText = stringResource(R.string.epg_favorites_filter_favorites_hint)
            )
        ),
        selectedKey = if (showFavoritesOnly) "favorites" else "all",
        onChipSelected = { key ->
            val shouldShowFavorites = key == "favorites"
            if (shouldShowFavorites != showFavoritesOnly) {
                onToggleFavoritesOnly()
            }
        },
        contentPadding = PaddingValues(horizontal = 24.dp)
    )
}

@Composable
internal fun GuideShortcutChip(
    modifier: Modifier = Modifier,
    label: String,
    onClick: () -> Unit,
    isSelected: Boolean = false
) {
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
            focusedContainerColor = SurfaceHighlight,
            contentColor = if (isSelected) Primary else OnSurface,
            focusedContentColor = OnSurface
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(999.dp)
            )
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun startOfDay(timestamp: Long): Long {
    return startOfGuideDay(timestamp)
}

@Composable
private fun dayRelativeLabel(dayStart: Long): String {
    val zoneId = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zoneId) }
    return when (dayRelativeOffset(dayStart, today, zoneId)) {
        -1L -> stringResource(R.string.epg_day_yesterday)
        0L -> stringResource(R.string.epg_day_today)
        1L -> stringResource(R.string.epg_day_tomorrow)
        else -> {
            val format = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
            format.format(Date(dayStart))
        }
    }
}
