package com.streamvault.player.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.text.Cue
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamvault.player.PlayerRenderSurfaceType
import com.streamvault.player.PlayerSurfaceResizeMode
import com.streamvault.player.R

internal fun buildInjectedSubtitleCues(text: String?): List<Cue> =
    text?.takeIf(String::isNotBlank)
        ?.let { listOf(Cue.Builder().setText(it).build()) }
        .orEmpty()

class PlayerViewBinder(
    private val subtitleStyleController: SubtitleStyleController
) {
    private var boundPlayerView: PlayerView? = null
    private var boundResizeMode: PlayerSurfaceResizeMode = PlayerSurfaceResizeMode.FIT
    private var injectedSubtitleCues: List<Cue> = emptyList()

    fun createRenderView(
        context: Context,
        resizeMode: PlayerSurfaceResizeMode,
        surfaceType: PlayerRenderSurfaceType
    ): View {
        return (LayoutInflater.from(context).inflate(layoutResId(surfaceType), null) as PlayerView).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setShutterBackgroundColor(Color.BLACK)
            applyResizeMode(resizeMode)
        }
    }

    fun bind(renderView: View, player: androidx.media3.exoplayer.ExoPlayer, resizeMode: PlayerSurfaceResizeMode) {
        val playerView = renderView as? PlayerView ?: return
        boundResizeMode = resizeMode
        if (boundPlayerView !== playerView) {
            runCatching {
                PlayerView.switchTargetView(player, boundPlayerView, playerView)
            }.getOrElse {
                boundPlayerView?.player = null
                playerView.player = player
            }
            boundPlayerView = playerView
        } else if (playerView.player !== player) {
            playerView.player = player
        }
        playerView.applyResizeMode(resizeMode)
        subtitleStyleController.apply(playerView)
        applyInjectedCues(playerView)
    }

    fun attachPlayer(player: androidx.media3.exoplayer.ExoPlayer?) {
        boundPlayerView?.player = player
        boundPlayerView?.applyResizeMode(boundResizeMode)
        subtitleStyleController.apply(boundPlayerView)
        applyInjectedCues(boundPlayerView)
    }

    fun release(renderView: View) {
        val playerView = renderView as? PlayerView ?: return
        if (boundPlayerView !== playerView && playerView.player == null) return
        playerView.player = null
        if (boundPlayerView === playerView) {
            boundPlayerView = null
        }
    }

    fun clear() {
        boundPlayerView?.player = null
        boundPlayerView = null
    }

    fun reapplyStyle() {
        subtitleStyleController.apply(boundPlayerView)
        applyInjectedCues(boundPlayerView)
    }

    fun setInjectedSubtitleText(text: String?) {
        injectedSubtitleCues = buildInjectedSubtitleCues(text)
        applyInjectedCues(boundPlayerView)
    }

    private fun applyInjectedCues(playerView: PlayerView?) {
        playerView?.subtitleView?.setCues(injectedSubtitleCues)
    }

    private fun PlayerView.applyResizeMode(surfaceResizeMode: PlayerSurfaceResizeMode) {
        resizeMode = when (surfaceResizeMode) {
            PlayerSurfaceResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            PlayerSurfaceResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            PlayerSurfaceResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    private fun layoutResId(surfaceType: PlayerRenderSurfaceType): Int = when (surfaceType) {
        PlayerRenderSurfaceType.TEXTURE_VIEW -> R.layout.player_texture_view
        PlayerRenderSurfaceType.AUTO,
        PlayerRenderSurfaceType.SURFACE_VIEW -> R.layout.player_surface_view
    }
}
