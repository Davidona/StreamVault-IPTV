package com.streamvault.feature.playback.cast

import com.streamvault.feature.playback.R
import com.streamvault.feature.playback.api.CastMediaRequest
import com.streamvault.feature.playback.api.CastRewriteRequiredReason

fun CastMediaRequestUnsupportedReason.toCastBuildFailureMessageRes(): Int = when (this) {
    CastMediaRequestUnsupportedReason.STREAM_UNAVAILABLE,
    CastMediaRequestUnsupportedReason.EMPTY_URL -> R.string.cast_item_unavailable
    CastMediaRequestUnsupportedReason.UNSUPPORTED_PROTOCOL -> R.string.cast_protocol_unsupported
    CastMediaRequestUnsupportedReason.DRM_PROTECTED -> R.string.cast_drm_unsupported
}

fun CastMediaRequest.toCastUnsupportedMessageRes(): Int = when (rewriteRequiredReason) {
    CastRewriteRequiredReason.LOCAL_URI -> R.string.cast_local_url_unsupported
    CastRewriteRequiredReason.CUSTOM_HEADERS -> R.string.cast_headers_unsupported
    CastRewriteRequiredReason.CUSTOM_USER_AGENT -> R.string.cast_user_agent_unsupported
    CastRewriteRequiredReason.PROXY -> R.string.cast_proxy_unsupported
    CastRewriteRequiredReason.INVALID_SSL,
    CastRewriteRequiredReason.SCOPED_TRANSPORT -> R.string.cast_invalid_ssl_unsupported
    null -> R.string.cast_stream_unsupported
}

fun CastPlaybackEvent.toCastPlaybackMessageRes(): Int = when (this) {
    is CastPlaybackEvent.MediaLoadSucceeded -> R.string.cast_started
    is CastPlaybackEvent.MediaLoadFailed -> R.string.cast_load_failed
    is CastPlaybackEvent.SessionStartFailed -> R.string.cast_session_failed
    is CastPlaybackEvent.ReceiverUnavailable -> R.string.cast_receiver_unavailable
    CastPlaybackEvent.RouteSelectionCancelled -> R.string.cast_selection_cancelled
}
