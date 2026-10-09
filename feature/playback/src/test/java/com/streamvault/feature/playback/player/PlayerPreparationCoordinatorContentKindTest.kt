package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DrmInfo
import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.settings.PlayerPreferences
import com.streamvault.feature.playback.api.PlaybackStreamPreparer
import com.streamvault.player.PlayerEngine
import com.streamvault.player.playback.ResolvedStreamType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlayerPreparationCoordinatorContentKindTest {

    @Test
    fun `plugin replacement keeps source intent and plugin metadata at engine prepare`() = runTest {
        val input = StreamInfo(
            url = "https://provider.example/movie.m3u8",
            headers = mapOf("Cookie" to "source=redacted"),
            playbackContentKind = PlaybackContentKind.ON_DEMAND
        )
        val replacement = StreamInfo(
            url = "https://cdn.example/movie.m3u8",
            headers = mapOf("Authorization" to "Bearer redacted"),
            drmInfo = DrmInfo(
                scheme = DrmScheme.WIDEVINE,
                licenseUrl = "https://license.example/widevine"
            )
        )
        val preparer = mock<PlaybackStreamPreparer>()
        whenever(preparer.prepare(input)).thenReturn(Result.success(replacement))

        val engine = mock<PlayerEngine>()
        val preferences = mock<PlayerPreferences>().apply {
            whenever(playerMuted).thenReturn(flowOf(false))
            whenever(playerPlaybackSpeed).thenReturn(flowOf(1f))
            whenever(preferredAudioLanguage).thenReturn(flowOf(null))
            whenever(appLanguage).thenReturn(flowOf("en"))
            whenever(playerWifiMaxVideoHeight).thenReturn(flowOf(null))
            whenever(playerEthernetMaxVideoHeight).thenReturn(flowOf(null))
            whenever(playerSurfaceMode).thenReturn(flowOf(PlayerSurfaceMode.AUTO))
            whenever(playerPlaybackBufferMode).thenReturn(flowOf(PlaybackBufferMode.AUTO))
            whenever(playerVodHttpProtocolMode).thenReturn(flowOf(VodHttpProtocolMode.COMPATIBILITY_HTTP1))
            whenever(playerFastRetryOnTransientFailures).thenReturn(flowOf(false))
        }
        val coordinator = PlayerPreparationCoordinator(
            streamPreparer = preparer,
            playerPreferencesCoordinator = PlayerPreferencesCoordinator(preferences),
            providerRepository = mock<ProviderRepository>(),
            okHttpClient = OkHttpClient(),
            playerEngineCoordinator = PlayerEngineCoordinator(engine),
            playerContentResolver = mock()
        )

        val result = coordinator.prepare(
            request = PlayerPreparationCoordinator.Request(
                streamInfo = input,
                contentType = ContentType.MOVIE,
                providerId = 0L,
                currentStreamUrl = null,
                probePassedPlaybackKeys = emptySet(),
                probeBeforePlayback = false,
                audioVideoOffsetMs = 0
            ),
            isCurrent = { true }
        )

        val expected = replacement.copy(playbackContentKind = PlaybackContentKind.ON_DEMAND)
        assertThat(result).isEqualTo(PlayerPreparationCoordinator.Result.Success(expected, null))
        verify(engine).prepare(expected)

        val guarded = coordinator.prepare(
            PlayerPreparationCoordinator.Request(
                streamInfo = input,
                contentType = ContentType.SERIES_EPISODE,
                providerId = 0L,
                currentStreamUrl = null,
                probePassedPlaybackKeys = emptySet(),
                probeBeforePlayback = false,
                audioVideoOffsetMs = 0,
                requiredSourceType = ResolvedStreamType.PROGRESSIVE
            ), isCurrent = { true })
        assertThat(guarded).isInstanceOf(PlayerPreparationCoordinator.Result.Failure::class.java)
        // Exactly the original unguarded call reached the engine; guarded HLS must not.
        org.mockito.kotlin.verify(engine, org.mockito.kotlin.times(1)).prepare(expected)

        val episodeState = PlayerEpisodeSourceState()
        val token = episodeState.reset(1)
        val revision = episodeState.manualVideoRevision
        var manualVideo = false
        whenever(preparer.prepare(input)).thenAnswer {
            manualVideo = true
            input.copy(url = "https://fixture.invalid/avc.mp4").let { Result.success(it) }
        }
        val manualCanceled = coordinator.prepare(
            PlayerPreparationCoordinator.Request(input, ContentType.SERIES_EPISODE, 0,
                null, emptySet(), false, 0),
            isCurrent = { isEpisodePreparationCurrent(episodeState, token, true, manualVideo, revision, true) })
        assertThat(manualCanceled).isNull()
        org.mockito.kotlin.verify(engine, org.mockito.kotlin.times(1)).prepare(org.mockito.kotlin.any())
    }
}
