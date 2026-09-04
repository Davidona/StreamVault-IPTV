package com.streamvault.app.system

import com.streamvault.feature.system.api.SystemPluginManagementPort
import com.streamvault.feature.system.api.SystemWelcomePort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppSystemBindings {
    @Binds
    @Singleton
    abstract fun bindSystemWelcomePort(impl: AppSystemWelcomeAdapter): SystemWelcomePort

    @Binds
    @Singleton
    abstract fun bindSystemPluginManagementPort(
        impl: AppSystemPluginManagementAdapter,
    ): SystemPluginManagementPort
}
