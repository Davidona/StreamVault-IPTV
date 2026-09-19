package com.streamvault.app.navigation

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.scopes.ActivityRetainedScoped

@Module
@InstallIn(ActivityRetainedComponent::class)
internal abstract class NavigationCoordinatorModule {
    @Binds
    @ActivityRetainedScoped
    abstract fun bindNavigationCommandIdSource(
        implementation: AtomicNavigationCommandIdSource
    ): NavigationCommandIdSource
}
