package com.streamvault.player.playback

import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/** Some service archive engines emit a byte beyond Content-Length on full reads.
 * Never reuse their HTTP/1 connection for a later seek or engine request. */
object StremioServiceConnectionPolicy {
    fun apply(client:OkHttpClient,mediaUrl:String,stremioScoped:Boolean):OkHttpClient {
        if(!stremioScoped || !isEngineUrl(mediaUrl)) return client
        return client.newBuilder().connectionPool(ConnectionPool(0,1,TimeUnit.SECONDS)).build()
    }
    internal fun isEngineUrl(value:String):Boolean=runCatching {
        val uri=URI(value)
        uri.scheme in setOf("http","https") &&
            uri.rawQuery.orEmpty().split('&').any { it.substringBefore('=')=="lz" } &&
            (Regex("/(?:zip|rar|7zip|tar|tgz|nzb)/create$").containsMatchIn(uri.path) ||
                Regex("/ftp/[^/]+$").containsMatchIn(uri.path))
    }.getOrDefault(false)
}
