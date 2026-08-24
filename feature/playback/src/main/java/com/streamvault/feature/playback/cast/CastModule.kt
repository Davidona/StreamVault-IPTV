package com.streamvault.feature.playback.cast
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CastModule {
    @Binds
    @Singleton
    abstract fun bindCastPlaybackCoordinator(
        impl: DefaultCastPlaybackCoordinator
    ): CastPlaybackCoordinator
}
