package com.streamvault.feature.live.presentation.model

import com.streamvault.domain.model.Channel

fun Channel.guideLookupKey(): String? {
    return streamId.takeIf { it > 0L }?.toString()
        ?: epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
}
