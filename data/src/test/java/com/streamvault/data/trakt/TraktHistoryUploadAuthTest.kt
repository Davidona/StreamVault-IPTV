package com.streamvault.data.trakt

import com.streamvault.domain.trakt.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TraktHistoryUploadAuthTest {
    private class Store(var session: TraktStoredSession) : TraktTokenStore {
        override fun read() = session
        override fun write(session: TraktStoredSession) { this.session = session }
    }

    private class Api : TraktApi, TraktHistoryApi {
        var refreshes = 0
        val uploads = mutableListOf<Pair<String, List<TraktHistoryUpload>>>()
        var rejectOld = false
        var unauthorized = false
        var failure: Exception? = null

        override suspend fun exchange(code: String, verifier: String) = error("Not used")
        override suspend fun refresh(refreshToken: String): TraktTokens {
            refreshes++
            return TraktTokens("new-access", "new-refresh", 1000, 1000)
        }
        override suspend fun username(accessToken: String) = "viewer"
        override suspend fun history(accessToken: String, page: Int) = TraktHistoryPage(emptyList(), page, false)
        override suspend fun upload(accessToken: String, events: List<TraktHistoryUpload>): TraktHistoryUploadResult {
            uploads += accessToken to events
            failure?.let { throw it }
            if (unauthorized || (rejectOld && accessToken == "old-access")) throw TraktApiException(401)
            return TraktHistoryUploadResult.Success(events.size)
        }
    }

    private val event = TraktHistoryUpload(TraktMediaIdentity.MovieId(603), 1_700_000_000_000L)
    private fun tokens() = TraktTokens("old-access", "old-refresh", 1000, 1000)
    private fun service(store: Store, api: Api) = TraktAuthService(
        TraktConfig("client", "https://example.org/callback"), store, api, now = { 1000 })

    @Test fun upload_rejects_a_different_account_before_calling_api() = runBlocking {
        val api = Api()
        val service = service(Store(TraktStoredSession(tokens())), api)
        service.load()

        assertEquals(TraktHistoryUploadResult.Disconnected, service.upload("another-viewer", listOf(event)))
        assertTrue(api.uploads.isEmpty())
        assertEquals(0, api.refreshes)
    }

    @Test fun explicit_unauthorized_upload_refreshes_once_and_retries_with_new_token() = runBlocking {
        val api = Api().apply { rejectOld = true }
        val store = Store(TraktStoredSession(tokens()))
        val service = service(store, api)
        service.load()

        assertEquals(TraktHistoryUploadResult.Success(1), service.upload("viewer", listOf(event)))
        assertEquals(listOf("old-access", "new-access"), api.uploads.map { it.first })
        assertEquals(1, api.refreshes)
        assertEquals("new-refresh", store.read().tokens?.refreshToken)
    }

    @Test fun second_unauthorized_upload_clears_session_without_retry_loop() = runBlocking {
        val api = Api().apply { unauthorized = true }
        val store = Store(TraktStoredSession(tokens()))
        val service = service(store, api)
        service.load()

        assertEquals(TraktHistoryUploadResult.Disconnected, service.upload("viewer", listOf(event)))
        assertEquals(2, api.uploads.size)
        assertEquals(1, api.refreshes)
        assertNull(store.read().tokens)
        assertEquals(TraktMessage.RECONNECT_REQUIRED, service.state.value.message)
    }

    @Test fun rate_limit_and_ambiguous_transport_failure_are_not_retried() = runBlocking {
        for (failure in listOf(TraktApiException(429), java.io.IOException("timeout"))) {
            val api = Api().apply { this.failure = failure }
            val store = Store(TraktStoredSession(tokens()))
            val service = service(store, api)
            service.load()

            assertEquals(TraktHistoryUploadResult.Failed, service.upload("viewer", listOf(event)))
            assertEquals(1, api.uploads.size)
            assertEquals(0, api.refreshes)
            assertNotNull(store.read().tokens)
        }
    }
}
