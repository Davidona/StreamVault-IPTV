@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlayerPreloadContentType
import com.streamvault.player.PlayerPreloadItem
import com.streamvault.player.PlayerPreloadWindow
import com.streamvault.player.normalizePlayerPreloadWindow
import kotlin.math.abs

internal const val PLAYER_PRELOAD_RANGE_MS = 3_000L

internal fun playerPreloadTargetStatus(
    currentIndex: Int?,
    rankingIndex: Int
): DefaultPreloadManager.PreloadStatus? {
    return if (currentIndex != null && abs(rankingIndex - currentIndex) == 1) {
        DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
            0L,
            PLAYER_PRELOAD_RANGE_MS
        )
    } else {
        null
    }
}

internal fun preloadStreamIdentity(streamInfo: StreamInfo): String {
    val headers = streamInfo.headers.entries
        .sortedBy { it.key }
        .joinToString("&") { "${it.key}=${it.value}" }
    val drm = streamInfo.drmInfo?.let { drmInfo ->
        val drmHeaders = drmInfo.headers.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        listOf(
            drmInfo.scheme,
            drmInfo.staticClearKeyLicense?.fingerprint ?: stableHash(drmInfo.licenseUrl),
            drmHeaders,
            drmInfo.multiSession,
            drmInfo.forceDefaultLicenseUrl,
            drmInfo.playClearContentWithoutKey
        ).joinToString("|")
    }.orEmpty()
    return stableHash(
        listOf(
            streamInfo.url.trim(),
            streamInfo.title.orEmpty(),
            headers,
            streamInfo.userAgent.orEmpty(),
            streamInfo.playbackTransportPolicy,
            streamInfo.allowInvalidSsl,
            streamInfo.proxyHost.trim(),
            streamInfo.proxyPort,
            streamInfo.streamType,
            streamInfo.containerExtension.orEmpty(),
            streamInfo.catchUpUrl.orEmpty(),
            streamInfo.expirationTime,
            drm
        ).joinToString("|")
    )
}

@UnstableApi
internal class Media3PreloadWindowManager(
    private val context: Context,
    private val mediaSourceFactory: PlayerMediaSourceFactory,
    @Suppress("UNUSED_PARAMETER")
    private val dataSourceFactoryProvider: PlayerDataSourceFactoryProvider,
    private val bandwidthMeter: BandwidthMeter
) {

    private data class ManagedCandidate(
        val key: String,
        val streamInfo: StreamInfo,
        val mediaItem: MediaItem,
        val mediaSource: MediaSource,
        val rankingIndex: Int,
        val streamIdentity: String
    )

    private var preloadManager: DefaultPreloadManager? = null
    private var currentIndex: Int? = null
    private var released = false
    private val candidatesByKey = linkedMapOf<String, ManagedCandidate>()
    private val mediaItemsByStreamIdentity = mutableMapOf<String, MediaItem>()

    private val listener = object : PreloadManagerListener {
        override fun onCompleted(mediaItem: MediaItem) {
            safeLog(
                "preload completed mediaId=${mediaItem.mediaId} sourceCount=${candidatesByKey.size}"
            )
        }

        override fun onError(exception: PreloadException) {
            val failed = candidatesByKey.values.firstOrNull { it.mediaItem == exception.mediaItem }
            failed?.let { candidate ->
                preloadManager?.remove(candidate.mediaItem)
                candidatesByKey.remove(candidate.key)
                mediaItemsByStreamIdentity.remove(candidate.streamIdentity)
            }
            safeLog(
                "preload failed mediaId=${exception.mediaItem.mediaId} " +
                    "candidateRemoved=${failed != null} sourceCount=${candidatesByKey.size}"
            )
        }
    }

    fun createPlayer(
        renderersFactory: RenderersFactory,
        loadControl: LoadControl,
        preloadDataSourceFactory: DataSource.Factory,
        configure: (ExoPlayer.Builder) -> Unit
    ): ExoPlayer {
        check(!released) { "Cannot create a preload player after release" }

        val rankingComparator = DefaultPreloadManager.SimpleRankingDataComparator()
        val targetStatusControl = object :
            TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
            override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus =
                playerPreloadTargetStatus(currentIndex, index)
                    ?: DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
        val builder = DefaultPreloadManager.Builder(
            context,
            rankingComparator,
            targetStatusControl
        )
            .setDataSourceFactory(preloadDataSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)

        preloadManager = builder.build().also { it.addListener(listener) }
        currentIndex = null
        safeLog("preload-window manager-created")

        return builder.buildExoPlayer(
            ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
                .apply(configure)
        )
    }

    fun updateWindow(window: PlayerPreloadWindow) {
        val manager = preloadManager ?: return
        if (released) return

        val normalized = normalizePlayerPreloadWindow(window)
        if (normalized.items.isEmpty()) {
            clearWindow()
            return
        }

        currentIndex = normalized.currentIndex
        manager.setCurrentPlayingIndex(normalized.currentIndex)

        val nextCandidates = linkedMapOf<String, ManagedCandidate>()
        normalized.items.forEachIndexed { rankingIndex, item ->
            val streamIdentity = preloadStreamIdentity(item.streamInfo)
            val existing = candidatesByKey[item.key]
            if (existing != null && existing.streamIdentity == streamIdentity) {
                nextCandidates[item.key] = existing.copy(rankingIndex = rankingIndex)
                return@forEachIndexed
            }
            existing?.let { manager.remove(it.mediaItem) }

            val candidate = createManagedCandidate(item, rankingIndex, streamIdentity)
            if (candidate != null) {
                manager.add(candidate.mediaSource, rankingIndex)
                nextCandidates[item.key] = candidate
            }
        }

        candidatesByKey.values
            .filter { it.key !in nextCandidates }
            .forEach { manager.remove(it.mediaItem) }
        candidatesByKey.clear()
        candidatesByKey.putAll(nextCandidates)
        mediaItemsByStreamIdentity.clear()
        candidatesByKey.values.forEach { candidate ->
            mediaItemsByStreamIdentity[candidate.streamIdentity] = candidate.mediaItem
        }
        manager.invalidate()
        safeLog(
            "preload-window accepted currentIndex=${normalized.currentIndex} " +
                "sourceCount=${candidatesByKey.size}"
        )
    }

    fun getMediaSource(streamInfo: StreamInfo): MediaSource? {
        val manager = preloadManager ?: return null
        if (released) return null
        val mediaItem = mediaItemsByStreamIdentity[preloadStreamIdentity(streamInfo)] ?: return null
        return manager.getMediaSource(mediaItem)
    }

    fun clearWindow() {
        val manager = preloadManager ?: return
        if (released) return
        manager.removeMediaItems(candidatesByKey.values.map(ManagedCandidate::mediaItem))
        candidatesByKey.clear()
        mediaItemsByStreamIdentity.clear()
        currentIndex = null
        manager.setCurrentPlayingIndex(-1)
        manager.invalidate()
        safeLog("preload-window reset reason=clear sourceCount=0")
    }

    fun release() {
        if (released) return
        released = true
        preloadManager?.clearListeners()
        preloadManager?.release()
        preloadManager = null
        candidatesByKey.clear()
        mediaItemsByStreamIdentity.clear()
        currentIndex = null
        safeLog("preload-window manager-released")
    }

    private fun createManagedCandidate(
        item: PlayerPreloadItem,
        rankingIndex: Int,
        streamIdentity: String
    ): ManagedCandidate? {
        if (item.contentType == PlayerPreloadContentType.LIVE || item.streamInfo.drmInfo != null) {
            logSkip(item, "ineligible-content")
            return null
        }
        if (item.streamInfo.expirationTime?.let { it <= System.currentTimeMillis() } == true) {
            logSkip(item, "expired")
            return null
        }

        val plan = runCatching {
            buildPlaybackPreparationPlan(
                streamInfo = item.streamInfo,
                preload = true,
                playbackStarted = { false }
            )
        }.getOrElse {
            logSkip(item, "plan-failed")
            return null
        }
        if (plan.resolvedStreamType == ResolvedStreamType.UNKNOWN ||
            plan.resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE ||
            plan.resolvedStreamType == ResolvedStreamType.RTSP
        ) {
            logSkip(item, "unsupported-stream")
            return null
        }

        val mediaSource = runCatching {
            mediaSourceFactory.create(
                streamInfo = item.streamInfo,
                resolvedStreamType = plan.resolvedStreamType,
                retryPolicy = plan.retryPolicy,
                preload = true
            ).second
        }.getOrElse {
            logSkip(item, "source-failed")
            return null
        }
        return ManagedCandidate(
            key = item.key,
            streamInfo = item.streamInfo,
            mediaItem = mediaSource.mediaItem,
            mediaSource = mediaSource,
            rankingIndex = rankingIndex,
            streamIdentity = streamIdentity
        )
    }

    private fun logSkip(item: PlayerPreloadItem, reason: String) {
        safeLog(
            "preload skipped key=${sanitizePreloadKey(item.key)} " +
                "kind=${item.contentType} reason=$reason"
        )
    }

    private fun sanitizePreloadKey(key: String): String =
        key.trim()
            .replace(Regex("[^A-Za-z0-9._:-]"), "_")
            .take(80)

    private fun safeLog(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private companion object {
        const val TAG = "Media3PreloadWindow"
    }
}
