package com.streamvault.player.playback

import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ts.TsExtractor
import com.google.common.truth.Truth.assertThat
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
}
