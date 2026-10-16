package com.streamvault.data.trakt

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktProfileApiTest {
    @Test fun `user settings profile parses rich textual account details`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{
                "user": {
                    "username":"viewer", "name":"Stream Vault User", "location":"Jerusalem",
                    "about":"Media fan", "gender":"male", "age":36,
                    "joined_at":"2020-02-03T10:11:12.000Z", "vip":true,
                    "images":{"avatar":{"full":"walter-r2.trakt.tv/avatar.webp"}}
                }
            }"""))
            val api = TraktApiClient(
                TraktConfig("client", "https://example.org/callback"), OkHttpClient(), server.url("/"),
            )

            val profile = api.profile("token")

            assertEquals("viewer", profile.username)
            assertEquals("Stream Vault User", profile.name)
            assertEquals("Jerusalem", profile.location)
            assertEquals("Media fan", profile.about)
            assertEquals("male", profile.gender)
            assertEquals(36, profile.age)
            assertEquals("2020-02-03T10:11:12.000Z", profile.joinedAt)
            assertTrue(profile.vip)
            assertEquals("/users/settings", server.takeRequest().path)
        }
    }
}
