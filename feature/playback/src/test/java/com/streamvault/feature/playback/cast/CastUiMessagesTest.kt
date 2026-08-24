package com.streamvault.feature.playback.cast

import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.playback.R
import com.streamvault.feature.playback.api.CastMediaRequest
import com.streamvault.feature.playback.api.CastRewriteRequiredReason
import org.junit.Test

class CastUiMessagesTest {

    @Test
    fun `maps build failures to the unchanged Cast messages`() {
        assertThat(CastMediaRequestUnsupportedReason.STREAM_UNAVAILABLE.toCastBuildFailureMessageRes())
            .isEqualTo(R.string.cast_item_unavailable)
        assertThat(CastMediaRequestUnsupportedReason.EMPTY_URL.toCastBuildFailureMessageRes())
            .isEqualTo(R.string.cast_item_unavailable)
        assertThat(CastMediaRequestUnsupportedReason.UNSUPPORTED_PROTOCOL.toCastBuildFailureMessageRes())
            .isEqualTo(R.string.cast_protocol_unsupported)
        assertThat(CastMediaRequestUnsupportedReason.DRM_PROTECTED.toCastBuildFailureMessageRes())
            .isEqualTo(R.string.cast_drm_unsupported)
    }

    @Test
    fun `maps rewrite requirements to the unchanged Cast messages`() {
        val expected = mapOf(
            CastRewriteRequiredReason.LOCAL_URI to R.string.cast_local_url_unsupported,
            CastRewriteRequiredReason.CUSTOM_HEADERS to R.string.cast_headers_unsupported,
            CastRewriteRequiredReason.CUSTOM_USER_AGENT to R.string.cast_user_agent_unsupported,
            CastRewriteRequiredReason.PROXY to R.string.cast_proxy_unsupported,
            CastRewriteRequiredReason.INVALID_SSL to R.string.cast_invalid_ssl_unsupported,
            CastRewriteRequiredReason.SCOPED_TRANSPORT to R.string.cast_invalid_ssl_unsupported
        )

        expected.forEach { (reason, messageRes) ->
            assertThat(
                CastMediaRequest(
                    url = "https://example.test/live.m3u8",
                    title = "Live",
                    rewriteRequiredReason = reason
                ).toCastUnsupportedMessageRes()
            ).isEqualTo(messageRes)
        }
        assertThat(
            CastMediaRequest(url = "https://example.test/live.m3u8", title = "Live")
                .toCastUnsupportedMessageRes()
        ).isEqualTo(R.string.cast_stream_unsupported)
    }

    @Test
    fun `maps playback events to the unchanged Cast messages`() {
        assertThat(CastPlaybackEvent.MediaLoadSucceeded("Live").toCastPlaybackMessageRes())
            .isEqualTo(R.string.cast_started)
        assertThat(CastPlaybackEvent.MediaLoadFailed("Live").toCastPlaybackMessageRes())
            .isEqualTo(R.string.cast_load_failed)
        assertThat(CastPlaybackEvent.SessionStartFailed(1).toCastPlaybackMessageRes())
            .isEqualTo(R.string.cast_session_failed)
        assertThat(CastPlaybackEvent.ReceiverUnavailable("Live").toCastPlaybackMessageRes())
            .isEqualTo(R.string.cast_receiver_unavailable)
        assertThat(CastPlaybackEvent.RouteSelectionCancelled.toCastPlaybackMessageRes())
            .isEqualTo(R.string.cast_selection_cancelled)
    }
}
