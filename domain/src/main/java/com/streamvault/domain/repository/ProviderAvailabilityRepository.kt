package com.streamvault.domain.repository

import com.streamvault.domain.model.ProviderAvailabilityResult

interface ProviderAvailabilityRepository {
    suspend fun check(providerId: Long): ProviderAvailabilityResult
}
