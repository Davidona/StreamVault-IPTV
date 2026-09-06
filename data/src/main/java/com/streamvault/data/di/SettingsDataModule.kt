package com.streamvault.data.di

import com.streamvault.data.settings.SettingsOperationsImpl
import com.streamvault.domain.settings.SettingsOperations
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsDataModule {
    @Binds
    abstract fun bindSettingsOperations(impl: SettingsOperationsImpl): SettingsOperations
}
