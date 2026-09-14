package com.streamvault.data.provider

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.dao.StalkerPortalStateDao
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.XtreamConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class RoomProviderSnapshotRepositoryTest {

    @Test
    fun `snapshot reads and decodes run on supplied dispatcher`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gson = Gson()
        val provider = ProviderEntity(
            id = 9L,
            name = "Living room",
            type = ProviderType.XTREAM_CODES
        )
        val codec = ProviderConfigurationCodec(
            gson = gson,
            credentialCrypto = object : com.streamvault.data.security.CredentialCrypto {
                override fun encryptIfNeeded(value: String): String = value
                override fun decryptIfNeeded(value: String): String = value
            }
        )
        val config = ProviderConfigEntity(
            providerId = provider.id,
            type = provider.type,
            schemaVersion = 1,
            configurationGeneration = 3L,
            identityKey = "identity",
            encryptedConfigJson = codec.encode(
                XtreamConfig("https://example.test", "user", "password")
            ),
            updatedAt = 1L
        )
        val providerDao = mock<ProviderDao>()
        val snapshotDao = mock<ProviderSnapshotDao>()
        val stalkerPortalStateDao = mock<StalkerPortalStateDao>()
        whenever(providerDao.getById(provider.id)).thenReturn(provider)
        whenever(snapshotDao.getConfig(provider.id)).thenReturn(config)
        whenever(snapshotDao.getRuntime(provider.id)).thenReturn(null)
        whenever(stalkerPortalStateDao.get(provider.id)).thenReturn(null)

        val repository = RoomProviderSnapshotRepository(
            providerDao = providerDao,
            snapshotDao = snapshotDao,
            stalkerPortalStateDao = stalkerPortalStateDao,
            codec = codec,
            gson = gson,
            workDispatcher = dispatcher
        )

        val result = async(start = CoroutineStart.UNDISPATCHED) {
            repository.getSnapshot(provider.id)
        }

        assertThat(result.isCompleted).isFalse()
        verifyNoInteractions(providerDao, snapshotDao, stalkerPortalStateDao)

        advanceUntilIdle()

        assertThat(result.await()?.provider?.id).isEqualTo(provider.id)
        verify(providerDao).getById(provider.id)
        verify(snapshotDao).getConfig(provider.id)
        verify(snapshotDao).getRuntime(provider.id)
    }
}
