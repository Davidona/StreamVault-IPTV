package com.streamvault.data.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.preferencesDataStoreFile
import com.streamvault.data.repository.LiveChannelOrderRepositoryImpl
import com.streamvault.data.repository.LiveWatchUsageRepositoryImpl
import com.streamvault.domain.repository.LiveChannelOrderRepository
import com.streamvault.domain.repository.LiveWatchUsageRepository
import com.streamvault.domain.settings.SettingsPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LiveOrderingModule {
    @Provides
    @Singleton
    fun provideLiveWatchUsageRepository(@ApplicationContext context: Context,
        preferences: SettingsPreferences): LiveWatchUsageRepository =
        LiveWatchUsageRepositoryImpl(PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
        ) { context.preferencesDataStoreFile("live_watch_usage") }, preferences.isIncognitoMode)

    @Provides
    @Singleton
    fun provideLiveChannelOrderRepository(@ApplicationContext context: Context): LiveChannelOrderRepository =
        LiveChannelOrderRepositoryImpl(PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("live_channel_order")
        })
}
