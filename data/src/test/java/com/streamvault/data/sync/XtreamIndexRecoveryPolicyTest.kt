package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamRequestException
import org.junit.Test

class XtreamIndexRecoveryPolicyTest {
    @Test fun `action forbidden remains retryable while account rejection stays permanent`() {
        assertThat(XtreamIndexRecoveryPolicy.failureState(XtreamRequestException(403, "HTTP 403")))
            .isEqualTo("FAILED_RETRYABLE")
        assertThat(XtreamIndexRecoveryPolicy.failureState(XtreamAuthenticationException(403, "HTTP 403")))
            .isEqualTo("FAILED_PERMANENT")
        assertThat(XtreamIndexRecoveryPolicy.failureState(XtreamAuthenticationException(401, "HTTP 401")))
            .isEqualTo("FAILED_PERMANENT")
    }
}
