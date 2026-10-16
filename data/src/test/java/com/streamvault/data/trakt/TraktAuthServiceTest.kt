package com.streamvault.data.trakt

import com.streamvault.domain.trakt.TraktMessage
import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavoriteType
import com.streamvault.domain.trakt.TraktFavoriteWriteResult
import com.streamvault.domain.trakt.TraktRatingIdentity
import com.streamvault.domain.trakt.TraktRatingType
import com.streamvault.domain.trakt.TraktRatingPage
import com.streamvault.domain.trakt.TraktRatingWriteResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class TraktAuthServiceTest {
    private val config = TraktConfig("public-client", "https://streamvault.test/oauth/trakt/callback")
    private val now = 2000000L
    private fun tokens(access: String = "access", refresh: String = "refresh", created: Long = now) =
        TraktTokens(access, refresh, created, 604800)
    private class Store : TraktTokenStore {
        var session = TraktStoredSession()
        val writes = mutableListOf<TraktStoredSession>()
        override fun read() = session
        override fun write(session: TraktStoredSession) { this.session = session; writes += session }
    }
    private inner class Api : TraktApi, TraktFavoritesApi, TraktRatingsApi {
        var exchanges = 0
        var refreshes = 0
        var refreshFailure: Exception? = null
        var unauthorized = false
        val usedTokens = mutableListOf<String>()
        val favoriteWrites = mutableListOf<Triple<String, TraktFavoriteIdentity, Boolean>>()
        var favoriteFailure: Exception? = null
        val ratingWrites = mutableListOf<Triple<String, TraktRatingIdentity, Int>>()
        val ratingReads = mutableListOf<Triple<String, TraktRatingType, Int>>()
        override suspend fun exchange(code: String, verifier: String): TraktTokens {
            exchanges++
            assertEquals("authorization-code", code)
            assertEquals(43, verifier.length)
            return tokens()
        }
        override suspend fun refresh(refreshToken: String): TraktTokens {
            refreshes++
            assertEquals("refresh", refreshToken)
            delay(10)
            refreshFailure?.let { throw it }
            return tokens("new-access", "new-refresh")
        }
        override suspend fun username(accessToken: String): String {
            usedTokens += accessToken
            if (unauthorized) throw TraktApiException(401)
            return "viewer"
        }
        override suspend fun setFavorited(accessToken: String, identity: TraktFavoriteIdentity, favorited: Boolean) {
            favoriteFailure?.let { throw it }
            favoriteWrites += Triple(accessToken, identity, favorited)
        }
        override suspend fun setRating(accessToken: String, identity: TraktRatingIdentity, rating: Int) {
            ratingWrites += Triple(accessToken, identity, rating)
        }
        override suspend fun ratings(accessToken: String, type: TraktRatingType, page: Int): TraktRatingPage {
            ratingReads += Triple(accessToken, type, page)
            return TraktRatingPage(emptyList(), page, hasMore = false)
        }
    }
    private fun service(store: Store, api: Api) = TraktAuthService(config, store, api) { now }
    private fun parameters(url: String) = URI(url).rawQuery.split('&').associate {
        val pair = it.split('=', limit = 2)
        pair[0] to URLDecoder.decode(pair[1], "UTF-8")
    }
    private fun callback(state: String) = "${config.redirectUri}?code=authorization-code&state=$state"

    @Test fun `favorites source uses the connected account token`() = runBlocking {
        val store = Store().apply { session = TraktStoredSession(tokens()) }
        val api = Api()
        val service = service(store, api)
        service.load()

        val identity = TraktFavoriteIdentity(TraktFavoriteType.SHOW, 1396)
        assertEquals(TraktFavoriteWriteResult.Success, service.setFavorited(identity, true))
        assertEquals(listOf(Triple("access", identity, true)), api.favoriteWrites)
    }

    @Test fun `ratings source uses the connected account token`() = runBlocking {
        val store = Store().apply { session = TraktStoredSession(tokens()) }
        val api = Api()
        val service = service(store, api)
        service.load()

        val identity = TraktRatingIdentity(TraktRatingType.SHOW, 1396)
        assertEquals(TraktRatingWriteResult.Success, service.setRating(identity, 8))
        assertEquals(listOf(Triple("access", identity, 8)), api.ratingWrites)
    }

    @Test fun `ratings read uses connected account token`() = runBlocking {
        val store = Store().apply { session = TraktStoredSession(tokens()) }
        val api = Api()
        val service = service(store, api)
        service.load()

        assertTrue(service.ratings(TraktRatingType.MOVIE, 1) is com.streamvault.domain.trakt.TraktRatingPageResult.Success)
        assertEquals(listOf(Triple("access", TraktRatingType.MOVIE, 1)), api.ratingReads)
    }

    @Test fun `PKCE matches RFC 7636 S256 example`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            TraktPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `login uses fresh state and verifier and consumes callback once`() = runBlocking {
        val store = Store(); val api = Api(); val service = service(store, api)
        val first = parameters(service.beginSignIn())
        val firstVerifier = store.session.pending!!.verifier
        val second = parameters(service.beginSignIn())
        assertNotEquals(first["state"], second["state"])
        assertNotEquals(firstVerifier, store.session.pending!!.verifier)
        assertEquals("S256", second["code_challenge_method"])
        assertEquals(config.redirectUri, second["redirect_uri"])
        assertFalse(second.containsKey("client_secret"))
        val callback = callback(second.getValue("state"))
        service.completeSignIn(callback)
        service.completeSignIn(callback)
        assertEquals(1, api.exchanges)
        assertTrue(service.state.value.connected)
        assertEquals("viewer", service.state.value.username)
        assertNull(store.session.pending)
    }

    @Test fun `state mismatch expiry duplicate parameters and wrong path abort before token exchange`() = runBlocking {
        for (kind in listOf("state", "expired", "duplicate", "path")) {
            val store = Store(); val api = Api(); val service = service(store, api)
            val state = parameters(service.beginSignIn()).getValue("state")
            val pending = store.session.pending!!
            val callback = when (kind) {
                "state" -> callback("wrong")
                "expired" -> { store.session = TraktStoredSession(pending = TraktPendingLogin(pending.verifier, state, now - 601)); callback(state) }
                "duplicate" -> callback(state) + "&state=$state"
                else -> callback(state).replace("/callback", "/other")
            }
            service.completeSignIn(callback)
            assertEquals(kind, 0, api.exchanges)
            assertNull(store.session.pending)
            assertFalse(service.state.value.connected)
            assertEquals(TraktMessage.SIGN_IN_FAILED, service.state.value.message)
        }
    }

    @Test fun `browser denial is silent and pending login survives new service`() = runBlocking {
        val store = Store(); val api = Api()
        val state = parameters(service(store, api).beginSignIn()).getValue("state")
        val recreated = service(store, api)
        recreated.load()
        assertTrue(recreated.state.value.connecting)
        recreated.completeSignIn("${config.redirectUri}?error=access_denied&state=$state")
        assertNull(recreated.state.value.message)
        assertFalse(recreated.state.value.connecting)
        assertNull(store.session.pending)
    }

    @Test fun `concurrent requests rotate both tokens exactly once`() = runBlocking {
        val store = Store(); val api = Api(); val service = service(store, api)
        store.session = TraktStoredSession(tokens(created = now - 604800))
        List(8) { async { service.refreshProfile() } }.awaitAll()
        assertEquals(1, api.refreshes)
        assertEquals("new-access", store.session.tokens!!.accessToken)
        assertEquals("new-refresh", store.session.tokens!!.refreshToken)
        assertTrue(api.usedTokens.all { it == "new-access" })
        assertNull(store.writes.first().tokens) // Durable consumption marker before refresh.
    }

    @Test fun `invalid grant and ambiguous refresh failure never reuse old token`() = runBlocking {
        for (failure in listOf(TraktApiException(400, "invalid_grant"), java.io.IOException("network"))) {
            val store = Store(); val api = Api(); val service = service(store, api)
            store.session = TraktStoredSession(tokens(created = now - 604800))
            api.refreshFailure = failure
            service.refreshProfile(); service.refreshProfile()
            assertEquals(1, api.refreshes)
            assertNull(store.session.tokens)
            assertEquals(TraktMessage.RECONNECT_REQUIRED, service.state.value.message)
            assertFalse(service.state.value.connected)
        }
    }

    @Test fun `401 after one refresh clears session`() = runBlocking {
        val store = Store(); val api = Api(); val service = service(store, api)
        store.session = TraktStoredSession(tokens())
        api.unauthorized = true
        service.refreshProfile()
        assertEquals(1, api.refreshes)
        assertEquals(listOf("access", "new-access"), api.usedTokens)
        assertNull(store.session.tokens)
        assertEquals(TraktMessage.RECONNECT_REQUIRED, service.state.value.message)
    }

    @Test fun `explicit rate limit during refresh retains session for later retry`() = runBlocking {
        val store = Store(); val api = Api(); val service = service(store, api)
        store.session = TraktStoredSession(tokens(created = now - 604800))
        api.refreshFailure = TraktApiException(429, retryAfterSeconds = 120)
        service.refreshProfile()
        assertEquals("refresh", store.session.tokens!!.refreshToken)
        assertEquals(TraktMessage.RATE_LIMITED, service.state.value.message)
        assertTrue(service.state.value.connected)
    }

    @Test fun `disconnect clears pending login and prevents late callback reconnect`() = runBlocking {
        val store = Store(); val api = Api(); val service = service(store, api)
        val state = parameters(service.beginSignIn()).getValue("state")
        service.disconnect(); service.completeSignIn(callback(state))
        assertEquals(0, api.exchanges)
        assertFalse(service.state.value.connected)
    }

    @Test fun `configuration rejects insecure or ambiguous redirects`() {
        for (uri in listOf("", "streamvault://callback", "http://example.com/callback", "https://localhost/callback",
            "https://example.com/callback?x=1", "https://example.com/callback#fragment", "https://user@example.com/callback")) {
            assertFalse(uri, TraktConfig("client", uri).configured)
        }
        assertTrue(config.configured)
    }
}
