@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.source.DefaultCompositeSequenceableLoaderFactory
import androidx.media3.exoplayer.source.SequenceableLoader
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HlsRenditionLoadingPolicyTest {

    @Test
    fun `busy text loader pins Media3 default but live policy lets video continue`() {
        val defaultText = FakeLoader(nextUs = 2 * SECOND_US, loading = true)
        val defaultVideo = FakeLoader(nextUs = 4 * SECOND_US) { loader ->
            if (loader.nextUs < 6 * SECOND_US) {
                loader.nextUs += 2 * SECOND_US
                true
            } else false
        }
        val default = DefaultCompositeSequenceableLoaderFactory().create(
            listOf(defaultVideo, defaultText),
            listOf(listOf(C.TRACK_TYPE_VIDEO), listOf(C.TRACK_TYPE_TEXT))
        )

        assertThat(default.nextLoadPositionUs).isEqualTo(2 * SECOND_US)
        default.continueLoading(loadingInfo())
        assertThat(defaultVideo.continueCalls).isEmpty()

        val policyText = FakeLoader(nextUs = 2 * SECOND_US, loading = true)
        val policyVideo = FakeLoader(nextUs = 4 * SECOND_US) { loader ->
            if (loader.nextUs < 6 * SECOND_US) {
                loader.nextUs += 2 * SECOND_US
                true
            } else false
        }
        val policy = InFlightOptionalRenditionLoaderFactory().create(
            listOf(policyVideo, policyText),
            listOf(listOf(C.TRACK_TYPE_VIDEO), listOf(C.TRACK_TYPE_TEXT))
        )

        assertThat(policy.nextLoadPositionUs).isEqualTo(4 * SECOND_US)
        policy.continueLoading(loadingInfo())

        assertThat(policyVideo.continueCalls).containsExactly(4 * SECOND_US, 6 * SECOND_US).inOrder()
        assertThat(policyText.continueCalls).isEmpty()
        assertThat(policy.nextLoadPositionUs).isEqualTo(6 * SECOND_US)
    }

    @Test
    fun `nonloading text rendition retains normal catchup without exceeding loader budget`() {
        val text = FakeLoader(nextUs = 4 * SECOND_US, maxLoadPositionUs = 6 * SECOND_US)
        val video = FakeLoader(nextUs = 2 * SECOND_US, maxLoadPositionUs = 6 * SECOND_US)
        val loader = policy(video, text)

        loader.continueLoading(loadingInfo())

        assertThat(text.continueCalls).isNotEmpty()
        assertThat(video.continueCalls)
            .containsAtLeast(2 * SECOND_US, 4 * SECOND_US)
        assertThat((video.continueCalls + text.continueCalls).max()).isAtMost(6 * SECOND_US)
    }

    @Test
    fun `text rendition becoming busy during continue does not extend av load budget`() {
        val video = FakeLoader(nextUs = 10 * SECOND_US) { loader ->
            loader.nextUs += 2 * SECOND_US
            true
        }
        val text = FakeLoader(nextUs = 2 * SECOND_US) { loader ->
            loader.nextUs = 4 * SECOND_US
            loader.loading = true
            true
        }
        val loader = policy(video, text)

        loader.continueLoading(loadingInfo())

        assertThat(video.continueCalls).isEmpty()
        assertThat(text.continueCalls).contains(2 * SECOND_US)
        assertThat(loader.nextLoadPositionUs).isEqualTo(10 * SECOND_US)

        text.loading = false

        assertThat(loader.nextLoadPositionUs).isEqualTo(4 * SECOND_US)
    }

    @Test
    fun `inflight audio video loaders remain coupled`() {
        val video = FakeLoader(nextUs = 2 * SECOND_US, loading = true)
        val audio = FakeLoader(nextUs = 4 * SECOND_US)
        val text = FakeLoader(nextUs = SECOND_US, loading = true)
        val loader = InFlightOptionalRenditionLoaderFactory().create(
            listOf(video, audio, text),
            listOf(
                listOf(C.TRACK_TYPE_VIDEO),
                listOf(C.TRACK_TYPE_AUDIO),
                listOf(C.TRACK_TYPE_TEXT)
            )
        )

        assertThat(loader.nextLoadPositionUs).isEqualTo(2 * SECOND_US)
        loader.continueLoading(loadingInfo())

        assertThat(video.continueCalls).containsExactly(2 * SECOND_US)
        assertThat(audio.continueCalls).isEmpty()
        assertThat(text.continueCalls).isEmpty()
    }

    @Test
    fun `inflight metadata rendition does not pin live video loading`() {
        val video = FakeLoader(nextUs = 4 * SECOND_US) { loader ->
            if (loader.nextUs < 6 * SECOND_US) {
                loader.nextUs += 2 * SECOND_US
                true
            } else false
        }
        val metadata = FakeLoader(nextUs = 2 * SECOND_US, loading = true)
        val loader = InFlightOptionalRenditionLoaderFactory().create(
            listOf(video, metadata),
            listOf(listOf(C.TRACK_TYPE_VIDEO), listOf(C.TRACK_TYPE_METADATA))
        )

        assertThat(loader.nextLoadPositionUs).isEqualTo(4 * SECOND_US)
        loader.continueLoading(loadingInfo())

        assertThat(video.continueCalls).contains(4 * SECOND_US)
        assertThat(metadata.continueCalls).isEmpty()
    }

    @Test
    fun `unknown mixed and image loaders are not classified as optional`() {
        val primaryVideo = FakeLoader(nextUs = 8 * SECOND_US)
        val busyText = FakeLoader(nextUs = SECOND_US, loading = true)
        val unknown = FakeLoader(nextUs = 3 * SECOND_US) { false }
        val mixed = FakeLoader(nextUs = 5 * SECOND_US, loading = true)
        val image = FakeLoader(nextUs = 6 * SECOND_US)
        val loaders = listOf(primaryVideo, busyText, unknown, mixed, image)
        val types = listOf(
            listOf(C.TRACK_TYPE_VIDEO),
            listOf(C.TRACK_TYPE_TEXT),
            listOf(C.TRACK_TYPE_UNKNOWN),
            listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT),
            listOf(C.TRACK_TYPE_IMAGE)
        )
        val loader = InFlightOptionalRenditionLoaderFactory().create(loaders, types)

        assertThat(loader.nextLoadPositionUs).isEqualTo(3 * SECOND_US)
        loader.continueLoading(loadingInfo())

        assertThat(busyText.continueCalls).isEmpty()
        assertThat(primaryVideo.continueCalls).isEmpty()
        assertThat(unknown.continueCalls).containsExactly(3 * SECOND_US)
        assertThat(mixed.continueCalls).isEmpty()
        assertThat(image.continueCalls).isEmpty()
    }

    @Test
    fun `active primary end of source falls back to aggregate including optional loader`() {
        val video = FakeLoader(nextUs = C.TIME_END_OF_SOURCE)
        val text = FakeLoader(nextUs = 2 * SECOND_US, loading = true)
        val loader = policy(video, text)

        assertThat(loader.nextLoadPositionUs).isEqualTo(2 * SECOND_US)
        loader.continueLoading(loadingInfo())

        assertThat(text.continueCalls).containsExactly(2 * SECOND_US)
        assertThat(loader.nextLoadPositionUs).isEqualTo(2 * SECOND_US)
    }

    @Test
    fun `empty and text-only factories retain default aggregate behavior`() {
        val factory = InFlightOptionalRenditionLoaderFactory()
        val empty = factory.create(emptyList(), emptyList())
        val text = FakeLoader(nextUs = 3 * SECOND_US, loading = true)
        val textOnly = factory.create(listOf(text), listOf(listOf(C.TRACK_TYPE_TEXT)))

        assertThat(empty.nextLoadPositionUs).isEqualTo(C.TIME_END_OF_SOURCE)
        assertThat(empty.bufferedPositionUs).isEqualTo(C.TIME_END_OF_SOURCE)
        assertThat(empty.isLoading).isFalse()
        assertThat(textOnly.nextLoadPositionUs).isEqualTo(3 * SECOND_US)
    }

    @Test
    fun `aggregate buffer loading and reevaluation behavior remains delegated`() {
        val video = FakeLoader(
            nextUs = 5 * SECOND_US,
            bufferedUs = 7 * SECOND_US,
            loading = true
        )
        val text = FakeLoader(
            nextUs = 2 * SECOND_US,
            bufferedUs = 4 * SECOND_US,
            loading = true
        )
        val default = DefaultCompositeSequenceableLoaderFactory().create(
            listOf(video, text),
            listOf(listOf(C.TRACK_TYPE_VIDEO), listOf(C.TRACK_TYPE_TEXT))
        )
        val videoForPolicy = FakeLoader(nextUs = 5 * SECOND_US, bufferedUs = 7 * SECOND_US, loading = true)
        val textForPolicy = FakeLoader(nextUs = 2 * SECOND_US, bufferedUs = 4 * SECOND_US, loading = true)
        val policy = policy(videoForPolicy, textForPolicy)

        assertThat(policy.bufferedPositionUs).isEqualTo(default.bufferedPositionUs)
        assertThat(policy.isLoading).isEqualTo(default.isLoading)
        policy.reevaluateBuffer(6 * SECOND_US)

        assertThat(videoForPolicy.reevaluatedAtUs).containsExactly(6 * SECOND_US)
        assertThat(textForPolicy.reevaluatedAtUs).containsExactly(6 * SECOND_US)
    }

    private fun policy(video: FakeLoader, text: FakeLoader): SequenceableLoader =
        InFlightOptionalRenditionLoaderFactory().create(
            listOf(video, text),
            listOf(listOf(C.TRACK_TYPE_VIDEO), listOf(C.TRACK_TYPE_TEXT))
        )

    private fun loadingInfo(positionUs: Long = 0L): LoadingInfo = LoadingInfo.Builder()
        .setPlaybackPositionUs(positionUs)
        .setPlaybackSpeed(1f)
        .setLastRebufferRealtimeMs(C.TIME_UNSET)
        .build()

    private class FakeLoader(
        var nextUs: Long,
        private val maxLoadPositionUs: Long = Long.MAX_VALUE,
        var bufferedUs: Long = nextUs,
        var loading: Boolean = false,
        private val action: ((FakeLoader) -> Boolean)? = null
    ) : SequenceableLoader {
        val continueCalls = mutableListOf<Long>()
        val reevaluatedAtUs = mutableListOf<Long>()

        override fun getBufferedPositionUs(): Long = bufferedUs

        override fun getNextLoadPositionUs(): Long = nextUs

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
            continueCalls += nextUs
            if (loading) return false
            action?.let { return it(this) }
            if (nextUs >= maxLoadPositionUs || nextUs == C.TIME_END_OF_SOURCE) return false
            nextUs = minOf(nextUs + 2 * SECOND_US, maxLoadPositionUs)
            bufferedUs = maxOf(bufferedUs, nextUs)
            return true
        }

        override fun isLoading(): Boolean = loading

        override fun reevaluateBuffer(positionUs: Long) {
            reevaluatedAtUs += positionUs
        }
    }

    private companion object {
        const val SECOND_US = 1_000_000L
    }
}
