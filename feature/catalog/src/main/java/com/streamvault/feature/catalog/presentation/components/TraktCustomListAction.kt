package com.streamvault.feature.catalog.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.components.TvOutlinedTextField
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.trakt.TraktCustomListActionResult
import com.streamvault.domain.trakt.TraktCustomListItem
import com.streamvault.domain.trakt.TraktCustomListItemType
import com.streamvault.domain.trakt.TraktCustomListItemsRefreshResult
import com.streamvault.domain.trakt.TraktCustomListRefreshResult
import com.streamvault.domain.trakt.TraktCustomLists
import com.streamvault.domain.trakt.TraktCustomListsFailure
import com.streamvault.feature.catalog.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TraktCustomListActionViewModel @Inject constructor(
    private val customLists: TraktCustomLists,
) : ViewModel() {
    val state = customLists.state
    private val mutableOperation = MutableStateFlow(false)
    val operation = mutableOperation.asStateFlow()
    private val mutableFailure = MutableStateFlow<TraktCustomListsFailure?>(null)
    val failure = mutableFailure.asStateFlow()

    fun refreshLists() = runOperation {
        when (val result = customLists.refreshLists()) {
            is TraktCustomListRefreshResult.Success -> Unit
            is TraktCustomListRefreshResult.Failure -> mutableFailure.value = result.reason
        }
    }

    fun refreshItems(listId: Long) = runOperation {
        when (val result = customLists.refreshItems(listId)) {
            is TraktCustomListItemsRefreshResult.Success -> Unit
            is TraktCustomListItemsRefreshResult.Failure -> mutableFailure.value = result.reason
        }
    }

    fun setItem(listId: Long, item: TraktCustomListItem, included: Boolean) = runOperation {
        when (val result = customLists.setItem(listId, item, included)) {
            TraktCustomListActionResult.Success -> mutableFailure.value = null
            is TraktCustomListActionResult.Failure -> mutableFailure.value = result.reason
        }
    }

    fun createList(name: String, description: String, onCreated: () -> Unit) = runOperation {
        when (val result = customLists.createList(name, description.takeIf(String::isNotBlank))) {
            TraktCustomListActionResult.Success -> {
                onCreated()
                when (val refresh = customLists.refreshLists(force = true)) {
                    is TraktCustomListRefreshResult.Success -> Unit
                    is TraktCustomListRefreshResult.Failure -> mutableFailure.value = refresh.reason
                }
            }
            is TraktCustomListActionResult.Failure -> mutableFailure.value = result.reason
        }
    }

    private fun runOperation(action: suspend () -> Unit) {
        if (mutableOperation.value) return
        viewModelScope.launch {
            mutableOperation.value = true
            mutableFailure.value = null
            try { action() } finally { mutableOperation.value = false }
        }
    }
}

@Composable
internal fun TraktCustomListActionButton(
    item: TraktCustomListItem?,
    additionalItems: List<TraktCustomListItem> = emptyList(),
) {
    if (item == null) return
    val viewModel: TraktCustomListActionViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val operation by viewModel.operation.collectAsState()
    val failure by viewModel.failure.collectAsState()
    var dialogOpen by remember(item.identity) { mutableStateOf(false) }
    var selectedListId by remember(item.identity) { mutableStateOf<Long?>(null) }
    var selectedItem by remember(item.identity) {
        mutableStateOf(if (additionalItems.isEmpty()) item else null)
    }
    var choosingItem by remember(item.identity) { mutableStateOf(additionalItems.isNotEmpty()) }
    var selectedItemType by remember(item.identity) { mutableStateOf<TraktCustomListItemType?>(null) }
    var creatingList by remember(item.identity) { mutableStateOf(false) }
    var listName by remember(item.identity) { mutableStateOf("") }
    var listDescription by remember(item.identity) { mutableStateOf("") }

    DetailActionButton(
        label = stringResource(R.string.trakt_custom_lists_action),
        onClick = {
            dialogOpen = true
            selectedListId = null
            selectedItem = if (additionalItems.isEmpty()) item else null
            choosingItem = additionalItems.isNotEmpty()
            selectedItemType = null
            creatingList = false
            listName = ""
            listDescription = ""
            viewModel.refreshLists()
        },
        enabled = state.connected && !operation,
        compact = true,
    )

    if (!dialogOpen) return
    val selectedList = selectedListId?.let(state::list)
    val targetItem = selectedItem
    PremiumDialog(
        title = selectedList?.name ?: stringResource(R.string.trakt_custom_lists_title),
        subtitle = targetItem?.let { stringResource(R.string.trakt_custom_lists_for_item, it.title) },
        onDismissRequest = { if (!operation) dialogOpen = false },
        widthFraction = 0.58f,
        smallScreenWidthFraction = 0.94f,
        bodyHeightFraction = 0.66f,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                if (state.refreshingLists || selectedListId?.let { it in state.loadingItems } == true || operation) {
                    Text(stringResource(R.string.trakt_custom_lists_loading), color = AppColors.TextSecondary)
                }
                if (!state.connected && !state.refreshingLists) {
                    Text(stringResource(R.string.trakt_custom_lists_connect_first), color = AppColors.TextSecondary)
                } else if (choosingItem) {
                    val choices = (listOf(item) + additionalItems).distinctBy { it.identity }
                    if (selectedItemType == null) {
                        Text(stringResource(R.string.trakt_custom_lists_choose_item), color = AppColors.TextSecondary)
                        choices.map { it.identity.type }.distinct().forEach { type ->
                            val count = choices.count { it.identity.type == type }
                            TvButton(
                                onClick = {
                                    if (count == 1) {
                                        selectedItem = choices.single { it.identity.type == type }
                                        choosingItem = false
                                    } else selectedItemType = type
                                },
                                enabled = !operation,
                                colors = traktNeutralButtonColors(),
                            ) {
                                val label = when (type) {
                                    TraktCustomListItemType.MOVIE -> stringResource(R.string.trakt_custom_lists_movies)
                                    TraktCustomListItemType.SHOW -> stringResource(R.string.trakt_custom_lists_series)
                                    TraktCustomListItemType.SEASON -> stringResource(R.string.trakt_custom_lists_seasons_count, count)
                                    TraktCustomListItemType.EPISODE -> stringResource(R.string.trakt_custom_lists_episodes_count, count)
                                }
                                Text(label)
                            }
                        }
                    } else {
                        val choicesOfType = choices.filter { it.identity.type == selectedItemType }
                        choicesOfType.forEach { choice ->
                            TvButton(
                                onClick = {
                                    selectedItem = choice
                                    choosingItem = false
                                    selectedItemType = null
                                },
                                enabled = !operation,
                                colors = traktNeutralButtonColors(),
                            ) { Text(choice.title) }
                        }
                        TvButton(
                            onClick = { selectedItemType = null },
                            enabled = !operation,
                            colors = traktNeutralButtonColors(),
                        ) { Text(stringResource(R.string.trakt_custom_lists_back)) }
                    }
                } else if (creatingList) {
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
                        enabled = !operation,
                        modifier = Modifier.fillMaxWidth(),
                        colors = { containerFocused -> traktCustomListTextFieldColors(containerFocused) },
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
                        enabled = !operation,
                        modifier = Modifier.fillMaxWidth(),
                        colors = { containerFocused -> traktCustomListTextFieldColors(containerFocused) },
                    )
                    Text(stringResource(R.string.trakt_custom_lists_private_note), color = AppColors.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(
                            onClick = { creatingList = false },
                            enabled = !operation,
                            colors = traktNeutralButtonColors(),
                        ) {
                            Text(stringResource(R.string.trakt_custom_lists_back))
                        }
                        TvButton(
                            onClick = {
                                viewModel.createList(listName.trim(), listDescription) {
                                    creatingList = false
                                    listName = ""
                                    listDescription = ""
                                }
                            },
                            enabled = !operation && listName.isNotBlank(),
                            colors = ButtonDefaults.colors(
                                containerColor = AppColors.Brand,
                                contentColor = AppColors.OnPrimary,
                                disabledContainerColor = AppColors.SurfaceEmphasis,
                                disabledContentColor = AppColors.TextSecondary,
                            ),
                        ) {
                            Text(stringResource(R.string.trakt_custom_lists_create))
                        }
                    }
                } else if (selectedItem != null && selectedListId == null) {
                    if (state.listsLoaded && state.lists.isEmpty()) {
                        Text(stringResource(R.string.trakt_custom_lists_empty), color = AppColors.TextSecondary)
                    }
                    state.lists.forEach { list ->
                        TvButton(
                            onClick = {
                                selectedListId = list.id
                                viewModel.refreshItems(list.id)
                            },
                            enabled = !operation,
                            colors = ButtonDefaults.colors(
                                containerColor = AppColors.SurfaceEmphasis,
                                contentColor = AppColors.TextPrimary,
                            ),
                        ) {
                            Text(stringResource(R.string.trakt_custom_list_open, list.name, list.itemCount))
                        }
                    }
                    TvButton(
                        onClick = { creatingList = true },
                        enabled = !operation,
                        colors = ButtonDefaults.colors(
                            containerColor = AppColors.Brand,
                            contentColor = AppColors.OnPrimary,
                            disabledContainerColor = AppColors.SurfaceEmphasis,
                            disabledContentColor = AppColors.TextSecondary,
                        ),
                    ) {
                        Text(stringResource(R.string.trakt_custom_lists_create_new))
                    }
                } else if (selectedItem != null && selectedList != null) {
                    val activeItem = requireNotNull(selectedItem)
                    val itemsLoaded = state.itemsLoaded(selectedList.id)
                    val containsTarget = itemsLoaded && state.contains(selectedList.id, activeItem.identity)
                    if (itemsLoaded) {
                        Text(
                            stringResource(
                                if (containsTarget) R.string.trakt_custom_list_contains_item
                                else R.string.trakt_custom_list_does_not_contain_item,
                                activeItem.title,
                            ),
                            color = AppColors.TextSecondary,
                        )
                        TvButton(
                            onClick = { viewModel.setItem(selectedList.id, activeItem, !containsTarget) },
                            enabled = !operation && (selectedList.id to activeItem.identity) !in state.updatingItems,
                            colors = ButtonDefaults.colors(
                                containerColor = AppColors.Brand,
                                contentColor = AppColors.OnPrimary,
                            ),
                        ) {
                            Text(stringResource(
                                if (containsTarget) R.string.trakt_custom_list_remove_item
                                else R.string.trakt_custom_list_add_item,
                            ))
                        }
                        state.itemsByList[selectedList.id].orEmpty().take(200).forEach { listedItem ->
                            if (listedItem.identity == activeItem.identity) return@forEach
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = listOfNotNull(listedItem.title, listedItem.year?.toString())
                                        .joinToString(" · "),
                                    color = AppColors.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                TvButton(
                                    onClick = { viewModel.setItem(selectedList.id, listedItem, false) },
                                    enabled = !operation,
                                    colors = traktNeutralButtonColors(),
                                ) { Text(stringResource(R.string.trakt_custom_list_remove_item)) }
                            }
                        }
                        if (state.itemsByList[selectedList.id].orEmpty().size > 200) {
                            Text(stringResource(R.string.trakt_custom_list_items_truncated), color = AppColors.TextSecondary)
                        }
                    }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton(
                                onClick = { selectedListId = null },
                                enabled = !operation,
                                colors = traktNeutralButtonColors(),
                            ) {
                                Text(stringResource(R.string.trakt_custom_lists_back))
                            }
                            if (additionalItems.isNotEmpty()) {
                                TvButton(
                                    onClick = {
                                        selectedListId = null
                                        selectedItem = null
                                        choosingItem = true
                                    },
                                    enabled = !operation,
                                    colors = traktNeutralButtonColors(),
                                ) { Text(stringResource(R.string.trakt_custom_lists_change_item)) }
                            }
                    }
                }

                if (failure != null) {
                    Text(
                        text = stringResource(
                            when (failure) {
                                TraktCustomListsFailure.DISCONNECTED -> R.string.trakt_custom_lists_connect_first
                                TraktCustomListsFailure.INCOGNITO -> R.string.trakt_custom_lists_incognito
                                TraktCustomListsFailure.READ_LIMIT -> R.string.trakt_custom_lists_too_large
                                else -> R.string.trakt_custom_lists_failed
                            },
                        ),
                        color = AppColors.Warning,
                    )
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = { dialogOpen = false },
                enabled = !operation,
            )
        },
    )
}

@Composable
private fun traktCustomListTextFieldColors(containerFocused: Boolean) = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
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

@Composable
private fun traktNeutralButtonColors() = ButtonDefaults.colors(
    containerColor = AppColors.SurfaceEmphasis,
    contentColor = AppColors.TextPrimary,
    disabledContainerColor = AppColors.SurfaceEmphasis.copy(alpha = 0.6f),
    disabledContentColor = AppColors.TextSecondary,
)
