package com.streamvault.player.playback

import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlaybackContentKind
import androidx.media3.common.C
import com.streamvault.domain.model.StreamType
import com.streamvault.player.PlayerPreloadContentType
import com.streamvault.player.PlayerPreloadItem
import com.streamvault.player.PlayerPreloadWindow
import com.streamvault.domain.model.StreamInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
class Media3PreloadWindowManagerTest {

    @Test
    fun `suspension cancels incomplete reads but keeps completed neighbor handoff`() {
        assertThat(playerPreloadTargetStatus(1, 2, readsAllowed = false, completed = false)).isNull()
        assertThat(playerPreloadTargetStatus(1, 2, readsAllowed = false, completed = true))
            .isEqualTo(DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(0L, 3_000L))
        assertThat(playerPreloadTargetStatus(1, 1, readsAllowed = false, completed = true)).isNull()
    }

    @Test
    fun `target status preloads only immediate neighbors`() {
        val adjacent = DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(0L, 3_000L)

        assertThat(playerPreloadTargetStatus(currentIndex = 1, rankingIndex = 0))
            .isEqualTo(adjacent)
        assertThat(playerPreloadTargetStatus(currentIndex = 1, rankingIndex = 2))
            .isEqualTo(adjacent)
        assertThat(playerPreloadTargetStatus(currentIndex = 1, rankingIndex = 1)).isNull()
        assertThat(playerPreloadTargetStatus(currentIndex = 1, rankingIndex = 3)).isNull()
        assertThat(playerPreloadTargetStatus(currentIndex = null, rankingIndex = 0)).isNull()
    }

    @Test
    fun `ranking comparator prefers the nearer candidate after current moves`() {
        val comparator = DefaultPreloadManager.SimpleRankingDataComparator()

        comparator.setCurrentPlayingIndex(2)
        assertThat(comparator.compare(1, 4)).isLessThan(0)

        comparator.setCurrentPlayingIndex(4)
        assertThat(comparator.compare(3, 1)).isLessThan(0)
    }

    @Test
    fun `stream identity includes request metadata as well as media identity`() {
        val base = StreamInfo(url = "https://example.com/movie.mp4")
        val changedHeaders = base.copy(headers = mapOf("Authorization" to "Bearer different"))

        assertThat(preloadStreamIdentity(base)).isNotEqualTo(preloadStreamIdentity(changedHeaders))
        assertThat(preloadStreamIdentity(base)).isEqualTo(preloadStreamIdentity(base.copy()))
    }

    @Test
    fun `manager creates foreground player from the preload builder`() {
        val context = RuntimeEnvironment.getApplication()
        val dataSourceProvider = PlayerDataSourceFactoryProvider(
            context = context,
            baseClient = okhttp3.OkHttpClient()
        )
        val manager = Media3PreloadWindowManager(
            context = context,
            mediaSourceFactory = PlayerMediaSourceFactory(dataSourceProvider),
            dataSourceFactoryProvider = dataSourceProvider,
            bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        )

        val player = manager.createPlayer(
            renderersFactory = DefaultRenderersFactory(context),
            loadControl = DefaultLoadControl(),
            preloadDataSourceFactory = DefaultDataSource.Factory(context)
        ) {
            it.setReleaseTimeoutMs(0L)
        }

        assertThat(player).isNotNull()
        player.release()
        manager.release()
    }

    @Test
    fun `manager looks up exact sources and replaces changed candidates`() {
        val context = RuntimeEnvironment.getApplication()
        val dataSourceProvider = PlayerDataSourceFactoryProvider(
            context = context,
            baseClient = okhttp3.OkHttpClient()
        )
        val manager = Media3PreloadWindowManager(
            context = context,
            mediaSourceFactory = PlayerMediaSourceFactory(dataSourceProvider),
            dataSourceFactoryProvider = dataSourceProvider,
            bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        )
        val player = manager.createPlayer(
            renderersFactory = DefaultRenderersFactory(context),
            loadControl = DefaultLoadControl(),
            preloadDataSourceFactory = DefaultDataSource.Factory(context)
        ) {
            it.setReleaseTimeoutMs(0L)
        }

        val current = StreamInfo(
            url = "file:///managed-current.mp4",
            streamType = StreamType.PROGRESSIVE
        )
        val next = StreamInfo(
            url = "file:///managed-next.mp4",
            streamType = StreamType.PROGRESSIVE
        )
        val changedNext = next.copy(url = "file:///managed-next-replaced.mp4")

        manager.updateWindow(
            PlayerPreloadWindow(
                items = listOf(
                    PlayerPreloadItem("current", current, PlayerPreloadContentType.VOD),
                    PlayerPreloadItem("next", next, PlayerPreloadContentType.VOD),
                    PlayerPreloadItem(
                        "live",
                        StreamInfo(
                            url = "file:///live.ts",
                            streamType = StreamType.MPEG_TS
                        ),
                        PlayerPreloadContentType.LIVE
                    )
                ),
                currentIndex = 0
            )
        )

        val currentSource = manager.getMediaSource(current)?.mediaSource
        val nextSource = manager.getMediaSource(next)?.mediaSource
        assertThat(currentSource).isNotNull()
        assertThat(nextSource).isNotNull()
        manager.updateForegroundPlayback(com.streamvault.player.PlaybackState.READY, true, 20_000L, false)
        manager.updateForegroundPlayback(com.streamvault.player.PlaybackState.BUFFERING, false, 0L, true)
        assertThat(manager.getMediaSource(next)?.mediaSource).isSameInstanceAs(nextSource)
        assertThat(
            manager.getMediaSource(
                StreamInfo(url = "file:///not-managed.mp4", streamType = StreamType.PROGRESSIVE)
            )
        ).isNull()
        assertThat(
            manager.getMediaSource(
                StreamInfo(url = "file:///live.ts", streamType = StreamType.MPEG_TS)
            )
        ).isNull()

        manager.updateWindow(
            PlayerPreloadWindow(
                items = listOf(
                    PlayerPreloadItem("current", current, PlayerPreloadContentType.VOD),
                    PlayerPreloadItem("next", changedNext, PlayerPreloadContentType.VOD)
                ),
                currentIndex = 0
            )
        )

        assertThat(manager.getMediaSource(current)?.mediaSource).isSameInstanceAs(currentSource)
        assertThat(manager.getMediaSource(next)).isNull()
        assertThat(manager.getMediaSource(changedNext)?.mediaSource).isNotNull()

        player.release()
        manager.release()
    }

    @Test
    fun `mode change cannot hand off a deliberately mislabeled live stream's previous target source`() {
        val context = RuntimeEnvironment.getApplication()
        val dataSourceProvider = PlayerDataSourceFactoryProvider(
            context = context,
            baseClient = okhttp3.OkHttpClient()
        )
        var mode = PlaybackBufferMode.AUTO
        val mediaSourceFactory = PlayerMediaSourceFactory(dataSourceProvider) { mode }
        val manager = Media3PreloadWindowManager(
            context = context,
            mediaSourceFactory = mediaSourceFactory,
            dataSourceFactoryProvider = dataSourceProvider,
            bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        )
        val player = manager.createPlayer(
            renderersFactory = DefaultRenderersFactory(context),
            loadControl = DefaultLoadControl(),
            preloadDataSourceFactory = DefaultDataSource.Factory(context)
        ) {
            it.setReleaseTimeoutMs(0L)
        }
        val liveHls = StreamInfo(
            url = "https://example.test/live/channel.m3u8",
            streamType = StreamType.HLS,
            containerExtension = "m3u8",
            playbackContentKind = PlaybackContentKind.LIVE
        )
        // Normal LIVE entries are excluded from preloading. Deliberately label this LIVE
        // StreamInfo as VOD to exercise defensive cache identity if such an item is supplied.
        val window = PlayerPreloadWindow(
            items = listOf(PlayerPreloadItem("managed-live-hls", liveHls, PlayerPreloadContentType.VOD)),
            currentIndex = 0
        )

        manager.updateWindow(window)
        val previous = manager.getMediaSource(liveHls)!!
        val previousMediaId = previous.mediaSource.mediaItem.mediaId
        mode = PlaybackBufferMode.MEDIUM

        assertThat(manager.getMediaSource(liveHls)).isNull()

        manager.updateWindow(window)
        val updated = manager.getMediaSource(liveHls)!!

        assertThat(updated.mediaSource).isNotSameInstanceAs(previous.mediaSource)
        assertThat(updated.mediaSource.mediaItem.mediaId).isNotEqualTo(previousMediaId)

        player.release()
        manager.release()
    }

    @Test
    fun `handed off source retains its retry policy and reports foreground recovery changes`() {
        val context = RuntimeEnvironment.getApplication()
        val dataSourceProvider = PlayerDataSourceFactoryProvider(
            context = context,
            baseClient = okhttp3.OkHttpClient()
        )
        var playbackStarted = false
        var recoveryNotifications = 0
        val manager = Media3PreloadWindowManager(
            context = context,
            mediaSourceFactory = PlayerMediaSourceFactory(dataSourceProvider),
            dataSourceFactoryProvider = dataSourceProvider,
            bandwidthMeter = DefaultBandwidthMeter.Builder(context).build(),
            onForegroundRecoveryChanged = { recoveryNotifications++ },
            playbackStarted = { playbackStarted }
        )
        val player = manager.createPlayer(
            renderersFactory = DefaultRenderersFactory(context),
            loadControl = DefaultLoadControl(),
            preloadDataSourceFactory = DefaultDataSource.Factory(context)
        ) {
            it.setReleaseTimeoutMs(0L)
        }
        val stream = StreamInfo(
            url = "file:///managed-retry.mp4",
            streamType = StreamType.PROGRESSIVE
        )
        manager.updateWindow(
            PlayerPreloadWindow(
                items = listOf(PlayerPreloadItem("retry", stream, PlayerPreloadContentType.VOD)),
                currentIndex = 0
            )
        )

        val managed = manager.getMediaSource(stream)!!
        assertThat(manager.getMediaSource(stream)?.retryPolicy).isSameInstanceAs(managed.retryPolicy)
        val dataSpec = DataSpec.Builder().setUri("https://example.com/movie.mp4").build()
        fun timeoutAtAttempt(attempt: Int): LoadErrorHandlingPolicy.LoadErrorInfo {
            val event = LoadEventInfo.Builder(LoadEventInfo.getNewId(), dataSpec, 0L).build()
            return LoadErrorHandlingPolicy.LoadErrorInfo(
                event,
                MediaLoadData(C.DATA_TYPE_MEDIA),
                SocketTimeoutException("timed out"),
                attempt
            )
        }

        assertThat(managed.retryPolicy.getRetryDelayMsFor(timeoutAtAttempt(attempt = 3)))
            .isEqualTo(C.TIME_UNSET)
        playbackStarted = true
        val foregroundTimeout = timeoutAtAttempt(attempt = 3)
        assertThat(managed.retryPolicy.getRetryDelayMsFor(foregroundTimeout)).isEqualTo(5_000L)
        assertThat(recoveryNotifications).isEqualTo(1)
        managed.retryPolicy.onLoadTaskConcluded(foregroundTimeout.loadEventInfo.loadTaskId)
        assertThat(recoveryNotifications).isEqualTo(2)

        player.release()
        manager.release()
    }
}
