package com.streamvault.feature.playback.player

import androidx.compose.ui.focus.FocusRequester
import com.streamvault.core.ui.design.requestFocusSafely

/** Overlay buttons that open a panel, so closing the panel can hand focus back to the one that opened it. */
internal enum class VodPanelOpener { AUDIO, SUBTITLES, CHAPTERS, EPISODES, SETTINGS }

/**
 * Remembers which overlay button opened the current panel. A panel opened from inside another one (audio from
 * the settings sheet) keeps the original button, because only the overlay buttons call [opened].
 */
internal class VodPanelOpenerFocus {
    private val requesters = VodPanelOpener.entries.associateWith { FocusRequester() }
    private var last: VodPanelOpener? = null

    fun requesterFor(opener: VodPanelOpener): FocusRequester = requesters.getValue(opener)

    fun opened(opener: VodPanelOpener) {
        last = opener
    }

    fun forget() {
        last = null
    }

    /** Returns false when there is no opener or its button is not on screen, so the caller can fall back. */
    fun restore(): Boolean {
        val opener = last ?: return false
        last = null
        return requesterFor(opener).requestFocusSafely(tag = "PlayerScreen", target = "VOD panel opener $opener")
    }
}
