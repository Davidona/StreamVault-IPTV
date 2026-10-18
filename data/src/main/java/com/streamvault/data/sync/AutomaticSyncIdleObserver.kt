package com.streamvault.data.sync

import android.content.Context
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.sync.AutomaticSyncActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Catch up after viewing/recording ends, without waiting for accumulated worker backoff. */
@Singleton
class AutomaticSyncIdleObserver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val recordingManager: RecordingManager
) {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(AutomaticSyncActivity.playbackActive, recordingManager.observeActiveRecordingCount()) { playback, recordings ->
                playback || recordings > 0
            }.distinctUntilChanged().debounce(2_000L).collect { busy ->
                if (!busy) ProviderSyncWorker.enqueueIdleCatchUp(context)
            }
        }
    }
}
