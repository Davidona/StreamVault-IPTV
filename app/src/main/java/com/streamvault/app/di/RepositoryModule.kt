package com.streamvault.app.di

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.RoomDatabaseTransactionRunner
import com.streamvault.data.manager.DownloadManagerImpl
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.security.AndroidKeystoreCredentialCrypto
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.sync.ProviderSyncStateReaderImpl
import com.streamvault.data.sync.CatalogHydrationCommands
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.data.sync.ProviderSyncLifecycle
import com.streamvault.data.sync.ProviderSyncStateSource
import com.streamvault.data.sync.SyncManager
import com.streamvault.data.provider.RoomProviderSnapshotRepository
import com.streamvault.data.provider.DefaultProviderCapabilityRegistry
import com.streamvault.data.provider.JellyfinCapabilityFactory
import com.streamvault.data.provider.M3uCapabilityFactory
import com.streamvault.data.provider.StalkerCapabilityFactory
import com.streamvault.data.provider.XtreamCapabilityFactory
import com.streamvault.data.remote.xtream.PlaybackObservationCoordinator
import com.streamvault.data.remote.xtream.PlaybackObservationSink
import com.streamvault.data.validation.ProviderSetupInputValidatorImpl
import com.streamvault.domain.manager.ParentalPinVerifier
import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.manager.ProviderSyncStateReader
import com.streamvault.data.repository.*
import com.streamvault.domain.manager.ParentalControlSessionStore
import com.streamvault.domain.repository.*
import com.streamvault.domain.manager.BackupRestoreStatusStore
import com.streamvault.data.manager.BackupRestoreStatusStoreImpl
import com.streamvault.domain.provider.ProviderCapabilityRegistry
import com.streamvault.domain.provider.ProviderSourceRegistry
import com.streamvault.app.plugins.StreamVaultPluginManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindBackupRestoreStatusStore(impl: BackupRestoreStatusStoreImpl): BackupRestoreStatusStore

    @Binds @Singleton
    abstract fun bindProviderRepository(impl: ProviderRepositoryImpl): ProviderRepository

    @Binds @Singleton
    abstract fun bindProviderSnapshotRepository(impl: RoomProviderSnapshotRepository): ProviderSnapshotRepository

    @Binds @Singleton
    abstract fun bindChannelRepository(impl: ChannelRepositoryImpl): ChannelRepository

    @Binds @Singleton
    abstract fun bindCombinedM3uRepository(impl: CombinedM3uRepositoryImpl): CombinedM3uRepository

    @Binds @Singleton
    abstract fun bindMovieRepository(impl: MovieRepositoryImpl): MovieRepository

    @Binds @Singleton
    abstract fun bindSeriesRepository(impl: SeriesRepositoryImpl): SeriesRepository

    @Binds @Singleton
    abstract fun bindVodRepository(impl: VodRepositoryImpl): VodRepository

    @Binds @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds @Singleton
    abstract fun bindEpgRepository(impl: EpgRepositoryImpl): EpgRepository

    @Binds @Singleton
    abstract fun bindEpgSourceRepository(impl: EpgSourceRepositoryImpl): EpgSourceRepository

    @Binds @Singleton
    abstract fun bindFavoriteRepository(impl: FavoriteRepositoryImpl): FavoriteRepository

    @Binds @Singleton
    abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository

    @Binds @Singleton
    abstract fun bindPlaybackHistoryRepository(impl: PlaybackHistoryRepositoryImpl): PlaybackHistoryRepository

    @Binds @Singleton
    abstract fun bindM3uClassificationRepository(impl: M3uClassificationRepositoryImpl): M3uClassificationRepository

    @Binds @Singleton
    abstract fun bindExternalRatingsRepository(impl: ExternalRatingsRepositoryImpl): ExternalRatingsRepository

    @Binds @Singleton
    abstract fun bindSyncMetadataRepository(impl: SyncMetadataRepositoryImpl): SyncMetadataRepository

    @Binds @Singleton
    abstract fun bindPlaybackCompatibilityRepository(impl: PlaybackCompatibilityRepositoryImpl): PlaybackCompatibilityRepository

    @Binds @Singleton
    abstract fun bindDatabaseTransactionRunner(impl: RoomDatabaseTransactionRunner): DatabaseTransactionRunner

    @Binds @Singleton
    abstract fun bindBackupManager(impl: com.streamvault.data.manager.BackupManagerImpl): com.streamvault.domain.manager.BackupManager

    @Binds @Singleton
    abstract fun bindDriveBackupSyncManager(impl: com.streamvault.data.manager.GoogleDriveBackupSyncManager): com.streamvault.domain.manager.DriveBackupSyncManager

    @Binds @Singleton
    abstract fun bindRecordingManager(impl: com.streamvault.data.manager.RecordingManagerImpl): com.streamvault.domain.manager.RecordingManager

    @Binds @Singleton
    abstract fun bindDownloadManager(impl: DownloadManagerImpl): DownloadManager

    @Binds @Singleton
    abstract fun bindProgramReminderManager(impl: com.streamvault.data.manager.ProgramReminderManagerImpl): com.streamvault.domain.manager.ProgramReminderManager

    @Binds @Singleton
    abstract fun bindParentalControlSessionStore(impl: PreferencesRepository): ParentalControlSessionStore

    @Binds @Singleton
    abstract fun bindParentalPinVerifier(impl: PreferencesRepository): ParentalPinVerifier

    @Binds @Singleton
    abstract fun bindProviderSetupInputValidator(impl: ProviderSetupInputValidatorImpl): ProviderSetupInputValidator

    @Binds @Singleton
    abstract fun bindProviderSyncStateReader(impl: ProviderSyncStateReaderImpl): ProviderSyncStateReader

    @Binds @Singleton
    abstract fun bindProviderSyncCommands(impl: SyncManager): ProviderSyncCommands

    @Binds @Singleton
    abstract fun bindCatalogHydrationCommands(impl: SyncManager): CatalogHydrationCommands

    @Binds @Singleton
    abstract fun bindProviderSyncStateSource(impl: SyncManager): ProviderSyncStateSource

    @Binds @Singleton
    abstract fun bindProviderSyncLifecycle(impl: SyncManager): ProviderSyncLifecycle

    @Binds @Singleton
    abstract fun bindCredentialCrypto(impl: AndroidKeystoreCredentialCrypto): CredentialCrypto

    @Binds @Singleton
    abstract fun bindProviderSourceRegistry(impl: StreamVaultPluginManager): ProviderSourceRegistry

    @Binds @Singleton
    abstract fun bindPlaybackObservationSink(impl: PlaybackObservationCoordinator): PlaybackObservationSink

    companion object {
        @Provides
        @Singleton
        fun provideRepositoryCoroutineScope(): CoroutineScope {
            return CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }

        @Provides
        @Singleton
        fun provideM3uParser(): com.streamvault.data.parser.M3uParser {
            return com.streamvault.data.parser.M3uParser()
        }

        @Provides
        @Singleton
        fun provideProviderCapabilityRegistry(
            xtream: XtreamCapabilityFactory,
            stalker: StalkerCapabilityFactory,
            m3u: M3uCapabilityFactory,
            jellyfin: JellyfinCapabilityFactory
        ): ProviderCapabilityRegistry = DefaultProviderCapabilityRegistry(
            listOf(xtream, stalker, m3u, jellyfin)
        )
    }
}
