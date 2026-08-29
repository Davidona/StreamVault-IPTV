package com.streamvault.app.ui.model

import com.streamvault.domain.model.Channel

fun Channel.guideLookupKey(): String? =
    streamId.takeIf { it > 0L }?.toString()
        ?: epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
