package com.streamvault.app.di

import com.streamvault.data.webhook.PlaybackWebhookService
import com.streamvault.domain.playback.events.PlaybackWebhooks
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackWebhookModule {
    @Binds abstract fun webhooks(service: PlaybackWebhookService): PlaybackWebhooks
}
