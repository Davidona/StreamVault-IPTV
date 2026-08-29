package com.streamvault.feature.live.presentation.epg

import com.streamvault.domain.model.Channel
import com.streamvault.feature.live.presentation.model.guideLookupKey

fun liveEpgChannelKey(channel: Channel, index: Int): String {
    val epgId = channel.guideLookupKey().orEmpty()
    return "channel:${channel.id}:${channel.streamId}:$epgId:${channel.name.trim()}:$index"
}
