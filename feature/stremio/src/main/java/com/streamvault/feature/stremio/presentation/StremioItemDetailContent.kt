package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.StremioPlaybackReference
import com.streamvault.core.navigation.StremioPlayerHandoff
import com.streamvault.core.navigation.StremioSubtitlePlaybackSource
import com.streamvault.core.ui.components.shell.AppSectionHeader
import com.streamvault.core.ui.components.shell.StatusPill
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.browsePanel
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvPrimaryButton
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioStream
import com.streamvault.domain.model.stremio.StremioStreamKind
import com.streamvault.domain.model.stremio.stremioMediaRequestHeaders
import com.streamvault.domain.model.stremio.toMagnetUri
import com.streamvault.feature.stremio.R
import com.streamvault.feature.stremio.api.StremioScaffoldContent

@Composable
internal fun StremioItemDetailScreen(
    scaffold: StremioScaffoldContent,
    onPlay: (StremioPlayerHandoff) -> Unit,
    onManagePlugins: () -> Unit = {},
    viewModel: StremioItemDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshTorrentPlaybackAvailability()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    scaffold(
        AppDestination.Addons,
        state.displayName,
        state.displayReleaseInfo,
        false,
        true
    ) {
        StremioItemDetailContent(
            state = state,
            onLoadMovieStreams = { viewModel.loadStreams(state.preview.id) },
            onLoadEpisodeStreams = viewModel::loadStreams,
            onClearSelectedEpisode = viewModel::clearSelectedEpisode,
            onRetryAllStreams = { videoId -> viewModel.loadStreams(videoId) },
            onRetryMetadata = viewModel::retryMetadata,
            onRetryAddon = viewModel::retryStreamsForAddon,
            onManagePlugins = onManagePlugins,
            onPlay = onPlay
        )
    }
}

@Composable
private fun StremioItemDetailContent(
    state: StremioItemDetailState,
    onLoadMovieStreams: () -> Unit,
    onLoadEpisodeStreams: (String) -> Unit,
    onClearSelectedEpisode: () -> Unit,
    onRetryAllStreams: (String) -> Unit,
    onRetryMetadata: () -> Unit,
    onRetryAddon: (Long) -> Unit,
    onManagePlugins: () -> Unit,
    onPlay: (StremioPlayerHandoff) -> Unit
) {
    var selectedSeason by rememberSaveable(state.preview.id) { mutableStateOf<Int?>(null) }
    val seasons = state.videos.mapNotNull { it.season }.distinct().sorted()
    LaunchedEffect(seasons) {
        if (selectedSeason !in seasons) selectedSeason = seasons.firstOrNull()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
    ) {
        if (state.replayOriginUnavailable) {
            item(key = "history-origin-unavailable") {
                StremioDetailMessage(
                    stringResource(R.string.stremio_history_origin_unavailable),
                    error = true
                )
            }
        }
        item(key = "meta") {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().browsePanel(BrowseShapes.Hero).padding(20.dp)
            ) {
                val poster = @Composable {
                    Box(
                        modifier = Modifier.width(160.dp).height(230.dp).clip(BrowseShapes.Artwork)
                            .background(AppColors.SurfaceEmphasis),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            state.displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.displaySmall,
                            color = AppColors.BrandStrong
                        )
                        AsyncImage(
                            model = state.displayPoster,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                if (maxWidth < 620.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { poster() }
                        StremioMetadataContent(state, onRetryMetadata)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Top) {
                        poster()
                        StremioMetadataContent(state, onRetryMetadata, Modifier.weight(1f))
                    }
                }
            }
        }

        if (state.preview.type == StremioMediaType.MOVIE) {
            item(key = "movie-streams") {
                TvPrimaryButton(onClick = onLoadMovieStreams, enabled = !state.replayOriginUnavailable) {
                    Text(stringResource(R.string.stremio_item_find_streams))
                }
            }
        } else if (state.preview.type == StremioMediaType.SERIES) {
            item(key = "episodes-header") {
                AppSectionHeader(title = stringResource(R.string.stremio_item_episodes))
            }
            if (state.metadataLoading) {
                item(key = "episodes-loading") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Brand)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.stremio_item_metadata_loading),
                            color = AppColors.TextSecondary
                        )
                    }
                }
            } else if (state.videos.isEmpty()) {
                item(key = "episodes-empty") {
                    StremioDetailMessage(stringResource(R.string.stremio_item_no_episode_ids))
                }
            } else if (state.requestedVideoId == null) {
                if (seasons.size > 1) {
                    item(key = "season-picker") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons, key = { "season:$it" }) { season ->
                                TvButton(onClick = { selectedSeason = season }) {
                                    Text(
                                        text = stringResource(R.string.stremio_item_season_number, season),
                                        color = if (season == selectedSeason) AppColors.TextPrimary else AppColors.TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
                items(
                    state.videos.filter { selectedSeason == null || it.season == selectedSeason },
                    key = { "episode:${it.id}" }
                ) { video ->
                    TvClickableSurface(
                        onClick = { onLoadEpisodeStreams(video.id) },
                        enabled = !state.replayOriginUnavailable,
                        modifier = Modifier.fillMaxWidth(),
                        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Panel),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = AppColors.SurfaceElevated,
                            focusedContainerColor = AppColors.SurfaceEmphasis,
                            contentColor = AppColors.TextPrimary,
                            focusedContentColor = AppColors.TextPrimary
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(episodeLabel(video), style = MaterialTheme.typography.titleMedium)
                                video.overview?.takeIf(String::isNotBlank)?.let { overview ->
                                    Text(
                                        overview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppColors.TextTertiary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            StatusPill(label = stringResource(R.string.stremio_item_play), containerColor = AppColors.BrandMuted)
                        }
                    }
                }
            } else {
                item(key = "selected-episode") {
                    Row(
                        modifier = Modifier.fillMaxWidth().browsePanel(BrowseShapes.Control).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                state.selectedVideo?.let(::episodeLabel)
                                    ?: state.selectedVideo?.title
                                    ?: "Episode ${state.requestedVideoId}",
                                style = MaterialTheme.typography.titleSmall,
                                color = AppColors.TextPrimary
                            )
                        }
                        TvButton(onClick = {
                            selectedSeason = state.selectedVideo?.season ?: selectedSeason
                            onClearSelectedEpisode()
                        }) { Text(stringResource(R.string.stremio_item_change_episode)) }
                    }
                }
            }
        }

        state.requestedVideoId?.let { requestedVideoId ->
            item(key = "streams-heading") {
                AppSectionHeader(
                    title = stringResource(
                        R.string.stremio_item_streams_for,
                        state.selectedVideo?.title ?: state.displayName
                    ),
                    subtitle = state.selectedVideo?.let(::episodeLabel)
                )
            }
            if (state.streamsLoading && state.streams.isEmpty()) {
                item(key = "streams-loading") {
                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppColors.Brand)
                    }
                }
            }
            if (state.streamsLoading && state.streams.isNotEmpty()) {
                val settledProviders = (state.streams.map { it.source.addonId } + state.streamFailures.map { it.source.addonId })
                    .distinct().size
                val remaining = (state.streamCandidateCount - settledProviders).coerceAtLeast(0)
                item(key = "streams-progress") {
                    StremioDetailMessage(
                        if (remaining > 0) {
                            stringResource(R.string.stremio_item_sources_remaining, remaining)
                        } else {
                            stringResource(R.string.stremio_item_sources_loading)
                        }
                    )
                }
            }
            state.streamError?.let { message ->
                item(key = "streams-operation-error") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(message, modifier = Modifier.weight(1f), color = AppColors.Live)
                        TvButton(onClick = { onRetryAllStreams(requestedVideoId) }) {
                            Text(stringResource(R.string.stremio_retry))
                        }
                    }
                }
            }
            if (!state.streamsLoading && state.streams.isEmpty() &&
                state.streamFailures.isEmpty() && state.streamCandidateCount == 0
            ) {
                item(key = "streams-no-addon") {
                    StremioDetailMessage(stringResource(R.string.stremio_item_no_stream_addons))
                }
            } else if (!state.streamsLoading && state.streams.isEmpty() && state.streamFailures.isEmpty()) {
                item(key = "streams-empty") {
                    StremioDetailMessage(stringResource(R.string.stremio_item_no_streams))
                }
            }
            itemsIndexed(
                items = state.streams,
                key = { index, item -> "stream:${item.source.addonId}:$index" }
            ) { _, attributed ->
                val stream = attributed.resource
                val mediaHeaders = stream.url?.let {
                    stremioMediaRequestHeaders(it, stream.behaviorHints?.proxyHeaders?.request)
                }
                val isHttpUrl = stream.url?.let {
                    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
                } == true
                val canPlay = when (stream.kind) {
                    StremioStreamKind.DIRECT -> mediaHeaders != null && isHttpUrl
                    StremioStreamKind.TORRENT ->
                        stream.toMagnetUri() != null && state.torrentPluginAvailable == true
                    else -> false
                }
                Column(
                    modifier = Modifier.fillMaxWidth().browsePanel(BrowseShapes.Panel).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stream.title ?: stream.name ?: stream.description ?: stream.kind.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.TextPrimary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(label = stream.kind.name.lowercase(), containerColor = AppColors.BrandMuted)
                                Text(
                                    attributed.source.addonName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!canPlay) {
                                Text(
                                    unsupportedStreamLabel(
                                        stream,
                                        checkingTorrentPlugin = state.torrentPluginCheckLoading
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextSecondary
                                )
                            } else if (stream.kind == StremioStreamKind.TORRENT) {
                                Text(
                                    stringResource(R.string.stremio_item_torrent_plugin_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextSecondary
                                )
                            }
                            if (stream.kind == StremioStreamKind.TORRENT &&
                                stream.toMagnetUri() != null && state.torrentPluginAvailable == false
                            ) {
                                TvButton(onClick = onManagePlugins) {
                                    Text(stringResource(R.string.stremio_item_manage_plugins))
                                }
                            }
                        }
                        if (canPlay) {
                            TvPrimaryButton(
                                onClick = {
                                    buildHandoff(state, attributed.source.installFingerprint, stream, requestedVideoId)
                                        ?.let(onPlay)
                                },
                                enabled = state.catalogOriginKey != null && requestedVideoId.isNotBlank()
                            ) { Text(stringResource(R.string.stremio_item_play)) }
                        }
                    }
                }
            }
            items(state.streamFailures, key = { "stream-error:${it.source.addonId}" }) { failure ->
                Row(
                    modifier = Modifier.fillMaxWidth().browsePanel(BrowseShapes.Control).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.stremio_item_stream_failed,
                            failure.source.addonName,
                            failure.message
                        ),
                        modifier = Modifier.weight(1f),
                        color = AppColors.Live,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TvButton(onClick = { onRetryAddon(failure.source.addonId) }) {
                        Text(stringResource(R.string.stremio_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun StremioMetadataContent(
    state: StremioItemDetailState,
    onRetryMetadata: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            state.displayName,
            style = MaterialTheme.typography.headlineSmall,
            color = AppColors.TextPrimary
        )
        state.displayReleaseInfo?.let { releaseInfo ->
            StatusPill(label = releaseInfo, containerColor = AppColors.BrandMuted)
        }
        state.metadata?.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
            Text(
                genres.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
        }
        state.displayDescription?.let { description ->
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                color = AppColors.TextPrimary
            )
        }
        state.metaOrigin?.let { origin ->
            StatusPill(
                label = "Metadata from ${origin.addonName}",
                containerColor = AppColors.SurfaceEmphasis,
                contentColor = AppColors.TextSecondary
            )
        }
        if (state.metadataLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Brand)
                Text(stringResource(R.string.stremio_item_metadata_loading), color = AppColors.TextSecondary)
            }
        } else if (state.metadata == null) {
            StremioDetailMessage(stringResource(R.string.stremio_item_preview_only))
            state.metadataError?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(message, modifier = Modifier.weight(1f), color = AppColors.Live)
                    TvButton(onClick = onRetryMetadata) { Text(stringResource(R.string.stremio_retry)) }
                }
            }
            state.metadataFailures.forEach { failure ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.stremio_item_metadata_failed,
                            failure.source.addonName,
                            failure.message
                        ),
                        modifier = Modifier.weight(1f),
                        color = AppColors.Live,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TvButton(onClick = onRetryMetadata) { Text(stringResource(R.string.stremio_retry)) }
                }
            }
        }
    }
}

@Composable
private fun StremioDetailMessage(message: String, error: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().browsePanel(BrowseShapes.Control).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message,
            color = if (error) AppColors.Live else AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun episodeLabel(video: com.streamvault.domain.model.stremio.StremioVideo): String {
    val number = listOfNotNull(video.season?.let { "S$it" }, video.episode?.let { "E$it" })
        .joinToString("")
    return listOfNotNull(number.takeIf(String::isNotBlank), video.title ?: video.id).joinToString(" · ")
}

@Composable
private fun unsupportedStreamLabel(
    stream: StremioStream,
    checkingTorrentPlugin: Boolean
): String {
    val stringRes = when {
    stream.kind == StremioStreamKind.DIRECT && stream.url?.let {
        stremioMediaRequestHeaders(it, stream.behaviorHints?.proxyHeaders?.request)
    } == null -> R.string.stremio_item_headers_unavailable
    stream.kind == StremioStreamKind.TORRENT -> when {
        stream.toMagnetUri() == null -> R.string.stremio_item_torrent_invalid
        checkingTorrentPlugin -> R.string.stremio_item_torrent_checking
        else -> R.string.stremio_item_torrent_unavailable
    }
    stream.kind == StremioStreamKind.UNKNOWN -> R.string.stremio_item_unsupported_stream
    stream.kind == StremioStreamKind.YOUTUBE || stream.kind == StremioStreamKind.EXTERNAL ->
        R.string.stremio_item_external_unavailable
    else -> R.string.stremio_item_external_unavailable
    }
    return stringResource(stringRes)
}

internal fun buildHandoff(
    state: StremioItemDetailState,
    streamOriginKey: String,
    stream: StremioStream,
    exactVideoId: String
): StremioPlayerHandoff? {
    val (url, requestHeaders) = when (stream.kind) {
        StremioStreamKind.DIRECT -> {
            val directUrl = stream.url?.takeIf {
                it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
            } ?: return null
            val headers = stremioMediaRequestHeaders(directUrl, stream.behaviorHints?.proxyHeaders?.request)
                ?: return null
            directUrl to headers.headers
        }
        StremioStreamKind.TORRENT -> (stream.toMagnetUri() ?: return null) to emptyMap()
        else -> return null
    }
    val catalogOriginKey = state.catalogOriginKey ?: return null
    val contentType = when (state.preview.type) {
        StremioMediaType.MOVIE -> "MOVIE"
        StremioMediaType.SERIES -> "SERIES_EPISODE"
        else -> return null
    }
    return StremioPlayerHandoff(
        streamUrl = url,
        title = state.selectedVideo?.title?.let { "${state.displayName} · $it" } ?: state.displayName,
        artworkUrl = state.selectedVideo?.thumbnail ?: state.displayPoster,
        contentType = contentType,
        mediaRequestHeaders = requestHeaders,
        reference = StremioPlaybackReference(
            mediaType = state.preview.type.typeName,
            exactVideoId = exactVideoId,
            parentSeriesId = state.preview.id.takeIf { state.preview.type == StremioMediaType.SERIES },
            catalogOriginKey = catalogOriginKey,
            metaOriginKey = state.metaOrigin?.installFingerprint,
            streamOriginKey = streamOriginKey,
            videoHash = stream.behaviorHints?.videoHash,
            videoSize = stream.behaviorHints?.videoSize,
            filename = stream.behaviorHints?.filename
        ),
        inlineSubtitles = stream.subtitles.map { subtitle ->
            StremioSubtitlePlaybackSource(
                url = subtitle.url,
                language = subtitle.lang,
                id = subtitle.id
            )
        }
    )
}
