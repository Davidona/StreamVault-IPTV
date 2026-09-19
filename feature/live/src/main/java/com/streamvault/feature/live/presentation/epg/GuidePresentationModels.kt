package com.streamvault.feature.live.presentation.epg

import com.streamvault.domain.model.ProgramReminder
import com.streamvault.domain.model.ProgramReminderDeliveryState

enum class GuideChannelMode {
    ALL,
    ANCHORED,
    ARCHIVE_READY
}

enum class GuideDensity {
    COMPACT,
    COMFORTABLE,
    CINEMATIC
}

fun programReminderDeliveryIssueMessage(reminders: List<ProgramReminder>): String? {
    val issue = reminders.firstOrNull {
        it.deliveryState == ProgramReminderDeliveryState.BLOCKED ||
            it.deliveryState == ProgramReminderDeliveryState.FAILED
    } ?: return null
    val reason = issue.deliveryFailureReason
        ?.takeIf { it.isNotBlank() }
        ?: "The notification was not accepted."
    return "Reminder for ${issue.programTitle} was not delivered: $reason"
}
