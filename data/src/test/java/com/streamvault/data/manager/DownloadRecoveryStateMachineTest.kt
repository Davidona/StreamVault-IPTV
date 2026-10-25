package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadRecoveryStateMachineTest {

    @Test
    fun `process death after row transition restarts without trusting missing output`() {
        assertThat(
            planInterruptedDownload(
                bytesWritten = 0L,
                totalBytes = null,
                supportsResume = false,
                targetLength = null
            )
        ).isEqualTo(InterruptedDownloadPlan.RESTART)
    }

    @Test
    fun `process death after target creation restarts zero byte output`() {
        assertThat(
            planInterruptedDownload(
                bytesWritten = 0L,
                totalBytes = 1_000L,
                supportsResume = true,
                targetLength = 0L
            )
        ).isEqualTo(InterruptedDownloadPlan.RESTART)
    }

    @Test
    fun `exact progress checkpoint resumes validated output`() {
        assertThat(
            planInterruptedDownload(
                bytesWritten = 512L,
                totalBytes = 1_000L,
                supportsResume = true,
                targetLength = 512L
            )
        ).isEqualTo(InterruptedDownloadPlan.RESUME)
    }

    @Test
    fun `bytes beyond last checkpoint restart instead of duplicating appended data`() {
        assertThat(
            planInterruptedDownload(
                bytesWritten = 512L,
                totalBytes = 1_000L,
                supportsResume = true,
                targetLength = 700L
            )
        ).isEqualTo(InterruptedDownloadPlan.RESTART)
    }

    @Test
    fun `final bytes persisted before completion update recover as complete`() {
        assertThat(
            planInterruptedDownload(
                bytesWritten = 512L,
                totalBytes = 1_000L,
                supportsResume = true,
                targetLength = 1_000L
            )
        ).isEqualTo(InterruptedDownloadPlan.COMPLETE)
    }

    @Test
    fun `revoked SAF target restarts without attempting append`() {
        assertThat(
            planInterruptedDownload(
                bytesWritten = 512L,
                totalBytes = 1_000L,
                supportsResume = true,
                targetLength = null
            )
        ).isEqualTo(InterruptedDownloadPlan.RESTART)
    }

    @Test
    fun `valid 206 appends from requested byte and trusts content range total`() {
        assertThat(
            resolveResumeResponse(
                resumeFrom = 512L,
                responseCode = 206,
                contentLength = 488L,
                acceptRanges = null,
                contentRange = "bytes 512-999/1000"
            )
        ).isEqualTo(
            DownloadResumeResponse.TRANSFER(
                append = true,
                totalBytes = 1_000L,
                supportsResume = true
            )
        )
    }

    @Test
    fun `resumed request carries exact range and refreshed stream headers`() {
        val request = buildDownloadHttpRequest(
            url = "https://example.com/movie.mp4",
            headers = mapOf("Authorization" to "Bearer refreshed"),
            userAgent = "StreamVault Test",
            resumeFrom = 512L
        )

        assertThat(request.header("Range")).isEqualTo("bytes=512-")
        assertThat(request.header("Authorization")).isEqualTo("Bearer refreshed")
        assertThat(request.header("User-Agent")).isEqualTo("StreamVault Test")
    }

    @Test
    fun `fresh request omits range header`() {
        val request = buildDownloadHttpRequest(
            url = "https://example.com/movie.mp4",
            headers = emptyMap(),
            userAgent = null,
            resumeFrom = 0L
        )

        assertThat(request.header("Range")).isNull()
    }

    @Test
    fun `misaligned 206 restarts instead of corrupting output`() {
        assertThat(
            resolveResumeResponse(
                resumeFrom = 512L,
                responseCode = 206,
                contentLength = 600L,
                acceptRanges = "bytes",
                contentRange = "bytes 400-999/1000"
            )
        ).isEqualTo(DownloadResumeResponse.RESTART)
    }

    @Test
    fun `malformed 206 on fresh request fails instead of restart looping`() {
        assertThat(
            resolveResumeResponse(
                resumeFrom = 0L,
                responseCode = 206,
                contentLength = 600L,
                acceptRanges = "bytes",
                contentRange = null
            )
        ).isEqualTo(DownloadResumeResponse.FAIL)
    }

    @Test
    fun `200 response to range request restarts transfer from zero`() {
        assertThat(
            resolveResumeResponse(
                resumeFrom = 512L,
                responseCode = 200,
                contentLength = 1_000L,
                acceptRanges = "bytes",
                contentRange = null
            )
        ).isEqualTo(
            DownloadResumeResponse.TRANSFER(
                append = false,
                totalBytes = 1_000L,
                supportsResume = true
            )
        )
    }

    @Test
    fun `416 at exact remote length completes existing output`() {
        assertThat(
            resolveResumeResponse(
                resumeFrom = 1_000L,
                responseCode = 416,
                contentLength = 0L,
                acceptRanges = "bytes",
                contentRange = "bytes */1000"
            )
        ).isEqualTo(DownloadResumeResponse.COMPLETE)
    }

    @Test
    fun `416 with different remote length restarts safely`() {
        assertThat(
            resolveResumeResponse(
                resumeFrom = 512L,
                responseCode = 416,
                contentLength = 0L,
                acceptRanges = "bytes",
                contentRange = "bytes */1000"
            )
        ).isEqualTo(DownloadResumeResponse.RESTART)
    }

    @Test
    fun `resumed 206 must describe a consistent range of the same resource`() {
        fun resume(contentRange: String?, contentLength: Long?, expectedTotal: Long? = 1_000L) = resolveResumeResponse(
            resumeFrom = 512L,
            responseCode = 206,
            contentLength = contentLength,
            acceptRanges = "bytes",
            contentRange = contentRange,
            expectedTotalBytes = expectedTotal
        )

        assertThat(resume("bytes 512-999/1000", 488L)).isEqualTo(DownloadResumeResponse.TRANSFER(true, 1_000L, true))
        assertThat(resume("bytes 512-999/1001", 488L)).isEqualTo(DownloadResumeResponse.RESTART)
        assertThat(resume("bytes 512-999/1000", 500L)).isEqualTo(DownloadResumeResponse.RESTART)
        assertThat(resume("bytes 512-1000/1000", 489L)).isEqualTo(DownloadResumeResponse.RESTART)
        assertThat(resume("bytes */1000", 488L)).isEqualTo(DownloadResumeResponse.RESTART)
        assertThat(resume(null, 488L)).isEqualTo(DownloadResumeResponse.RESTART)
        assertThat(resume("bytes 512-999/*", 488L, expectedTotal = null))
            .isEqualTo(DownloadResumeResponse.TRANSFER(true, 1_000L, true))
    }
}
