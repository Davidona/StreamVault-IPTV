package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.StremioPlaybackReference
import com.streamvault.core.navigation.StremioPlayerHandoff
import com.streamvault.core.navigation.StremioSubtitlePlaybackSource
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
    viewModel: StremioItemDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
            onRetryMetadata = viewModel::retryMetadata,
            onRetryAddon = viewModel::retryStreamsForAddon,
            onPlay = onPlay
        )
    }
}

@Composable
private fun StremioItemDetailContent(
    state: StremioItemDetailState,
    onLoadMovieStreams: () -> Unit,
    onLoadEpisodeStreams: (String) -> Unit,
    onRetryMetadata: () -> Unit,
    onRetryAddon: (Long) -> Unit,
    onPlay: (StremioPlayerHandoff) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state.replayOriginUnavailable) {
            item(key = "history-origin-unavailable") {
                Text(
                    stringResource(R.string.stremio_history_origin_unavailable),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        item(key = "meta") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                AsyncImage(
                    model = state.displayPoster,
                    contentDescription = null,
                    modifier = Modifier.width(160.dp).height(230.dp),
                    contentScale = ContentScale.Crop
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        state.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    state.displayReleaseInfo?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.metadata?.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
                        Text(
                            genres.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.displayDescription?.let { description ->
                        Text(
                            description,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    state.metaOrigin?.let { origin ->
                        Text(
                            "Metadata from ${origin.addonName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.metadataLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.stremio_item_metadata_loading),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else if (state.metadata == null) {
                        Text(
                            stringResource(R.string.stremio_item_preview_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state.metadataFailures.forEach { failure ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(
                                        R.string.stremio_item_metadata_failed,
                                        failure.source.addonName,
                                        failure.message
                                    ),
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(onClick = onRetryMetadata) {
                                    Text(stringResource(R.string.stremio_retry))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.preview.type == StremioMediaType.MOVIE) {
            item(key = "movie-streams") {
                Button(onClick = onLoadMovieStreams, enabled = !state.replayOriginUnavailable) {
                    Text(stringResource(R.string.stremio_item_find_streams))
                }
            }
        } else if (state.preview.type == StremioMediaType.SERIES) {
            item(key = "episodes-header") {
                Text(
                    stringResource(R.string.stremio_item_episodes),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (state.metadataLoading) {
                item(key = "episodes-loading") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.stremio_item_metadata_loading),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else if (state.videos.isEmpty()) {
                item(key = "episodes-empty") {
                    Text(
                        stringResource(R.string.stremio_item_no_episode_ids),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(state.videos, key = { "episode:${it.id}" }) { video ->
                    Button(
                        onClick = { onLoadEpisodeStreams(video.id) },
                        enabled = !state.replayOriginUnavailable,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(episodeLabel(video))
                    }
                }
            }
        }

        state.requestedVideoId?.let { requestedVideoId ->
            item(key = "streams-heading") {
                Text(
                    stringResource(
                        R.string.stremio_item_streams_for,
                        state.selectedVideo?.title ?: state.displayName
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (state.streamsLoading && state.streams.isEmpty()) {
                item(key = "streams-loading") {
                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (!state.streamsLoading && state.streams.isEmpty() &&
                state.streamFailures.isEmpty() && state.streamCandidateCount == 0
            ) {
                item(key = "streams-no-addon") {
                    Text(
                        stringResource(R.string.stremio_item_no_stream_addons),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (!state.streamsLoading && state.streams.isEmpty() && state.streamFailures.isEmpty()) {
                item(key = "streams-empty") {
                    Text(
                        stringResource(R.string.stremio_item_no_streams),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    StremioStreamKind.TORRENT -> stream.toMagnetUri() != null
                    else -> false
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stream.title ?: stream.name ?: stream.description ?: stream.kind.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "${attributed.source.addonName} · ${stream.kind.name.lowercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!canPlay) {
                                Text(
                                    unsupportedStreamLabel(stream),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (stream.kind == StremioStreamKind.TORRENT) {
                                Text(
                                    stringResource(R.string.stremio_item_torrent_plugin_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (canPlay) {
                            Button(
                                enabled = state.catalogOriginKey != null && requestedVideoId.isNotBlank(),
                                onClick = {
                                    buildHandoff(state, attributed.source.installFingerprint, stream, requestedVideoId)
                                        ?.let(onPlay)
                                }
                            ) { Text(stringResource(R.string.stremio_item_play)) }
                        }
                    }
                }
            }
            items(state.streamFailures, key = { "stream-error:${it.source.addonId}" }) { failure ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.stremio_item_stream_failed,
                            failure.source.addonName,
                            failure.message
                        ),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { onRetryAddon(failure.source.addonId) }) {
                        Text(stringResource(R.string.stremio_retry))
                    }
                }
            }
        }
    }
}

private fun episodeLabel(video: com.streamvault.domain.model.stremio.StremioVideo): String {
    val number = listOfNotNull(video.season?.let { "S$it" }, video.episode?.let { "E$it" })
        .joinToString("")
    return listOfNotNull(number.takeIf(String::isNotBlank), video.title ?: video.id).joinToString(" · ")
}

@Composable
private fun unsupportedStreamLabel(stream: StremioStream): String {
    val stringRes = when {
    stream.kind == StremioStreamKind.DIRECT && stream.url?.let {
        stremioMediaRequestHeaders(it, stream.behaviorHints?.proxyHeaders?.request)
    } == null -> R.string.stremio_item_headers_unavailable
    stream.kind == StremioStreamKind.TORRENT -> if (stream.toMagnetUri() == null) {
        R.string.stremio_item_torrent_invalid
    } else {
        R.string.stremio_item_torrent_unavailable
    }
    stream.kind == StremioStreamKind.UNKNOWN -> R.string.stremio_item_unsupported_stream
    stream.kind == StremioStreamKind.YOUTUBE || stream.kind == StremioStreamKind.EXTERNAL ->
        R.string.stremio_item_external_unavailable
    else -> R.string.stremio_item_external_unavailable
    }
    return stringResource(stringRes)
}

private fun buildHandoff(
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
