package com.streamvault.data.trakt

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TraktDeviceApiTest {
    @Test fun `device authorization exchanges codes without a client secret`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_code":"private-code","user_code":"USER1234","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":5}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"access","refresh_token":"refresh","created_at":2000000,"expires_in":604800}"""))
            val api = TraktApiClient(TraktConfig("client", "https://example.com/callback"), OkHttpClient(), server.url("/"))
            val code = api.deviceCode()
            assertEquals("USER1234", code.userCode)
            val start = server.takeRequest()
            assertEquals("/oauth/device/code", start.path)
            assertEquals(setOf("client_id"), Gson().fromJson(start.body.readUtf8(), JsonObject::class.java).keySet())
            assertEquals("access", api.deviceToken(code.deviceCode).accessToken)
            val poll = server.takeRequest()
            assertEquals("/oauth/device/token", poll.path)
            val body = Gson().fromJson(poll.body.readUtf8(), JsonObject::class.java)
            assertEquals(setOf("code", "client_id"), body.keySet())
            assertEquals("private-code", body["code"].asString)
        }
    }
}
