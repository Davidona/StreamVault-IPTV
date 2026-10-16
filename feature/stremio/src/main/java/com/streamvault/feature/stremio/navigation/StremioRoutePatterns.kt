package com.streamvault.feature.stremio.navigation

import android.net.Uri
import com.streamvault.domain.model.stremio.StremioMetaPreview

object StremioRoutePatterns {
    const val ADDONS = "addons"
    const val ITEM = "addons/item/{originAddonId}/{type}/{exactId}?name={name}&poster={poster}&description={description}&releaseInfo={releaseInfo}&replayVideoId={replayVideoId}&replayOriginKey={replayOriginKey}&replayRequiresOrigin={replayRequiresOrigin}"

    fun itemRoute(
        originAddonId: Long,
        preview: StremioMetaPreview,
        replayVideoId: String? = null,
        replayOriginKey: String? = null,
        replayRequiresOrigin: Boolean = false
    ): String =
        "addons/item/$originAddonId/${preview.type.typeName}/${Uri.encode(preview.id)}" +
            "?name=${Uri.encode(preview.name)}" +
            "&poster=${Uri.encode(preview.poster.orEmpty())}" +
            "&description=${Uri.encode(preview.description.orEmpty())}" +
            "&releaseInfo=${Uri.encode(preview.releaseInfo.orEmpty())}" +
            "&replayVideoId=${Uri.encode(replayVideoId.orEmpty())}" +
            "&replayOriginKey=${Uri.encode(replayOriginKey.orEmpty())}" +
            "&replayRequiresOrigin=$replayRequiresOrigin"

    fun historyRoute(originAddonId: Long, history: com.streamvault.domain.model.stremio.StremioPlaybackHistory): String {
        val type = com.streamvault.domain.model.stremio.StremioMediaType.fromTypeName(history.mediaType)
        val itemId = if (type == com.streamvault.domain.model.stremio.StremioMediaType.SERIES) {
            history.parentSeriesId ?: history.exactVideoId
        } else history.exactVideoId
        val preview = StremioMetaPreview(
            id = itemId,
            type = type,
            name = history.title,
            poster = history.posterUrl
        )
        val requireOrigin = !history.contentKey.startsWith("stremio:global:")
        return itemRoute(
            originAddonId = originAddonId,
            preview = preview,
            replayVideoId = history.exactVideoId,
            replayOriginKey = history.catalogOriginKey,
            replayRequiresOrigin = requireOrigin
        )
    }
}
