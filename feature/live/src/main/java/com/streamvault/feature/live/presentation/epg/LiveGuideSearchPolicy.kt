package com.streamvault.feature.live.presentation.epg

import com.streamvault.domain.model.Channel

fun matchesLiveGuideMetadataSearch(channel: Channel, searchQuery: String): Boolean =
    channel.name.contains(searchQuery, ignoreCase = true) ||
        channel.categoryName?.contains(searchQuery, ignoreCase = true) == true
