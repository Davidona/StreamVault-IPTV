package com.streamvault.app.ui.screens.player

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program

/** Stable identity for the channel rows in the live player overlay. */
internal fun playerChannelOverlayItemKey(channel: Channel): String =
    "channel:${channel.providerId}:${channel.id}:${channel.streamId}"

/** Stable identity for adjacent EPG programs, including providers with id=0. */
internal fun playerProgramOverlayItemKey(program: Program): String =
    "program:${program.providerId}:${program.channelId}:${program.id}:${program.startTime}:${program.endTime}"

/** Stable identity for category rows across display-name updates. */
internal fun playerCategoryOverlayItemKey(category: Category): String =
    "category:${category.roomId}:${category.id}"
