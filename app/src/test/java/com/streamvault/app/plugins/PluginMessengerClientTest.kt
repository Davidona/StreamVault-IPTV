package com.streamvault.app.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.system.api.StreamVaultPluginContract
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.doAnswer
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PluginMessengerClientTest {
    @Test
    fun `handler failure response is returned with request identity and unbinds service`() =
        roundTrip(
            response = { request ->
                Bundle().apply {
                    putBoolean(StreamVaultPluginContract.KEY_HANDLED, true)
                    putBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)
                    putString(StreamVaultPluginContract.KEY_MESSAGE, "Torrent engine unavailable")
                }
            },
        ) { result, request, unbinds ->
            assertThat(result.getBoolean(StreamVaultPluginContract.KEY_HANDLED)).isTrue()
            assertThat(result.getBoolean(StreamVaultPluginContract.KEY_SUCCESS)).isFalse()
            assertThat(result.getString(StreamVaultPluginContract.KEY_MESSAGE))
                .isEqualTo("Torrent engine unavailable")
            assertValidPlaybackRequest(request)
            assertThat(unbinds).isEqualTo(1)
        }

    @Test
    fun `successful magnet response returns safe stream URL and unbinds service`() =
        roundTrip(
            response = { _ ->
                Bundle().apply {
                    putBoolean(StreamVaultPluginContract.KEY_HANDLED, true)
                    putBoolean(StreamVaultPluginContract.KEY_SUCCESS, true)
                    putString(StreamVaultPluginContract.KEY_OUTPUT_URL, "http://127.0.0.1:6878/stream/test")
                }
            },
        ) { result, request, unbinds ->
            assertThat(result.getBoolean(StreamVaultPluginContract.KEY_HANDLED)).isTrue()
            assertThat(result.getBoolean(StreamVaultPluginContract.KEY_SUCCESS)).isTrue()
            assertThat(result.getString(StreamVaultPluginContract.KEY_OUTPUT_URL))
                .isEqualTo("http://127.0.0.1:6878/stream/test")
            assertValidPlaybackRequest(request)
            assertThat(unbinds).isEqualTo(1)
        }

    private fun roundTrip(
        response: (Message) -> Bundle,
        verify: (Bundle, Bundle, Int) -> Unit,
    ) = runBlocking {
        val worker = HandlerThread("plugin-messenger-test").apply { start() }
        val seenRequest = AtomicReference<Bundle>()
        val service = Messenger(Handler(worker.looper) { request ->
            seenRequest.set(Bundle(request.data))
            val reply = Message.obtain().apply {
                data = response(request).apply {
                    putString(
                        StreamVaultPluginContract.KEY_REQUEST_ID,
                        request.data.getString(StreamVaultPluginContract.KEY_REQUEST_ID),
                    )
                }
            }
            try {
                request.replyTo.send(reply)
            } catch (error: RemoteException) {
                throw AssertionError("Test plugin could not reply", error)
            }
            true
        })
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        var unbinds = 0
        doAnswer { invocation ->
            val intent = invocation.getArgument<Intent>(0)
            val connection = invocation.getArgument<ServiceConnection>(1)
            connection.onServiceConnected(
                intent.component,
                service.binder,
            )
            true
        }.whenever(context).bindService(any(), any(), eq(Context.BIND_AUTO_CREATE))
        doAnswer { unbinds++; null }.whenever(context).unbindService(any())

        try {
            val client = PluginMessengerClient(context)
            val call = async(Dispatchers.IO) {
                client.send(
                    packageName = "com.example.torrent",
                    serviceClassName = "com.example.torrent.PluginService",
                    what = StreamVaultPluginContract.MSG_PREPARE_PLAYBACK,
                    data = Bundle().apply {
                        putString(
                            StreamVaultPluginContract.KEY_INPUT_URL,
                            "magnet:?xt=urn:btih:0123456789abcdef",
                        )
                    },
                    timeoutMillis = 2_000L,
                )
            }

            val mainLooper = shadowOf(Looper.getMainLooper())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!call.isCompleted && System.nanoTime() < deadline) {
                mainLooper.idle()
                Thread.sleep(10)
            }
            mainLooper.idle()

            assertThat(call.isCompleted).isTrue()
            verify(call.await(), checkNotNull(seenRequest.get()), unbinds)
        } finally {
            worker.quitSafely()
        }
    }

    private fun assertValidPlaybackRequest(request: Bundle) {
        assertThat(request.getInt(StreamVaultPluginContract.KEY_API_VERSION))
            .isEqualTo(StreamVaultPluginContract.API_VERSION)
        assertThat(request.getString(StreamVaultPluginContract.KEY_REQUEST_ID)).isNotEmpty()
        assertThat(request.getString(StreamVaultPluginContract.KEY_INPUT_URL))
            .isEqualTo("magnet:?xt=urn:btih:0123456789abcdef")
    }
}
