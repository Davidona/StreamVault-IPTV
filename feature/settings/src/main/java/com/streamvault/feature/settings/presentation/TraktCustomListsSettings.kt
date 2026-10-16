package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.components.TvOutlinedTextField
import com.streamvault.domain.trakt.TraktCustomList
import com.streamvault.domain.trakt.TraktCustomListActionResult
import com.streamvault.domain.trakt.TraktCustomListItemsRefreshResult
import com.streamvault.domain.trakt.TraktCustomListRefreshResult
import com.streamvault.domain.trakt.TraktCustomLists
import com.streamvault.domain.trakt.TraktCustomListsFailure
import com.streamvault.domain.trakt.TraktCustomListsState
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class TraktCustomListsSettingsViewModel @Inject constructor(
    private val customLists: TraktCustomLists,
    private val account: TraktAccount,
) : ViewModel() {
    val state = customLists.state
    val accountState = account.state
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableFailure = MutableStateFlow<TraktCustomListsFailure?>(null)
    val failure = mutableFailure.asStateFlow()

    fun refresh() = perform {
        when (val result = customLists.refreshLists(force = true)) {
            is TraktCustomListRefreshResult.Success -> Unit
            is TraktCustomListRefreshResult.Failure -> mutableFailure.value = result.reason
        }
    }

    fun refreshIfAccountChanged(username: String?, connected: Boolean) {
        if (!connected || username.isNullOrBlank()) return
        viewModelScope.launch {
            mutableBusy.first { !it }
            val currentAccount = account.state.value
            val currentLists = customLists.state.value
            if (currentAccount.connected && currentAccount.username == username &&
                (!currentLists.listsLoaded || currentLists.accountUsername != username)
            ) {
                refresh()
            }
        }
    }

    fun openList(listId: Long) = perform {
        when (val result = customLists.refreshItems(listId, force = true)) {
            is TraktCustomListItemsRefreshResult.Success -> Unit
            is TraktCustomListItemsRefreshResult.Failure -> mutableFailure.value = result.reason
        }
    }

    fun create(name: String, description: String) = perform {
        when (val result = customLists.createList(name, description.takeIf(String::isNotBlank))) {
            TraktCustomListActionResult.Success -> Unit
            is TraktCustomListActionResult.Failure -> mutableFailure.value = result.reason
        }
    }

    fun remove(listId: Long, item: com.streamvault.domain.trakt.TraktCustomListItem) = perform {
        when (val result = customLists.setItem(listId, item, included = false)) {
            TraktCustomListActionResult.Success -> Unit
            is TraktCustomListActionResult.Failure -> mutableFailure.value = result.reason
        }
    }

    fun deleteList(listId: Long) = perform {
        when (val result = customLists.deleteList(listId)) {
            TraktCustomListActionResult.Success -> Unit
            is TraktCustomListActionResult.Failure -> mutableFailure.value = result.reason
        }
    }

    fun moveList(listId: Long, direction: Int) = perform {
        val current = state.value.lists
        val from = current.indexOfFirst { it.id == listId }
        val to = from + direction
        if (from < 0 || to !in current.indices) return@perform
        val reordered = current.toMutableList().apply {
            add(to, removeAt(from))
        }
        when (val result = customLists.reorderLists(reordered.map { it.id })) {
            TraktCustomListActionResult.Success -> Unit
            is TraktCustomListActionResult.Failure -> mutableFailure.value = result.reason
        }
    }

    fun moveItem(listId: Long, item: com.streamvault.domain.trakt.TraktCustomListItem, direction: Int) = perform {
        val current = state.value.itemsByList[listId].orEmpty()
        val from = current.indexOfFirst { it.identity == item.identity }
        val to = from + direction
        if (from < 0 || to !in current.indices) return@perform
        val reordered = current.toMutableList().apply { add(to, removeAt(from)) }
        when (val result = customLists.reorderItems(listId, reordered)) {
            TraktCustomListActionResult.Success -> Unit
            is TraktCustomListActionResult.Failure -> mutableFailure.value = result.reason
        }
    }

    fun clearFailure() {
        mutableFailure.value = null
    }

    private fun perform(action: suspend () -> Unit) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableFailure.value = null
            try { action() } finally { mutableBusy.value = false }
        }
    }
}

@Composable
internal fun TraktCustomListsSettings() {
    val viewModel: TraktCustomListsSettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val accountState by viewModel.accountState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val failure by viewModel.failure.collectAsState()
    var selectedListId by remember { mutableStateOf<Long?>(null) }
    var createExpanded by remember { mutableStateOf(false) }
    var listName by remember { mutableStateOf("") }
    var listDescription by remember { mutableStateOf("") }
    var deleteConfirmation by remember { mutableStateOf<TraktCustomList?>(null) }

    LaunchedEffect(accountState.connected, accountState.username) {
        viewModel.refreshIfAccountChanged(accountState.username, accountState.connected)
    }
    LaunchedEffect(state.failure) {
        if (state.failure == null) viewModel.clearFailure()
    }

    Column(
        modifier = Modifier.fillMaxWidth().settingsDetailPanel().padding(SettingsDesignTokens.space16),
        verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4)) {
            Text(
                stringResource(R.string.trakt_custom_lists_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            Text(stringResource(R.string.trakt_custom_lists_description), color = AppColors.TextSecondary)
        }
        TraktListSettingsAction(
            label = stringResource(R.string.trakt_custom_lists_refresh),
            onClick = viewModel::refresh,
            enabled = !busy,
        )
        TraktListSettingsAction(
            label = stringResource(if (createExpanded) R.string.settings_cancel else R.string.trakt_custom_lists_create),
            onClick = { createExpanded = !createExpanded },
            enabled = !busy,
            emphasized = !createExpanded,
        )

        if (createExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvOutlinedTextField(
                    value = listName,
                    onValueChange = { listName = it },
                    label = {
                        Text(
                            stringResource(R.string.trakt_custom_lists_name),
                            color = AppColors.TextSecondary,
                        )
                    },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.widthIn(max = 520.dp),
                    colors = { containerFocused -> traktListTextFieldColors(containerFocused) },
                )
                TvOutlinedTextField(
                    value = listDescription,
                    onValueChange = { listDescription = it },
                    label = {
                        Text(
                            stringResource(R.string.trakt_custom_lists_optional_description),
                            color = AppColors.TextSecondary,
                        )
                    },
                    minLines = 2,
                    maxLines = 3,
                    enabled = !busy,
                    modifier = Modifier.widthIn(max = 520.dp),
                    colors = { containerFocused -> traktListTextFieldColors(containerFocused) },
                )
                Text(stringResource(R.string.trakt_custom_lists_private_note), color = AppColors.TextSecondary)
                Button(
                    onClick = {
                        viewModel.create(listName, listDescription)
                        listName = ""
                        listDescription = ""
                        createExpanded = false
                    },
                    enabled = !busy && listName.isNotBlank(),
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.Brand,
                        contentColor = AppColors.OnPrimary,
                        disabledContainerColor = AppColors.SurfaceEmphasis,
                        disabledContentColor = AppColors.TextSecondary,
                    ),
                ) { Text(stringResource(R.string.trakt_custom_lists_create)) }
            }
        }

        when {
            !state.listsLoaded -> Text(
                stringResource(if (busy || state.refreshingLists) R.string.trakt_custom_lists_loading
                    else R.string.trakt_custom_lists_not_loaded),
                color = AppColors.TextSecondary,
            )
            state.lists.isEmpty() -> Text(stringResource(R.string.trakt_custom_lists_empty), color = AppColors.TextSecondary)
            else -> state.lists.forEachIndexed { index, list ->
                CustomListRow(
                    list = list,
                    expanded = selectedListId == list.id,
                    state = state,
                    busy = busy,
                    onClick = {
                        if (selectedListId == list.id) {
                            selectedListId = null
                        } else {
                            selectedListId = list.id
                            viewModel.openList(list.id)
                        }
                    },
                    onRemove = { item -> viewModel.remove(list.id, item) },
                    onDelete = { deleteConfirmation = list },
                    canMoveUp = index > 0,
                    canMoveDown = index < state.lists.lastIndex,
                    onMoveUp = { viewModel.moveList(list.id, -1) },
                    onMoveDown = { viewModel.moveList(list.id, 1) },
                    onMoveItemUp = { item -> viewModel.moveItem(list.id, item, -1) },
                    onMoveItemDown = { item -> viewModel.moveItem(list.id, item, 1) },
                )
            }
        }

        if (failure != null) {
            Text(
                stringResource(when (failure) {
                    TraktCustomListsFailure.DISCONNECTED -> R.string.trakt_custom_lists_connect_first
                    TraktCustomListsFailure.INCOGNITO -> R.string.trakt_custom_lists_incognito
                    TraktCustomListsFailure.READ_LIMIT -> R.string.trakt_custom_lists_too_large
                    else -> R.string.trakt_custom_lists_failed
                }),
                color = AppColors.Warning,
            )
        }
    }

    deleteConfirmation?.let { list ->
        PremiumDialog(
            title = stringResource(R.string.trakt_custom_lists_delete_title),
            subtitle = stringResource(R.string.trakt_custom_lists_delete_message, list.name),
            onDismissRequest = { if (!busy) deleteConfirmation = null },
            widthFraction = 0.46f,
            content = {},
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = { deleteConfirmation = null },
                    enabled = !busy,
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.trakt_custom_lists_delete),
                    onClick = {
                        viewModel.deleteList(list.id)
                        deleteConfirmation = null
                        if (selectedListId == list.id) selectedListId = null
                    },
                    enabled = !busy,
                    destructive = true,
                )
            },
        )
    }
}

@Composable
private fun CustomListRow(
    list: TraktCustomList,
    expanded: Boolean,
    state: TraktCustomListsState,
    busy: Boolean,
    onClick: () -> Unit,
    onRemove: (com.streamvault.domain.trakt.TraktCustomListItem) -> Unit,
    onDelete: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveItemUp: (com.streamvault.domain.trakt.TraktCustomListItem) -> Unit,
    onMoveItemDown: (com.streamvault.domain.trakt.TraktCustomListItem) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().settingsDetailPanel().padding(SettingsDesignTokens.space12),
        verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onClick,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = AppColors.SurfaceEmphasis,
                    contentColor = AppColors.TextPrimary,
                ),
            ) {
                Text(stringResource(R.string.trakt_custom_list_open_with_privacy,
                    list.name, list.itemCount, list.privacy))
            }
            Button(onClick = onMoveUp, enabled = !busy && canMoveUp, colors = traktListManageButtonColors()) {
                Text(stringResource(R.string.trakt_custom_lists_move_up))
            }
            Button(onClick = onMoveDown, enabled = !busy && canMoveDown, colors = traktListManageButtonColors()) {
                Text(stringResource(R.string.trakt_custom_lists_move_down))
            }
            Button(onClick = onDelete, enabled = !busy, colors = traktListManageButtonColors()) {
                Text(stringResource(R.string.trakt_custom_lists_delete))
            }
        }
        if (!expanded) return
        if (list.id in state.loadingItems) {
            Text(stringResource(R.string.trakt_custom_lists_loading), color = AppColors.TextSecondary)
        } else if (state.itemsLoaded(list.id)) {
            val items = state.itemsByList[list.id].orEmpty()
            val canReorderItems = items.isNotEmpty() && items.all { it.listItemId != null } &&
                items.mapNotNull { it.listItemId }.distinct().size == items.size
            if (items.isEmpty()) {
                Text(stringResource(R.string.trakt_custom_lists_no_items), color = AppColors.TextSecondary)
            } else {
                items.take(200).forEachIndexed { index, item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = listOfNotNull(item.title, item.year?.toString()).joinToString(" · "),
                            color = AppColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (canReorderItems) {
                            Button(
                                onClick = { onMoveItemUp(item) },
                                enabled = !busy && index > 0,
                                colors = traktListManageButtonColors(),
                            ) { Text(stringResource(R.string.trakt_custom_lists_move_up)) }
                            Button(
                                onClick = { onMoveItemDown(item) },
                                enabled = !busy && index < items.lastIndex,
                                colors = traktListManageButtonColors(),
                            ) { Text(stringResource(R.string.trakt_custom_lists_move_down)) }
                        }
                        Button(
                            onClick = { onRemove(item) },
                            enabled = !busy,
                            colors = ButtonDefaults.colors(
                                containerColor = AppColors.SurfaceEmphasis,
                                contentColor = AppColors.TextPrimary,
                                disabledContainerColor = AppColors.SurfaceEmphasis.copy(alpha = 0.6f),
                                disabledContentColor = AppColors.TextSecondary,
                            ),
                        ) {
                            Text(stringResource(R.string.trakt_custom_list_remove_item))
                        }
                    }
                }
                if (items.size > 200) Text(
                    stringResource(R.string.trakt_custom_list_items_truncated),
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun TraktListSettingsAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    SettingsActionSurface(onClick = onClick, enabled = enabled) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsDesignTokens.space16, vertical = SettingsDesignTokens.space12),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled && emphasized) colors.accent else colors.primaryText,
        )
    }
}

@Composable
private fun traktListManageButtonColors() = ButtonDefaults.colors(
    containerColor = AppColors.SurfaceEmphasis,
    contentColor = AppColors.TextPrimary,
    disabledContainerColor = AppColors.SurfaceEmphasis.copy(alpha = 0.5f),
    disabledContentColor = AppColors.TextTertiary,
)

@Composable
private fun traktListTextFieldColors(containerFocused: Boolean) = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = AppColors.TextPrimary,
    unfocusedTextColor = AppColors.TextPrimary,
    disabledTextColor = AppColors.TextDisabled,
    cursorColor = AppColors.Brand,
    focusedBorderColor = AppColors.Brand,
    unfocusedBorderColor = if (containerFocused) AppColors.Brand else AppColors.Outline,
    disabledBorderColor = AppColors.Outline.copy(alpha = 0.5f),
    focusedLabelColor = AppColors.Brand,
    unfocusedLabelColor = AppColors.TextSecondary,
    disabledLabelColor = AppColors.TextTertiary,
    focusedContainerColor = AppColors.Surface,
    unfocusedContainerColor = AppColors.Surface,
    disabledContainerColor = AppColors.Surface,
)
