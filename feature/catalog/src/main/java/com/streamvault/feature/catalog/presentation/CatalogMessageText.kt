package com.streamvault.feature.catalog.presentation

import android.content.Context
import com.streamvault.feature.catalog.R
import com.streamvault.feature.catalog.api.CatalogMessage

internal fun Context.catalogMessageText(message: CatalogMessage): String = when (message) {
    CatalogMessage.CastStarted -> getString(R.string.cast_started)
    CatalogMessage.CastUnavailable -> getString(R.string.cast_unavailable)
    CatalogMessage.CastUnsupported,
    CatalogMessage.CastItemUnavailable -> getString(R.string.cast_item_unavailable)
    CatalogMessage.CastSessionFailed,
    CatalogMessage.CastLoadFailed -> getString(R.string.cast_item_unavailable)
    CatalogMessage.DownloadStarted -> getString(R.string.download_started)
    CatalogMessage.DownloadFailed -> getString(R.string.download_failed, "")
    CatalogMessage.DownloadUrlUnavailable -> getString(R.string.download_error_no_url)
    CatalogMessage.TraktWatchlistFailed -> getString(R.string.trakt_watchlist_failed)
    CatalogMessage.TraktFavoriteSyncFailed -> getString(R.string.trakt_favorite_sync_failed)
    CatalogMessage.TraktRatingSaved -> getString(R.string.trakt_rating_saved)
    CatalogMessage.TraktRatingFailed -> getString(R.string.trakt_rating_failed)
    CatalogMessage.SeriesVariantLoadFailed -> getString(R.string.series_episodes_load_failed)
}
