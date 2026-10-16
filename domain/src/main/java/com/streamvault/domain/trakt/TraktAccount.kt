package com.streamvault.domain.trakt

import kotlinx.coroutines.flow.StateFlow

data class TraktAccountState(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val username: String? = null,
    val connecting: Boolean = false,
    val message: TraktMessage? = null,
    val profile: TraktUserProfile? = null,
)

data class TraktUserProfile(
    val username: String,
    val name: String? = null,
    val location: String? = null,
    val about: String? = null,
    val gender: String? = null,
    val age: Int? = null,
    val joinedAt: String? = null,
    val vip: Boolean = false,
)

enum class TraktMessage { CONFIGURATION_REQUIRED, SIGN_IN_FAILED, RECONNECT_REQUIRED, NETWORK_ERROR, RATE_LIMITED }

/** Account operations expose no credentials to presentation code. */
interface TraktAccount {
    val state: StateFlow<TraktAccountState>
    suspend fun load()
    suspend fun beginSignIn(): String
    suspend fun completeSignIn(callback: String)
    suspend fun cancelSignIn()
    suspend fun disconnect()
    suspend fun refreshProfile()
}
