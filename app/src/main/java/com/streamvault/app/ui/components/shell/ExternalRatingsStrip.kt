package com.streamvault.app.ui.components.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.design.AppColors
import com.streamvault.domain.model.ExternalRatingValue
import com.streamvault.domain.model.ExternalRatings

@Composable
fun ExternalRatingsStrip(
    ratings: ExternalRatings,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isLoading && !ratings.hasAnyAvailableValue()) {
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.vod_external_ratings_heading),
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextSecondary
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RatingBadgeCard(
                token = "IMDb",
                title = stringResource(R.string.vod_rating_imdb),
                value = ratings.imdb,
                isLoading = isLoading,
                tokenBackground = Color(0xFFF5C449),
                tokenContent = Color(0xFF111111)
            )
            RatingBadgeCard(
                token = "RT",
                title = stringResource(R.string.vod_rating_rotten_tomatoes),
                value = ratings.rottenTomatoes,
                isLoading = isLoading,
                tokenBackground = Color(0xFFE24E41),
                tokenContent = Color.White
            )
            RatingBadgeCard(
                token = "MC",
                title = stringResource(R.string.vod_rating_metacritic),
                value = ratings.metacritic,
                isLoading = isLoading,
                tokenBackground = Color(0xFFD6E04B),
                tokenContent = Color(0xFF111111)
            )
            RatingBadgeCard(
                token = "TMDb",
                title = stringResource(R.string.vod_rating_tmdb),
                value = ratings.tmdb,
                isLoading = isLoading,
                tokenBackground = Color(0xFF17C7A2),
                tokenContent = Color(0xFF072720)
            )
        }
    }
}

private fun ExternalRatings.hasAnyAvailableValue(): Boolean {
    return imdb.available || rottenTomatoes.available || metacritic.available || tmdb.available
}

@Composable
private fun RatingBadgeCard(
    token: String,
    title: String,
    value: ExternalRatingValue,
    isLoading: Boolean,
    tokenBackground: Color,
    tokenContent: Color
) {
    val displayValue = if (isLoading) "..." else value.displayValue
    val containerColor = if (value.available || isLoading) {
        AppColors.SurfaceElevated
    } else {
        AppColors.Surface
    }

    Column(
        modifier = Modifier
            .widthIn(min = 92.dp)
            .background(containerColor, RoundedCornerShape(18.dp))
            .border(1.dp, AppColors.Outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(tokenBackground, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = token,
                    color = tokenContent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = title,
                color = AppColors.TextTertiary,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }

        Text(
            text = displayValue,
            color = if (value.available || isLoading) AppColors.TextPrimary else AppColors.TextSecondary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}