package com.streamvault.data.provider

import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderConfiguration
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.XtreamConfig

internal fun ProviderConfiguration.withCanonicalProviderType(): ProviderConfiguration = when (this) {
    is XtreamConfig -> copy()
    is M3uConfig -> copy()
    is StalkerConfig -> copy()
    is JellyfinConfig -> copy()
}
