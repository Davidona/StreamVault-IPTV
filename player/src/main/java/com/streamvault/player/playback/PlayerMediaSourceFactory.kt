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
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.streamvault.domain.model.DrmInfo
import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import java.util.UUID

@UnstableApi
class PlayerMediaSourceFactory(
    private val dataSourceFactoryProvider: PlayerDataSourceFactoryProvider
) {
    fun create(
        streamInfo: StreamInfo,
        resolvedStreamType: ResolvedStreamType,
        retryPolicy: PlayerRetryPolicy,
        vodHttpProtocolMode: VodHttpProtocolMode = VodHttpProtocolMode.COMPATIBILITY_HTTP1,
        preload: Boolean = false
    ): Pair<PlayerTimeoutProfile, MediaSource> {
        val (timeoutProfile, dataSourceFactory) = dataSourceFactoryProvider.createFactory(
            streamInfo = streamInfo,
            resolvedStreamType = resolvedStreamType,
            vodHttpProtocolMode = vodHttpProtocolMode,
            preload = preload
        )
        val mediaItem = buildMediaItem(streamInfo)
        val mediaSource = when {
            streamInfo.streamType == StreamType.RTSP || resolvedStreamType == ResolvedStreamType.RTSP ->
                RtspMediaSource.Factory().createMediaSource(mediaItem)
            resolvedStreamType == ResolvedStreamType.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(retryPolicy)
                .createMediaSource(mediaItem)

            resolvedStreamType == ResolvedStreamType.DASH -> DashMediaSource.Factory(dataSourceFactory)
                .setMatchLastModifiedTime(true)
                .setLoadErrorHandlingPolicy(retryPolicy)
                .createMediaSource(mediaItem)

            resolvedStreamType == ResolvedStreamType.SMOOTH_STREAMING -> SsMediaSource.Factory(dataSourceFactory)
                .apply {
                    if (streamInfo.drmInfo?.scheme == DrmScheme.CLEARKEY) {
                        setManifestParser(ClearKeySmoothStreamingManifestParser())
                    }
                }
                .setLoadErrorHandlingPolicy(retryPolicy)
                .createMediaSource(mediaItem)

            resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE -> ProgressiveMediaSource.Factory(
                dataSourceFactory,
                DefaultExtractorsFactory()
                    .setTsExtractorFlags(
                        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                            or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                            or DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
                    )
                    .setTsSubtitleFormats(TS_SUBTITLE_FORMATS)
            )
                .setLoadErrorHandlingPolicy(retryPolicy)
                .createMediaSource(mediaItem)

            resolvedStreamType == ResolvedStreamType.PROGRESSIVE -> ProgressiveMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(retryPolicy)
                .createMediaSource(mediaItem)

            else -> DefaultMediaSourceFactory(
                dataSourceFactory,
                DefaultExtractorsFactory()
                    .setTsExtractorFlags(
                        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                            or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                            or DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
                    )
                    .setTsSubtitleFormats(TS_SUBTITLE_FORMATS)
            )
                .setLoadErrorHandlingPolicy(retryPolicy)
                .createMediaSource(mediaItem)
        }

        Log.i(
            TAG,
            "media-source streamType=$resolvedStreamType timeout=$timeoutProfile source=${mediaSource::class.java.simpleName} target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )
        return timeoutProfile to mediaSource
    }

    private fun buildMediaItem(streamInfo: StreamInfo): MediaItem {
        return MediaItem.Builder()
            .setUri(Uri.parse(streamInfo.url))
            .setMediaId(mediaIdFor(streamInfo))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(streamInfo.title)
                    .build()
            )
            .apply {
                streamInfo.drmInfo?.let { drmInfo ->
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(drmInfo.scheme.toUuid())
                            .setLicenseUri(drmInfo.licenseUrl)
                            .setLicenseRequestHeaders(drmInfo.headers)
                            .setMultiSession(drmInfo.multiSession)
                            .setForceDefaultLicenseUri(drmInfo.forceDefaultLicenseUrl)
                            .setPlayClearContentWithoutKey(drmInfo.playClearContentWithoutKey)
                            .build()
                    )
                }
            }
            .build()
    }

    fun mediaIdFor(streamInfo: StreamInfo): String {
        val drmKey = streamInfo.drmInfo?.let { "${it.scheme}:${it.licenseUrl}" }.orEmpty()
        return stableHash("${streamInfo.url}|${streamInfo.title.orEmpty()}|$drmKey")
    }

    private fun DrmScheme.toUuid(): UUID = when (this) {
        DrmScheme.WIDEVINE -> C.WIDEVINE_UUID
        DrmScheme.PLAYREADY -> C.PLAYREADY_UUID
        DrmScheme.CLEARKEY -> C.CLEARKEY_UUID
    }

    companion object {
        private const val TAG = "PlayerMediaSourceFactory"

        /** Explicit CEA-608 and CEA-708 closed caption formats for MPEG-TS streams. */
        private val TS_SUBTITLE_FORMATS: List<Format> = listOf(
            Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build(),
            Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_CEA708)
                .setCodecs("cea-708")
                .setAccessibilityChannel(1)
                .build()
        )
    }
}
