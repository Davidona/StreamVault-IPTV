package com.streamvault.domain.model.stremio

import kotlinx.coroutines.flow.StateFlow

enum class StremioAccountError { NETWORK, SIGN_IN, EXPIRED, STORAGE, SYNC, INCOGNITO }

data class StremioAccountState(
    val ready: Boolean = false,
    val connected: Boolean = false,
    val displayName: String? = null,
    val linkUrl: String? = null,
    val linkCode: String? = null,
    val busy: Boolean = false,
    val lastSyncAt: Long = 0,
    val error: StremioAccountError? = null
)

/** Presentation never receives the account credential. */
interface StremioAccount {
    val state: StateFlow<StremioAccountState>
    fun start()
    suspend fun load()
    suspend fun beginLink()
    suspend fun pollLink()
    suspend fun cancelLink()
    suspend fun disconnect()
    suspend fun sync(): Boolean
}
