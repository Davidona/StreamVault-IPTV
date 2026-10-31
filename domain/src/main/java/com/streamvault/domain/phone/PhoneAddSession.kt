package com.streamvault.domain.phone

import kotlinx.coroutines.flow.StateFlow

enum class PhoneAddKind { STREMIO, EPG }

data class PhoneAddState(
    val kind: PhoneAddKind? = null,
    val url: String? = null,
    val busy: Boolean = false,
    val completed: Boolean = false,
    val error: Boolean = false,
)

interface PhoneAddSession {
    val state: StateFlow<PhoneAddState>
    suspend fun start(kind: PhoneAddKind)
    fun stop(kind: PhoneAddKind)
}
