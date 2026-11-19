package com.streamvault.app.benchmark

import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ValidateAndAddProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Typed access to the production graph for the opt-in, isolated sync probe. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface XtreamSyncBenchmarkEntryPoint {
    @com.streamvault.player.di.MainPlayerEngine
    fun launcherPlayerEngine(): com.streamvault.player.PlayerEngine
    fun launcherHistory(): com.streamvault.domain.repository.PlaybackHistoryRepository
    fun launcherWatchNext(): com.streamvault.app.tv.WatchNextManager
    fun launcherRecommendations(): com.streamvault.app.tv.LauncherRecommendationsManager
    fun launcherFavorites(): com.streamvault.domain.repository.FavoriteRepository
    fun launcherCategories(): com.streamvault.domain.repository.CategoryRepository
    fun webhooks(): com.streamvault.domain.playback.events.PlaybackWebhooks
    fun setup(): ValidateAndAddProvider
    fun providers(): ProviderRepository
    fun sync(): ProviderSyncCommands
    fun database(): StreamVaultDatabase
    fun preferences(): SettingsPreferences
    fun syncMetadata(): SyncMetadataRepository
}
