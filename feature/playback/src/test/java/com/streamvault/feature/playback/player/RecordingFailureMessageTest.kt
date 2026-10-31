package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ExactRecordingAlarmAccessException
import com.streamvault.domain.model.Result
import com.streamvault.feature.playback.R
import org.junit.Test

class RecordingFailureMessageTest {
    @Test
    fun `missing alarm access gets actionable message while other errors remain generic`() {
        assertThat(recordingFailureMessageRes(Result.error("internal", ExactRecordingAlarmAccessException("internal"))))
            .isEqualTo(R.string.player_recording_exact_alarm_required)
        assertThat(recordingFailureMessageRes(Result.error("private provider detail")))
            .isEqualTo(R.string.player_recording_failed_notice)
    }
}
