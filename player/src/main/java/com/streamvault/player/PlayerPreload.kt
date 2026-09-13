package com.streamvault.player

import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.util.StreamEntryUrlPolicy

enum class PlayerPreloadContentType {
    VOD,
    CATCH_UP,
    LIVE
}

data class PlayerPreloadItem(
    val key: String,
    val streamInfo: StreamInfo,
    val contentType: PlayerPreloadContentType
)

data class PlayerPreloadWindow(
    val items: List<PlayerPreloadItem>,
    val currentIndex: Int
)

/**
 * Keeps the player-facing preload window small and deterministic.
 *
 * Stream type, expiry, and DRM eligibility are deliberately left to the player
 * manager because they may require the resolved preparation plan and current
 * time. This policy only handles the data that can be checked at the boundary.
 */
fun normalizePlayerPreloadWindow(
    window: PlayerPreloadWindow,
    maxManagedItems: Int = 3
): PlayerPreloadWindow {
    if (maxManagedItems <= 0 || window.currentIndex !in window.items.indices) {
        return emptyPlayerPreloadWindow()
    }

    val candidates = mutableListOf<PlayerPreloadItem>()
    val firstIndexByKey = mutableMapOf<String, Int>()
    var currentKey: String? = null

    window.items.forEachIndexed { index, item ->
        val key = item.key.trim()
        val url = item.streamInfo.url.trim()
        val isEligible = key.isNotEmpty() &&
            StreamEntryUrlPolicy.isAllowed(url) &&
            item.contentType != PlayerPreloadContentType.LIVE

        if (index == window.currentIndex && !isEligible) {
            currentKey = null
        } else if (isEligible) {
            val normalizedItem = item.copy(
                key = key,
                streamInfo = item.streamInfo.copy(url = url)
            )
            if (index == window.currentIndex) {
                currentKey = key
            }
            if (key !in firstIndexByKey) {
                firstIndexByKey[key] = candidates.size
                candidates += normalizedItem
            }
        }
    }

    val currentPosition = currentKey?.let(firstIndexByKey::get) ?: return emptyPlayerPreloadWindow()
    val maxItems = minOf(maxManagedItems, 3)
    val startPosition: Int
    val endPosition: Int
    when (maxItems) {
        1 -> {
            startPosition = currentPosition
            endPosition = currentPosition
        }
        2 -> {
            if (currentPosition > 0) {
                startPosition = currentPosition - 1
                endPosition = currentPosition
            } else {
                startPosition = currentPosition
                endPosition = minOf(candidates.lastIndex, currentPosition + 1)
            }
        }
        else -> {
            startPosition = maxOf(0, currentPosition - 1)
            endPosition = minOf(candidates.lastIndex, currentPosition + 1)
        }
    }
    val selected = candidates.subList(startPosition, endPosition + 1).toList()

    return PlayerPreloadWindow(
        items = selected,
        currentIndex = currentPosition - startPosition
    )
}

private fun emptyPlayerPreloadWindow(): PlayerPreloadWindow =
    PlayerPreloadWindow(items = emptyList(), currentIndex = -1)
