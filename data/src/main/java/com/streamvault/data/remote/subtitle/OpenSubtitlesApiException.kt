package com.streamvault.data.remote.subtitle

import java.io.IOException

class OpenSubtitlesApiException(
    message: String,
    val statusCode: Int? = null,
    val isUnauthorized: Boolean = false,
    val isRateLimited: Boolean = false,
    cause: Throwable? = null
) : IOException(message, cause)
