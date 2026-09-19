package com.streamvault.app.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.stalker.StalkerPlaybackResolutionException
import com.streamvault.data.remote.xtream.ProviderPlaybackResolver
import com.streamvault.data.remote.xtream.ResolvedStreamUrl
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.domain.model.Result
import com.streamvault.domain.provider.PlayerCredentialFailure
import com.streamvault.domain.provider.PlayerPlaybackResolutionFailure
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AppPlayerPlaybackResolverTest {
    private val delegate: ProviderPlaybackResolver = mock()

    @Test
    fun `maps data resolver metadata to domain playback`() = runTest {
        whenever(delegate.resolveAndCommitMetadata("logical", null, null, null, null, false))
            .thenReturn(
                ResolvedStreamUrl(
                    url = "https://example.test/live.ts",
                    containerExtension = "ts",
                    headers = mapOf("X-Test" to "ok"),
                    expirationTime = 123L
                )
            )

        val result = AppPlayerPlaybackResolver(delegate).resolveAndCommitMetadata("logical")

        assertThat(result).isEqualTo(
            Result.success(
                com.streamvault.domain.provider.ResolvedPlayback(
                    url = "https://example.test/live.ts",
                    containerExtension = "ts",
                    headers = mapOf("X-Test" to "ok"),
                    expirationTime = 123L
                )
            )
        )
    }

    @Test
    fun `translates credential failures into the domain failure type`() = runTest {
        whenever(delegate.resolveAndCommitMetadata("logical", null, null, null, null, false))
            .thenThrow(CredentialDecryptionException("credentials unavailable"))

        val result = AppPlayerPlaybackResolver(delegate).resolveAndCommitMetadata("logical")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).isEqualTo("credentials unavailable")
        assertThat(error.exception).isInstanceOf(PlayerCredentialFailure::class.java)
    }

    @Test
    fun `translates Stalker resolution failures into the domain failure type`() = runTest {
        whenever(delegate.resolveAndCommitMetadata("logical", null, null, null, null, false))
            .thenAnswer { throw StalkerPlaybackResolutionException("stalker unavailable") }

        val result = AppPlayerPlaybackResolver(delegate).resolveAndCommitMetadata("logical")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).isEqualTo("stalker unavailable")
        assertThat(error.exception).isInstanceOf(PlayerPlaybackResolutionFailure::class.java)
    }
}
