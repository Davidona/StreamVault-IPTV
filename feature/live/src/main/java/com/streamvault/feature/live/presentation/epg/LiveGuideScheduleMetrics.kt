package com.streamvault.feature.live.presentation.epg

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.feature.live.presentation.model.guideLookupKey

fun countMissingLiveGuideEntries(
    channels: List<Channel>,
    programsByChannel: Map<String, List<Program>>
): Int = channels.mapNotNull(Channel::guideLookupKey)
    .distinct()
    .count { lookupKey -> programsByChannel[lookupKey].isNullOrEmpty() }

fun countLiveGuideChannelsWithSchedule(
    channels: List<Channel>,
    programsByChannel: Map<String, List<Program>>
): Int = channels.count { channel ->
    channel.guideLookupKey()
        ?.let { lookupKey -> programsByChannel[lookupKey].orEmpty().isNotEmpty() }
        ?: false
}

fun hasUpcomingLiveGuideData(
    programsByChannel: Map<String, List<Program>>,
    windowStart: Long
): Boolean = programsByChannel.values.any { programs ->
    programs.any { program -> program.endTime > windowStart }
}
