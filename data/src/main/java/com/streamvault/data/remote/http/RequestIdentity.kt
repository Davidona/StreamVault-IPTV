package com.streamvault.data.remote.http

import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.LegacyProvider as Provider
import okhttp3.Interceptor
import okhttp3.Request

const val USER_AGENT_HEADER = "User-Agent"
private val DIAGNOSTIC_HEADER_ALLOWLIST = setOf(
    USER_AGENT_HEADER.lowercase(),
    "accept",
    "accept-encoding",
    "referer",
    "if-none-match",
    "if-modified-since"
)

data class HttpRequestProfile(
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val ownerTag: String? = null
) {
    fun mergedWithDefaults(defaultProfile: HttpRequestProfile): HttpRequestProfile =
        HttpRequestProfile(
            userAgent = userAgent?.takeIf { it.isNotBlank() } ?: defaultProfile.userAgent,
            headers = defaultProfile.headers + headers,
            ownerTag = ownerTag?.takeIf { it.isNotBlank() } ?: defaultProfile.ownerTag
        )
}

fun Request.withRequestProfile(profile: HttpRequestProfile): Request {
    val builder = newBuilder()
    if (header(USER_AGENT_HEADER).isNullOrBlank()) {
        profile.userAgent?.takeIf { it.isNotBlank() }?.let { builder.header(USER_AGENT_HEADER, it) }
    }
    profile.headers.forEach { (name, value) ->
        if (name.isBlank() || value.isBlank()) return@forEach
        if (header(name).isNullOrBlank()) {
            builder.header(name, value)
        }
    }
    return builder.build()
}

fun Request.safeRequestIdentitySummary(profile: HttpRequestProfile? = null): String {
    val ownerSummary = profile?.ownerTag?.takeIf { it.isNotBlank() }?.let { "owner=$it, " }.orEmpty()
    val userAgent = header(USER_AGENT_HEADER)?.takeIf { it.isNotBlank() } ?: "<none>"
    val safeHeaders = headers.names()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filter { name ->
            val normalized = name.lowercase()
            normalized in DIAGNOSTIC_HEADER_ALLOWLIST && normalized != USER_AGENT_HEADER.lowercase()
        }
        .sorted()
        .toList()
    return buildString {
        append(ownerSummary)
        append("userAgent=")
        append(userAgent)
        append(", headers=")
        append(safeHeaders)
    }
}

class DefaultUserAgentInterceptor(
    private val userAgent: String
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain) =
        chain.proceed(
            chain.request().withRequestProfile(
                HttpRequestProfile(userAgent = userAgent)
            )
        )
}

fun buildAppUserAgent(versionName: String?): String {
    val normalizedVersion = versionName?.trim().orEmpty().ifBlank { "dev" }
    return "StreamVault/$normalizedVersion (Android; Media3; OkHttp)"
}

fun buildOpenSubtitlesUserAgent(versionName: String?): String {
    val normalizedVersion = versionName?.trim().orEmpty().ifBlank { "dev" }
    return "StreamVault v$normalizedVersion"
}

fun buildAppRequestProfile(
    versionName: String?,
    ownerTag: String
): HttpRequestProfile = HttpRequestProfile(
    userAgent = buildAppUserAgent(versionName),
    ownerTag = ownerTag
)

fun Provider.toGenericRequestProfile(ownerTag: String): HttpRequestProfile =
    buildGenericProviderRequestProfile(ownerTag, httpUserAgent, httpHeaders)

fun buildGenericProviderRequestProfile(
    ownerTag: String,
    httpUserAgent: String,
    httpHeaders: String
): HttpRequestProfile = HttpRequestProfile(
    userAgent = httpUserAgent.takeIf { it.isNotBlank() },
    headers = parseProviderHttpHeaders(httpHeaders),
    ownerTag = ownerTag
)

private fun parseProviderHttpHeaders(httpHeaders: String): Map<String, String> {
    if (httpHeaders.isBlank()) {
        return emptyMap()
    }
    return buildMap {
        httpHeaders
            .split(Regex("\\s*\\|\\s*|[\\r\\n]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { entry ->
                val separatorIndex = entry.indexOf(':')
                if (separatorIndex <= 0 || separatorIndex == entry.lastIndex) {
                    return@forEach
                }
                val name = entry.substring(0, separatorIndex).trim()
                val value = entry.substring(separatorIndex + 1).trim()
                if (name.isBlank() || value.isBlank()) {
                    return@forEach
                }
                put(name, value)
            }
    }
}
