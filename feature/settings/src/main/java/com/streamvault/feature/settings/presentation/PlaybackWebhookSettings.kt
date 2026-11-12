package com.streamvault.feature.settings.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.domain.playback.events.*
import com.streamvault.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat

@HiltViewModel
class PlaybackWebhookSettingsViewModel @Inject constructor(val webhooks: PlaybackWebhooks) : ViewModel() {
    val busy = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    fun save(configuration: PlaybackWebhookConfiguration, done: () -> Unit = {}) {
        if (busy.value || webhooks.diagnostics.value[""]?.status == PlaybackWebhookStatus.IDLE) return
        busy.value = true
        failed.value = false
        viewModelScope.launch {
            try { webhooks.save(configuration); done() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                failed.value = true
            } finally { busy.value = false }
        }
    }
    fun test(id: String) {
        if (busy.value || webhooks.diagnostics.value[""]?.status == PlaybackWebhookStatus.IDLE) return
        busy.value = true
        failed.value = false
        viewModelScope.launch {
            try { webhooks.test(id) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                failed.value = true
            } finally { busy.value = false }
        }
    }
}

@Composable
internal fun PlaybackWebhookSettingsPage(modifier: Modifier = Modifier,
    viewModel: PlaybackWebhookSettingsViewModel = hiltViewModel()) {
    val configuration by viewModel.webhooks.configuration.collectAsStateWithLifecycle()
    val diagnostics by viewModel.webhooks.diagnostics.collectAsStateWithLifecycle()
    val saving by viewModel.busy.collectAsStateWithLifecycle()
    val busy = saving || diagnostics[""]?.status == PlaybackWebhookStatus.IDLE
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PlaybackWebhookDestination?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    val spacing = LocalAppSpacing.current
    val entryFocus = remember { FocusRequester() }
    LaunchedEffect(editing?.id, preview != null, configuration.destinations.map { it.id }) {
        withFrameNanos { }
        runCatching { entryFocus.requestFocus() }
    }
    BackHandler(editing != null || preview != null) {
        if (preview != null) preview = null else editing = null
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        WebhookText(stringResource(R.string.webhook_help))
        if (failed || diagnostics[""]?.status == PlaybackWebhookStatus.STORAGE_ERROR) WebhookText(stringResource(R.string.webhook_error))
        val draft = editing
        if (preview != null) {
            WebhookText(preview!!, lines = 40)
            WebhookAction(stringResource(R.string.webhook_back), modifier = Modifier.focusRequester(entryFocus), onClick = { preview = null })
        } else if (draft != null) {
            WebhookField(stringResource(R.string.webhook_name), draft.name, modifier = Modifier.focusRequester(entryFocus)) { editing = draft.copy(name = it) }
            WebhookField(stringResource(R.string.webhook_url), draft.url, secret = true) { editing = draft.copy(url = it) }
            WebhookField(stringResource(R.string.webhook_header_name), draft.headerName) { editing = draft.copy(headerName = it) }
            WebhookField(stringResource(R.string.webhook_header_value), draft.headerValue, secret = true) { editing = draft.copy(headerValue = it) }
            WebhookAction(stringResource(R.string.webhook_enabled), onOff(draft.enabled), !busy) { editing = draft.copy(enabled = !draft.enabled) }
            WebhookAction(stringResource(R.string.webhook_format), when (draft.format) {
                PlaybackWebhookFormat.NATIVE -> stringResource(R.string.webhook_native)
                PlaybackWebhookFormat.JELLYFIN -> stringResource(R.string.webhook_jellyfin)
                PlaybackWebhookFormat.YAMTRACK -> stringResource(R.string.webhook_yamtrack)
            }) {
                val format = PlaybackWebhookFormat.entries[(draft.format.ordinal + 1) % PlaybackWebhookFormat.entries.size]
                editing = draft.copy(format = format, delivery = if (format == PlaybackWebhookFormat.NATIVE)
                    PlaybackWebhookDelivery.REALTIME else PlaybackWebhookDelivery.HISTORY)
            }
            if (draft.format != PlaybackWebhookFormat.NATIVE) WebhookText(stringResource(R.string.webhook_compatibility))
            WebhookAction(stringResource(R.string.webhook_delivery), stringResource(if (draft.delivery == PlaybackWebhookDelivery.REALTIME)
                R.string.webhook_realtime else R.string.webhook_history)) {
                editing = draft.copy(delivery = if (draft.delivery == PlaybackWebhookDelivery.REALTIME)
                    PlaybackWebhookDelivery.HISTORY else PlaybackWebhookDelivery.REALTIME)
            }
            WebhookAction(stringResource(R.string.webhook_interval), if (draft.policy.progressIntervalMs == 0L) onOff(false)
                else stringResource(R.string.webhook_seconds, draft.policy.progressIntervalMs / 1000)) {
                val values = listOf(0L, 15_000L, 30_000L, 60_000L)
                editing = draft.copy(policy = draft.policy.copy(progressIntervalMs = values[(values.indexOf(draft.policy.progressIntervalMs) + 1) % values.size]))
            }
            WebhookAction(stringResource(R.string.webhook_threshold), NumberFormat.getPercentInstance(
                LocalContext.current.resources.configuration.locales[0]).format(draft.policy.watchedPercent / 100.0)) {
                editing = draft.copy(policy = draft.policy.copy(watchedPercent = if (draft.policy.watchedPercent >= 100) 50 else draft.policy.watchedPercent + 5))
            }
            PlaybackMediaType.entries.forEach { type ->
                WebhookAction(stringResource(when (type) {
                    PlaybackMediaType.MOVIE -> R.string.webhook_movies
                    PlaybackMediaType.EPISODE -> R.string.webhook_episodes
                    PlaybackMediaType.LIVE -> R.string.webhook_live
                }), onOff(type in draft.mediaTypes)) {
                    editing = draft.copy(mediaTypes = if (type in draft.mediaTypes) draft.mediaTypes - type else draft.mediaTypes + type)
                }
            }
            PlaybackEventType.entries.forEach { type ->
                WebhookAction(stringResource(eventLabel(type)), onOff(type in draft.events)) {
                    editing = draft.copy(events = if (type in draft.events) draft.events - type else draft.events + type)
                }
            }
            WebhookAction(stringResource(R.string.webhook_preview)) { preview = viewModel.webhooks.preview(draft) }
            WebhookAction(stringResource(R.string.webhook_save), enabled = !busy) {
                viewModel.save(configuration.copy(destinations = configuration.destinations.filterNot { it.id == draft.id } + draft)) { editing = null }
            }
            WebhookAction(stringResource(R.string.webhook_cancel), enabled = !busy) { editing = null }
        } else {
            WebhookAction(stringResource(R.string.webhook_enabled), onOff(configuration.enabled), !busy,
                Modifier.focusRequester(entryFocus)) {
                viewModel.save(configuration.copy(enabled = !configuration.enabled))
            }
            configuration.destinations.forEach { destination ->
                val diagnostic = diagnostics[destination.id] ?: PlaybackWebhookDiagnostic()
                WebhookAction(destination.name, onOff(destination.enabled), !busy) { editing = destination }
                WebhookText(stringResource(statusLabel(diagnostic.status)) + " · " + stringResource(R.string.webhook_pending, diagnostic.pending) +
                    (diagnostic.httpStatus?.let { " · " + stringResource(R.string.webhook_http, it) } ?: ""))
                WebhookAction(stringResource(R.string.webhook_preview)) { preview = viewModel.webhooks.preview(destination) }
                if (destination.format == PlaybackWebhookFormat.NATIVE) WebhookAction(stringResource(R.string.webhook_test), enabled = !busy) { viewModel.test(destination.id) }
                WebhookAction(stringResource(R.string.webhook_delete), enabled = !busy) {
                    viewModel.save(configuration.copy(destinations = configuration.destinations.filterNot { it.id == destination.id }))
                }
            }
            if (configuration.destinations.size < 4) WebhookAction(stringResource(R.string.webhook_add), enabled = !busy) {
                editing = PlaybackWebhookDestination()
            }
        }
    }
}

@Composable private fun onOff(enabled: Boolean) = stringResource(if (enabled) R.string.webhook_on else R.string.webhook_off)

@Composable private fun WebhookText(text: String, lines: Int = 20) {
    val spacing = LocalAppSpacing.current
    Text(text, color = AppColors.TextPrimary, style = MaterialTheme.typography.bodyMedium,
        maxLines = lines, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(AppColors.SurfaceElevated, BrowseShapes.Control).padding(spacing.md))
}

@Composable internal fun WebhookAction(label: String, value: String = "", enabled: Boolean = true, modifier: Modifier = Modifier,
    onClick: () -> Unit) {
    val spacing = LocalAppSpacing.current
    val palette = AppColors.current
    TvClickableSurface(onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = spacing.xl + spacing.md),
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Control),
        colors = ClickableSurfaceDefaults.colors(containerColor = palette.surfaceElevated,
            focusedContainerColor = palette.surfaceElevated, pressedContainerColor = palette.surfaceElevated,
            disabledContainerColor = palette.surfaceElevated),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, AppColors.Focus), shape = BrowseShapes.Control)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Column(Modifier.fillMaxWidth().padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(label, color = palette.textPrimary, style = MaterialTheme.typography.bodyLarge,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (value.isNotBlank()) Text(value, color = palette.textPrimary, style = MaterialTheme.typography.bodyMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun WebhookField(label: String, value: String, secret: Boolean = false,
    modifier: Modifier = Modifier, change: (String) -> Unit) {
    val palette = AppColors.current
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(
        handleColor = palette.focus, backgroundColor = palette.focus.copy(alpha = 0.22f))) {
    OutlinedTextField(value, { change(it.take(4096)) }, modifier = modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis) },
        textStyle = MaterialTheme.typography.bodyMedium,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = palette.surfaceElevated,
            unfocusedContainerColor = palette.surfaceElevated, focusedTextColor = palette.textPrimary,
            unfocusedTextColor = palette.textPrimary, focusedBorderColor = palette.focus,
            unfocusedBorderColor = palette.focus, cursorColor = palette.focus))
    }
}

private fun eventLabel(type: PlaybackEventType): Int = when (type) {
    PlaybackEventType.START -> R.string.webhook_start
    PlaybackEventType.PAUSE -> R.string.webhook_pause
    PlaybackEventType.RESUME -> R.string.webhook_resume
    PlaybackEventType.PROGRESS -> R.string.webhook_progress
    PlaybackEventType.WATCHED -> R.string.webhook_watched
    PlaybackEventType.STOP -> R.string.webhook_stop
}

private fun statusLabel(status: PlaybackWebhookStatus): Int = when (status) {
    PlaybackWebhookStatus.IDLE -> R.string.webhook_idle
    PlaybackWebhookStatus.SENT -> R.string.webhook_sent
    PlaybackWebhookStatus.RETRYING -> R.string.webhook_retrying
    PlaybackWebhookStatus.REJECTED -> R.string.webhook_rejected
    PlaybackWebhookStatus.EXPIRED -> R.string.webhook_expired
    PlaybackWebhookStatus.OVERFLOW -> R.string.webhook_overflow
    PlaybackWebhookStatus.SKIPPED -> R.string.webhook_skipped
    PlaybackWebhookStatus.STORAGE_ERROR -> R.string.webhook_error
}
