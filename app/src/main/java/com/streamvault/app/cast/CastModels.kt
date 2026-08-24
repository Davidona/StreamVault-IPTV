package com.streamvault.app.cast

typealias CastMediaRequest = com.streamvault.feature.playback.api.CastMediaRequest
typealias CastRewriteRequiredReason = com.streamvault.feature.playback.api.CastRewriteRequiredReason

enum class CastConnectionState {
    UNAVAILABLE,
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

enum class CastStartResult {
    STARTED,
    ROUTE_SELECTION_REQUIRED,
    UNAVAILABLE,
    UNSUPPORTED
}

sealed interface CastPlaybackEvent {
    data class MediaLoadSucceeded(val title: String) : CastPlaybackEvent
    data class MediaLoadFailed(val title: String?, val statusCode: Int? = null) : CastPlaybackEvent
    data class SessionStartFailed(val errorCode: Int) : CastPlaybackEvent
    data class ReceiverUnavailable(val title: String?) : CastPlaybackEvent
    data object RouteSelectionCancelled : CastPlaybackEvent
}

enum class CastPlaybackReportMode {
    NONE,
    FAILURES_ONLY,
    SUCCESS_AND_FAILURE
}

sealed interface CastUiEvent {
    data object OpenRouteChooser : CastUiEvent
    data class ShowMessage(val messageResId: Int) : CastUiEvent
}
