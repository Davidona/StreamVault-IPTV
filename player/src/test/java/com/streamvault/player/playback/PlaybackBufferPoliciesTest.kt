package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferPoliciesTest {

    @Test
    fun `low-memory adaptive live delegates byte sizing to selected tracks`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = false,
            lowMemoryDevice = true,
            bufferMode = PlaybackBufferMode.AUTO,
            streamInfo = StreamInfo(
                url = "https://example.invalid/live/index.m3u8",
                streamType = StreamType.HLS,
                playbackContentKind = PlaybackContentKind.LIVE
            )
        )

        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isFalse()
    }

    @Test
    fun `low-memory automatic UHD live delegates byte sizing to selected tracks`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = false,
            lowMemoryDevice = true,
            bufferMode = PlaybackBufferMode.AUTO,
            streamInfo = StreamInfo(
                url = "https://example.invalid/live/2160p/index.m3u8",
                streamType = StreamType.HLS,
                playbackContentKind = PlaybackContentKind.LIVE
            )
        )

        assertThat(policy.lowMemoryCapped).isTrue()
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isFalse()
    }

    @Test
    fun `buffer policy matrix keeps content intent and explicit modes predictable`() {
        val resolvedTypes = listOf(
            ResolvedStreamType.HLS to StreamType.HLS,
            ResolvedStreamType.DASH to StreamType.DASH,
            ResolvedStreamType.SMOOTH_STREAMING to StreamType.SMOOTH_STREAMING,
            ResolvedStreamType.PROGRESSIVE to StreamType.PROGRESSIVE,
            ResolvedStreamType.MPEG_TS_LIVE to StreamType.MPEG_TS,
            ResolvedStreamType.RTSP to StreamType.RTSP
        )
        val kinds = PlaybackContentKind.entries
        val modes = PlaybackBufferMode.entries

        for (kind in kinds) {
            for ((resolvedType, declaredType) in resolvedTypes) {
                for (lowMemory in listOf(false, true)) {
                    for (mode in modes) {
                        val context = "$kind / $resolvedType / lowMemory=$lowMemory / $mode"
                        val info = StreamInfo(
                            url = "https://example.invalid/content/index.m3u8",
                            streamType = declaredType,
                            playbackContentKind = kind
                        )
                        val actual = PlaybackBufferPolicies.forPlayback(
                            resolvedStreamType = resolvedType,
                            compatibilityMode = false,
                            lowMemoryDevice = lowMemory,
                            bufferMode = mode,
                            streamInfo = info
                        )

                        assertEquals("$context policy", expectedPolicy(kind, resolvedType, lowMemory, mode), actual)
                    }
                }
            }
        }
    }

    private fun expectedPolicy(
        kind: PlaybackContentKind,
        resolvedType: ResolvedStreamType,
        lowMemory: Boolean,
        mode: PlaybackBufferMode
    ): PlaybackBufferPolicy {
        val live = when (kind) {
            PlaybackContentKind.LIVE -> true
            PlaybackContentKind.ON_DEMAND, PlaybackContentKind.CATCH_UP -> false
            PlaybackContentKind.UNKNOWN -> resolvedType in setOf(
                ResolvedStreamType.HLS,
                ResolvedStreamType.SMOOTH_STREAMING,
                ResolvedStreamType.MPEG_TS_LIVE,
                ResolvedStreamType.RTSP
            )
        }

        if (live && mode == PlaybackBufferMode.MEDIUM) {
            return PlaybackBufferPolicy(
                label = "medium-live",
                minBufferMs = 15_000,
                maxBufferMs = 45_000,
                playbackBufferMs = 3_000,
                rebufferMs = 10_000,
                targetBufferBytes = 32 * 1024 * 1024,
                prioritizeTimeOverSizeThresholds = false,
                qualityReason = "user-medium"
            )
        }
        if (live && mode == PlaybackBufferMode.LARGE) {
            return PlaybackBufferPolicy(
                label = "large-live",
                minBufferMs = 30_000,
                maxBufferMs = 90_000,
                playbackBufferMs = 5_000,
                rebufferMs = 15_000,
                targetBufferBytes = 64 * 1024 * 1024,
                prioritizeTimeOverSizeThresholds = false,
                qualityReason = "user-large"
            )
        }

        if (!live && resolvedType == ResolvedStreamType.HLS) {
            return PlaybackBufferPolicy(
                label = "vod-hls-initial",
                minBufferMs = if (lowMemory) 4_000 else 8_000,
                maxBufferMs = if (lowMemory) 12_000 else 30_000,
                playbackBufferMs = if (lowMemory) 1_000 else 1_500,
                rebufferMs = if (lowMemory) 3_000 else 5_000,
                targetBufferBytes = -1,
                prioritizeTimeOverSizeThresholds = false,
                qualityReason = "finite-content"
            )
        }

        if (resolvedType == ResolvedStreamType.MPEG_TS_LIVE) {
            return PlaybackBufferPolicy(
                label = if (lowMemory) "lowmem-mpeg-ts-live" else "mpeg-ts-live",
                minBufferMs = if (lowMemory) 4_000 else 5_000,
                maxBufferMs = if (lowMemory) 12_000 else 10_000,
                playbackBufferMs = if (lowMemory) 1_000 else 1_500,
                rebufferMs = if (lowMemory) 3_000 else 5_000,
                targetBufferBytes = 16 * 1024 * 1024,
                prioritizeTimeOverSizeThresholds = false
            )
        }

        if (lowMemory && live) {
            return PlaybackBufferPolicy(
                label = "lowmem-live",
                minBufferMs = 4_000,
                maxBufferMs = 12_000,
                playbackBufferMs = 1_000,
                rebufferMs = 3_000,
                targetBufferBytes = -1,
                prioritizeTimeOverSizeThresholds = false
            )
        }

        if (lowMemory) {
            return PlaybackBufferPolicy(
                label = if (live) "lowmem-live" else "lowmem-vod",
                minBufferMs = if (live) 4_000 else 15_000,
                maxBufferMs = if (live) 12_000 else 45_000,
                playbackBufferMs = 1_000,
                rebufferMs = 3_000,
                targetBufferBytes = -1,
                prioritizeTimeOverSizeThresholds = false
            )
        }

        return if (live) {
            PlaybackBufferPolicy(
                label = "stable-live",
                minBufferMs = 8_000,
                maxBufferMs = 30_000,
                playbackBufferMs = 1_500,
                rebufferMs = 5_000,
                targetBufferBytes = -1,
                prioritizeTimeOverSizeThresholds = false
            )
        } else {
            PlaybackBufferPolicy(
                label = "stable-vod",
                minBufferMs = 90_000,
                maxBufferMs = 240_000,
                playbackBufferMs = 8_000,
                rebufferMs = 18_000,
                targetBufferBytes = -1,
                prioritizeTimeOverSizeThresholds = false
            )
        }
    }

    @Test
    fun `normal live uses production live buffer baseline`() {
        val policy = PlaybackBufferPolicies.forPlayback(isLive = true, compatibilityMode = false)

        assertThat(policy.label).isEqualTo("stable-live")
        assertThat(policy.minBufferMs).isEqualTo(8_000)
        assertThat(policy.maxBufferMs).isEqualTo(30_000)
        assertThat(policy.playbackBufferMs).isEqualTo(1_500)
        assertThat(policy.rebufferMs).isEqualTo(5_000)
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isFalse()
    }

    @Test
    fun `small live hls uses production live buffer baseline`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = false,
            lowMemoryDevice = false,
            bufferMode = PlaybackBufferMode.SMALL
        )

        assertThat(policy.label).isEqualTo("stable-live")
        assertThat(policy.minBufferMs).isEqualTo(8_000)
        assertThat(policy.maxBufferMs).isEqualTo(30_000)
        assertThat(policy.playbackBufferMs).isEqualTo(1_500)
        assertThat(policy.rebufferMs).isEqualTo(5_000)
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
    }

    @Test
    fun `medium live hls uses deeper buffer and byte target`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = false,
            lowMemoryDevice = false,
            bufferMode = PlaybackBufferMode.MEDIUM
        )

        assertThat(policy.label).isEqualTo("medium-live")
        assertThat(policy.minBufferMs).isEqualTo(15_000)
        assertThat(policy.maxBufferMs).isEqualTo(45_000)
        assertThat(policy.playbackBufferMs).isEqualTo(3_000)
        assertThat(policy.rebufferMs).isEqualTo(10_000)
        assertThat(policy.targetBufferBytes).isEqualTo(32 * 1024 * 1024)
        assertThat(policy.qualityReason).isEqualTo("user-medium")
    }

    @Test
    fun `large live hls uses deepest buffer and byte target`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = false,
            lowMemoryDevice = false,
            bufferMode = PlaybackBufferMode.LARGE
        )

        assertThat(policy.label).isEqualTo("large-live")
        assertThat(policy.minBufferMs).isEqualTo(30_000)
        assertThat(policy.maxBufferMs).isEqualTo(90_000)
        assertThat(policy.playbackBufferMs).isEqualTo(5_000)
        assertThat(policy.rebufferMs).isEqualTo(15_000)
        assertThat(policy.targetBufferBytes).isEqualTo(64 * 1024 * 1024)
        assertThat(policy.qualityReason).isEqualTo("user-large")
    }

    @Test
    fun `explicit live hls still honors user selected medium and large buffers`() {
        val streamInfo = StreamInfo(
            url = "https://example.com/live/channel.m3u8",
            playbackContentKind = PlaybackContentKind.LIVE
        )
        val medium = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = false,
            lowMemoryDevice = false,
            bufferMode = PlaybackBufferMode.MEDIUM,
            streamInfo = streamInfo
        )
        val large = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = false,
            lowMemoryDevice = false,
            bufferMode = PlaybackBufferMode.LARGE,
            streamInfo = streamInfo
        )

        assertThat(medium.label).isEqualTo("medium-live")
        assertThat(medium.targetBufferBytes).isEqualTo(32 * 1024 * 1024)
        assertThat(large.label).isEqualTo("large-live")
        assertThat(large.targetBufferBytes).isEqualTo(64 * 1024 * 1024)
    }

    @Test
    fun `auto live hls promotes to large when metadata hints uhd`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = false,
            lowMemoryDevice = false,
            bufferMode = PlaybackBufferMode.AUTO,
            streamInfo = StreamInfo(
                url = "https://example.com/live/sports_2160p/index.m3u8",
                title = "Sports 4K HDR",
                containerExtension = "m3u8"
            )
        )

        assertThat(policy.label).isEqualTo("auto-uhd-live-hls")
        assertThat(policy.minBufferMs).isEqualTo(30_000)
        assertThat(policy.maxBufferMs).isEqualTo(90_000)
        assertThat(policy.playbackBufferMs).isEqualTo(5_000)
        assertThat(policy.rebufferMs).isEqualTo(15_000)
        assertThat(policy.targetBufferBytes).isEqualTo(64 * 1024 * 1024)
        assertThat(policy.qualityReason).isEqualTo("metadata-2160p")
    }

    @Test
    fun `explicit hls vod keeps baseline buffer sizes and skips quality promotion`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = false,
            lowMemoryDevice = false,
            bufferMode = PlaybackBufferMode.AUTO,
            streamInfo = StreamInfo(
                url = "https://example.com/live/movie_2160p/index.m3u8",
                title = "Movie UHD HDR",
                playbackContentKind = PlaybackContentKind.ON_DEMAND
            )
        )

        assertThat(policy.label).isEqualTo("vod-hls-initial")
        assertThat(policy.minBufferMs).isEqualTo(8_000)
        assertThat(policy.maxBufferMs).isEqualTo(30_000)
        assertThat(policy.playbackBufferMs).isEqualTo(1_500)
        assertThat(policy.rebufferMs).isEqualTo(5_000)
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.qualityReason).isEqualTo("finite-content")
    }

    @Test
    fun `explicit hls vod keeps low memory hls buffer limits`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = false,
            lowMemoryDevice = true,
            bufferMode = PlaybackBufferMode.AUTO,
            streamInfo = StreamInfo(
                url = "https://example.com/movie_uhd.m3u8",
                playbackContentKind = PlaybackContentKind.ON_DEMAND
            )
        )

        assertThat(policy.label).isEqualTo("vod-hls-initial")
        assertThat(policy.minBufferMs).isEqualTo(4_000)
        assertThat(policy.maxBufferMs).isEqualTo(12_000)
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isFalse()
    }

    @Test
    fun `low-memory compatibility live policy enforces its byte budget`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = true,
            lowMemoryDevice = true,
            bufferMode = PlaybackBufferMode.AUTO,
            streamInfo = StreamInfo(
                url = "https://example.invalid/live/index.m3u8",
                streamType = StreamType.HLS,
                playbackContentKind = PlaybackContentKind.LIVE
            )
        )

        assertThat(policy.label).isEqualTo("lowmem-compat-live")
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isFalse()
    }

    @Test
    fun `explicit live dash gets live baseline buffer`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.DASH,
            compatibilityMode = false,
            lowMemoryDevice = false,
            bufferMode = PlaybackBufferMode.AUTO,
            streamInfo = StreamInfo(
                url = "https://example.com/channel.mpd",
                streamType = StreamType.DASH,
                playbackContentKind = PlaybackContentKind.LIVE
            )
        )

        assertThat(policy.label).isEqualTo("stable-live")
        assertThat(policy.minBufferMs).isEqualTo(8_000)
        assertThat(policy.maxBufferMs).isEqualTo(30_000)
        assertThat(policy.playbackBufferMs).isEqualTo(1_500)
        assertThat(policy.rebufferMs).isEqualTo(5_000)
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
    }

    @Test
    fun `auto live hls caps uhd promotion to medium on low memory devices`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = false,
            lowMemoryDevice = true,
            bufferMode = PlaybackBufferMode.AUTO,
            streamInfo = StreamInfo(
                url = "https://example.com/live/uhd/index.m3u8",
                title = "Nature UHD"
            )
        )

        assertThat(policy.label).isEqualTo("auto-uhd-live-hls-capped")
        assertThat(policy.minBufferMs).isEqualTo(15_000)
        assertThat(policy.maxBufferMs).isEqualTo(45_000)
        assertThat(policy.playbackBufferMs).isEqualTo(3_000)
        assertThat(policy.rebufferMs).isEqualTo(10_000)
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.lowMemoryCapped).isTrue()
        assertThat(policy.qualityReason).isEqualTo("metadata-uhd")
    }

    @Test
    fun `compatibility live uses larger live buffer`() {
        val policy = PlaybackBufferPolicies.forPlayback(isLive = true, compatibilityMode = true)

        assertThat(policy.label).isEqualTo("compat-live")
        assertThat(policy.minBufferMs).isEqualTo(15_000)
        assertThat(policy.maxBufferMs).isEqualTo(45_000)
        assertThat(policy.playbackBufferMs).isEqualTo(1_500)
        assertThat(policy.rebufferMs).isEqualTo(5_000)
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isFalse()
    }

    @Test
    fun `compatibility live hls in auto can still promote from metadata`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.HLS,
            compatibilityMode = true,
            lowMemoryDevice = false,
            bufferMode = PlaybackBufferMode.AUTO,
            streamInfo = StreamInfo(url = "https://example.com/live/4k/index.m3u8")
        )

        assertThat(policy.label).isEqualTo("auto-uhd-live-hls")
        assertThat(policy.minBufferMs).isEqualTo(30_000)
        assertThat(policy.targetBufferBytes).isEqualTo(64 * 1024 * 1024)
    }

    @Test
    fun `mpeg ts live lets the device budget bound loading`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
            compatibilityMode = false
        )

        assertThat(policy.label).isEqualTo("mpeg-ts-live")
        assertThat(policy.minBufferMs).isEqualTo(5_000)
        assertThat(policy.maxBufferMs).isEqualTo(10_000)
        assertThat(policy.playbackBufferMs).isEqualTo(1_500)
        assertThat(policy.rebufferMs).isEqualTo(5_000)
        assertThat(policy.targetBufferBytes).isEqualTo(16 * 1024 * 1024)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isFalse()
    }

    @Test
    fun `mpeg ts live remains unchanged in auto even with uhd metadata`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
            compatibilityMode = false,
            lowMemoryDevice = false,
            bufferMode = PlaybackBufferMode.AUTO,
            streamInfo = StreamInfo(url = "https://example.com/live/4k/channel.ts")
        )

        assertThat(policy.label).isEqualTo("mpeg-ts-live")
        assertThat(policy.minBufferMs).isEqualTo(5_000)
        assertThat(policy.maxBufferMs).isEqualTo(10_000)
        assertThat(policy.targetBufferBytes).isEqualTo(16 * 1024 * 1024)
    }

    @Test
    fun `mpeg ts live uses medium only when user selects medium`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
            compatibilityMode = false,
            lowMemoryDevice = false,
            bufferMode = PlaybackBufferMode.MEDIUM
        )

        assertThat(policy.label).isEqualTo("medium-live")
        assertThat(policy.minBufferMs).isEqualTo(15_000)
        assertThat(policy.maxBufferMs).isEqualTo(45_000)
        assertThat(policy.targetBufferBytes).isEqualTo(32 * 1024 * 1024)
    }

    @Test
    fun `vod uses deeper movie buffer`() {
        val policy = PlaybackBufferPolicies.forPlayback(isLive = false, compatibilityMode = false)

        assertThat(policy.label).isEqualTo("stable-vod")
        assertThat(policy.minBufferMs).isEqualTo(90_000)
        assertThat(policy.maxBufferMs).isEqualTo(240_000)
        assertThat(policy.playbackBufferMs).isEqualTo(8_000)
        assertThat(policy.rebufferMs).isEqualTo(18_000)
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isFalse()
    }
}
