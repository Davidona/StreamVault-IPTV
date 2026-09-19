package com.streamvault.feature.live.epg

import com.streamvault.feature.live.presentation.epg.programReminderDeliveryIssueMessage

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ProgramReminder
import com.streamvault.domain.model.ProgramReminderDeliveryState
import org.junit.Test

class ProgramReminderIssueMessageTest {

    @Test
    fun `blocked persisted reminder is surfaced with its reason`() {
        val reminder = ProgramReminder(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = 10_000L,
            remindAt = 5_000L,
            deliveryState = ProgramReminderDeliveryState.BLOCKED,
            deliveryFailureReason = "Program reminders channel is disabled"
        )

        assertThat(programReminderDeliveryIssueMessage(listOf(reminder)))
            .isEqualTo(
                "Reminder for World News was not delivered: " +
                    "Program reminders channel is disabled"
            )
    }

    @Test
    fun `delivered reminders do not surface an issue`() {
        val reminder = ProgramReminder(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = 10_000L,
            remindAt = 5_000L,
            deliveryState = ProgramReminderDeliveryState.DELIVERED,
            notifiedAt = 5_000L
        )

        assertThat(programReminderDeliveryIssueMessage(listOf(reminder))).isNull()
    }
}
