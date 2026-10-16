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
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
import com.streamvault.player.PlaybackState
import com.streamvault.player.normalizePlayerPreloadWindow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

internal const val PLAYER_PRELOAD_RANGE_MS = 3_000L

internal fun playerPreloadTargetStatus(
    currentIndex: Int?,
    rankingIndex: Int,
    readsAllowed: Boolean = true,
    completed: Boolean = false,
    sourcePrepareOnly: Boolean = false
): DefaultPreloadManager.PreloadStatus? {
    return if ((readsAllowed || completed) && currentIndex != null && abs(rankingIndex - currentIndex) == 1) {
        if (sourcePrepareOnly) {
            DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
        } else {
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                0L,
                PLAYER_PRELOAD_RANGE_MS
            )
        }
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
    private val bandwidthMeter: BandwidthMeter,
    private val onForegroundRecoveryChanged: () -> Unit = {},
    private val playbackStarted: () -> Boolean = { false }
) {

    data class ManagedMediaSource(
        val mediaSource: MediaSource,
        val retryPolicy: PlayerRetryPolicy
    )

    private data class ManagedCandidate(
        val key: String,
        val streamInfo: StreamInfo,
        val mediaItem: MediaItem,
        val mediaSource: MediaSource,
        val retryPolicy: PlayerRetryPolicy,
        val wasHandedToPlayback: AtomicBoolean,
        val rankingIndex: Int,
        val streamIdentity: String
    )

    private var preloadManager: DefaultPreloadManager? = null
    private var currentIndex: Int? = null
    private var released = false
    private val candidatesByKey = linkedMapOf<String, ManagedCandidate>()
    private val mediaItemsByStreamIdentity = mutableMapOf<String, MediaItem>()
    private val completedMediaItems = mutableSetOf<MediaItem>()
    private val candidatesByRanking = mutableMapOf<Int, ManagedCandidate>()
    private val foregroundMediaItems = mutableSetOf<MediaItem>()
    private var readsAllowed = false
    private var qualityPolicyAllowsVideoPreload = true
    private var providerLimitBlocked = false

    private val listener = object : PreloadManagerListener {
        override fun onCompleted(mediaItem: MediaItem) {
            val candidate = candidatesByKey.values.firstOrNull { it.mediaItem == mediaItem }
            if (candidate != null && mediaItem !in foregroundMediaItems && readsAllowed && qualityPolicyAllowsVideoPreload &&
                currentIndex?.let { abs(candidate.rankingIndex - it) == 1 } == true
            ) {
                completedMediaItems += mediaItem
            }
            safeLog(
                "preload completed mediaId=${mediaItem.mediaId} sourceCount=${candidatesByKey.size}"
            )
        }

        override fun onError(exception: PreloadException) {
            val failed = candidatesByKey.values.firstOrNull { it.mediaItem == exception.mediaItem }
            if (PlayerErrorClassifier.classify(exception) == PlaybackErrorCategory.PROVIDER_LIMIT) {
                providerLimitBlocked = true
                readsAllowed = false
                safeLog("preload-window disabled reason=provider-limit")
            }
            failed?.let { candidate ->
                completedMediaItems.remove(candidate.mediaItem)
                foregroundMediaItems.remove(candidate.mediaItem)
                candidatesByRanking.remove(candidate.rankingIndex)
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
                playerPreloadTargetStatus(
                    currentIndex,
                    index,
                    readsAllowed = readsAllowed,
                    completed = qualityPolicyAllowsVideoPreload &&
                        candidatesByRanking[index]?.mediaItem in completedMediaItems,
                    sourcePrepareOnly = !qualityPolicyAllowsVideoPreload
                )
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
            .setTrackSelectorFactory { selectorContext ->
                DefaultTrackSelector(selectorContext).apply {
                    parameters = parameters.buildUpon()
                        .setExceedVideoConstraintsIfNecessary(false)
                        .build()
                }
            }
            .setBandwidthMeter(bandwidthMeter)

        preloadManager = builder.build().also { it.addListener(listener) }
        currentIndex = null
        readsAllowed = false
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

        val currentItem = normalized.items[normalized.currentIndex]
        val reusableCurrent = candidatesByKey[currentItem.key]
            ?.takeIf { it.streamIdentity == preloadStreamIdentity(currentItem.streamInfo) }
        if (reusableCurrent == null) providerLimitBlocked = false
        val stableCurrentIndex = reusableCurrent?.rankingIndex ?: normalized.currentIndex

        val nextCandidates = linkedMapOf<String, ManagedCandidate>()
        normalized.items.forEachIndexed { rankingIndex, item ->
            val streamIdentity = preloadStreamIdentity(item.streamInfo)
            val expectedRankingIndex = stableCurrentIndex + rankingIndex - normalized.currentIndex
            val existing = candidatesByKey[item.key]
            if (existing != null && existing.streamIdentity == streamIdentity &&
                existing.rankingIndex == expectedRankingIndex
            ) {
                nextCandidates[item.key] = existing
                return@forEachIndexed
            }
            existing?.let {
                manager.remove(it.mediaItem)
                completedMediaItems.remove(it.mediaItem)
            }

            val candidate = createManagedCandidate(item, expectedRankingIndex, streamIdentity)
            if (candidate != null) {
                manager.add(candidate.mediaSource, expectedRankingIndex)
                // RankingData is immutable inside Media3; every added candidate must use its
                // stable ranking index, not the candidate's transient list position.
                nextCandidates[item.key] = candidate
            }
        }

        candidatesByKey.values
            .filter { it.key !in nextCandidates }
            .forEach {
                manager.remove(it.mediaItem)
                completedMediaItems.remove(it.mediaItem)
            }
        candidatesByKey.clear()
        candidatesByKey.putAll(nextCandidates)
        candidatesByRanking.clear()
        candidatesByKey.values.forEach { candidate -> candidatesByRanking[candidate.rankingIndex] = candidate }
        val adjacentCompletedItems = candidatesByKey.values
            .filter { abs(it.rankingIndex - stableCurrentIndex) == 1 }
            .mapTo(mutableSetOf()) { it.mediaItem }
        completedMediaItems.retainAll(adjacentCompletedItems)
        foregroundMediaItems.retainAll(candidatesByKey.values.mapTo(mutableSetOf()) { it.mediaItem })
        currentIndex = stableCurrentIndex
        manager.setCurrentPlayingIndex(stableCurrentIndex)
        mediaItemsByStreamIdentity.clear()
        candidatesByKey.values.forEach { candidate ->
            mediaItemsByStreamIdentity[candidate.streamIdentity] = candidate.mediaItem
        }
        manager.invalidate()
        safeLog(
                "preload-window accepted currentIndex=$stableCurrentIndex " +
                "sourceCount=${candidatesByKey.size}"
        )
    }

    fun setQualityPolicyAllowsVideoPreload(allowed: Boolean) {
        if (qualityPolicyAllowsVideoPreload == allowed) return
        qualityPolicyAllowsVideoPreload = allowed
        if (!allowed) {
            clearWindow()
        } else {
            preloadManager?.invalidate()
        }
        safeLog("preload-window quality-policy videoPreloadAllowed=$allowed")
    }

    fun getMediaSource(streamInfo: StreamInfo): ManagedMediaSource? {
        val manager = preloadManager ?: return null
        if (released) return null
        val mediaItem = mediaItemsByStreamIdentity[preloadStreamIdentity(streamInfo)] ?: return null
        val candidate = candidatesByKey.values.firstOrNull { it.mediaItem == mediaItem } ?: return null
        completedMediaItems.remove(mediaItem)
        foregroundMediaItems += mediaItem
        val mediaSource = manager.getMediaSource(mediaItem) ?: return null
        candidate.wasHandedToPlayback.set(true)
        if (candidate.retryPolicy.hasPendingLoadRecovery) onForegroundRecoveryChanged()
        return ManagedMediaSource(mediaSource, candidate.retryPolicy)
    }

    fun updateForegroundPlayback(
        state: PlaybackState,
        isPlaying: Boolean,
        bufferedDurationMs: Long,
        recovering: Boolean
    ) {
        if (released) return
        val allowed = shouldAllowNeighborPreload(
            readsAllowed, state, isPlaying, bufferedDurationMs, recovering, providerLimitBlocked
        )
        if (allowed == readsAllowed) return
        readsAllowed = allowed
        // NOT_PRELOADED cancels incomplete media periods. Completed ranges keep their handoff data.
        preloadManager?.invalidate()
        safeLog("preload-window readsAllowed=$allowed bufferMs=$bufferedDurationMs state=$state")
    }

    fun clearWindow() {
        val manager = preloadManager ?: return
        if (released) return
        manager.removeMediaItems(candidatesByKey.values.map(ManagedCandidate::mediaItem))
        candidatesByKey.clear()
        candidatesByRanking.clear()
        mediaItemsByStreamIdentity.clear()
        completedMediaItems.clear()
        foregroundMediaItems.clear()
        readsAllowed = false
        providerLimitBlocked = false
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
        candidatesByRanking.clear()
        mediaItemsByStreamIdentity.clear()
        completedMediaItems.clear()
        foregroundMediaItems.clear()
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

        val wasHandedToPlayback = AtomicBoolean(false)
        val plan = runCatching {
            buildPlaybackPreparationPlan(
                streamInfo = item.streamInfo,
                preload = true,
                playbackStarted = { wasHandedToPlayback.get() && playbackStarted() },
                onLoadRecoveryChanged = {
                    // The policy is first used for background preloading, then reused if this
                    // exact source is handed to ExoPlayer. Refresh on either transition; the
                    // query below decides whether this candidate is currently foreground.
                    if (wasHandedToPlayback.get()) {
                        onForegroundRecoveryChanged()
                    }
                }
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
            retryPolicy = plan.retryPolicy,
            wasHandedToPlayback = wasHandedToPlayback,
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
