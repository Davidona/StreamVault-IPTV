package com.streamvault.app.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import dagger.hilt.android.qualifiers.ApplicationContext
import com.streamvault.feature.system.api.StreamVaultPluginContract
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class PluginMessengerClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun send(
        packageName: String,
        serviceClassName: String,
        what: Int,
        data: Bundle = Bundle(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS
    ): Bundle {
        val appContext = context.applicationContext
        val serviceDeferred = CompletableDeferred<Messenger>()
        val responseDeferred = CompletableDeferred<Bundle>()
        val requestId = UUID.randomUUID().toString()
        var bound = false

        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            val response = message.data ?: Bundle.EMPTY
            if (response.getString(StreamVaultPluginContract.KEY_REQUEST_ID) == requestId &&
                !responseDeferred.isCompleted
            ) {
                responseDeferred.complete(Bundle(response))
            }
            true
        })

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    serviceDeferred.completeExceptionally(IllegalStateException("Plugin service returned no binder"))
                } else {
                    serviceDeferred.complete(Messenger(service))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!serviceDeferred.isCompleted) {
                    serviceDeferred.completeExceptionally(IllegalStateException("Plugin service disconnected"))
                }
                if (!responseDeferred.isCompleted) {
                    responseDeferred.completeExceptionally(IllegalStateException("Plugin service disconnected"))
                }
            }
        }

        try {
            withContext(Dispatchers.Main.immediate) {
                val intent = Intent(StreamVaultPluginContract.ACTION_PLUGIN_SERVICE).apply {
                    component = ComponentName(packageName, serviceClassName)
                }
                bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    throw IllegalStateException("Unable to bind plugin service")
                }
            }

            val service = withTimeoutOrNull(timeoutMillis) { serviceDeferred.await() }
                ?: throw IllegalStateException("Plugin request timed out")
            val request = Message.obtain(null, what).apply {
                replyTo = replyMessenger
                this.data = Bundle(data).apply {
                    putInt(StreamVaultPluginContract.KEY_API_VERSION, StreamVaultPluginContract.API_VERSION)
                    putString(StreamVaultPluginContract.KEY_REQUEST_ID, requestId)
                }
            }
            try {
                service.send(request)
            } catch (error: RemoteException) {
                throw IllegalStateException("Plugin service did not accept the request", error)
            }
            return withTimeoutOrNull(timeoutMillis) { responseDeferred.await() }
                ?: throw IllegalStateException("Plugin request timed out")
        } finally {
            if (bound) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    runCatching { appContext.unbindService(connection) }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
