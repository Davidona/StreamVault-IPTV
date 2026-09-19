package com.streamvault.feature.settings.api

interface SettingsSurfaceRefreshPort {
    suspend fun refreshWatchNext()
    suspend fun refreshRecommendations()
    suspend fun refreshTvInputCatalog()
    fun enqueueTvInputCatalogRefresh()
}
