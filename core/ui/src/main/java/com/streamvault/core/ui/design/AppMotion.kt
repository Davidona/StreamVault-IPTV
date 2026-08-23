package com.streamvault.core.ui.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween

object AppMotion {
    const val Fast = 120
    const val Standard = 160
    const val Emphasis = 180

    val FocusSpec: FiniteAnimationSpec<Float> = tween(
        durationMillis = Standard,
        easing = LinearOutSlowInEasing
    )
}
