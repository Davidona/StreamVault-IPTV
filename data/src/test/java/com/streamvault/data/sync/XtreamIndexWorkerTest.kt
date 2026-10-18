package com.streamvault.data.sync

import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XtreamIndexWorkerTest {
    @Test
    fun `recorded permanent section failure releases appended provider work`() {
        assertThat(xtreamIndexWorkResult(7L, false, true))
            .isInstanceOf(ListenableWorker.Result.Success::class.java)
    }

    @Test
    fun `transient failure keeps backoff even alongside a permanent failure`() {
        assertThat(xtreamIndexWorkResult(7L, true, true))
            .isInstanceOf(ListenableWorker.Result.Retry::class.java)
    }

    @Test
    fun `standalone periodic batch reports permanent failure`() {
        assertThat(xtreamIndexWorkResult(-1L, false, true))
            .isInstanceOf(ListenableWorker.Result.Failure::class.java)
    }
}
