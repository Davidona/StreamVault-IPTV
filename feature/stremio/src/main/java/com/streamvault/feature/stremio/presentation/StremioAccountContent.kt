package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.PhoneAddQrPanel
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.model.stremio.StremioAccount
import com.streamvault.domain.model.stremio.StremioAccountError
import com.streamvault.feature.stremio.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltViewModel
class StremioAccountViewModel @Inject constructor(private val account: StremioAccount): ViewModel() {
    val state=account.state
    init { account.start(); viewModelScope.launch { account.load() } }
    fun connect()=action { account.beginLink() }
    fun cancel()=action { account.cancelLink() }
    fun disconnect()=action { account.disconnect() }
    fun sync()=action { account.sync() }
    suspend fun poll() { account.pollLink() }
    private fun action(block:suspend () -> Any?) { viewModelScope.launch {
        try { block() } catch(cancel:CancellationException) { throw cancel }
    } }
}

@Composable
internal fun StremioAccountContent(onBack:()->Unit,viewModel:StremioAccountViewModel=hiltViewModel()) {
    androidx.activity.compose.BackHandler(onBack=onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uri=LocalUriHandler.current
    LaunchedEffect(state.linkCode) {
        if(state.linkCode!=null) while(true) { delay(5_000); viewModel.poll() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TvButton(onClick=onBack) { Text(stringResource(R.string.stremio_manage_addons)) }
        Text(stringResource(R.string.stremio_account))
        if(state.connected) {
            Text(state.displayName.orEmpty())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TvButton(onClick=viewModel::sync,enabled=!state.busy) { Text(stringResource(R.string.stremio_sync_now)) }
                TvButton(onClick=viewModel::disconnect) { Text(stringResource(R.string.stremio_disconnect)) }
            }
        } else if(state.linkUrl!=null) {
            PhoneAddQrPanel(url=state.linkUrl,title=stringResource(R.string.stremio_account_link),
                description=stringResource(R.string.stremio_account_link),status=state.linkCode,
                startLabel=stringResource(R.string.stremio_connect),stopLabel=stringResource(R.string.stremio_cancel),
                onStart={ state.linkUrl?.let { runCatching { uri.openUri(it) } } },onStop=viewModel::cancel)
        } else {
            Text(stringResource(R.string.stremio_account_sync_description))
            TvButton(onClick=viewModel::connect,enabled=state.ready && !state.busy) { Text(stringResource(R.string.stremio_connect)) }
        }
        if(state.busy) Text(stringResource(R.string.stremio_syncing))
        state.error?.let { error -> Text(stringResource(when(error) {
            StremioAccountError.EXPIRED -> R.string.stremio_link_expired
            StremioAccountError.INCOGNITO -> R.string.stremio_sync_incognito
            else -> R.string.stremio_error_generic
        })) }
        if(state.error==StremioAccountError.STORAGE) {
            TvButton(onClick=viewModel::cancel) { Text(stringResource(R.string.stremio_cancel)) }
        }
        StremioStreamingServiceContent()
    }
}
