package com.streamvault.data.stremio.account

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StremioAccountApiTest {
    @Test fun pendingLinkDoesNotExposeAnAuthenticationFailure()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"error":{"code":101,"message":"Invalid or expired token"}}"""))
            val api=StremioAccountApi(OkHttpClient(),server.url("/api/").toString(),server.url("/link/").toString())
            assertNull(api.readLink("pending"))
            assertEquals("/link/read?type=Read&code=pending",server.takeRequest().path)
        }
    }
    @Test fun inheritedInterceptorsNeverSeeAccountCredentialsAndRedirectsAreNotFollowed()=runBlocking {
        MockWebServer().use { server ->
            var intercepted=false
            val client=OkHttpClient.Builder().addInterceptor { intercepted=true;it.proceed(it.request()) }.build()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/unexpected")))
            val api=StremioAccountApi(client,server.url("/api/").toString())
            try { api.call("getUser",jsonObject("authKey" to "private"));fail("Redirect must fail") }
            catch(error:StremioAccountApiException) { assertFalse(error.toString().contains("private")) }
            assertFalse(intercepted);assertEquals(1,server.requestCount)
        }
    }
}
