package com.streamvault.core.ui.interaction

import android.view.SoundEffectConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

data class TvInteractionSounds(
    val playNavigate: () -> Unit,
    val playSelect: () -> Unit
)

@Composable
fun rememberTvInteractionSounds(): TvInteractionSounds {
    val view = LocalView.current
    return remember(view) {
        TvInteractionSounds(
            playNavigate = { playSound(view, SoundEffectConstants.NAVIGATION_RIGHT) },
            playSelect = { playSound(view, SoundEffectConstants.CLICK) }
        )
    }
}

private fun playSound(view: View, sound: Int) {
    runCatching { view.playSoundEffect(sound) }
}
