package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.feature.playback.api.PlaybackStreamPreparer
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlayerPreparationCoordinatorPreloadTest {

    @Test
    fun `preload preparation preserves prepared stream without touching foreground engine`() = runTest {
        val preparer = mock<PlaybackStreamPreparer>()
        val engine = mock<PlayerEngine>()
        val input = StreamInfo("https://example.com/logical.m3u8")
        val prepared = input.copy(
            url = "https://cdn.example.com/prepared.m3u8",
            headers = mapOf("Authorization" to "Bearer redacted")
        )
        whenever(preparer.prepare(input)).thenReturn(Result.success(prepared))
        val coordinator = PlayerPreparationCoordinator(
            streamPreparer = preparer,
            playerPreferencesCoordinator = mock(),
            providerRepository = mock<ProviderRepository>(),
            okHttpClient = OkHttpClient(),
            playerEngineCoordinator = PlayerEngineCoordinator(engine),
            playerContentResolver = mock()
        )

        val result = coordinator.prepareStreamForPreload(input)

        assertThat(result).isEqualTo(prepared)
        verify(engine, never()).prepare(any())
        verify(engine, never()).setMuted(any())
    }

    @Test
    fun `expired preload stream is rejected before provider preparation`() = runTest {
        val preparer = mock<PlaybackStreamPreparer>()
        val input = StreamInfo(
            url = "https://example.com/expired.mp4",
            expirationTime = System.currentTimeMillis() - 1L
        )
        val coordinator = PlayerPreparationCoordinator(
            streamPreparer = preparer,
            playerPreferencesCoordinator = mock(),
            providerRepository = mock<ProviderRepository>(),
            okHttpClient = OkHttpClient(),
            playerEngineCoordinator = PlayerEngineCoordinator(mock()),
            playerContentResolver = mock()
        )

        assertThat(coordinator.prepareStreamForPreload(input)).isNull()
        verify(preparer, never()).prepare(any())
    }
}
