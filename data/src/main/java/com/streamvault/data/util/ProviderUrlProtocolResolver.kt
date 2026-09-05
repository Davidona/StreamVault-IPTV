package com.streamvault.data.util

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ProviderUrlProtocolResolver {
    suspend fun resolve(url: String): String {
        // Respect explicit schemes; only schemeless input needs network probing.
        if (URL_SCHEME_REGEX.containsMatchIn(url)) return url
        return withContext(Dispatchers.IO) {
            val httpsUrl = "https://$url"
            try {
                val connection = URL(httpsUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.requestMethod = "HEAD"
                connection.instanceFollowRedirects = false
                connection.connect()
                val responseCode = connection.responseCode
                connection.disconnect()
                if (httpsProbeAccepts(responseCode)) httpsUrl else "http://$url"
            } catch (_: Exception) {
                "http://$url"
            }
        }
    }

    internal fun httpsProbeAccepts(responseCode: Int): Boolean = responseCode in 200..299

    private val URL_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
}
