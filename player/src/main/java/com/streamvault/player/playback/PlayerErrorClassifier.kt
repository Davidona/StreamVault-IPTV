package com.streamvault.player.playback

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.BehindLiveWindowException
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

enum class PlaybackErrorCategory {
    NETWORK,
    HTTP_AUTH,
    HTTP_NOT_FOUND,
    HTTP_SERVER,
    PROVIDER_LIMIT,
    EMPTY_RESPONSE,
    FORMAT_UNSUPPORTED,
    DECODER,
    DRM,
    SOURCE_MALFORMED,
    LIVE_WINDOW,
    CLEAR_TEXT_BLOCKED,
    SSL,
    UNKNOWN
}

object PlayerErrorClassifier {
    fun classify(error: Throwable): PlaybackErrorCategory {
        val chain = generateSequence(error) { it.cause }.toList()
        val playbackException = chain.filterIsInstance<PlaybackException>().firstOrNull()
        val httpError = chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()

        val httpCode = httpError?.responseCode
            ?: when (playbackException?.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> parseHttpStatus(playbackException.message)
                else -> null
            }
            ?: chain.firstNotNullOfOrNull { parseHttpStatus(it.message) }

        return when {
            chain.any { it is BehindLiveWindowException } ||
                playbackException?.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                PlaybackErrorCategory.LIVE_WINDOW
            httpCode == 401 || httpCode == 403 || httpCode == 456 -> PlaybackErrorCategory.HTTP_AUTH
            httpCode == 404 -> PlaybackErrorCategory.HTTP_NOT_FOUND
            httpCode == 509 -> PlaybackErrorCategory.PROVIDER_LIMIT
            httpCode == 204 -> PlaybackErrorCategory.EMPTY_RESPONSE
            httpCode in setOf(408, 429, 500, 502, 503, 504) -> PlaybackErrorCategory.HTTP_SERVER
            chain.any { it is SSLHandshakeException || it is SSLException } ||
                chain.any { it.message?.contains("certificate", ignoreCase = true) == true } ->
                PlaybackErrorCategory.SSL
            playbackException?.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ||
                chain.any { it.message?.contains("cleartext", ignoreCase = true) == true } ->
                PlaybackErrorCategory.CLEAR_TEXT_BLOCKED
            chain.any { it is MediaCodecRenderer.DecoderInitializationException } ||
                chain.any {
                    it.message?.contains("decoder init", ignoreCase = true) == true ||
                        it.message?.contains("codec init", ignoreCase = true) == true
                } ||
                playbackException?.errorCode in decoderErrors ->
                PlaybackErrorCategory.DECODER
            playbackException?.errorCode in drmErrors -> PlaybackErrorCategory.DRM
            playbackException?.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                playbackException?.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                chain.any { it.message?.contains("unsupported", ignoreCase = true) == true } ->
                PlaybackErrorCategory.FORMAT_UNSUPPORTED
            chain.any { it is ParserException } ||
                playbackException?.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                playbackException?.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                PlaybackErrorCategory.SOURCE_MALFORMED
            chain.any { it is SocketTimeoutException || it is ConnectException || it is UnknownHostException || it is EOFException } ||
                playbackException?.errorCode in networkErrors ->
                PlaybackErrorCategory.NETWORK
            else -> PlaybackErrorCategory.UNKNOWN
        }
    }

    private fun parseHttpStatus(message: String?): Int? {
        return Regex("""\b(204|401|403|404|408|429|456|500|502|503|504|509)\b""").find(message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private val networkErrors = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED
    )
    private val decoderErrors = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED
    )
    private val drmErrors = setOf(
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
    )
}
