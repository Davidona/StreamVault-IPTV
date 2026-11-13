package com.streamvault.app.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.streamvault.app.MainActivity
import com.streamvault.core.navigation.ExternalItemAction
import com.streamvault.core.navigation.ExternalItemLink
import com.streamvault.core.navigation.ExternalItemType
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory

/** Public URI contract; intentionally carries no playback URLs, headers, or credentials. */
internal object ExternalItemLinkCodec {
    fun decode(uri: Uri): ExternalItemLink? = runCatching {
        if (uri.toString().length > 2048 || uri.scheme != "streamvault" ||
            !uri.isHierarchical || uri.fragment != null || uri.userInfo != null || uri.port != -1 ||
            uri.toString().any(Char::isISOControl)) return null
        val type = ExternalItemType.entries.firstOrNull { it.path == uri.encodedAuthority } ?: return null
        val path = uri.encodedPath?.removePrefix("/") ?: return null
        if (!path.matches(Regex("[1-9][0-9]{0,18}"))) return null
        val id = path.toLongOrNull() ?: return null
        if (uri.queryParameterNames.any { it !in setOf("provider", "action") } ||
            uri.getQueryParameters("provider").size != 1 ||
            uri.getQueryParameters("action").size > 1) return null
        val provider = uri.getQueryParameter("provider") ?: return null
        if (!provider.matches(Regex("[1-9][0-9]{0,18}"))) return null
        val providerId = provider.toLongOrNull() ?: return null
        val rawAction = uri.getQueryParameter("action")
        val action = if (rawAction == null) when (type) {
            ExternalItemType.MOVIE, ExternalItemType.SERIES -> ExternalItemAction.DETAILS
            ExternalItemType.EPISODE -> ExternalItemAction.RESUME
            ExternalItemType.LIVE -> ExternalItemAction.PLAY
        } else ExternalItemAction.entries.firstOrNull { it.parameter == rawAction } ?: return null
        ExternalItemLink(type, id, providerId, action)
    }.getOrNull()

    fun encode(link: ExternalItemLink): Uri = Uri.Builder()
        .scheme("streamvault").authority(link.type.path).appendPath(link.id.toString())
        .appendQueryParameter("provider", link.providerId.toString())
        .appendQueryParameter("action", link.action.parameter).build()

    fun intent(context: Context, link: ExternalItemLink): Intent =
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(encode(link))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun historyLink(history: PlaybackHistory): ExternalItemLink? {
        val type = when (history.contentType) {
            ContentType.MOVIE -> ExternalItemType.MOVIE
            ContentType.SERIES_EPISODE -> ExternalItemType.EPISODE
            ContentType.SERIES -> ExternalItemType.SERIES
            else -> return null
        }
        if (history.contentId <= 0 || history.providerId <= 0) return null
        return ExternalItemLink(type, history.contentId, history.providerId, ExternalItemAction.RESUME)
    }
}
