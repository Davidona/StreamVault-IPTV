package com.streamvault.feature.system.presentation.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.feature.system.R
import com.streamvault.core.ui.components.shell.StatusPill
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.sync.Section
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import com.streamvault.feature.system.api.SystemWelcomePort
import com.streamvault.feature.system.api.WelcomeSyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val validateAndAddProvider: ValidateAndAddProvider,
    private val welcomePort: SystemWelcomePort,
) : ViewModel() {

    private val _hasProviders = MutableStateFlow<Boolean?>(null)
    val hasProviders: StateFlow<Boolean?> = _hasProviders.asStateFlow()

    private val acceptingProgress = MutableStateFlow(true)

    val syncProgress: StateFlow<WelcomeSyncProgress?> =
        combine(welcomePort.syncProgress, acceptingProgress) { progress, accept ->
            if (accept) progress else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            maybeSeedDevProvider()
            providerRepository.getProviders()
                .map { it.isNotEmpty() }
                .collect { _hasProviders.value = it }
        }
        viewModelScope.launch {
            _hasProviders
                .filterNotNull()
                .first()
            acceptingProgress.value = false
        }
    }

    private suspend fun maybeSeedDevProvider() {
        if (providerRepository.getProviders().first().isNotEmpty()) return

        val devConfig = welcomePort.devProviderConfig
        val xtreamServer = devConfig.xtreamServer
        val xtreamUser = devConfig.xtreamUsername
        val xtreamPass = devConfig.xtreamPassword
        if (xtreamServer.isNotBlank() && xtreamUser.isNotBlank() && xtreamPass.isNotBlank()) {
            validateAndAddProvider.loginXtream(
                XtreamProviderSetupCommand(
                    serverUrl = xtreamServer,
                    username = xtreamUser,
                    password = xtreamPass,
                    name = devConfig.xtreamName.ifBlank { "Dev (seeded)" },
                    xtreamFastSyncEnabled = true
                )
            )
            return
        }

        val m3uUrl = devConfig.m3uUrl
        if (m3uUrl.isNotBlank()) {
            validateAndAddProvider.addM3u(
                M3uProviderSetupCommand(
                    url = m3uUrl,
                    name = devConfig.m3uName.ifBlank { "Dev M3U (seeded)" }
                )
            )
        }
    }
}

@Composable
fun WelcomeScreen(
    onNavigateToHome: () -> Unit,
    startupReady: Boolean = true,
    onNavigateToSetup: () -> Unit,
    viewModel: WelcomeViewModel = hiltViewModel()
) {
    val hasProviders by viewModel.hasProviders.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()

    LaunchedEffect(hasProviders, startupReady) {
        when (hasProviders) {
            true -> if (startupReady) onNavigateToHome()
            false -> Unit
            null -> Unit
        }
    }

    WelcomeContent(
        hasProviders = hasProviders,
        syncProgress = syncProgress,
        onNavigateToHome = onNavigateToHome,
        onNavigateToSetup = onNavigateToSetup,
    )
}

@Composable
internal fun WelcomeContent(
    hasProviders: Boolean?,
    syncProgress: WelcomeSyncProgress?,
    onNavigateToHome: () -> Unit,
    onNavigateToSetup: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.22f),
                            AppColors.HeroTop,
                            AppColors.HeroBottom
                        )
                    )
                )
        )

        when (hasProviders) {
            false -> WelcomeStartCard(
                onNavigateToHome = onNavigateToHome,
                onNavigateToSetup = onNavigateToSetup,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
            )

            else -> WelcomeLoadingCard(
                syncProgress = syncProgress,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
            )
        }
    }
}

@Composable
private fun WelcomeLoadingCard(
    syncProgress: WelcomeSyncProgress?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = SurfaceDefaults.colors(containerColor = AppColors.Surface.copy(alpha = 0.9f))
    ) {
        Column(
        modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
            val representativeProgress = syncProgress
            val pillLabel = if (representativeProgress != null) {
                stringResource(sectionLabelRes(representativeProgress.section))
            } else {
                stringResource(R.string.app_name)
            }
            val pillColor = if (representativeProgress != null) {
                sectionColor(representativeProgress.section)
            } else {
                AppColors.BrandMuted
            }
            StatusPill(
                label = pillLabel,
                containerColor = pillColor
            )
            Spacer(modifier = Modifier.height(18.dp))
            if (representativeProgress == null) {
                CircularProgressIndicator(color = AppColors.Brand)
                Spacer(modifier = Modifier.height(18.dp))
            }
            Text(
                text = stringResource(R.string.welcome_loading_title),
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            val subtitle = if (representativeProgress != null && representativeProgress.currentLabel.isNotBlank()) {
                representativeProgress.currentLabel
            } else {
                stringResource(R.string.welcome_loading_subtitle)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary
            )
            if (representativeProgress != null) {
                Spacer(modifier = Modifier.height(14.dp))
                if (representativeProgress.total > 0) {
                    LinearProgressIndicator(
                        progress = { representativeProgress.current.toFloat() / representativeProgress.total.toFloat() },
                        modifier = Modifier.width(260.dp),
                        color = AppColors.Brand,
                        trackColor = AppColors.BrandMuted
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.width(260.dp),
                        color = AppColors.Brand,
                        trackColor = AppColors.BrandMuted
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        R.string.sync_items_indexed_format,
                        representativeProgress.itemsIndexed
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun WelcomeStartCard(
    onNavigateToHome: () -> Unit,
    onNavigateToSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = SurfaceDefaults.colors(containerColor = AppColors.Surface.copy(alpha = 0.9f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 40.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            StatusPill(
                label = stringResource(R.string.app_name),
                containerColor = AppColors.BrandMuted
            )
            Text(
                text = stringResource(R.string.welcome_tagline),
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvButton(
                    onClick = onNavigateToSetup,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.welcome_setup_provider))
                }
                TvButton(
                    onClick = onNavigateToHome,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.SurfaceElevated,
                        focusedContainerColor = Color.White,
                        contentColor = AppColors.TextPrimary
                    )
                ) {
                    Text(text = stringResource(R.string.welcome_setup_later))
                }
            }
        }
    }
}

private fun sectionColor(section: Section): Color = when (section) {
    Section.LIVE -> AppColors.Brand
    Section.VOD -> AppColors.Success
    Section.SERIES -> AppColors.Warning
}

private fun sectionLabelRes(section: Section): Int = when (section) {
    Section.LIVE -> R.string.sync_section_live
    Section.VOD -> R.string.sync_section_vod
    Section.SERIES -> R.string.sync_section_series
}
