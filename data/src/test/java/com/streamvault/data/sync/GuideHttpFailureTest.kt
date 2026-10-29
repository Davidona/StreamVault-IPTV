package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.parser.XmltvParser
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.repository.EpgRepositoryImpl
import com.streamvault.domain.model.*
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.mockito.kotlin.*

/** Real HTTP response -> repository result -> guide executor classification and freshness. */
class GuideHttpFailureTest {
    @Test fun `temporary HTTP guide failures remain retryable without replacing cached data`() = runTest {
        for (status in listOf(408, 429, 500, 502, 503, 504)) verifyFailure(status, retryable = true)
    }

    @Test fun `authentication and permanent HTTP guide failures do not schedule retries`() = runTest {
        for (status in listOf(400, 401, 403, 404, 410)) verifyFailure(status, retryable = false)
    }

    private suspend fun verifyFailure(status: Int, retryable: Boolean) {
        val preferences: PreferencesRepository = mock()
        val programs: ProgramDao = mock()
        val providers: ProviderDao = mock()
        val metadata: SyncMetadataRepository = mock()
        val sources: EpgSourceRepository = mock()
        whenever(preferences.getEpgTimeShiftMinutes(7L)).thenReturn(0)
        whenever(preferences.getHiddenCategoryIds(7L, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(programs.countByProvider(7L)).thenReturn(1)
        whenever(providers.getById(7L)).thenReturn(null)
        whenever(sources.resolveForProvider(7L, emptySet())).thenReturn(EpgResolutionSummary())
        val transaction = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Fixture HTTP error").body("fixture".toResponseBody()).build()
        }.build()
        val repository = EpgRepositoryImpl(programDao = programs, providerDao = providers,
            xmltvParser = XmltvParser(), okHttpClient = client, transactionRunner = transaction,
            epgSourceRepository = sources, preferencesRepository = preferences)
        val direct = repository.refreshEpg(7L, "https://example.test/guide.xml")
        assertThat(direct).isInstanceOf(Result.Error::class.java)
        val error = direct as Result.Error
        assertThat(error.message).isEqualTo("Failed to download EPG: HTTP $status")
        assertThat(error.exception is java.io.IOException).isEqualTo(retryable)
        val executor = ProviderEpgSyncExecutor(preferencesRepository = preferences,
            epgRepository = repository, epgSourceRepository = sources, channelDao = mock(),
            programDao = programs, transactionRunner = transaction, syncMetadataRepository = metadata,
            stalkerPortalStateStore = mock(), stalkerRequestCoordinator = mock(),
            createStalkerProvider = { error("Unexpected Stalker request") },
            xtreamSupport = SyncManagerXtreamSupport(mock(), { false }, { it?.message.orEmpty() },
                { _, _, _ -> }, 1000L, 1000L, "fixture"),
            progress = { _, _, _ -> }, sanitizeThrowableMessage = { it?.message.orEmpty() })
        val provider = LegacyProvider(id = 7L, name = "Fixture", type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test", username = "fixture", password = "fixture",
            guideSourcePolicy = GuideSourcePolicy.PROVIDER_ONLY)
        val outcome = executor.syncXtreamProviderEpg(provider, SyncMetadata(7L), 1000L, true, null)
        assertThat(outcome.hasRetryableFailure).isEqualTo(retryable)
        assertThat(outcome.warnings).isNotEmpty()
        verify(metadata, never()).updateMetadata(any())
        verify(programs, never()).deleteByProvider(any())
        verify(programs, never()).moveToProvider(any(), any())
        verify(programs, never()).insertAll(any())
    }
}
