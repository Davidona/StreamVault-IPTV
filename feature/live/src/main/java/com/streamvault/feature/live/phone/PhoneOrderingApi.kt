package com.streamvault.feature.live.phone

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamvault.core.ui.companion.ParsedCompanionRequest
import com.streamvault.domain.model.LiveChannelOrderMode
import com.streamvault.domain.model.VirtualCategoryIds

/** JSON-only API. The server must enforce a bounded request body and serve no cross-origin responses. */
internal class PhoneOrderingApi(
    private val guard: PhoneSessionGuard,
    private val backend: PhoneOrderingBackend,
    private val labels: PhoneLabels,
    private val uiStrings: Map<String, String> = emptyMap(),
    private val allowProtected: Boolean = false,
    private val parentalLevel: suspend () -> Int,
) {
    data class Response(val status: Int, val body: String = "{}", val cookie: String? = null)

    suspend fun handle(request: ParsedCompanionRequest): Response {
        if (request.method != "POST" || !request.pathAndQuery.startsWith("/api/") ||
            request.pathAndQuery.contains('?') ||
            request.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
            return Response(400)
        }
        val host = request.headers["host"]
        val origin = request.headers["origin"]
        val input = try { JsonParser.parseString(request.body).asJsonObject } catch (_: Exception) { return Response(400) }
        if (request.pathAndQuery == "/api/pair") {
            val secret = input.string("secret") ?: return Response(400)
            val credentials = guard.pair(host, origin, secret, parentalLevel()) ?: return Response(403)
            return Response(200, JsonObject().apply { addProperty("csrf", credentials.csrf) }.toString(), credentials.cookie)
        }
        val cookies = request.headers["cookie"].orEmpty().split(';').map { it.trim() }
        val sessionCookies = cookies.filter { it.startsWith("ordering_session=") }
        if (sessionCookies.size != 1 || !guard.authorize(host, origin,
                sessionCookies.single().substringAfter('='), request.headers["x-ordering-csrf"], parentalLevel())) {
            return Response(403)
        }
        return try {
            route(request.pathAndQuery, input)
        } catch (_: IllegalArgumentException) {
            Response(400)
        } catch (_: IllegalStateException) {
            Response(400)
        } catch (_: ClassCastException) {
            Response(400)
        }
    }

    private suspend fun route(path: String, input: JsonObject): Response {
        return when (path) {
        "/api/labels" -> Response(200, JsonObject().apply { uiStrings.forEach { (key, value) -> addProperty(key, value) } }.toString())
        "/api/categories" -> {
            val catalog = guard.combinedProfileId?.let { backend.combinedCategories(it, labels, allowProtected) }
                ?: backend.categories(guard.providerId, labels, allowProtected)
            Response(200, JsonObject().apply {
                addProperty("title", catalog.sourceName)
                addProperty("revision", catalog.revision.toString())
                addProperty("fingerprint", catalog.fingerprint)
                add("normalReference", JsonArray().apply { catalog.normalReference.forEach(::add) })
                add("editableSections", JsonArray().apply { catalog.editableSections.forEach { add(it.name) } })
                add("sections", JsonObject().apply {
                    PhoneSection.entries.forEach { section -> add(section.name, JsonArray().apply {
                        catalog.sections.getValue(section).forEach { row -> add(JsonObject().apply {
                            addProperty("key", row.key); addProperty("name", row.name)
                            addProperty("hidden", row.hidden); addProperty("editable", row.canEditChannels)
                        }) }
                    }) }
                })
            }.toString())
        }
        "/api/categories/save" -> {
            val sections = input.getAsJsonObject("sections") ?: return Response(400)
            val proposal = PhoneSection.entries.associateWith { sections.strings(it.name) ?: return Response(400) }
            val result = guard.combinedProfileId?.let { profileId ->
                backend.saveCombinedCategories(profileId, labels, input.revision(), input.fingerprint(), proposal, allowProtected)
            } ?: backend.saveCategories(guard.providerId, labels, input.revision(), input.fingerprint(), proposal, allowProtected)
            result.response()
        }
        "/api/channels" -> {
            val categoryId = input.categoryId()
            val catalog = guard.combinedProfileId?.let { backend.combinedChannels(it, categoryId, labels, allowProtected) }
                ?: backend.channels(guard.providerId, categoryId, labels.all, allowProtected)
                ?: return Response(400)
            Response(200, JsonObject().apply {
                addProperty("title", if (categoryId == VirtualCategoryIds.FAVORITES) labels.favorites else catalog.title)
                addProperty("revision", catalog.revision.toString())
                addProperty("fingerprint", catalog.fingerprint); addProperty("mode", catalog.mode.name)
                addProperty("defaultMode", catalog.defaultMode.name); addProperty("hasManualOrder", catalog.hasManualOrder)
                addProperty("fixedOrder", catalog.fixedOrder)
                add("rows", catalog.rows.json())
            }.toString())
        }
        "/api/channels/preview" -> {
            val categoryId = input.categoryId()
            val (result, rows) = guard.combinedProfileId?.let { profileId ->
                backend.previewCombinedChannels(profileId, categoryId, labels, input.revision(), input.fingerprint(), input.mode(), allowProtected)
            } ?: backend.previewChannels(guard.providerId, categoryId, labels.all,
                input.revision(), input.fingerprint(), input.mode(), allowProtected)
            if (result != PhoneSaveResult.Saved) result.response() else Response(200, JsonObject().apply { add("rows", rows.json()) }.toString())
        }
        "/api/channels/save" -> {
            val categoryId = input.categoryId()
            val keys = input.strings("keys") ?: return Response(400)
            val result = guard.combinedProfileId?.let { profileId ->
                backend.saveCombinedChannels(profileId, categoryId, labels, input.revision(), input.fingerprint(),
                    input.mode(), input.boolean("manuallyMoved"), keys, allowProtected)
            } ?: backend.saveChannels(guard.providerId, categoryId, labels.all,
                input.revision(), input.fingerprint(), input.mode(), input.boolean("manuallyMoved"), keys, allowProtected)
            result.response()
        }
        else -> Response(404)
        }
    }

    private fun List<PhoneChannelRow>.json() = JsonArray().also { array -> forEach { row ->
        array.add(JsonObject().apply { addProperty("key", row.key); addProperty("name", row.name) })
    } }
    private fun PhoneSaveResult.response() = Response(when (this) {
        PhoneSaveResult.Saved -> 200
        PhoneSaveResult.Conflict -> 409
        PhoneSaveResult.Invalid -> 400
    })
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.strings(key: String): List<String>? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.map {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw IllegalArgumentException()
        it.asString
    }
    private fun JsonObject.revision(): Long = string("revision")?.toLongOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException()
    private fun JsonObject.categoryId(): Long = string("categoryId")?.toLongOrNull() ?: throw IllegalArgumentException()
    private fun JsonObject.fingerprint(): String = string("fingerprint")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: throw IllegalArgumentException()
    private fun JsonObject.mode(): LiveChannelOrderMode = LiveChannelOrderMode.valueOf(string("mode") ?: throw IllegalArgumentException())
    private fun JsonObject.boolean(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        ?: throw IllegalArgumentException()
}
