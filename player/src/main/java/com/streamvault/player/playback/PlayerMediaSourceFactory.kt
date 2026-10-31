@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import com.streamvault.domain.model.DrmInfo
import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import java.util.UUID

/** Explicit CEA-608 and CEA-708 closed caption formats for MPEG-TS streams. */
private val TS_SUBTITLE_FORMATS: List<Format> = listOf(
    Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build(),
    Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_CEA708)
        .setCodecs("cea-708")
        .setAccessibilityChannel(1)
        .build()
)

internal fun liveMpegTsExtractorsFactory(normalizeAvcSamples: Boolean = true): ExtractorsFactory {
    val factory = DefaultExtractorsFactory()
        .setDisableArtworkMetadata(true)
        // Xtream live ".ts" URLs are raw transport streams, not HLS segments. The HLS
        // mode path is more permissive about segment continuity, but it also enables
        // HLS-specific extractor behavior that does not match direct long-lived TS input.
        .setTsExtractorMode(DIRECT_LIVE_MPEG_TS_POLICY.media3ExtractorMode)
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                or DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
        )
        .setTsSubtitleFormats(TS_SUBTITLE_FORMATS)
    // Detection remains enabled for streams without AUD. Normalize prefix-only samples
    // instead of removing their bytes or disabling that compatibility path.
    return if (normalizeAvcSamples) AvcAccessUnitExtractorsFactory(factory) else factory
}

@UnstableApi
class PlayerMediaSourceFactory(
    private val dataSourceFactoryProvider: PlayerDataSourceFactoryProvider,
    private val bufferModeProvider: () -> PlaybackBufferMode = { PlaybackBufferMode.AUTO }
) {
    private val transfers = PlaybackTransferTracker()
    val hasActiveNetworkTransfers: Boolean get() = transfers.hasActiveTransfers
    fun create(
        streamInfo: StreamInfo,
        resolvedStreamType: ResolvedStreamType,
        retryPolicy: PlayerRetryPolicy,
        vodHttpProtocolMode: VodHttpProtocolMode = VodHttpProtocolMode.COMPATIBILITY_HTTP1,
        preload: Boolean = false
    ): Pair<PlayerTimeoutProfile, MediaSource> {
        val (timeoutProfile, mediaSourceFactory) = createMediaSourceFactory(
            streamInfo = streamInfo,
            resolvedStreamType = resolvedStreamType,
            retryPolicy = retryPolicy,
            vodHttpProtocolMode = vodHttpProtocolMode,
            preload = preload
        )
        val mediaItem = mediaItemFor(streamInfo, resolvedStreamType)
        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

        Log.i(
            TAG,
            "media-source streamType=$resolvedStreamType timeout=$timeoutProfile source=${mediaSource::class.java.simpleName} " +
                "requestedLiveTargetFloorMs=${liveTargetOffsetMsFor(streamInfo, resolvedStreamType)} " +
                "target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.requestHeadersOrigin ?: streamInfo.url)}"
        )
        return timeoutProfile to mediaSource
    }

    fun createMediaSourceFactory(
        streamInfo: StreamInfo,
        resolvedStreamType: ResolvedStreamType,
        retryPolicy: PlayerRetryPolicy,
        vodHttpProtocolMode: VodHttpProtocolMode = VodHttpProtocolMode.COMPATIBILITY_HTTP1,
        preload: Boolean = false
    ): Pair<PlayerTimeoutProfile, MediaSource.Factory> {
        val (timeoutProfile, upstreamDataSourceFactory) = dataSourceFactoryProvider.createFactory(
            streamInfo = streamInfo,
            resolvedStreamType = resolvedStreamType,
            vodHttpProtocolMode = vodHttpProtocolMode,
            preload = preload
        )
        val dataSourceFactory = if (isLivePlayback(streamInfo.playbackContentKind, resolvedStreamType) &&
            streamInfo.drmInfo == null && resolvedStreamType != ResolvedStreamType.RTSP
        ) transfers.wrap(upstreamDataSourceFactory) else upstreamDataSourceFactory
        val mediaSourceFactory = when {
            streamInfo.streamType == StreamType.RTSP || resolvedStreamType == ResolvedStreamType.RTSP ->
                RtspMediaSource.Factory()
            resolvedStreamType == ResolvedStreamType.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .applyPlaybackTimestampInitializationPolicy(streamInfo.playbackContentKind)
                .applyPlaybackRenditionLoadingPolicy(streamInfo.playbackContentKind)
                .setPlaylistParserFactory(LenientHlsPlaylistParserFactory(
                    stabilityTargetOffsetMs = liveTargetOffsetMsFor(streamInfo, resolvedStreamType)
                ))
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(retryPolicy)

            resolvedStreamType == ResolvedStreamType.DASH -> DashMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(retryPolicy)

            resolvedStreamType == ResolvedStreamType.SMOOTH_STREAMING -> SsMediaSource.Factory(dataSourceFactory)
                .apply {
                    if (streamInfo.drmInfo?.scheme == DrmScheme.CLEARKEY) {
                        setManifestParser(ClearKeySmoothStreamingManifestParser())
                    }
                }
                .setLoadErrorHandlingPolicy(retryPolicy)

            resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE -> ProgressiveMediaSource.Factory(
                dataSourceFactory,
                liveMpegTsExtractorsFactory(
                    normalizeAvcSamples = isLivePlayback(streamInfo.playbackContentKind, resolvedStreamType)
                )
            )
                .setLoadErrorHandlingPolicy(retryPolicy)

            resolvedStreamType == ResolvedStreamType.PROGRESSIVE -> ProgressiveMediaSource.Factory(
                dataSourceFactory,
                playbackExtractorsFactory()
            )
                .setLoadErrorHandlingPolicy(retryPolicy)

            else -> DefaultMediaSourceFactory(
                dataSourceFactory,
                playbackExtractorsFactory()
            )
                .setLoadErrorHandlingPolicy(retryPolicy)
        }
        streamInfo.drmInfo?.staticClearKeyLicense?.let { license ->
            val drmSessionManager = staticClearKeyDrmSessionManager(license)
            val provider = DrmSessionManagerProvider { drmSessionManager }
            when (mediaSourceFactory) {
                is DashMediaSource.Factory -> mediaSourceFactory.setDrmSessionManagerProvider(provider)
                is HlsMediaSource.Factory -> mediaSourceFactory.setDrmSessionManagerProvider(provider)
                is SsMediaSource.Factory -> mediaSourceFactory.setDrmSessionManagerProvider(provider)
            }
        }
        return timeoutProfile to mediaSourceFactory
    }

    internal fun liveTargetOffsetMsFor(
        streamInfo: StreamInfo,
        resolvedStreamType: ResolvedStreamType = StreamTypeResolver.resolve(streamInfo),
        bufferMode: PlaybackBufferMode = bufferModeProvider()
    ): Long? = if (
        streamInfo.playbackContentKind == PlaybackContentKind.LIVE &&
        resolvedStreamType == ResolvedStreamType.HLS &&
        bufferMode in setOf(PlaybackBufferMode.MEDIUM, PlaybackBufferMode.LARGE)
    ) STABILITY_LIVE_TARGET_MS else null

    internal fun mediaItemFor(
        streamInfo: StreamInfo,
        resolvedStreamType: ResolvedStreamType = StreamTypeResolver.resolve(streamInfo)
    ): MediaItem {
        return MediaItem.Builder()
            .setUri(Uri.parse(streamInfo.url))
            .setMediaId(mediaIdFor(streamInfo, resolvedStreamType))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(streamInfo.title)
                    .build()
            )
            .apply {
                streamInfo.drmInfo?.let { drmInfo ->
                    val drmConfiguration = MediaItem.DrmConfiguration.Builder(drmInfo.scheme.toUuid())
                        .apply {
                            if (drmInfo.licenseUrl.isNotBlank()) {
                                setLicenseUri(drmInfo.licenseUrl)
                            }
                        }
                        .setLicenseRequestHeaders(drmInfo.headers)
                        .setMultiSession(drmInfo.multiSession)
                        .setForceDefaultLicenseUri(drmInfo.forceDefaultLicenseUrl)
                        .setPlayClearContentWithoutKey(drmInfo.playClearContentWithoutKey)
                        .build()
                    setDrmConfiguration(drmConfiguration)
                }
            }
            .build()
    }

    fun mediaIdFor(
        streamInfo: StreamInfo,
        resolvedStreamType: ResolvedStreamType = StreamTypeResolver.resolve(streamInfo)
    ): String {
        val key = mediaIdentityKeyFor(streamInfo)
        val target = liveTargetOffsetMsFor(streamInfo, resolvedStreamType)
        return stableHash(if (target == null) key else "$key|liveTargetOffsetMs=$target")
    }

    /** Decoder history belongs to the media, independently of its live-start policy. */
    internal fun compatibilityMediaIdFor(streamInfo: StreamInfo): String =
        stableHash(mediaIdentityKeyFor(streamInfo))

    private fun mediaIdentityKeyFor(streamInfo: StreamInfo): String {
        val drmKey = streamInfo.drmInfo?.let { "${it.scheme}:${it.licenseUrl}" }.orEmpty()
        return "${streamInfo.url}|${streamInfo.title.orEmpty()}|$drmKey"
    }

    private fun DrmScheme.toUuid(): UUID = when (this) {
        DrmScheme.WIDEVINE -> C.WIDEVINE_UUID
        DrmScheme.PLAYREADY -> C.PLAYREADY_UUID
        DrmScheme.CLEARKEY -> C.CLEARKEY_UUID
    }

    companion object {
        private const val TAG = "PlayerMediaSourceFactory"
        private const val STABILITY_LIVE_TARGET_MS = 10_000L
    }
}

private fun playbackExtractorsFactory(): DefaultExtractorsFactory =
    DefaultExtractorsFactory()
        .setDisableArtworkMetadata(true)
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                or DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
        )
        .setTsSubtitleFormats(TS_SUBTITLE_FORMATS)
