package com.streamvault.player.playback

import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultCompositeSequenceableLoaderFactory
import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.DrmInfo
import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.StaticClearKey
import com.streamvault.domain.model.StaticClearKeyLicense
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerMediaSourceFactoryTest {

    @Test
    fun `live hls foreground and preload wire optional rendition policy while finite hls stays default`() {
        val dataSourceProvider = dataSourceProvider()
        val sourceFactory = PlayerMediaSourceFactory(dataSourceProvider)
        val live = liveHls()
        val retryPolicy = PlayerRetryPolicy(
            streamContext = PlaybackRetryContext(
                resolvedStreamType = ResolvedStreamType.HLS,
                timeoutProfile = PlayerTimeoutProfile.LIVE,
                playbackContentKind = PlaybackContentKind.LIVE
            ),
            playbackStarted = { false }
        )

        val foreground = sourceFactory.createMediaSourceFactory(
            live, ResolvedStreamType.HLS, retryPolicy, preload = false
        ).second as HlsMediaSource.Factory
        val preload = sourceFactory.createMediaSourceFactory(
            live, ResolvedStreamType.HLS, retryPolicy, preload = true
        ).second as HlsMediaSource.Factory
        val finite = sourceFactory.createMediaSourceFactory(
            liveHls(PlaybackContentKind.ON_DEMAND), ResolvedStreamType.HLS, retryPolicy, preload = false
        ).second as HlsMediaSource.Factory

        assertThat(compositeLoaderFactory(foreground))
            .isInstanceOf(InFlightOptionalRenditionLoaderFactory::class.java)
        assertThat(compositeLoaderFactory(preload))
            .isInstanceOf(InFlightOptionalRenditionLoaderFactory::class.java)
        assertThat(compositeLoaderFactory(finite))
            .isInstanceOf(DefaultCompositeSequenceableLoaderFactory::class.java)
    }

    @Test
    fun `direct live mpeg ts policy declares single pmt and live duration`() {
        assertThat(DIRECT_LIVE_MPEG_TS_POLICY.extractorMode)
            .isEqualTo(LiveMpegTsPolicy.ExtractorMode.SINGLE_PMT)
        assertThat(DIRECT_LIVE_MPEG_TS_POLICY.duration)
            .isEqualTo(LiveMpegTsPolicy.DurationPolicy.LIVE_UNKNOWN)
        assertThat(DIRECT_LIVE_MPEG_TS_POLICY.reconnect)
            .isEqualTo(LiveMpegTsPolicy.ReconnectPolicy.RECREATE_SOURCE)
    }

    @Test
    fun `policy maps to the Media3 single pmt mode`() {
        assertThat(DIRECT_LIVE_MPEG_TS_POLICY.media3ExtractorMode)
            .isEqualTo(TsExtractor.MODE_SINGLE_PMT)
    }

    @Test
    fun `media source factory exposes preload timeout and progressive source`() {
        val dataSourceProvider = PlayerDataSourceFactoryProvider(
            context = RuntimeEnvironment.getApplication(),
            baseClient = OkHttpClient()
        )
        val mediaSourceFactory = PlayerMediaSourceFactory(dataSourceProvider)
        val streamInfo = StreamInfo(
            url = "https://example.test/video.mp4",
            streamType = StreamType.PROGRESSIVE
        )
        val retryPolicy = PlayerRetryPolicy(
            streamContext = PlaybackRetryContext(
                resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
                timeoutProfile = PlayerTimeoutProfile.PRELOAD
            ),
            playbackStarted = { false }
        )

        val (timeoutProfile, factory) = mediaSourceFactory.createMediaSourceFactory(
            streamInfo = streamInfo,
            resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
            retryPolicy = retryPolicy,
            preload = true
        )
        val mediaSource = factory.createMediaSource(mediaSourceFactory.mediaItemFor(streamInfo))

        assertThat(timeoutProfile).isEqualTo(PlayerTimeoutProfile.PRELOAD)
        assertThat(mediaSource).isInstanceOf(ProgressiveMediaSource::class.java)
    }

    @Test
    fun `static clearKey media item omits remote license URI`() {
        val dataSourceProvider = PlayerDataSourceFactoryProvider(
            context = RuntimeEnvironment.getApplication(),
            baseClient = OkHttpClient()
        )
        val factory = PlayerMediaSourceFactory(dataSourceProvider)
        val streamInfo = StreamInfo(
            url = "https://example.test/channel.mpd",
            streamType = StreamType.DASH,
            drmInfo = DrmInfo(
                scheme = DrmScheme.CLEARKEY,
                staticClearKeyLicense = StaticClearKeyLicense(
                    keys = listOf(StaticClearKey("ABEiM0RVZneImaq7zN3u_w", "_-7dzLuqmYh3ZlVEMyIRAA")),
                    fingerprint = "fingerprint"
                )
            )
        )

        val drmConfiguration = factory.mediaItemFor(streamInfo).localConfiguration?.drmConfiguration

        assertThat(drmConfiguration).isNotNull()
        assertThat(drmConfiguration?.licenseUri?.toString().orEmpty()).isEmpty()
    }

    @Test
    fun `explicit medium and large live hls modes request parser floor without media item bounds`() {
        val dataSourceProvider = dataSourceProvider()
        val streamInfo = liveHls()

        for (mode in listOf(PlaybackBufferMode.MEDIUM, PlaybackBufferMode.LARGE)) {
            val item = PlayerMediaSourceFactory(dataSourceProvider) { mode }
                .mediaItemFor(streamInfo, ResolvedStreamType.HLS)

            assertThat(PlayerMediaSourceFactory(dataSourceProvider) { mode }
                .liveTargetOffsetMsFor(streamInfo, ResolvedStreamType.HLS)).isEqualTo(10_000L)
            assertThat(item.liveConfiguration?.targetOffsetMs).isEqualTo(C.TIME_UNSET)
            assertThat(item.liveConfiguration?.minPlaybackSpeed).isEqualTo(C.RATE_UNSET)
            assertThat(item.liveConfiguration?.maxPlaybackSpeed).isEqualTo(C.RATE_UNSET)
            assertThat(item.liveConfiguration?.minOffsetMs).isEqualTo(C.TIME_UNSET)
            assertThat(item.liveConfiguration?.maxOffsetMs).isEqualTo(C.TIME_UNSET)
        }
    }

    @Test
    fun `auto and small modes leave live hls configuration unchanged`() {
        val dataSourceProvider = dataSourceProvider()
        val streamInfo = liveHls()
        val defaultItem = PlayerMediaSourceFactory(dataSourceProvider)
            .mediaItemFor(streamInfo, ResolvedStreamType.HLS)

        for (mode in listOf(PlaybackBufferMode.AUTO, PlaybackBufferMode.SMALL)) {
            val item = PlayerMediaSourceFactory(dataSourceProvider) { mode }
                .mediaItemFor(streamInfo, ResolvedStreamType.HLS)

            assertThat(item.liveConfiguration).isEqualTo(defaultItem.liveConfiguration)
            assertThat(item.liveConfiguration?.targetOffsetMs).isEqualTo(C.TIME_UNSET)
        }
    }

    @Test
    fun `live target applies only to known live content resolved as hls`() {
        val factory = PlayerMediaSourceFactory(dataSourceProvider()) { PlaybackBufferMode.MEDIUM }
        val cases = listOf(
            liveHls(PlaybackContentKind.ON_DEMAND) to ResolvedStreamType.HLS,
            liveHls(PlaybackContentKind.CATCH_UP) to ResolvedStreamType.HLS,
            liveHls(PlaybackContentKind.UNKNOWN) to ResolvedStreamType.HLS,
            liveHls() to ResolvedStreamType.DASH,
            liveTs() to ResolvedStreamType.MPEG_TS_LIVE,
            liveHls() to ResolvedStreamType.UNKNOWN
        )

        cases.forEach { (streamInfo, resolvedType) ->
            assertThat(factory.mediaItemFor(streamInfo, resolvedType).liveConfiguration?.targetOffsetMs)
                .isEqualTo(C.TIME_UNSET)
        }
    }

    @Test
    fun `manual live hls target separates media ids while medium and large share identity`() {
        val dataSourceProvider = dataSourceProvider()
        val streamInfo = liveHls()
        val defaultId = PlayerMediaSourceFactory(dataSourceProvider)
            .mediaIdFor(streamInfo, ResolvedStreamType.HLS)
        val mediumId = PlayerMediaSourceFactory(dataSourceProvider) { PlaybackBufferMode.MEDIUM }
            .mediaIdFor(streamInfo, ResolvedStreamType.HLS)
        val largeId = PlayerMediaSourceFactory(dataSourceProvider) { PlaybackBufferMode.LARGE }
            .mediaIdFor(streamInfo, ResolvedStreamType.HLS)
        val vod = streamInfo.copy(playbackContentKind = PlaybackContentKind.ON_DEMAND)
        val mediumVodId = PlayerMediaSourceFactory(dataSourceProvider) { PlaybackBufferMode.MEDIUM }
            .mediaIdFor(vod, ResolvedStreamType.HLS)

        assertThat(mediumId).isNotEqualTo(defaultId)
        assertThat(largeId).isEqualTo(mediumId)
        assertThat(mediumVodId)
            .isEqualTo(PlayerMediaSourceFactory(dataSourceProvider).mediaIdFor(vod, ResolvedStreamType.HLS))
    }

    @Test
    fun `target-aware source ids do not change compatibility identity across mode switches`() {
        val dataSourceProvider = dataSourceProvider()
        val streamInfo = liveHls()
        var mode = PlaybackBufferMode.AUTO
        val factory = PlayerMediaSourceFactory(dataSourceProvider) { mode }
        val legacyId = factory.compatibilityMediaIdFor(streamInfo)
        val autoSourceId = factory.mediaIdFor(streamInfo, ResolvedStreamType.HLS)

        mode = PlaybackBufferMode.MEDIUM
        val mediumSourceId = factory.mediaIdFor(streamInfo, ResolvedStreamType.HLS)
        val mediumCompatibilityId = factory.compatibilityMediaIdFor(streamInfo)
        mode = PlaybackBufferMode.LARGE
        val largeSourceId = factory.mediaIdFor(streamInfo, ResolvedStreamType.HLS)

        assertThat(autoSourceId).isEqualTo(legacyId)
        assertThat(mediumCompatibilityId).isEqualTo(legacyId)
        assertThat(factory.compatibilityMediaIdFor(streamInfo)).isEqualTo(legacyId)
        assertThat(mediumSourceId).isNotEqualTo(autoSourceId)
        assertThat(largeSourceId).isEqualTo(mediumSourceId)
        assertThat(largeSourceId).isNotEqualTo(legacyId)
    }

    @Test
    fun `manual live hls parser floor preserves request headers and drm configuration`() {
        val dataSourceProvider = dataSourceProvider()
        val drm = DrmInfo(
            scheme = DrmScheme.WIDEVINE,
            licenseUrl = "https://license.example.test/widevine",
            headers = mapOf("X-License-Token" to "test-token")
        )
        val streamInfo = liveHls().copy(
            headers = mapOf("Authorization" to "Bearer test-token"),
            drmInfo = drm
        )
        val item = PlayerMediaSourceFactory(dataSourceProvider) { PlaybackBufferMode.MEDIUM }
            .mediaItemFor(streamInfo, ResolvedStreamType.HLS)
        val drmConfiguration = item.localConfiguration?.drmConfiguration

        assertThat(item.localConfiguration?.uri?.toString()).isEqualTo(streamInfo.url)
        assertThat(drmConfiguration?.licenseUri?.toString()).isEqualTo(drm.licenseUrl)
        assertThat(drmConfiguration?.licenseRequestHeaders).isEqualTo(drm.headers)
        assertThat(item.liveConfiguration?.targetOffsetMs).isEqualTo(C.TIME_UNSET)
        assertThat(PlayerMediaSourceFactory(dataSourceProvider) { PlaybackBufferMode.MEDIUM }
            .liveTargetOffsetMsFor(streamInfo, ResolvedStreamType.HLS)).isEqualTo(10_000L)
    }

    private fun dataSourceProvider() = PlayerDataSourceFactoryProvider(
        context = RuntimeEnvironment.getApplication(),
        baseClient = OkHttpClient()
    )

    private fun liveHls(
        contentKind: PlaybackContentKind = PlaybackContentKind.LIVE
    ) = StreamInfo(
        url = "https://example.test/live/channel.m3u8",
        streamType = StreamType.HLS,
        containerExtension = "m3u8",
        playbackContentKind = contentKind
    )

    private fun liveTs() = StreamInfo(
        url = "https://example.test/live/channel.ts",
        streamType = StreamType.MPEG_TS,
        containerExtension = "ts",
        playbackContentKind = PlaybackContentKind.LIVE
    )

    private fun compositeLoaderFactory(factory: HlsMediaSource.Factory): Any {
        val field = HlsMediaSource.Factory::class.java
            .getDeclaredField("compositeSequenceableLoaderFactory")
        field.isAccessible = true
        return requireNotNull(field.get(factory))
    }
}
