package com.streamvault.app.ui.screens.provider

/**
 * Central provider catalog for seller-managed Xtream services.
 *
 * Today this is hardcoded in-app.
 * Future step: replace/augment with remote config from your portal endpoint
 * so URL changes can be applied without shipping a new APK.
 */
data class SellerXtreamService(
    val id: String,
    val displayName: String,
    val serverUrl: String,
    val defaultPlaylistName: String = displayName
)

object SellerProviderCatalog {
    /**
     * TODO (phase 2): point this to your portal endpoint and hydrate [services]
     * from remote JSON with local cache + background refresh.
     */
    const val REMOTE_CONFIG_URL: String = ""

    val services: List<SellerXtreamService> = listOf(
        SellerXtreamService(
            id = "apex",
            displayName = "Apex",
            serverUrl = "https://hyper-apex.com"
        ),
        SellerXtreamService(
            id = "combo",
            displayName = "Combo",
            serverUrl = "https://toastybread.fyi"
        ),
        SellerXtreamService(
            id = "premium",
            displayName = "Premium",
            serverUrl = "https://musclesx.cc"
        )
    )

    fun findById(id: String?): SellerXtreamService? = services.firstOrNull { it.id == id }
}
