package com.streamvault.domain.playback

import com.streamvault.domain.model.Channel

fun List<Channel>.associateByAnyRawId(): Map<Long, Channel> = buildMap {
    this@associateByAnyRawId.forEach { channel ->
        channel.allVariantRawIds().forEach { rawId ->
            putIfAbsent(rawId, channel)
        }
    }
}

fun List<Channel>.orderedByRequestedRawIds(
    requestedIds: List<Long>,
    requiredProviderId: Long? = null
): List<Channel> {
    val filtered = requiredProviderId?.let { providerId ->
        filter { it.providerId == providerId }
    } ?: this
    val byRawId = filtered.associateByAnyRawId()
    val orderedChannels = requestedIds.mapNotNull { rawId ->
        byRawId[rawId]
    }
    val shouldCollapseGroupedDuplicates = orderedChannels.any { channel ->
        channel.allVariantRawIds().size > 1
    }
    if (!shouldCollapseGroupedDuplicates) {
        return orderedChannels
    }

    val seenKeys = linkedSetOf<String>()
    return orderedChannels.filter { channel ->
        seenKeys.add(channel.logicalGroupId.ifBlank { channel.id.toString() })
    }
}
