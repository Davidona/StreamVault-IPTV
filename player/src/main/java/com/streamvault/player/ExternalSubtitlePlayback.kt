package com.streamvault.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.IOException

internal data class ExternalSubtitlePlaybackTransition(
    val resumePositionMs: Long,
    val playWhenReady: Boolean
)

internal fun buildExternalSubtitlePlaybackTransition(
    currentPositionMs: Long,
    playWhenReady: Boolean
): ExternalSubtitlePlaybackTransition = ExternalSubtitlePlaybackTransition(
    resumePositionMs = currentPositionMs.coerceAtLeast(0L),
    playWhenReady = playWhenReady
)

internal fun TrackSelectionParameters.withExternalSubtitleEnabled(): TrackSelectionParameters =
    buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()

internal fun buildExternalSubtitleConfiguration(
    subtitleUri: Uri,
    language: String
): MediaItem.SubtitleConfiguration = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
    .setLanguage(language)
    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
    .build()

@UnstableApi
internal fun buildExternalSubtitleMediaSource(
    dataSourceFactory: DataSource.Factory,
    configuration: MediaItem.SubtitleConfiguration
): MediaSource {
    val format = Format.Builder()
        .setSampleMimeType(configuration.mimeType)
        .setLanguage(configuration.language)
        .setSelectionFlags(configuration.selectionFlags)
        .setRoleFlags(configuration.roleFlags)
        .setLabel(configuration.label)
        .setId(configuration.id)
        .build()
    val subtitleParserFactory = DefaultSubtitleParserFactory()
    require(subtitleParserFactory.supportsFormat(format)) {
        "Unsupported external subtitle format: ${configuration.mimeType}"
    }
    val extractorsFactory = ExtractorsFactory {
        arrayOf(SubtitleExtractor(subtitleParserFactory.create(format), format))
    }
    return ProgressiveMediaSource.Factory(
        optionalExternalSubtitleDataSourceFactory(dataSourceFactory),
        extractorsFactory
    )
        .createMediaSource(MediaItem.fromUri(configuration.uri))
}

@UnstableApi
internal fun optionalExternalSubtitleDataSourceFactory(
    delegateFactory: DataSource.Factory
): DataSource.Factory = DataSource.Factory {
    OptionalExternalSubtitleDataSource(delegateFactory.createDataSource())
}

@UnstableApi
private class OptionalExternalSubtitleDataSource(
    private val delegate: DataSource
) : DataSource by delegate {
    private var reachedOptionalEnd = false

    override fun open(dataSpec: DataSpec): Long {
        reachedOptionalEnd = false
        return try {
            delegate.open(dataSpec)
        } catch (_: IOException) {
            reachedOptionalEnd = true
            0L
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (reachedOptionalEnd) return C.RESULT_END_OF_INPUT
        return try {
            delegate.read(buffer, offset, length)
        } catch (_: IOException) {
            reachedOptionalEnd = true
            C.RESULT_END_OF_INPUT
        }
    }
}
