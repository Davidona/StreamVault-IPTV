@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.streamvault.feature.live.sports

import com.streamvault.core.ui.layout.AdaptiveRow

import com.streamvault.core.ui.layout.isPortraitLayout

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.streamvault.domain.sports.provider.ProviderGameState
import com.streamvault.domain.sports.provider.ProviderAttributionFormat
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.contentOnColor
import com.streamvault.core.ui.image.ChannelLogoBadge
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import androidx.tv.material3.ClickableSurfaceDefaults
import com.streamvault.domain.model.Channel
import com.streamvault.feature.live.R
import com.streamvault.feature.live.api.LiveChannelPlaybackRequest
import com.streamvault.feature.live.api.LiveHomeScaffoldContent
import com.streamvault.feature.live.navigation.LiveRoutePatterns
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.launch

private data class SportsLeagueFilterOption(val id: String, val label: String)

@Composable
fun SportsHubScreen(
    onPlaybackRequested: (LiveChannelPlaybackRequest) -> Unit,
    scaffold: LiveHomeScaffoldContent,
) {
    val viewModel: SportsHubViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    scaffold(LiveRoutePatterns.SPORTS, stringResource(R.string.nav_sports), null) {
        SportsHubContent(
            state = state,
            onRetry = viewModel::retry,
            onSearchQueryChanged = viewModel::setSearchQuery,
            onSetSportIncluded = viewModel::setSportIncluded,
            onSetLeagueIncluded = viewModel::setLeagueIncluded,
            onResetInclusions = viewModel::resetInclusions,
            onSelectTimeRange = viewModel::selectTimeRange,
            onSelectSportFilter = viewModel::selectSportFilter,
            onSelectLeagueFilter = viewModel::selectLeagueFilter,
            onSearchChannels = viewModel::searchChannels,
            onRememberChannelForEvent = viewModel::rememberChannelForEvent,
            onForgetChannelForEvent = viewModel::forgetChannelForEvent,
            onLoadStandings = viewModel::loadStandings,
            onLoadExtraDetails = viewModel::loadExtraDetails,
            onPlayBroadcast = { broadcast ->
                onPlaybackRequested(
                    LiveChannelPlaybackRequest(
                        channel = broadcast.channel,
                        categoryId = broadcast.channel.categoryId,
                        providerId = broadcast.channel.providerId,
                        isVirtual = false,
                        combinedProfileId = broadcast.combinedProfileId,
                        combinedSourceFilterProviderId = broadcast.combinedProfileId
                            ?.let { broadcast.channel.providerId },
                        returnRoute = LiveRoutePatterns.SPORTS,
                    ),
                )
            },
        )
    }
}

@Composable
internal fun SportsHubContent(
    state: SportsHubUiState,
    onRetry: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSetSportIncluded: (SportType, Boolean) -> Unit,
    onSetLeagueIncluded: (String, Boolean) -> Unit,
    onResetInclusions: () -> Unit,
    onSelectTimeRange: (SportsTimeRange) -> Unit,
    onSelectSportFilter: (SportsSportFilter) -> Unit,
    onSelectLeagueFilter: (String?) -> Unit,
    onSearchChannels: suspend (SportsEvent, String) -> List<SportsBroadcast>,
    onRememberChannelForEvent: suspend (SportsEvent, SportsBroadcast) -> Boolean,
    onForgetChannelForEvent: suspend (SportsEvent) -> Unit,
    onLoadStandings: suspend (SportsEvent) -> LoadedSportsStandings?,
    onLoadExtraDetails: suspend (SportsEvent) -> LoadedSportsEventExtraDetails?,
    onPlayBroadcast: (SportsBroadcast) -> Unit,
) {
    var detailsEvent by remember { mutableStateOf<SportsEvent?>(null) }
    var manualPickerEvent by remember { mutableStateOf<SportsEvent?>(null) }
    val actionScope = rememberCoroutineScope()
    var inclusionsOpen by remember { mutableStateOf(false) }
    val visibleEvents = state.events.filter { it.matchesDiscovery(state) }
    val localizedLeagueNames = sportsLeagueLabels()
    val availableLeagues = state.events.asSequence()
        .filter { it.matches(state.selectedSportFilter) && it.sport.discoveryKey !in state.excludedSports &&
            it.leagueFilterKey !in state.excludedLeagues }
        .mapNotNull { event -> event.leagueFilterKey?.let { key ->
            SportsLeagueFilterOption(key, event.localizedLeagueName(localizedLeagueNames).orEmpty())
        } }
        .filter { it.label.isNotBlank() }
        .distinctBy(SportsLeagueFilterOption::id)
        .sortedBy { it.label }
        .toList()
    val fallbackLeague = stringResource(R.string.nav_sports)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "sports-header") {
            AdaptiveRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.nav_sports), fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, color = BrowseColors.TextPrimary)
                if (!isPortraitLayout()) Spacer(Modifier.weight(1f))
                SportsTimeRangeFilters(state.selectedTimeRange, onSelectTimeRange)
            }
        }
        item(key = "sports-filters") {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(BrowseShapes.Panel)
                    .background(BrowseColors.Surface.copy(alpha = 0.65f))
                    .border(1.dp, BrowseColors.Outline, BrowseShapes.Panel)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SportsDiscoverySearch(state.searchQuery, onSearchQueryChanged, onOpenInclusions = { inclusionsOpen = true }) {
                    SportsSportFilters(state.selectedSportFilter, onSelectSportFilter)
                }
                if (availableLeagues.isNotEmpty()) {
                    SportsLeagueFilters(availableLeagues, state.selectedLeagueFilter, onSelectLeagueFilter)
                }
            }
        }
        when {
            state.isLoading -> item { SportsLoadingState() }
            state.hasError -> item { SportsErrorState(onRetry) }
            visibleEvents.isEmpty() -> item { SportsEmptyState() }
            else -> {
                val leagueGroups = visibleEvents.groupBy { event ->
                    event.leagueFilterKey ?: event.categoryLabel?.let {
                        "category:${SportsTextNormalizer.normalize(it)}"
                    } ?: "category:sports"
                }.map { (key, events) ->
                    val label = events.firstNotNullOfOrNull { it.localizedLeagueName(localizedLeagueNames) }
                        ?: events.firstNotNullOfOrNull { it.categoryLabel }
                        ?: fallbackLeague
                    Triple(key, label, events)
                }.sortedWith(compareBy<Triple<String, String, List<SportsEvent>>> {
                    it.third.none { event -> event.leagueInfo != null || event.league != null }
                }.thenByDescending { it.third.size })
                val leadEvents = leagueGroups.first().third
                val featured = leadEvents.firstOrNull { it.competitors.size == 2 } ?: leadEvents.first()
                item(key = "sports-featured") {
                    FeaturedSportsEvent(
                        event = featured,
                        isLive = featured.isLiveAt(state.nowMillis),
                        showDate = state.selectedTimeRange == SportsTimeRange.UPCOMING,
                        onShowDetails = { detailsEvent = it },
                        onPlayBroadcast = onPlayBroadcast,
                    )
                }
                leagueGroups.forEach { (leagueKey, league, events) ->
                    item(key = "league:$leagueKey") {
                        val attributionLines = events.maxOf { event ->
                            event.attributionCredits.sumOf { it.lineCount }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(league, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                color = BrowseColors.TextPrimary)
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().focusGroup(),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(events, key = SportsEvent::id) { event ->
                                    SportsEventCard(
                                        event = event,
                                        isLive = event.isLiveAt(state.nowMillis),
                                        showDate = state.selectedTimeRange == SportsTimeRange.UPCOMING,
                                        attributionLines = attributionLines,
                                        onShowDetails = { detailsEvent = it },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (inclusionsOpen) {
        SportsInclusionsDialog(state, onSetSportIncluded, onSetLeagueIncluded, onResetInclusions) { inclusionsOpen = false }
    }
    detailsEvent?.let { selected ->
        val event = state.events.firstOrNull { it.id == selected.id } ?: selected
        SportsEventDetailsDialog(
            event = event,
            nowMillis = state.nowMillis,
            onLoadStandings = onLoadStandings,
            onLoadExtraDetails = onLoadExtraDetails,
            onDismiss = { detailsEvent = null },
            onFindAnotherChannel = {
                detailsEvent = null
                manualPickerEvent = event
            },
            onForgetChoice = {
                actionScope.launch {
                    onForgetChannelForEvent(event)
                    detailsEvent = null
                }
            },
            onPlayBroadcast = { broadcast ->
                detailsEvent = null
                onPlayBroadcast(broadcast)
            },
        )
    }
    manualPickerEvent?.let { event ->
        SportsManualChannelPickerDialog(
            event = event,
            onDismiss = { manualPickerEvent = null },
            onSearchChannels = onSearchChannels,
            onUseForEvent = { broadcast ->
                actionScope.launch {
                    if (onRememberChannelForEvent(event, broadcast)) {
                        manualPickerEvent = null
                        onPlayBroadcast(broadcast)
                    }
                }
            },
        )
    }
}

@Composable
private fun SportsTimeRangeFilters(
    selected: SportsTimeRange,
    onSelected: (SportsTimeRange) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(3.dp).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SportsFilterButton(
            label = stringResource(R.string.live_sports_on_now),
            selected = selected == SportsTimeRange.ON_NOW,
            onClick = { onSelected(SportsTimeRange.ON_NOW) },
        )
        SportsFilterButton(
            label = stringResource(R.string.epg_day_today),
            selected = selected == SportsTimeRange.TODAY,
            onClick = { onSelected(SportsTimeRange.TODAY) },
        )
        SportsFilterButton(
            label = stringResource(R.string.live_sports_upcoming),
            selected = selected == SportsTimeRange.UPCOMING,
            onClick = { onSelected(SportsTimeRange.UPCOMING) },
        )
    }
}

@Composable
private fun SportsSportFilters(
    selected: SportsSportFilter,
    onSelected: (SportsSportFilter) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(3.dp).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SportsFilterButton(
            label = stringResource(R.string.home_quick_filters_all),
            selected = selected == SportsSportFilter.ALL,
            onClick = { onSelected(SportsSportFilter.ALL) },
        )
        SportsFilterButton(
            label = stringResource(R.string.live_sports_filter_football),
            selected = selected == SportsSportFilter.FOOTBALL,
            onClick = { onSelected(SportsSportFilter.FOOTBALL) },
        )
        SportsFilterButton(
            label = stringResource(R.string.live_sports_filter_basketball),
            selected = selected == SportsSportFilter.BASKETBALL,
            onClick = { onSelected(SportsSportFilter.BASKETBALL) },
        )
        SportsFilterButton(
            label = stringResource(R.string.live_sports_filter_motorsport),
            selected = selected == SportsSportFilter.MOTORSPORT,
            onClick = { onSelected(SportsSportFilter.MOTORSPORT) },
        )
        SportsFilterButton(
            label = stringResource(R.string.live_sports_filter_other),
            selected = selected == SportsSportFilter.OTHER,
            onClick = { onSelected(SportsSportFilter.OTHER) },
        )
    }
}

@Composable
private fun SportsLeagueFilters(
    leagues: List<SportsLeagueFilterOption>,
    selected: String?,
    onSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(3.dp).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SportsFilterButton(
            label = stringResource(R.string.live_sports_filter_all_leagues),
            selected = selected == null,
            onClick = { onSelected(null) },
        )
        leagues.forEach { league ->
            SportsFilterButton(
                label = league.label,
                selected = selected == league.id,
                onClick = { onSelected(league.id) },
            )
        }
    }
}

@Composable
private fun SportsFilterButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TvButton(
        onClick = onClick,
        modifier = Modifier.height(30.dp).semantics { this.selected = selected },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) BrowseColors.Brand.copy(alpha = 0.20f) else Color.Transparent,
            contentColor = if (selected) BrowseColors.BrandStrong else BrowseColors.TextSecondary,
            focusedContainerColor = BrowseColors.BrandStrong,
            focusedContentColor = contentOnColor(BrowseColors.BrandStrong),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
    ) {
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun FeaturedSportsEvent(
    event: SportsEvent,
    isLive: Boolean,
    showDate: Boolean,
    onShowDetails: (SportsEvent) -> Unit,
    onPlayBroadcast: (SportsBroadcast) -> Unit,
) {
    if (isPortraitLayout()) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(listOf(BrowseColors.SurfaceEmphasis, BrowseColors.Surface)))
                .border(1.dp, BrowseColors.Outline, RoundedCornerShape(12.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EventEyebrow(event, isLive)
            SportsFixture(event, featured = true, modifier = Modifier.fillMaxWidth().height(72.dp))
            Text(formatEventTime(event, showDate), fontSize = 12.sp, color = BrowseColors.TextSecondary)
            Text(sportsDescriptionForPresentation(event.description).ifBlank { event.title },
                fontSize = 12.sp, color = BrowseColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                event.broadcastsForPresentation().firstOrNull()?.let { broadcast ->
                    SportsChannelButton(channel = broadcast.channel, compact = true, onClick = { onPlayBroadcast(broadcast) })
                }
                TvButton(onClick = { onShowDetails(event) }) {
                    Text(stringResource(R.string.epg_program_details_show), fontSize = 12.sp)
                }
            }
            Text(stringResource(R.string.live_sports_channel_choices, event.broadcasts.size),
                fontSize = 11.sp, color = BrowseColors.TextTertiary)
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(180.dp + 18.dp * event.attributionCredits.sumOf { it.lineCount })
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(BrowseColors.SurfaceEmphasis, BrowseColors.Surface)))
            .border(1.dp, BrowseColors.Outline, RoundedCornerShape(12.dp))
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EventEyebrow(event, isLive)
            SportsFixture(event, featured = true, modifier = Modifier.weight(1f))
            Text(formatEventTime(event, showDate), fontSize = 12.sp, color = BrowseColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(BrowseColors.Outline))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(sportsDescriptionForPresentation(event.description).ifBlank { event.title }, fontSize = 12.sp,
                color = BrowseColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                event.broadcastsForPresentation().firstOrNull()?.let { broadcast ->
                    SportsChannelButton(channel = broadcast.channel, compact = true,
                        modifier = Modifier.weight(1f), onClick = { onPlayBroadcast(broadcast) })
                }
                TvButton(onClick = { onShowDetails(event) }, modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.epg_program_details_show), fontSize = 12.sp)
                }
            }
            Text(stringResource(R.string.live_sports_channel_choices, event.broadcasts.size),
                fontSize = 11.sp, color = BrowseColors.TextTertiary)
        }
    }
}

@Composable
private fun EventEyebrow(event: SportsEvent, isLive: Boolean) {
    val leagueLabel = event.localizedLeagueName(sportsLeagueLabels())
        ?: event.categoryLabel ?: stringResource(R.string.nav_sports)
    val sourcedStatus = event.provenance.status
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(leagueLabel,
                modifier = Modifier.weight(1f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = BrowseColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isLive) LiveBadge()
            sourcedStatus?.value?.let { status ->
                val liveDetail = listOfNotNull(status.period, status.clock, status.detail)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .joinToString(" · ")
                if (liveDetail.isNotEmpty()) {
                    Text(
                        liveDetail,
                        fontSize = 10.sp,
                        color = if (sourcedStatus?.isStale == true) BrowseColors.TextTertiary else BrowseColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        SportsProviderCredits(event)
    }
}

@Composable
private fun SportsProviderCredits(event: SportsEvent) {
    event.attributionCredits.forEach { attribution -> SportsAttributionLine(attribution) }
}

private data class SportsAttributionCredit(
    val sourceName: String,
    val format: ProviderAttributionFormat,
    val sourceUrl: String? = null,
    val licenseUrl: String? = null,
) {
    val lineCount: Int get() = if (format == ProviderAttributionFormat.ODBL) 2 else 1
}

private val SportsEvent.attributionCredits: List<SportsAttributionCredit>
    get() = (providerAttributions.map {
        SportsAttributionCredit(it.sourceName, it.format, it.sourceUrl, it.licenseUrl)
    } + listOfNotNull(metadataAttributionName?.let {
        SportsAttributionCredit(it, ProviderAttributionFormat.SOURCE_NAME)
    })).distinctBy(SportsAttributionCredit::sourceName)

@Composable
private fun SportsAttributionLine(
    attribution: SportsAttributionCredit,
    modifier: Modifier = Modifier,
    color: Color = BrowseColors.TextSecondary,
) {
    if (attribution.format == ProviderAttributionFormat.ODBL && attribution.licenseUrl != null) {
        val uriHandler = LocalUriHandler.current
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                attributionText(attribution),
                modifier = Modifier.clickable { attribution.sourceUrl?.let(uriHandler::openUri) },
                fontSize = 10.sp,
                color = color,
                textDecoration = TextDecoration.Underline,
            )
            Text(
                stringResource(R.string.live_sports_odbl_license_name),
                modifier = Modifier.clickable { uriHandler.openUri(attribution.licenseUrl) },
                fontSize = 10.sp,
                color = color,
                textDecoration = TextDecoration.Underline,
            )
        }
    } else {
        Text(attributionText(attribution), modifier = modifier, fontSize = 10.sp, color = color)
    }
}

@Composable
private fun attributionText(attribution: SportsAttributionCredit): String = when (attribution.format) {
    ProviderAttributionFormat.SOURCE_NAME, ProviderAttributionFormat.ODBL -> stringResource(
        R.string.live_sports_standings_attribution, attribution.sourceName,
    )
    ProviderAttributionFormat.FOOTBALL_DATA -> stringResource(
        R.string.live_sports_football_data_attribution, attribution.sourceName,
    )
}

private fun SportsEvent.isLiveAt(nowMillis: Long): Boolean =
    provenance.status?.value?.state == ProviderGameState.LIVE ||
        broadcastStatusAt(nowMillis) == SportsBroadcastStatus.ON_AIR

@Composable
internal fun sportsLeagueLabels(): Map<String, String> = mapOf(
    "efl-championship" to stringResource(R.string.live_sports_league_efl_championship),
    "brasileirao-serie-a" to stringResource(R.string.live_sports_league_brasileirao_serie_a),
    "brasileirao-serie-b" to stringResource(R.string.live_sports_league_brasileirao_serie_b),
    "copa-do-brasil" to stringResource(R.string.live_sports_league_copa_do_brasil),
    "copa-libertadores" to stringResource(R.string.live_sports_league_copa_libertadores),
    "copa-sul-americana" to stringResource(R.string.live_sports_league_copa_sul_americana),
)

private fun SportsEvent.localizedLeagueName(localizedLeagueNames: Map<String, String>): String? =
    leagueInfo?.id?.let(localizedLeagueNames::get) ?: league ?: leagueInfo?.displayName

internal fun SportsEvent.scoreFor(competitor: SportsCompetitor): String? {
    val scores = provenance.score?.value?.scores.orEmpty()
    return scores.firstOrNull {
        SportsAliasRegistry.normalizeTeamName(it.competitor.name) == competitor.canonicalName
    }?.value?.takeIf(String::isNotBlank)
}

@Composable
private fun SportsFixture(event: SportsEvent, featured: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (featured) 8.dp else 6.dp)) {
        if (event.competitors.size == 2) {
            event.competitors.forEach { competitor ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChannelLogoBadge(
                        channelName = competitor.displayName,
                        logoUrl = competitor.logoUrl,
                        modifier = Modifier.size(if (featured) 32.dp else 26.dp),
                        shape = CircleShape,
                        backgroundColor = BrowseColors.Brand.copy(alpha = 0.14f),
                    )
                    Text(competitor.displayName, fontSize = if (featured) 18.sp else 14.sp,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold, color = BrowseColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    event.scoreFor(competitor)?.let { score ->
                        Text(
                            score,
                            fontSize = if (featured) 18.sp else 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (event.provenance.score?.isStale == true) {
                                BrowseColors.TextTertiary
                            } else {
                                BrowseColors.TextPrimary
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        } else {
            Text(event.title, fontSize = if (featured) 20.sp else 15.sp,
                fontWeight = FontWeight.SemiBold, color = BrowseColors.TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            event.session?.let {
                Text(it, fontSize = 11.sp, color = BrowseColors.TextSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SportsEventCard(
    event: SportsEvent,
    isLive: Boolean,
    showDate: Boolean,
    attributionLines: Int,
    onShowDetails: (SportsEvent) -> Unit,
) {
    TvClickableSurface(
        onClick = { onShowDetails(event) },
        modifier = Modifier.width(244.dp).height(172.dp + 18.dp * attributionLines),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EventEyebrow(event, isLive)
            SportsFixture(event, featured = false, modifier = Modifier.weight(1f))
            Text(formatEventTime(event, showDate), fontSize = 11.sp,
                color = BrowseColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                event.broadcastsForPresentation().firstOrNull()?.channel?.let { channel ->
                    ChannelLogoBadge(channelName = channel.name, logoUrl = channel.logoUrl, modifier = Modifier.size(18.dp))
                    Text(channel.name, modifier = Modifier.weight(1f), fontSize = 10.sp,
                        color = BrowseColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SportsChannelButton(
    channel: Channel,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accessibilityLabel = stringResource(
        R.string.live_sports_play_channel_content_description,
        channel.name,
    )
    TvButton(
        onClick = onClick,
        modifier = modifier
            .height(if (compact) 36.dp else 60.dp)
            .semantics { contentDescription = accessibilityLabel },
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(if (compact) 16.dp else 20.dp))
        Spacer(Modifier.width(6.dp))
        ChannelLogoBadge(
            channelName = channel.name,
            logoUrl = channel.logoUrl,
            modifier = Modifier.size(if (compact) 22.dp else 34.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = channel.name,
            fontSize = if (compact) 11.sp else 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SportsEventDetailsDialog(
    event: SportsEvent,
    nowMillis: Long,
    onDismiss: () -> Unit,
    onFindAnotherChannel: () -> Unit,
    onForgetChoice: () -> Unit,
    onPlayBroadcast: (SportsBroadcast) -> Unit,
    onLoadStandings: suspend (SportsEvent) -> LoadedSportsStandings?,
    onLoadExtraDetails: suspend (SportsEvent) -> LoadedSportsEventExtraDetails?,
) {
    val firstChannelFocusRequester = remember(event.id) { FocusRequester() }
    val displayBroadcasts = event.broadcastsForPresentation()
    val standingsScope = rememberCoroutineScope()
    var standings by remember(event.id) { mutableStateOf<LoadedSportsStandings?>(null) }
    var standingsLoading by remember(event.id) { mutableStateOf(false) }
    var standingsRequested by remember(event.id) { mutableStateOf(false) }
    var extraDetails by remember(event.id) { mutableStateOf<LoadedSportsEventExtraDetails?>(null) }
    var extraDetailsLoading by remember(event.id) { mutableStateOf(false) }
    var extraDetailsRequested by remember(event.id) { mutableStateOf(false) }
    LaunchedEffect(event.id) {
        if (displayBroadcasts.isNotEmpty()) firstChannelFocusRequester.requestFocus()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false,
    )) {
        Surface(
            modifier = Modifier.padding(24.dp).widthIn(max = 720.dp).fillMaxHeight(0.92f),
            colors = SurfaceDefaults.colors(containerColor = BrowseColors.SurfaceElevated),
            shape = BrowseShapes.Panel,
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EventEyebrow(event, event.isLiveAt(nowMillis))
                Text(event.title, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = BrowseColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatEventTime(event, showDate = true), fontSize = 12.sp, color = BrowseColors.TextSecondary)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.live_sports_channel_choices, event.broadcasts.size),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BrowseColors.TextPrimary)
                    event.rememberedBroadcast()?.let { broadcast ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.badge_saved), fontSize = 11.sp, color = BrowseColors.Brand)
                            Text(broadcast.channel.name, fontSize = 11.sp, color = BrowseColors.TextSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    displayBroadcasts.forEachIndexed { index, broadcast ->
                        SportsChannelButton(channel = broadcast.channel, compact = true,
                            modifier = Modifier.fillMaxWidth().then(
                                if (index == 0) Modifier.focusRequester(firstChannelFocusRequester) else Modifier),
                            onClick = { onPlayBroadcast(broadcast) })
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(sportsDescriptionForPresentation(event.description).ifBlank { stringResource(R.string.epg_hero_no_program_description) },
                        fontSize = 13.sp, color = BrowseColors.TextSecondary)
                    val teams = event.provenance.teamMetadata?.value.orEmpty()
                    if (teams.isNotEmpty()) {
                        val details = teams.joinToString(" · ") { team ->
                            listOfNotNull(team.shortName?.takeIf(String::isNotBlank), team.country?.takeIf(String::isNotBlank))
                                .joinToString(" · ").ifBlank { team.team.name }
                        }
                        Text(
                            stringResource(R.string.live_sports_team_metadata, details),
                            fontSize = 11.sp,
                            color = BrowseColors.TextSecondary,
                        )
                    }
                    event.provenance.leagueMetadata?.value?.let { league ->
                        val details = listOfNotNull(
                            league.country?.takeIf(String::isNotBlank),
                            league.season?.takeIf(String::isNotBlank),
                        ).joinToString(" · ")
                        if (details.isNotBlank()) Text(
                            stringResource(R.string.live_sports_league_metadata, details),
                            fontSize = 11.sp,
                            color = BrowseColors.TextSecondary,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.live_sports_standings),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BrowseColors.TextPrimary,
                        )
                        Spacer(Modifier.weight(1f))
                        TvButton(
                            onClick = {
                                if (!standingsLoading) standingsScope.launch {
                                    standingsRequested = true
                                    standingsLoading = true
                                    standings = onLoadStandings(event)
                                    standingsLoading = false
                                }
                            },
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text(stringResource(R.string.live_sports_standings_load), fontSize = 11.sp)
                        }
                    }
                    when {
                        standingsLoading -> Text(
                            stringResource(R.string.live_sports_standings_loading),
                            fontSize = 11.sp,
                            color = BrowseColors.TextSecondary,
                        )
                        standingsRequested && standings == null -> Text(
                            stringResource(R.string.live_sports_standings_unavailable),
                            fontSize = 11.sp,
                            color = BrowseColors.TextSecondary,
                        )
                        standings != null -> SportsStandingsContent(checkNotNull(standings))
                    }
                    if (event.provenance.sources.any { it is com.streamvault.domain.sports.provider.SportsSourceRef.Provider }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.live_sports_extra_details),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BrowseColors.TextPrimary,
                            )
                            Spacer(Modifier.weight(1f))
                            TvButton(
                                onClick = {
                                    if (!extraDetailsLoading) standingsScope.launch {
                                        extraDetailsRequested = true
                                        extraDetailsLoading = true
                                        extraDetails = onLoadExtraDetails(event)
                                        extraDetailsLoading = false
                                    }
                                },
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text(stringResource(R.string.live_sports_extra_details_load), fontSize = 11.sp)
                            }
                        }
                        when {
                            extraDetailsLoading -> Text(
                                stringResource(R.string.live_sports_extra_details_loading),
                                fontSize = 11.sp,
                                color = BrowseColors.TextSecondary,
                            )
                            extraDetailsRequested && extraDetails == null -> Text(
                                stringResource(R.string.live_sports_extra_details_unavailable),
                                fontSize = 11.sp,
                                color = BrowseColors.TextSecondary,
                            )
                            extraDetails != null -> SportsExtraDetailsContent(checkNotNull(extraDetails))
                        }
                    }
                }
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton(onClick = onFindAnotherChannel, modifier = Modifier.height(36.dp)) {
                        Text(stringResource(R.string.live_sports_find_another_channel), fontSize = 12.sp)
                    }
                    if (event.rememberedChannelKey != null) {
                        TvButton(onClick = onForgetChoice, modifier = Modifier.height(36.dp)) {
                            Text(stringResource(R.string.live_sports_forget_choice), fontSize = 12.sp)
                        }
                    }
                    TvButton(onClick = onDismiss, modifier = Modifier.height(36.dp)) {
                        Text(stringResource(R.string.hidden_channels_dialog_close), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SportsExtraDetailsContent(details: LoadedSportsEventExtraDetails) {
    val teamArtwork = details.teamArtwork.distinctBy(LoadedSportsTeamArtwork::teamName)
    if (teamArtwork.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            teamArtwork.forEach { item ->
                ChannelLogoBadge(
                    channelName = item.teamName,
                    logoUrl = item.artwork.uri.toString(),
                    modifier = Modifier.size(46.dp),
                    shape = CircleShape,
                )
            }
        }
    }
    details.statistics?.let { result ->
        if (result.statistics.isNotEmpty()) {
            Text(
                stringResource(R.string.live_sports_statistics),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = BrowseColors.TextPrimary,
            )
            result.statistics.forEach { statistic ->
                val label = statistic.label?.takeIf(String::isNotBlank) ?: statistic.key
                val value = statistic.value?.takeIf(String::isNotBlank) ?: return@forEach
                val teamName = statistic.competitor?.name?.takeIf(String::isNotBlank)
                Text(
                    listOfNotNull(teamName, label, value).joinToString(" · "),
                    fontSize = 11.sp,
                    color = BrowseColors.TextSecondary,
                )
            }
        }
    }
    details.lineups.forEach { lineup ->
        if (lineup.members.isNotEmpty()) {
            Text(
                lineup.team?.name?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.live_sports_lineups),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = BrowseColors.TextPrimary,
            )
            lineup.members.forEach { member ->
                val detailsText = listOfNotNull(
                    member.position?.takeIf(String::isNotBlank),
                    member.shirtNumber?.takeIf(String::isNotBlank)?.let { "#$it" },
                    if (member.starter == true) stringResource(R.string.live_sports_lineup_starter) else null,
                ).joinToString(" · ")
                Text(
                    listOfNotNull(member.competitor.name, detailsText.takeIf(String::isNotBlank))
                        .joinToString(" · "),
                    fontSize = 11.sp,
                    color = BrowseColors.TextSecondary,
                )
            }
        }
    }
    val attribution = details.attribution
    SportsAttributionLine(
        SportsAttributionCredit(
            sourceName = attribution?.sourceName ?: details.providerName,
            format = attribution?.format ?: ProviderAttributionFormat.SOURCE_NAME,
            sourceUrl = attribution?.sourceUrl,
            licenseUrl = attribution?.licenseUrl,
        ),
        color = BrowseColors.TextSecondary,
    )
}

@Composable
private fun SportsStandingsContent(loaded: LoadedSportsStandings) {
    val standings = loaded.standings
    standings.season?.let { season ->
        Text(
            stringResource(R.string.live_sports_standings_season, season),
            fontSize = 10.sp,
            color = BrowseColors.TextSecondary,
        )
    }
    val groups = standings.groups
    if (groups.isEmpty()) {
        Text(
            stringResource(R.string.live_sports_standings_unavailable),
            fontSize = 11.sp,
            color = BrowseColors.TextSecondary,
        )
    } else {
        groups.forEach { group ->
            group.name?.takeIf(String::isNotBlank)?.let { name ->
                Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BrowseColors.TextPrimary)
            }
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.live_sports_standings_rank),
                        modifier = Modifier.width(30.dp),
                        fontSize = 10.sp,
                        color = BrowseColors.TextSecondary,
                    )
                    Text(
                        stringResource(R.string.live_sports_standings_team),
                        modifier = Modifier.width(152.dp),
                        fontSize = 10.sp,
                        color = BrowseColors.TextSecondary,
                    )
                    group.columns.forEach { column ->
                        Text(
                            localizedTableColumn(column.key) ?: column.label?.takeIf(String::isNotBlank) ?: column.key,
                            modifier = Modifier.width(68.dp),
                            fontSize = 10.sp,
                            color = BrowseColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                group.rows.forEachIndexed { index, row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.rank?.toString() ?: (index + 1).toString(),
                            modifier = Modifier.width(30.dp),
                            fontSize = 11.sp,
                            color = BrowseColors.TextSecondary,
                        )
                        Text(
                            row.competitor.name,
                            modifier = Modifier.width(152.dp),
                            fontSize = 11.sp,
                            color = BrowseColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        group.columns.forEach { column ->
                            Text(
                                row.values[column.key].orEmpty(),
                                modifier = Modifier.width(68.dp),
                                fontSize = 11.sp,
                                color = BrowseColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
    SportsAttributionLine(
        SportsAttributionCredit(
            sourceName = loaded.attribution?.sourceName ?: loaded.providerName,
            format = loaded.attribution?.format ?: ProviderAttributionFormat.SOURCE_NAME,
            sourceUrl = loaded.attribution?.sourceUrl,
            licenseUrl = loaded.attribution?.licenseUrl,
        ),
        color = BrowseColors.TextSecondary.copy(alpha = if (loaded.isStale) 0.65f else 1f),
    )
}

@Composable
private fun localizedTableColumn(key: String): String? = when (key) {
    "intPlayed", "played", "games_played" -> stringResource(R.string.live_sports_table_played)
    "intWin", "won", "wins" -> stringResource(R.string.live_sports_table_won)
    "intDraw", "drawn", "ties" -> stringResource(R.string.live_sports_table_drawn)
    "intLoss", "lost", "losses" -> stringResource(R.string.live_sports_table_lost)
    "intGoalsFor", "goals_for" -> stringResource(R.string.live_sports_table_goals_for)
    "intGoalsAgainst", "goals_against" -> stringResource(R.string.live_sports_table_goals_against)
    "intGoalDifference", "goal_difference" -> stringResource(R.string.live_sports_table_goal_difference)
    "points" -> stringResource(R.string.live_sports_table_points)
    else -> null
}

@Composable
private fun SportsManualChannelPickerDialog(
    event: SportsEvent,
    onDismiss: () -> Unit,
    onSearchChannels: suspend (SportsEvent, String) -> List<SportsBroadcast>,
    onUseForEvent: (SportsBroadcast) -> Unit,
) {
    var query by remember(event.id) { mutableStateOf("") }
    var results by remember(event.id) { mutableStateOf(emptyList<SportsBroadcast>()) }
    var isSearching by remember(event.id) { mutableStateOf(true) }
    var hasRequestedInitialFocus by remember(event.id) { mutableStateOf(false) }
    val searchFocusRequester = remember(event.id) { FocusRequester() }
    val firstResultFocusRequester = remember(event.id) { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(event.id, query) {
        isSearching = true
        delay(200)
        results = onSearchChannels(event, query)
        isSearching = false
    }
    LaunchedEffect(event.id, isSearching, results.size) {
        if (isSearching || hasRequestedInitialFocus) return@LaunchedEffect
        hasRequestedInitialFocus = true
        if (results.isEmpty()) searchFocusRequester.requestFocus()
        else firstResultFocusRequester.requestFocus()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.68f))
                    .clickable(
                        onClick = onDismiss,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ),
            )
            Surface(
                modifier = Modifier.widthIn(min = 560.dp, max = 900.dp),
                colors = SurfaceDefaults.colors(containerColor = BrowseColors.SurfaceElevated),
                shape = BrowseShapes.Panel,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.live_sports_find_another_channel),
                        style = MaterialTheme.typography.headlineSmall,
                        color = BrowseColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = BrowseColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SportsProviderCredits(event)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.home_search_channels)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester)
                            .focusProperties { down = firstResultFocusRequester }
                            .onPreviewKeyEvent { keyEvent ->
                                if (
                                    keyEvent.type == KeyEventType.KeyDown &&
                                    keyEvent.key == Key.DirectionDown &&
                                    results.isNotEmpty()
                                ) {
                                    keyboardController?.hide()
                                    firstResultFocusRequester.requestFocus()
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                    when {
                        isSearching -> Text(
                            text = stringResource(R.string.home_loading_channels),
                            style = MaterialTheme.typography.bodyMedium,
                            color = BrowseColors.TextSecondary,
                        )
                        results.isEmpty() -> Text(
                            text = stringResource(R.string.live_sports_no_matching_channels),
                            style = MaterialTheme.typography.bodyMedium,
                            color = BrowseColors.TextSecondary,
                        )
                        else -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 410.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            results.forEachIndexed { index, broadcast ->
                                TvButton(
                                    onClick = { onUseForEvent(broadcast) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 64.dp)
                                        .then(if (index == 0) Modifier.focusRequester(firstResultFocusRequester) else Modifier),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        ChannelLogoBadge(
                                            channelName = broadcast.channel.name,
                                            logoUrl = broadcast.channel.logoUrl,
                                            modifier = Modifier.size(36.dp),
                                        )
                                        Text(
                                            text = broadcast.channel.name,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = stringResource(R.string.live_sports_use_for_event),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    TvButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.colors(
                            containerColor = BrowseColors.SurfaceEmphasis,
                            contentColor = BrowseColors.TextPrimary,
                        ),
                    ) {
                        Text(stringResource(R.string.hidden_channels_dialog_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBadge() {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(BrowseColors.Live.copy(alpha = 0.18f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = stringResource(R.string.live_sports_live_badge),
            fontSize = 9.sp,
            color = BrowseColors.Live,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
        )
    }
}

@Composable
private fun SportsLoadingState() {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = BrowseColors.Brand, strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.live_sports_loading),
            style = MaterialTheme.typography.titleLarge,
            color = BrowseColors.TextSecondary,
        )
    }
}

@Composable
private fun SportsEmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = BrowseColors.TextTertiary,
            modifier = Modifier.size(54.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.live_sports_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = BrowseColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.live_sports_empty_message),
            style = MaterialTheme.typography.bodyLarge,
            color = BrowseColors.TextSecondary,
        )
    }
}

@Composable
private fun SportsErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.live_sports_error),
            style = MaterialTheme.typography.titleLarge,
            color = BrowseColors.TextPrimary,
        )
        Spacer(Modifier.height(16.dp))
        TvButton(onClick = onRetry) {
            Text(stringResource(R.string.live_sports_retry))
        }
    }
}

@Composable
private fun formatEventTime(event: SportsEvent, showDate: Boolean): String {
    val flags = DateUtils.FORMAT_SHOW_TIME or if (showDate) {
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    } else {
        0
    }
    return DateUtils.formatDateRange(LocalContext.current, event.startTime, event.endTime, flags)
}
