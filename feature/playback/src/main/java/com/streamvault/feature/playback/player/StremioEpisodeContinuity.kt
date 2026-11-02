package com.streamvault.feature.playback.player

import com.streamvault.core.navigation.StremioSubtitlePlaybackSource
import com.streamvault.domain.model.stremio.*
import kotlinx.coroutines.flow.first

/** Autoplay only when the same add-on offers the same explicit binge group. */
internal suspend fun PlayerViewModel.tryNextStremioEpisode(): Boolean {
    val reference = currentStremioPlaybackReference ?: return false
    if (reference.mediaType != "series") return false
    val parentId = reference.parentSeriesId ?: return false
    val group = reference.bingeGroup ?: return false
    val requestVersion = prepareRequestVersion
    val origin = stremioAddonRepository.observeAddons().first()
        .firstOrNull { it.enabled && it.installFingerprint() == reference.catalogOriginKey } ?: return false
    val metaResult = stremioAddonRepository.resolveMeta(origin.id, StremioMediaType.SERIES, parentId).result ?: return false
    val next = metaResult.resource.nextVideoAfter(reference.exactVideoId) ?: return false
    val resources = next.streams?.map { StremioAttributedResource(metaResult.source, it) }
        ?: stremioAddonRepository.resolveStreams(StremioMediaType.SERIES, next.id).results
    stremioStreamingService.load()
    val preparedResources = resources.mapNotNull { attributed ->
        stremioStreamingService.prepare(attributed.resource)?.let { attributed.copy(resource=it) }
    }
    val candidate = preparedResources.firstOrNull { attributed ->
        attributed.source.installFingerprint == reference.streamOriginKey &&
            attributed.resource.behaviorHints?.bingeGroup == group &&
            (attributed.resource.kind == StremioStreamKind.DIRECT || attributed.resource.toMagnetUri() != null)
    } ?: return false
    val stream = candidate.resource
    val url = when (stream.kind) {
        StremioStreamKind.TORRENT -> stream.toMagnetUri() ?: return false
        StremioStreamKind.DIRECT -> stream.url?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) } ?: return false
        else -> return false
    }
    val headers = if (stream.kind == StremioStreamKind.DIRECT) {
        stremioMediaRequestHeaders(url, stream.behaviorHints?.proxyHeaders?.request)?.headers ?: return false
    } else emptyMap()
    if (requestVersion != prepareRequestVersion || currentStremioPlaybackReference != reference) return false
    val title = reference.itemTitle ?: metaResult.resource.name
    prepare(
        streamUrl = url, epgChannelId = null, internalChannelId = -1L,
        contentType = "SERIES_EPISODE",
        title = next.title?.let { "$title · $it" } ?: title,
        artworkUrl = next.thumbnail ?: metaResult.resource.poster,
        stremioPlaybackReference = reference.copy(
            exactVideoId = next.id, metaOriginKey = metaResult.source.installFingerprint,
            streamOriginKey = candidate.source.installFingerprint,
            videoHash = stream.behaviorHints?.videoHash, videoSize = stream.behaviorHints?.videoSize,
            filename = stream.behaviorHints?.filename
        ),
        stremioMediaRequestHeaders = headers,
        stremioInlineSubtitles = stream.subtitles.map { StremioSubtitlePlaybackSource(it.url, it.lang, it.id, it.label) }
    )
    return true
}
