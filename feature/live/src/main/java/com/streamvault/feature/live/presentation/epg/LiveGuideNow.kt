package com.streamvault.feature.live.presentation.epg

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

private val LocalLiveGuideNow = staticCompositionLocalOf { 0L }

@Composable
fun rememberLiveGuideNow(): Long {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    return currentTime
}

@Composable
fun LiveGuideNowProvider(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLiveGuideNow provides rememberLiveGuideNow()) {
        content()
    }
}

@Composable
fun currentLiveGuideNow(): Long = LocalLiveGuideNow.current
