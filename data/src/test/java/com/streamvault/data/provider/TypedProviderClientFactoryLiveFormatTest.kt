package com.streamvault.data.provider

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerPortalStateStore
import com.streamvault.data.remote.stalker.StalkerRemoteIdentityResolver
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.XtreamConfig
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TypedProviderClientFactoryLiveFormatTest {
    @Test
    fun `auto honors imported provider TS format over catalog HLS extension`() = runTest {
        val factory = factory(LiveStreamFormatMode.AUTO)

        val resolved = factory.resolveLiveContainerExtension(
            snapshot(LiveStreamFormatMode.MPEG_TS),
            XtreamStreamKind.LIVE,
            "m3u8"
        )

        assertThat(resolved).isEqualTo("ts")
    }

    @Test
    fun `explicit app HLS format overrides imported provider TS format`() = runTest {
        val factory = factory(LiveStreamFormatMode.HLS)

        val resolved = factory.resolveLiveContainerExtension(
            snapshot(LiveStreamFormatMode.MPEG_TS),
            XtreamStreamKind.LIVE,
            "ts"
        )

        assertThat(resolved).isEqualTo("m3u8")
    }

    @Test
    fun `provider live format preference does not change VOD extension`() = runTest {
        val factory = factory(LiveStreamFormatMode.AUTO)

        val resolved = factory.resolveLiveContainerExtension(
            snapshot(LiveStreamFormatMode.MPEG_TS),
            XtreamStreamKind.MOVIE,
            "mp4"
        )

        assertThat(resolved).isEqualTo("mp4")
    }

    private fun factory(mode: LiveStreamFormatMode): TypedProviderClientFactory {
        val preferences = mock<PreferencesRepository>().apply {
            whenever(playerLiveStreamFormatMode).thenReturn(flowOf(mode))
        }
        return TypedProviderClientFactory(
            xtreamApiService = mock<XtreamApiService>(),
            stalkerApiService = mock<StalkerApiService>(),
            jellyfinProvider = mock<JellyfinProvider>(),
            preferencesRepository = preferences,
            stalkerRemoteIdentityResolver = mock<StalkerRemoteIdentityResolver>(),
            stalkerPortalStateStore = mock<StalkerPortalStateStore>()
        )
    }

    private fun snapshot(preference: LiveStreamFormatMode) = ProviderSnapshot(
        provider = Provider(
            id = 1L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES
        ),
        configuration = XtreamConfig(
            serverUrl = "https://provider.example",
            username = "user",
            password = "pass",
            preferredLiveStreamFormat = preference
        ),
        configurationGeneration = 1L
    )
}
