package com.streamvault.feature.live.phone

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.streamvault.core.ui.companion.ParsedCompanionRequest
import com.streamvault.domain.model.LiveCategoryOrder
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.*

class PhoneOrderingApiTest {
    private val backend: PhoneOrderingBackend = mock()
    private val host = "192.168.1.2:1234"
    private val origin = "http://$host"
    private val guard = PhoneSessionGuard(7, host, 0)
    private val api = PhoneOrderingApi(guard, backend, PhoneLabels("Favorites", "Recent", "All")) { 0 }

    private fun request(path: String, body: String = "{}", cookie: String? = null, csrf: String? = null,
        requestOrigin: String = origin) = ParsedCompanionRequest("POST", path, buildMap {
        put("host", host); put("origin", requestOrigin); put("content-type", "application/json")
        if (cookie != null) put("cookie", "ordering_session=$cookie")
        if (csrf != null) put("x-ordering-csrf", csrf)
    }, body)

    @Test fun `metadata is never read before pairing and csrf authorization`() = runTest {
        assertThat(api.handle(request("/api/categories")).status).isEqualTo(403)
        val code = guard.pairingCode()!!
        val response = api.handle(request("/api/pair", """{"secret":"$code"}"""))
        assertThat(response.status).isEqualTo(200)
        val csrf = JsonParser.parseString(response.body).asJsonObject["csrf"].asString
        assertThat(api.handle(request("/api/categories", cookie = response.cookie, csrf = "wrong")).status).isEqualTo(403)
        assertThat(api.handle(request("/api/categories", cookie = response.cookie, csrf = csrf, requestOrigin = "http://other" )).status).isEqualTo(403)
        verify(backend, never()).categories(any(), any(), any())
    }

    @Test fun `invalid JSON types and unknown endpoints are rejected`() = runTest {
        val code = guard.pairingCode()!!
        val pair = api.handle(request("/api/pair", """{"secret":"$code"}"""))
        val csrf = JsonParser.parseString(pair.body).asJsonObject["csrf"].asString
        assertThat(api.handle(request("/api/channels", """{"categoryId":12}""", pair.cookie, csrf)).status).isEqualTo(400)
        assertThat(api.handle(request("/api/categories/save", """{"sections":[]}""", pair.cookie, csrf)).status).isEqualTo(400)
        assertThat(api.handle(request("/api/unknown", cookie = pair.cookie, csrf = csrf)).status).isEqualTo(404)
    }

    @Test fun `category response exposes only explicit row fields`() = runTest {
        val sections = PhoneSection.entries.associateWith { section ->
            if (section == PhoneSection.PROVIDER) listOf(PhoneCategoryRow("12", "Sports", false, true)) else emptyList()
        }
        whenever(backend.categories(any(), any(), eq(false))).thenReturn(PhoneOrderingBackend.CategoryCatalog(
            "Provider", sections, 9007199254740993L, "a".repeat(64), listOf("12"), LiveCategoryOrder(), listOf(12), emptyList()))
        val code = guard.pairingCode()!!
        val pair = api.handle(request("/api/pair", """{"secret":"$code"}"""))
        val csrf = JsonParser.parseString(pair.body).asJsonObject["csrf"].asString
        val response = api.handle(request("/api/categories", cookie = pair.cookie, csrf = csrf))
        val json = JsonParser.parseString(response.body).asJsonObject
        assertThat(json["revision"].asString).isEqualTo("9007199254740993")
        val row = json.getAsJsonObject("sections").getAsJsonArray("PROVIDER")[0].asJsonObject
        assertThat(row.keySet()).containsExactly("key", "name", "hidden", "editable")
        verify(backend).categories(eq(7), any(), eq(false))
    }

    @Test fun `combined session exposes profile categories without provider scope`() = runTest {
        val combinedGuard = PhoneSessionGuard(0L, host, 0, combinedProfileId = 31L)
        val combinedApi = PhoneOrderingApi(combinedGuard, backend, PhoneLabels("Favorites", "Recent", "All")) { 0 }
        val sections = PhoneSection.entries.associateWith { section ->
            if (section == PhoneSection.PROVIDER) listOf(PhoneCategoryRow("-2000000001", "News", false, true)) else emptyList()
        }
        whenever(backend.combinedCategories(eq(31L), any(), eq(false))).thenReturn(
            PhoneOrderingBackend.CategoryCatalog(
                "Family", sections, 3L, "b".repeat(64), listOf("-2000000001"),
                LiveCategoryOrder(), emptyList(), emptyList(), setOf(PhoneSection.PINNED, PhoneSection.PROVIDER)
            )
        )
        val code = combinedGuard.pairingCode()!!
        val pair = combinedApi.handle(request("/api/pair", """{"secret":"$code"}"""))
        val csrf = JsonParser.parseString(pair.body).asJsonObject["csrf"].asString

        val response = combinedApi.handle(request("/api/categories", cookie = pair.cookie, csrf = csrf))
        assertThat(response.status).isEqualTo(200)
        assertThat(JsonParser.parseString(response.body).asJsonObject.getAsJsonArray("editableSections").map { it.asString })
            .containsExactly("PINNED", "PROVIDER")
        verify(backend).combinedCategories(eq(31L), any(), eq(false))
        verify(backend, never()).categories(any(), any(), any())
    }
}
