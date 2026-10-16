package com.streamvault.feature.catalog.presentation.vod

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.streamvault.feature.catalog.R
import com.streamvault.domain.trakt.TraktDiscoveryCategory
import com.streamvault.domain.trakt.TraktDiscoveryKind
import com.streamvault.domain.trakt.TraktDiscoveryMedia
import com.streamvault.core.ui.components.dialogs.PinDialog
import kotlinx.coroutines.launch

object VodBrowseDefaults {
    const val FAVORITES_CATEGORY = "\u2605 Favorites"
    const val FAVORITES_SENTINEL_ID = -999L
    const val TRAKT_WATCHLIST_CATEGORY = "__trakt_watchlist__"
    const val TRAKT_TRENDING_MOVIES_CATEGORY = "__trakt_trending_movies__"
    const val TRAKT_POPULAR_MOVIES_CATEGORY = "__trakt_popular_movies__"
    const val TRAKT_TRENDING_SERIES_CATEGORY = "__trakt_trending_series__"
    const val TRAKT_POPULAR_SERIES_CATEGORY = "__trakt_popular_series__"
    val TRAKT_TRENDING_MOVIES = TraktDiscoveryCategory(TraktDiscoveryMedia.MOVIE, TraktDiscoveryKind.TRENDING)
    val TRAKT_POPULAR_MOVIES = TraktDiscoveryCategory(TraktDiscoveryMedia.MOVIE, TraktDiscoveryKind.POPULAR)
    val TRAKT_TRENDING_SERIES = TraktDiscoveryCategory(TraktDiscoveryMedia.SHOW, TraktDiscoveryKind.TRENDING)
    val TRAKT_POPULAR_SERIES = TraktDiscoveryCategory(TraktDiscoveryMedia.SHOW, TraktDiscoveryKind.POPULAR)
    const val FULL_LIBRARY_CATEGORY = "__full_library__"
    const val PREVIEW_ROW_LIMIT = 18
    const val SELECTED_CATEGORY_PAGE_SIZE = 60

    fun traktDiscoveryCategory(categoryName: String?): TraktDiscoveryCategory? = when (categoryName) {
        TRAKT_TRENDING_MOVIES_CATEGORY -> TRAKT_TRENDING_MOVIES
        TRAKT_POPULAR_MOVIES_CATEGORY -> TRAKT_POPULAR_MOVIES
        TRAKT_TRENDING_SERIES_CATEGORY -> TRAKT_TRENDING_SERIES
        TRAKT_POPULAR_SERIES_CATEGORY -> TRAKT_POPULAR_SERIES
        else -> null
    }
}

@Composable
fun HandleVodUserMessage(
    userMessage: String?,
    snackbarHostState: SnackbarHostState,
    onShown: () -> Unit
) {
    LaunchedEffect(userMessage) {
        userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onShown()
        }
    }
}

@Composable
fun ProtectedVodPinDialog(
    visible: Boolean,
    error: String?,
    incorrectPinMessage: String,
    onDismissRequest: () -> Unit,
    onVerified: () -> Unit,
    onErrorChange: (String?) -> Unit,
    verifyPin: suspend (String) -> Boolean
) {
    if (!visible) return

    val scope = rememberCoroutineScope()
    PinDialog(
        title = stringResource(R.string.pin_dialog_title),
        cancelLabel = stringResource(R.string.pin_dialog_cancel),
        onDismissRequest = onDismissRequest,
        onPinEntered = { pin ->
            scope.launch {
                if (verifyPin(pin)) {
                    onErrorChange(null)
                    onVerified()
                } else {
                    onErrorChange(incorrectPinMessage)
                }
            }
        },
        error = error
    )
}

