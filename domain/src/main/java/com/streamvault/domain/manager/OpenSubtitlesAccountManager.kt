package com.streamvault.domain.manager

import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

data class OpenSubtitlesAccount(
    val username: String,
    val allowedDownloads: Int,
    val remainingDownloads: Int?,
    val level: String?,
    val vip: Boolean,
)

/** Account operations and current sign-in state for the OpenSubtitles provider. */
interface OpenSubtitlesAccountManager {
    val account: Flow<OpenSubtitlesAccount?>

    suspend fun signIn(username: String, password: String): Result<OpenSubtitlesAccount>

    suspend fun signOut(): Result<Unit>
}
