package com.streamvault.feature.live.sports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import androidx.tv.material3.ButtonDefaults
import androidx.compose.ui.text.style.TextOverflow
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.feature.live.R

@Composable
internal fun SportsDiscoverySearch(
    query: String,
    onQuery: (String) -> Unit,
    onOpenInclusions: () -> Unit,
    filters: @Composable () -> Unit,
) {
    var searchOpen by remember { mutableStateOf(false) }
    var acceptsInput by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchLabel = stringResource(R.string.live_sports_search_events)
    LaunchedEffect(acceptsInput) {
        if (acceptsInput) {
            withFrameNanos { }
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { filters() }
            TvButton(
                onClick = { searchOpen = !searchOpen; acceptsInput = searchOpen },
                modifier = Modifier.height(34.dp),
                scale = ButtonDefaults.scale(focusedScale = 1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(searchLabel, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TvButton(
                onClick = onOpenInclusions,
                modifier = Modifier.height(34.dp),
                scale = ButtonDefaults.scale(focusedScale = 1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.live_sports_inclusions), fontSize = 11.sp, maxLines = 1)
            }
        }
        if (searchOpen || query.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = query, onValueChange = onQuery, singleLine = true,
                        label = { Text(searchLabel, color = BrowseColors.TextSecondary) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocusRequester)
                            .focusProperties { canFocus = acceptsInput }
                            .onFocusChanged { focusState ->
                                val lostFocus = inputFocused && !focusState.isFocused
                                inputFocused = focusState.isFocused
                                if (lostFocus) {
                                    acceptsInput = false
                                    keyboardController?.hide()
                                }
                            },
                        readOnly = !acceptsInput,
                        shape = BrowseShapes.Control,
                        colors = sportsSearchColors(),
                    )
                    if (!acceptsInput) {
                        TvClickableSurface(
                            onClick = { acceptsInput = true },
                            modifier = Modifier
                                .matchParentSize()
                                .semantics {
                                    contentDescription = searchLabel
                                },
                            shape = ClickableSurfaceDefaults.shape(BrowseShapes.Control),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                contentColor = Color.Transparent,
                                focusedContentColor = Color.Transparent,
                            ),
                            border = ClickableSurfaceDefaults.border(
                                border = Border(BorderStroke(0.dp, Color.Transparent), shape = BrowseShapes.Control),
                                focusedBorder = Border(BorderStroke(2.dp, BrowseColors.Focus), shape = BrowseShapes.Control),
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        ) { }
                    }
                }
                if (query.isNotEmpty()) TvButton(onClick = { onQuery("") }) { Text(stringResource(R.string.epg_clear_search)) }
            }
        }
    }
}

@Composable
internal fun SportsInclusionsDialog(
    state: SportsHubUiState,
    onSport: (SportType, Boolean) -> Unit,
    onLeague: (String, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var leagueQuery by remember { mutableStateOf("") }
    val localizedLeagueNames = sportsLeagueLabels()
    val leagues = (state.excludedLeagues.associateWith { it } + state.knownLeagues)
        .mapValues { (id, name) -> localizedLeagueNames[id] ?: name }.entries
        .filter { SportsTextNormalizer.normalize(it.value).contains(SportsTextNormalizer.normalize(leagueQuery)) }
        .sortedBy { it.value }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(24.dp).widthIn(max = 880.dp).fillMaxHeight(0.92f),
            colors = SurfaceDefaults.colors(containerColor = BrowseColors.SurfaceElevated),
            shape = BrowseShapes.Panel,
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.live_sports_inclusions), fontSize = 22.sp, color = BrowseColors.TextPrimary)
                Text(stringResource(R.string.live_sports_inclusions_hint), fontSize = 12.sp, color = BrowseColors.TextSecondary)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(0.4f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.nav_sports), color = BrowseColors.TextPrimary)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(4.dp)) {
                            items(SportType.entries.filter { it != SportType.UNKNOWN }, key = { it.name }) { sport ->
                                InclusionRow(stringResource(sport.labelResource()), sport.discoveryKey !in state.excludedSports) {
                                    onSport(sport, sport.discoveryKey in state.excludedSports)
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(0.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.live_sports_leagues), color = BrowseColors.TextPrimary)
                        OutlinedTextField(
                            value = leagueQuery, onValueChange = { leagueQuery = it }, singleLine = true,
                            label = { Text(stringResource(R.string.live_sports_search_leagues), color = BrowseColors.TextSecondary) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = sportsSearchColors(),
                        )
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(4.dp)) {
                            items(leagues, key = { it.key }) { league ->
                                InclusionRow(league.value, league.key !in state.excludedLeagues) {
                                    onLeague(league.key, league.key in state.excludedLeagues)
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(onClick = onReset) { Text(stringResource(R.string.hidden_categories_dialog_unhide_all)) }
                    Spacer(Modifier.weight(1f))
                    TvButton(onClick = onDismiss) { Text(stringResource(R.string.hidden_categories_dialog_close)) }
                }
            }
        }
    }
}

@Composable
private fun sportsSearchColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = BrowseColors.TextPrimary,
    unfocusedTextColor = BrowseColors.TextPrimary,
    cursorColor = BrowseColors.Brand,
    focusedBorderColor = BrowseColors.Brand,
    unfocusedBorderColor = BrowseColors.TextSecondary,
)

@Composable
private fun InclusionRow(label: String, included: Boolean, onClick: () -> Unit) {
    TvClickableSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        toggleableState = if (included) ToggleableState.On else ToggleableState.Off
    }) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(included, onCheckedChange = null, colors = CheckboxDefaults.colors(
                checkedColor = BrowseColors.Brand, uncheckedColor = BrowseColors.TextSecondary,
            ))
            Text(label, color = BrowseColors.TextPrimary, modifier = Modifier.padding(start = 6.dp), maxLines = 2)
        }
    }
}

private fun SportType.labelResource(): Int = when (this) {
    SportType.FOOTBALL -> R.string.live_sports_filter_football
    SportType.BASKETBALL -> R.string.live_sports_filter_basketball
    SportType.MOTORSPORT -> R.string.live_sports_filter_motorsport
    SportType.AMERICAN_FOOTBALL -> R.string.live_sports_american_football
    SportType.BASEBALL -> R.string.live_sports_baseball
    SportType.HOCKEY -> R.string.live_sports_hockey
    SportType.TENNIS -> R.string.live_sports_tennis
    SportType.MMA -> R.string.live_sports_mma
    SportType.BOXING -> R.string.live_sports_boxing
    SportType.CRICKET -> R.string.live_sports_cricket
    SportType.RUGBY -> R.string.live_sports_rugby
    SportType.GOLF -> R.string.live_sports_golf
    SportType.VOLLEYBALL -> R.string.live_sports_volleyball
    SportType.HANDBALL -> R.string.live_sports_handball
    SportType.SNOOKER -> R.string.live_sports_snooker
    SportType.DARTS -> R.string.live_sports_darts
    SportType.CYCLING -> R.string.live_sports_cycling
    SportType.OTHER, SportType.UNKNOWN -> R.string.live_sports_filter_other
}
