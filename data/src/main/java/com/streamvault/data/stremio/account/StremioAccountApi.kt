package com.streamvault.data.stremio.account

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.streamvault.data.remote.http.useCancellableResponse
import java.util.concurrent.TimeUnit

internal class StremioAccountApiException(val authentication: Boolean = false) : Exception("Stremio account request failed")

class StremioAccountApi(
    client: OkHttpClient,
    private val apiRoot: String = "https://api.strem.io/api/",
    private val linkRoot: String = "https://link.stremio.com/api/v2/"
) {
    // Account credentials must never reach add-on interceptors, redirects, caches or network logs.
    private val http = client.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.cache(null).cookieJar(okhttp3.CookieJar.NO_COOKIES).authenticator(okhttp3.Authenticator.NONE)
        .proxyAuthenticator(okhttp3.Authenticator.NONE).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()

    suspend fun createLink(): JsonObject = link("create", null).asJsonObject
    suspend fun readLink(code: String): JsonObject? = link("read", code).takeUnless { it.isJsonNull }?.asJsonObject

    private suspend fun link(path: String, code: String?): JsonElement {
        val url = (linkRoot + path).toHttpUrl().newBuilder().addQueryParameter("type", if (code == null) "Create" else "Read")
            .apply { code?.let { addQueryParameter("code", it) } }.build()
        return request(Request.Builder().url(url).header("Accept", "application/json")
            .header("Cache-Control", "no-store").build(), pendingLink = code != null)
    }

    suspend fun call(path: String, body: JsonObject): JsonElement = request(Request.Builder()
        .url(apiRoot + path).header("Accept", "application/json")
        .post(body.toString().toRequestBody("application/json".toMediaType())).build())

    private suspend fun request(request: Request, pendingLink: Boolean = false): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(request).useCancellableResponse { response ->
            if (!response.isSuccessful) throw StremioAccountApiException(response.code == 401 || response.code == 403)
            val body = response.body ?: throw StremioAccountApiException()
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 8 * 1024 * 1024) throw StremioAccountApiException()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val envelope = try { JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject }
                catch (_: Exception) { throw StremioAccountApiException() }
            if (envelope.has("error")) {
                val code = envelope.getAsJsonObject("error").get("code")?.asInt
                if (pendingLink && code == 101) return@withContext com.google.gson.JsonNull.INSTANCE
                throw StremioAccountApiException(code == 101)
            }
            envelope.get("result") ?: throw StremioAccountApiException()
        }
    }
}

internal fun jsonObject(vararg fields: Pair<String, Any?>): JsonObject = JsonObject().apply {
    fields.forEach { (key, value) -> when (value) {
        is JsonElement -> add(key, value)
        is Boolean -> addProperty(key, value)
        is Number -> addProperty(key, value)
        is String -> addProperty(key, value)
        null -> add(key, com.google.gson.JsonNull.INSTANCE)
        else -> error("Unsupported JSON value")
    } }
}

internal fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
internal fun JsonObject.number(key: String): Long = runCatching { get(key)?.asLong ?: 0L }.getOrDefault(0L)
internal fun JsonObject.flag(key: String): Boolean = runCatching { get(key)?.asBoolean ?: false }.getOrDefault(false)
