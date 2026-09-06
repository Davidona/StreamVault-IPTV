package com.streamvault.app.tvinput

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.tv.TvInputInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.streamvault.app.R
import com.streamvault.app.MainActivity
import com.streamvault.app.navigation.ExternalDestination
import com.streamvault.app.ui.theme.ErrorColor
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.StreamVaultTheme
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TvInputSetupActivity : ComponentActivity() {
    private val viewModel: TvInputSetupViewModel by viewModels()

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inputId = intent.getStringExtra(TvInputInfo.EXTRA_INPUT_ID)
            ?: ComponentName(this, StreamVaultTvInputService::class.java).flattenToShortString()
        viewModel.startSetup(inputId)
        setContent {
            val darkTheme by preferencesRepository.darkTheme.collectAsState(initial = false)
            StreamVaultTheme(useDarkTheme = darkTheme) {
                TvInputSetupRoute(
                    onOpenProviderSetup = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(
                                    MainActivity.EXTRA_EXTERNAL_DESTINATION,
                                    ExternalDestination.ProviderSetup()
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                    },
                    onRetry = { viewModel.retry() },
                    onFinishSuccess = { resolvedInputId ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(TvInputInfo.EXTRA_INPUT_ID, resolvedInputId)
                        )
                        finish()
                    },
                    onFinishCanceled = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumeIfAwaitingProvider()
    }
}

@Composable
private fun TvInputSetupRoute(
    onOpenProviderSetup: () -> Unit,
    onRetry: () -> Unit,
    onFinishSuccess: (String) -> Unit,
    onFinishCanceled: () -> Unit,
    viewModel: TvInputSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.status, uiState.inputId) {
        val resolvedInputId = uiState.inputId
        if (uiState.status == TvInputSetupStatus.SUCCESS && resolvedInputId != null) {
            delay(350)
            onFinishSuccess(resolvedInputId)
        }
    }

    TvInputSetupScreen(
        uiState = uiState,
        onOpenProviderSetup = onOpenProviderSetup,
        onRetry = onRetry,
        onCancel = onFinishCanceled
    )
}

@Composable
private fun TvInputSetupScreen(
    uiState: TvInputSetupUiState,
    onOpenProviderSetup: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = SurfaceElevated,
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when (uiState.status) {
                        TvInputSetupStatus.SCANNING -> androidx.compose.ui.res.stringResource(R.string.tv_input_setup_scanning_title)
                        TvInputSetupStatus.SUCCESS -> androidx.compose.ui.res.stringResource(R.string.tv_input_setup_success_title)
                        TvInputSetupStatus.NO_PROVIDER -> androidx.compose.ui.res.stringResource(R.string.tv_input_setup_no_provider_title)
                        TvInputSetupStatus.ERROR -> androidx.compose.ui.res.stringResource(R.string.tv_input_setup_error_title)
                        TvInputSetupStatus.IDLE -> androidx.compose.ui.res.stringResource(R.string.tv_input_setup_idle_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnBackground,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = when (uiState.status) {
                        TvInputSetupStatus.SCANNING -> androidx.compose.ui.res.stringResource(R.string.tv_input_setup_scanning_body)
                        TvInputSetupStatus.SUCCESS -> uiState.message ?: androidx.compose.ui.res.stringResource(R.string.tv_input_setup_success_body)
                        TvInputSetupStatus.NO_PROVIDER -> androidx.compose.ui.res.stringResource(R.string.tv_input_setup_no_provider_body)
                        TvInputSetupStatus.ERROR -> uiState.message ?: androidx.compose.ui.res.stringResource(R.string.tv_input_setup_error_body)
                        TvInputSetupStatus.IDLE -> androidx.compose.ui.res.stringResource(R.string.tv_input_setup_idle_body)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurfaceDim,
                    textAlign = TextAlign.Center
                )

                if (uiState.status == TvInputSetupStatus.SCANNING) {
                    CircularProgressIndicator(color = Primary)
                }

                if (uiState.providerName != null && uiState.status != TvInputSetupStatus.NO_PROVIDER) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(
                            R.string.tv_input_setup_source_format,
                            uiState.providerName
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                when (uiState.status) {
                    TvInputSetupStatus.NO_PROVIDER -> {
                        Button(onClick = onOpenProviderSetup) {
                            Text(androidx.compose.ui.res.stringResource(R.string.tv_input_setup_open_setup))
                        }
                        Button(onClick = onRetry) {
                            Text(androidx.compose.ui.res.stringResource(R.string.tv_input_setup_try_scan_again))
                        }
                        Button(onClick = onCancel) {
                            Text(androidx.compose.ui.res.stringResource(R.string.settings_cancel))
                        }
                    }
                    TvInputSetupStatus.ERROR -> {
                        Text(
                            text = uiState.message.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorColor,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = onRetry) {
                            Text(androidx.compose.ui.res.stringResource(R.string.tv_input_setup_retry_scan))
                        }
                        Button(onClick = onCancel) {
                            Text(androidx.compose.ui.res.stringResource(R.string.settings_cancel))
                        }
                    }
                    TvInputSetupStatus.SUCCESS -> {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.tv_input_setup_success_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim,
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

@HiltViewModel
class TvInputSetupViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(TvInputSetupUiState())
    val uiState: StateFlow<TvInputSetupUiState> = _uiState.asStateFlow()

    fun startSetup(inputId: String) {
        if (_uiState.value.status == TvInputSetupStatus.SCANNING) return
        scan(inputId)
    }

    fun retry() {
        val inputId = _uiState.value.inputId ?: return
        scan(inputId)
    }

    fun resumeIfAwaitingProvider() {
        val state = _uiState.value
        if (state.status == TvInputSetupStatus.NO_PROVIDER && state.inputId != null) {
            scan(state.inputId)
        }
    }

    private fun scan(inputId: String) {
        viewModelScope.launch {
            _uiState.value = TvInputSetupUiState(
                inputId = inputId,
                status = TvInputSetupStatus.SCANNING
            )

            val providers = providerRepository.getProviders().first()
            if (providers.isEmpty()) {
                _uiState.value = TvInputSetupUiState(
                    inputId = inputId,
                    status = TvInputSetupStatus.NO_PROVIDER
                )
                return@launch
            }

            var activeProvider = providerRepository.getActiveProvider().first()
            if (activeProvider == null) {
                val fallbackProvider = providers.first()
                providerRepository.setActiveProvider(fallbackProvider.id)
                activeProvider = fallbackProvider
            }

            val syncResult = tvInputChannelSyncManager.refreshTvInputCatalogResult()
            syncResult.onSuccess {
                _uiState.value = TvInputSetupUiState(
                    inputId = inputId,
                    status = TvInputSetupStatus.SUCCESS,
                    providerName = activeProvider?.name,
                    message = "Synced ${providers.size} configured provider(s). Android TV Live TV will use ${activeProvider?.name ?: "your active source"}."
                )
            }.onFailure { throwable ->
                _uiState.value = TvInputSetupUiState(
                    inputId = inputId,
                    status = TvInputSetupStatus.ERROR,
                    providerName = activeProvider?.name,
                    message = throwable.message ?: "Unknown TV input sync error"
                )
            }
        }
    }
}

data class TvInputSetupUiState(
    val inputId: String? = null,
    val status: TvInputSetupStatus = TvInputSetupStatus.IDLE,
    val providerName: String? = null,
    val message: String? = null
)

enum class TvInputSetupStatus {
    IDLE,
    SCANNING,
    SUCCESS,
    NO_PROVIDER,
    ERROR
}
