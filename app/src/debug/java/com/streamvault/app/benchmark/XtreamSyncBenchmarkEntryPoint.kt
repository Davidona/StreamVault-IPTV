package com.streamvault.app.benchmark

import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ValidateAndAddProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Typed access to the production graph for the opt-in, isolated sync probe. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface XtreamSyncBenchmarkEntryPoint {
    fun setup(): ValidateAndAddProvider
    fun providers(): ProviderRepository
    fun sync(): ProviderSyncCommands
    fun database(): StreamVaultDatabase
}
