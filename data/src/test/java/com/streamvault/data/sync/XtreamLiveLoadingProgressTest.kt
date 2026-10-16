package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XtreamLiveLoadingProgressTest {
    @Test
    fun `batch notifications are throttled and final drained count is retained`() {
        var now = 0L
        val messages = mutableListOf<String>()
        val progress = XtreamLiveLoadingProgress({ now }, messages::add)
        progress.startAttempt()
        progress.batchStaged(100)
        now = 100L
        progress.batchStaged(200)
        progress.readerDrained(250)
        assertThat(messages).hasSize(3)
        assertThat(messages.last()).endsWith("250 channels processed")
        assertThat(messages.none { it.contains("ready", ignoreCase = true) || it.contains('%') }).isTrue()
        progress.readerDrained(250)
        assertThat(messages).hasSize(3)
    }

    @Test
    fun `retry clears discarded attempt count and immediately shows new staged rows`() {
        val messages = mutableListOf<String>()
        val progress = XtreamLiveLoadingProgress({ 0L }, messages::add)
        progress.startAttempt()
        progress.batchStaged(900)
        progress.startAttempt()
        progress.batchStaged(100)
        assertThat(messages[2]).isEqualTo(XtreamLiveProgressLabels.LOADING)
        assertThat(messages.last()).endsWith("100 channels processed")
    }
}
