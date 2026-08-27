package com.streamvault.app.settings

import com.streamvault.feature.settings.api.SettingsAppUpdatePort
import com.streamvault.feature.settings.api.SettingsDiagnosticsPort
import com.streamvault.feature.settings.api.SettingsSurfaceRefreshPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AppSettingsBindings {
    @Binds
    abstract fun bindSettingsSurfaceRefreshPort(
        adapter: AppSettingsSurfaceRefreshAdapter,
    ): SettingsSurfaceRefreshPort

    @Binds
    abstract fun bindSettingsDiagnosticsPort(
        adapter: AppSettingsDiagnosticsAdapter,
    ): SettingsDiagnosticsPort

    @Binds
    abstract fun bindSettingsAppUpdatePort(
        adapter: AppSettingsUpdateAdapter,
    ): SettingsAppUpdatePort
}
