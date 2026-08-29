package com.streamvault.feature.live.presentation.epg

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.feature.live.presentation.model.guideLookupKey

data class LiveGuideFocusSelection(
    val channel: Channel?,
    val program: Program?
)

fun resolveLiveGuideFocus(
    channels: List<Channel>,
    programsByChannel: Map<String, List<Program>>,
    focusedChannel: Channel?,
    focusedProgram: Program?,
    now: Long
): LiveGuideFocusSelection {
    if (channels.isEmpty()) {
        return LiveGuideFocusSelection(channel = null, program = null)
    }

    val resolvedChannel = focusedChannel?.let { current ->
        channels.firstOrNull { it.id == current.id }
    } ?: channels.firstOrNull()
    val resolvedPrograms = resolvedChannel?.let { channel ->
        channel.guideLookupKey()?.let { lookupKey ->
            programsByChannel[lookupKey].orEmpty()
        }.orEmpty()
    }.orEmpty()
    val resolvedProgram = focusedProgram?.let { focused ->
        resolvedPrograms.firstOrNull {
            it.startTime == focused.startTime &&
                it.endTime == focused.endTime &&
                it.title == focused.title
        }
    } ?: resolvedPrograms.firstOrNull {
        now in it.startTime until it.endTime
    }

    return LiveGuideFocusSelection(
        channel = resolvedChannel,
        program = resolvedProgram
    )
}
