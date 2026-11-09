package com.streamvault.feature.live.home

import android.view.KeyEvent

/** What the inline fullscreen preview is currently showing / where D-pad input goes. */
internal enum class InlineFullscreenMode {
    /** Video only (info banner may be visible). D-pad zaps; OK opens the controls. */
    WATCHING,

    /** The control row has focus. Left/Right move between buttons; OK activates. */
    CONTROLS,

    /** The subtitle list has focus. Up/Down move; OK selects. */
    SUBTITLES
}

internal sealed interface InlineFullscreenAction {
    /** Let the focused child (a button / list item) handle the key. */
    data object Pass : InlineFullscreenAction
    /** Swallow the key (e.g. the matching down/up half of an action, or a blocked direction). */
    data object Consume : InlineFullscreenAction
    data object ExitFullscreen : InlineFullscreenAction
    data object ShowControls : InlineFullscreenAction
    data object HideControls : InlineFullscreenAction
    data object CloseSubtitles : InlineFullscreenAction
    data class MoveFocus(val delta: Int) : InlineFullscreenAction
    data class Zap(val step: Int) : InlineFullscreenAction
    data object TogglePlayPause : InlineFullscreenAction
    data object Play : InlineFullscreenAction
    data object Pause : InlineFullscreenAction
    data object SeekBackward : InlineFullscreenAction
    data object SeekForward : InlineFullscreenAction
    data object OpenFullPlayer : InlineFullscreenAction
}

/**
 * Remote-control policy for the inline fullscreen preview (pure, so it can be unit tested).
 *
 * - Media keys (⏯ ⏪ ⏩) always work, like the full player's media session: ⏪/⏩ jump 10 s.
 * - Actions that move focus onto a button fire on key *up*, so the same press can't also
 *   activate the newly focused button.
 * - Focus inside the controls/subtitle list is moved explicitly (MoveFocus) rather than by
 *   geometric focus search, which could escape to the channel list hidden behind the video.
 */
internal fun decideInlineFullscreenKey(
    mode: InlineFullscreenMode,
    keyCode: Int,
    isDown: Boolean,
    repeatCount: Int
): InlineFullscreenAction {
    val isUp = !isDown
    val firstDown = isDown && repeatCount == 0
    fun onFirstDown(action: InlineFullscreenAction) =
        if (firstDown) action else InlineFullscreenAction.Consume
    fun onUp(action: InlineFullscreenAction) =
        if (isUp) action else InlineFullscreenAction.Consume

    when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> return onUp(
            when (mode) {
                InlineFullscreenMode.SUBTITLES -> InlineFullscreenAction.CloseSubtitles
                InlineFullscreenMode.CONTROLS -> InlineFullscreenAction.HideControls
                InlineFullscreenMode.WATCHING -> InlineFullscreenAction.ExitFullscreen
            }
        )
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> return onFirstDown(InlineFullscreenAction.TogglePlayPause)
        KeyEvent.KEYCODE_MEDIA_PLAY -> return onFirstDown(InlineFullscreenAction.Play)
        KeyEvent.KEYCODE_MEDIA_PAUSE -> return onFirstDown(InlineFullscreenAction.Pause)
        KeyEvent.KEYCODE_MEDIA_REWIND -> return onFirstDown(InlineFullscreenAction.SeekBackward)
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return onFirstDown(InlineFullscreenAction.SeekForward)
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> return onUp(InlineFullscreenAction.OpenFullPlayer)
    }

    val isCenter = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    return when (mode) {
        InlineFullscreenMode.WATCHING -> when {
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP ->
                onFirstDown(InlineFullscreenAction.Zap(-1))
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ->
                onFirstDown(InlineFullscreenAction.Zap(1))
            isCenter || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ->
                onUp(InlineFullscreenAction.ShowControls)
            else -> InlineFullscreenAction.Pass
        }
        InlineFullscreenMode.CONTROLS -> when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (isDown) InlineFullscreenAction.MoveFocus(-1) else InlineFullscreenAction.Consume
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (isDown) InlineFullscreenAction.MoveFocus(1) else InlineFullscreenAction.Consume
            KeyEvent.KEYCODE_DPAD_UP -> onFirstDown(InlineFullscreenAction.HideControls)
            KeyEvent.KEYCODE_DPAD_DOWN -> InlineFullscreenAction.Consume
            else -> InlineFullscreenAction.Pass
        }
        InlineFullscreenMode.SUBTITLES -> when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (isDown) InlineFullscreenAction.MoveFocus(-1) else InlineFullscreenAction.Consume
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isDown) InlineFullscreenAction.MoveFocus(1) else InlineFullscreenAction.Consume
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> InlineFullscreenAction.Consume
            else -> InlineFullscreenAction.Pass
        }
    }
}
