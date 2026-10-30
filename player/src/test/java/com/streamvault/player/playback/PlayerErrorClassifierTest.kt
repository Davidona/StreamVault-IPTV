package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import androidx.media3.exoplayer.source.BehindLiveWindowException
import java.io.IOException
import java.net.SocketException
import javax.net.ssl.SSLHandshakeException
import org.junit.Test

class PlayerErrorClassifierTest {

    @Test
    fun `mid-load connection reset maps to network`() {
        // As delivered to the load error policy: Media3's HttpDataSourceException caused by the socket reset.
        val error = IOException(SocketException("Connection reset"))

        assertThat(PlayerErrorClassifier.classify(error)).isEqualTo(PlaybackErrorCategory.NETWORK)
    }

    @Test
    fun `401 maps to auth`() {
        assertThat(PlayerErrorClassifier.classify(IOException("HTTP 401")))
            .isEqualTo(PlaybackErrorCategory.HTTP_AUTH)
    }

    @Test
    fun `500 maps to server`() {
        assertThat(PlayerErrorClassifier.classify(IOException("HTTP 500")))
            .isEqualTo(PlaybackErrorCategory.HTTP_SERVER)
    }

    @Test
    fun `404 maps to a missing source`() {
        assertThat(PlayerErrorClassifier.classify(IOException("HTTP 404")))
            .isEqualTo(PlaybackErrorCategory.HTTP_NOT_FOUND)
    }

    @Test
    fun `456 maps to auth`() {
        assertThat(PlayerErrorClassifier.classify(IOException("HTTP 456")))
            .isEqualTo(PlaybackErrorCategory.HTTP_AUTH)
    }

    @Test
    fun `509 maps to provider limit`() {
        assertThat(PlayerErrorClassifier.classify(IOException("HTTP 509")))
            .isEqualTo(PlaybackErrorCategory.PROVIDER_LIMIT)
    }

    @Test
    fun `204 maps to empty response`() {
        assertThat(PlayerErrorClassifier.classify(IOException("HTTP 204")))
            .isEqualTo(PlaybackErrorCategory.EMPTY_RESPONSE)
    }

    @Test
    fun `ssl exception maps to ssl`() {
        assertThat(PlayerErrorClassifier.classify(SSLHandshakeException("certificate verify failed")))
            .isEqualTo(PlaybackErrorCategory.SSL)
    }

    @Test
    fun `behind live window maps to live window`() {
        assertThat(PlayerErrorClassifier.classify(BehindLiveWindowException()))
            .isEqualTo(PlaybackErrorCategory.LIVE_WINDOW)
    }
}

