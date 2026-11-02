package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.model.stremio.StremioStreamingService
import com.streamvault.domain.model.stremio.StremioStream
import com.streamvault.feature.stremio.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class StremioStreamingServiceViewModel @Inject constructor(private val service:StremioStreamingService):ViewModel() {
    val state=service.state
    init { viewModelScope.launch { service.load() } }
    fun save(url:String) { viewModelScope.launch { service.configure(url) } }
    fun clear() { viewModelScope.launch { service.clear() } }
    fun prepare(stream:StremioStream)=service.prepare(stream)
}

@Composable
internal fun StremioStreamingServiceContent(viewModel:StremioStreamingServiceViewModel=hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var url by remember(state.url) { mutableStateOf(state.url.orEmpty()) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.stremio_streaming_service))
        Text(stringResource(R.string.stremio_streaming_service_description))
        OutlinedTextField(value=url,onValueChange={ url=it },singleLine=true,modifier=Modifier.fillMaxWidth(),
            label={ Text(stringResource(R.string.stremio_service_url)) })
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TvButton(onClick={ viewModel.save(url) },enabled=state.ready && url.isNotBlank()) { Text(stringResource(R.string.stremio_service_save)) }
            TvButton(onClick=viewModel::clear,enabled=state.url!=null) { Text(stringResource(R.string.stremio_remove)) }
        }
        if(state.error) Text(stringResource(R.string.stremio_error_generic))
    }
}
