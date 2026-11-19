package com.streamvault.data.repository

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.parser.XmltvParser
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgSourceRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class EpgRetryAfterStoreTest {
    @Test
    fun `server deadline survives repository recreation and avoids another network request`() = runTest {
        val values = mutableMapOf<String, Long>()
        val prefs: SharedPreferences = mock()
        val editor: SharedPreferences.Editor = mock()
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.getLong(any(), any())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Long>(1) }
        whenever(editor.putLong(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument(1)
            editor
        }
        whenever(editor.commit()).thenReturn(true)
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("Limited").header("Retry-After", "600")
                .body("fixture".toResponseBody()).build()
        }.build()
        val programs: ProgramDao = mock()
        val transaction = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        }
        fun repository() = EpgRepositoryImpl(
            programDao = programs, providerDao = mock<ProviderDao>(), xmltvParser = XmltvParser(),
            okHttpClient = client, transactionRunner = transaction,
            epgSourceRepository = mock<EpgSourceRepository>(), preferencesRepository = mock<PreferencesRepository>(),
            retryAfterStore = EpgRetryAfterStore(prefs)
        )
        val url = "https://example.test/xmltv?username=secret&password=secret"
        val initial = repository().refreshEpg(7L, url) as Result.Error
        assertThat((initial.exception as XtreamNetworkException).retryAfterMs).isEqualTo(600_000L)
        assertThat(values.keys.single()).doesNotContain("secret")

        val afterRestart = repository().refreshEpg(7L, url) as Result.Error
        assertThat((afterRestart.exception as XtreamNetworkException).retryAfterMs).isGreaterThan(0L)
        assertThat(requests).isEqualTo(1)

        // A different provider or guide must not inherit this provider's deadline.
        repository().refreshEpg(8L, url)
        repository().refreshEpg(7L, "https://example.test/another.xml")
        assertThat(requests).isEqualTo(3)

        // Once the saved deadline expires, the same guide can reach the server again.
        values.replaceAll { _, _ -> 1L }
        repository().refreshEpg(7L, url)
        assertThat(requests).isEqualTo(4)
    }
}
