package com.streamvault.core.navigation

import java.io.Serializable

/** Local catalogue identity. Provider stream IDs and TMDB IDs are different namespaces. */
data class ExternalItemLink(
    val type: ExternalItemType,
    val id: Long,
    val providerId: Long,
    val action: ExternalItemAction
) : Serializable {
    init {
        require(id > 0 && providerId > 0)
        require(when (type) {
            ExternalItemType.LIVE -> action == ExternalItemAction.PLAY
            ExternalItemType.SERIES -> action in setOf(ExternalItemAction.DETAILS, ExternalItemAction.RESUME)
            ExternalItemType.EPISODE -> action != ExternalItemAction.DETAILS
            ExternalItemType.MOVIE -> true
        })
    }
}

enum class ExternalItemType(val path: String) {
    MOVIE("movie"), SERIES("series"), EPISODE("episode"), LIVE("live")
}

enum class ExternalItemAction(val parameter: String) {
    DETAILS("details"), PLAY("play"), RESUME("resume"), START_OVER("start_over")
}

/** PROMPT preserves the normal in-app resume dialog. External resume uses saved progress directly. */
enum class PlaybackStartMode { PROMPT, RESUME, START_OVER }
