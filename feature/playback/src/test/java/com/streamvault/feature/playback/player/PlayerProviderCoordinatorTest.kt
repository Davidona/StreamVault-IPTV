package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.provider.PlayerCredentialFailure
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PlayerProviderCoordinatorTest {

    @Test
    fun `catch-up credential failures become domain errors`() = runTest {
        val repository = mock<ProviderRepository>()
        whenever(repository.buildCatchUpUrls(any(), any(), any(), any()))
            .thenThrow(PlayerCredentialFailure("credentials unavailable"))

        val result = PlayerProviderCoordinator(repository).buildCatchUpUrls(1L, 2L, 3L, 4L)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).isEqualTo("credentials unavailable")
    }

    @Test
    fun `catch-up urls remain domain successes`() = runTest {
        val repository = mock<ProviderRepository>()
        whenever(repository.buildCatchUpUrls(any(), any(), any(), any()))
            .thenReturn(listOf("https://example.test/catchup"))

        val result = PlayerProviderCoordinator(repository).buildCatchUpUrls(1L, 2L, 3L, 4L)

        assertThat(result).isEqualTo(Result.Success(listOf("https://example.test/catchup")))
    }
}
