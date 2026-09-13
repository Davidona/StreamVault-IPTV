package com.streamvault.player.playback

import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamType
import com.streamvault.player.PlayerPreloadContentType
import com.streamvault.player.PlayerPreloadItem
import com.streamvault.player.PlayerPreloadWindow
import com.streamvault.domain.model.StreamInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Media3PreloadWindowManagerTest {

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

        val currentSource = manager.getMediaSource(current)
        val nextSource = manager.getMediaSource(next)
        assertThat(currentSource).isNotNull()
        assertThat(nextSource).isNotNull()
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

        assertThat(manager.getMediaSource(current)).isSameInstanceAs(currentSource)
        assertThat(manager.getMediaSource(next)).isNull()
        assertThat(manager.getMediaSource(changedNext)).isNotNull()

        player.release()
        manager.release()
    }
}
